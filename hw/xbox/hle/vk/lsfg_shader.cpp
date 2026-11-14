/*
 * hakuX HLE — Lossless Scaling shader resource reader.
 *
 * The Windows PE is treated as data. Only numeric RCDATA resources are
 * extracted; no Windows code, imports, relocations or entry points execute.
 * Resource layout and shader IDs follow EmuCoreX / lsfg-vk (GPL-3.0-or-later).
 */
#include "hw/xbox/hle/vk/lsfg_shader.h"
#include "hw/xbox/hle/vk/lsfg_native.h"

#include <algorithm>
#include <cstring>
#include <fstream>
#include <limits>
#include <mutex>
#include <cstdio>
#include <span>

namespace HleLsfg {
namespace {
constexpr uint16_t DOS_MAGIC = 0x5a4d;
constexpr uint32_t PE_MAGIC = 0x00004550;
constexpr uint16_t PE32 = 0x010b;
constexpr uint16_t PE32_PLUS = 0x020b;
constexpr uint32_t RCDATA = 10;
constexpr uint32_t DIR_FLAG = 0x80000000u;
constexpr size_t MAX_IMAGE = 256u * 1024u * 1024u;
constexpr size_t RESOURCE_LIMIT = 4096;

struct Section { uint32_t va, virtual_size, raw, raw_size; };
struct Entry { uint32_t id, offset; bool directory, named; };

class Reader {
public:
  explicit Reader(const std::vector<uint8_t> &b) : bytes(b) {}
  template<typename T> bool get(size_t at, T &out) const {
    if (at > bytes.size() || bytes.size() - at < sizeof(T)) return false;
    std::memcpy(&out, bytes.data() + at, sizeof(T));
    return true;
  }
  bool range(size_t at, size_t n) const {
    return at <= bytes.size() && n <= bytes.size() - at;
  }
  const uint8_t *ptr(size_t at) const { return bytes.data() + at; }
  size_t size() const { return bytes.size(); }
private:
  const std::vector<uint8_t> &bytes;
};

bool fail(std::string *e, const char *why) {
  if (e) *e = why;
  return false;
}
bool entries(const Reader &r, size_t at, std::vector<Entry> &out) {
  uint16_t named = 0, ids = 0;
  if (!r.get(at + 12, named) || !r.get(at + 14, ids)) return false;
  const size_t n = size_t(named) + ids;
  if (n > RESOURCE_LIMIT || !r.range(at + 16, n * 8)) return false;
  out.clear(); out.reserve(n);
  for (size_t i = 0; i < n; ++i) {
    uint32_t name = 0, data = 0;
    if (!r.get(at + 16 + i * 8, name) || !r.get(at + 20 + i * 8, data)) return false;
    out.push_back({name & ~DIR_FLAG, data & ~DIR_FLAG,
                   (data & DIR_FLAG) != 0, (name & DIR_FLAG) != 0});
  }
  return true;
}
bool rva_to_file(const std::vector<Section> &sections, uint32_t rva,
                 size_t &offset) {
  for (const auto &s : sections) {
    const uint32_t span = std::max(s.virtual_size, s.raw_size);
    if (!span || rva < s.va) continue;
    const uint32_t delta = rva - s.va;
    if (delta < span && delta < s.raw_size) {
      offset = size_t(s.raw) + delta;
      return true;
    }
  }
  return false;
}
bool collect(const Reader &r, const std::vector<Section> &sections,
             size_t base, std::map<uint32_t, std::vector<uint8_t>> &out) {
  std::vector<Entry> types;
  if (!entries(r, base, types)) return false;
  for (const auto &type : types) {
    if (type.named || type.id != RCDATA || !type.directory) continue;
    std::vector<Entry> names;
    if (!entries(r, base + type.offset, names)) return false;
    for (const auto &name : names) {
      if (name.named || !name.directory) continue;
      std::vector<Entry> langs;
      if (!entries(r, base + name.offset, langs)) return false;
      for (const auto &lang : langs) {
        if (lang.directory) continue;
        const size_t leaf = base + lang.offset;
        uint32_t data_rva = 0, data_size = 0;
        size_t data_at = 0;
        if (!r.get(leaf, data_rva) || !r.get(leaf + 4, data_size) ||
            !data_size || !rva_to_file(sections, data_rva, data_at) ||
            !r.range(data_at, data_size)) continue;
        out.insert_or_assign(name.id,
          std::vector<uint8_t>(r.ptr(data_at), r.ptr(data_at) + data_size));
        break;
      }
    }
  }
  return !out.empty();
}
bool spirv(const std::vector<uint8_t> &b) {
  if (b.size() < 20 || b.size() % 4) return false;
  uint32_t magic = 0;
  std::memcpy(&magic, b.data(), 4);
  return magic == 0x07230203u;
}
std::vector<uint32_t> adopt(const std::vector<uint8_t> &b) {
  if (!spirv(b)) return {};
  std::vector<uint32_t> w(b.size() / 4);
  std::memcpy(w.data(), b.data(), b.size());
  struct Slot { uint32_t set, binding; size_t literal; };
  std::map<uint32_t, uint32_t> sets;
  std::vector<Slot> slots;
  size_t at = 5;
  while (at < w.size()) {
    const uint32_t count = w[at] >> 16;
    const uint32_t op = w[at] & 0xffff;
    if (!count || count > w.size() - at) return {};
    if (op == 54) break; // OpFunction: decorations are in the module header.
    if (op == 71 && count >= 4) { // OpDecorate
      if (w[at + 2] == 34) sets[w[at + 1]] = w[at + 3]; // DescriptorSet
      if (w[at + 2] == 33) slots.push_back({0, w[at + 3], at + 3}); // Binding
    }
    at += count;
  }
  for (auto &s : slots) {
    const auto it = sets.find(w[s.literal - 2]);
    s.set = it == sets.end() ? 0 : it->second;
  }
  std::stable_sort(slots.begin(), slots.end(), [](const Slot &a, const Slot &b) {
    return a.set == b.set ? a.binding < b.binding : a.set < b.set;
  });
  for (size_t i = 0; i < slots.size(); ++i) w[slots[i].literal] = uint32_t(i);
  return w;
}
constexpr uint32_t kIds[] = {
  255, 256, 280, 281, 282, 283, 284, 285, 286, 287, 288, 289,
  290, 291, 292, 293, 294, 295, 296, 297, 298, 299, 300, 301, 302
};
bool has_variant(const std::map<uint32_t, std::vector<uint8_t>> &r, uint32_t offset) {
  for (uint32_t id : kIds) {
    auto it = r.find(id + offset);
    if (it == r.end() || !spirv(it->second)) return false;
  }
  return true;
}
} // namespace

bool ReadResources(const char *path,
                   std::map<uint32_t, std::vector<uint8_t>> &resources,
                   std::string *error) {
  if (!path || !*path) return fail(error, "empty DLL path");
  std::ifstream file(path, std::ios::binary | std::ios::ate);
  if (!file) return fail(error, "DLL cannot be opened");
  const auto end = file.tellg();
  if (end < 64 || uint64_t(end) > MAX_IMAGE) return fail(error, "invalid PE file size");
  std::vector<uint8_t> image(static_cast<size_t>(end));
  file.seekg(0);
  if (!file.read(reinterpret_cast<char *>(image.data()), std::streamsize(image.size())))
    return fail(error, "DLL read failed");
  Reader r(image);
  uint16_t mz = 0;
  uint32_t pe_at = 0, signature = 0;
  if (!r.get(0, mz) || mz != DOS_MAGIC || !r.get(0x3c, pe_at) ||
      !r.get(pe_at, signature) || signature != PE_MAGIC)
    return fail(error, "not a valid PE image");
  uint16_t section_count = 0, optional_size = 0, magic = 0;
  if (!r.get(size_t(pe_at) + 6, section_count) ||
      !r.get(size_t(pe_at) + 20, optional_size) ||
      !r.get(size_t(pe_at) + 24, magic))
    return fail(error, "truncated PE headers");
  const size_t optional = size_t(pe_at) + 24;
  const size_t dd = magic == PE32 ? optional + 96 :
                    magic == PE32_PLUS ? optional + 112 : 0;
  if (!dd || optional_size < (magic == PE32 ? 104 : 120) ||
      !r.range(optional, optional_size))
    return fail(error, "unsupported or truncated PE optional header");
  const size_t section_table = optional + optional_size;
  if (!r.range(section_table, size_t(section_count) * 40))
    return fail(error, "truncated PE section table");
  std::vector<Section> sections;
  sections.reserve(section_count);
  for (uint32_t i = 0; i < section_count; ++i) {
    const size_t at = section_table + size_t(i) * 40;
    Section s{};
    if (!r.get(at + 8, s.virtual_size) || !r.get(at + 12, s.va) ||
        !r.get(at + 16, s.raw_size) || !r.get(at + 20, s.raw))
      return fail(error, "invalid PE section");
    if (!r.range(s.raw, s.raw_size)) return fail(error, "PE section outside file");
    sections.push_back(s);
  }
  uint32_t resource_rva = 0, resource_size = 0;
  if (!r.get(dd + 2 * 8, resource_rva) || !r.get(dd + 2 * 8 + 4, resource_size) ||
      !resource_rva || resource_size < 16)
    return fail(error, "PE has no resource directory");
  size_t resource_base = 0;
  if (!rva_to_file(sections, resource_rva, resource_base) ||
      !r.range(resource_base, resource_size))
    return fail(error, "invalid PE resource directory");
  std::map<uint32_t, std::vector<uint8_t>> parsed;
  if (!collect(r, sections, resource_base, parsed))
    return fail(error, "no readable numeric RCDATA resources");
  resources.swap(parsed);
  if (error) error->clear();
  return true;
}

bool SelectSpirvModules(
    const std::map<uint32_t, std::vector<uint8_t>> &resources,
    bool allow_fp16, bool prefer_fp16,
    std::map<uint32_t, std::vector<uint32_t>> &modules,
    bool *using_fp16, std::string *error) {
  uint32_t offset = 0;
  if (prefer_fp16 && allow_fp16 && has_variant(resources, 49)) offset = 49;
  else if (has_variant(resources, 98)) offset = 98;
  else if (allow_fp16 && has_variant(resources, 49)) offset = 49;
  else {
    if (error) *error = "Lossless.dll has no complete native SPIR-V shader family";
    return false;
  }
  std::map<uint32_t, std::vector<uint32_t>> parsed;
  for (uint32_t id : kIds) {
    const auto it = resources.find(id + offset);
    if (it == resources.end()) return fail(error, "required LSFG shader resource is missing");
    auto words = adopt(it->second);
    if (words.empty()) return fail(error, "invalid SPIR-V module in Lossless.dll");
    parsed.emplace(id, std::move(words));
  }
  modules.swap(parsed);
  if (using_fp16) *using_fp16 = offset == 49;
  if (error) error->clear();
  return true;
}
} // namespace HleLsfg


