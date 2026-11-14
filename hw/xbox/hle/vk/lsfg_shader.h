/*
 * hakuX HLE — Lossless Scaling shader resource reader.
 * Reads PE RCDATA resources only; never loads or executes the Windows DLL.
 */
#ifndef HW_XBOX_HLE_VK_LSFG_SHADER_H
#define HW_XBOX_HLE_VK_LSFG_SHADER_H

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace HleLsfg {

/* Extracts numeric RCDATA resources from a PE32/PE32+ file.
 * Output is replaced only after the complete resource tree was validated. */
bool ReadResources(const char *path,
                   std::map<uint32_t, std::vector<uint8_t>> &resources,
                   std::string *error = nullptr);

/* Selects the native SPIR-V family used by EmuCoreX: FP16 offset 49,
 * FP32 offset 98. The output keys are the original shader IDs. */
bool SelectSpirvModules(
    const std::map<uint32_t, std::vector<uint8_t>> &resources,
    bool allow_fp16, bool prefer_fp16,
    std::map<uint32_t, std::vector<uint32_t>> &modules,
    bool *using_fp16 = nullptr,
    std::string *error = nullptr);

} // namespace HleLsfg
#endif