/* C ABI used by the Android core. Shader modules are retained so the Vulkan
 * presenter can consume them without reopening or reparsing Lossless.dll. */
namespace {
std::mutex g_lsfg_mutex;
std::map<uint32_t, std::vector<uint32_t>> g_lsfg_modules;
std::string g_lsfg_error;
}

extern "C" int hle_lsfg_load_shaders(const char *path, int allow_fp16, int prefer_fp16,
                                      char *error, size_t error_capacity) {
  std::map<uint32_t, std::vector<uint8_t>> resources;
  std::map<uint32_t, std::vector<uint32_t>> modules;
  std::string reason;
  bool fp16 = false;
  const bool ok = HleLsfg::ReadResources(path, resources, &reason) &&
      HleLsfg::SelectSpirvModules(resources, allow_fp16 != 0, prefer_fp16 != 0,
                                  modules, &fp16, &reason);
  {
    std::lock_guard<std::mutex> lock(g_lsfg_mutex);
    if (ok) {
      g_lsfg_modules.swap(modules);
      g_lsfg_error.clear();
    } else {
      g_lsfg_modules.clear();
      g_lsfg_error = reason.empty() ? "Lossless.dll shader parsing failed" : reason;
    }
    if (error && error_capacity) {
      const std::string &message = ok ? std::string() : g_lsfg_error;
      std::snprintf(error, error_capacity, "%s", message.c_str());
    }
  }
  return ok ? 1 : 0;
}

extern "C" void hle_lsfg_clear_shaders(void) {
  std::lock_guard<std::mutex> lock(g_lsfg_mutex);
  g_lsfg_modules.clear();
  g_lsfg_error.clear();
}

extern "C" int hle_lsfg_shader_count(void) {
  std::lock_guard<std::mutex> lock(g_lsfg_mutex);
  return static_cast<int>(g_lsfg_modules.size());
}

extern "C" int hle_lsfg_get_shader(uint32_t id, const uint32_t **words, size_t *word_count) {
  if (!words || !word_count) return 0;
  std::lock_guard<std::mutex> lock(g_lsfg_mutex);
  const auto it = g_lsfg_modules.find(id);
  if (it == g_lsfg_modules.end()) return 0;
  *words = it->second.data();
  *word_count = it->second.size();
  return 1;
}
