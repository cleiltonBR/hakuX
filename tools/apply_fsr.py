#!/usr/bin/env python3
"""
Adds AMD FidelityFX Super Resolution 1.0 (EASU + RCAS) to hakuX's presenter
(hw/xbox/hle/vk/swapchain.c).

Usage:  python3 apply_fsr.py hw/xbox/hle/vk/swapchain.c

FSR replaces the SGSR scaling path: choosing "SGSR" in the app's settings
runs FSR 1.0 instead. Set HAKUX_FSR to 0 in the patched file to get SGSR back.
Every edit is anchored on exact text; if one anchor is missing or ambiguous
the script stops and writes nothing.

FSR 1.0 is Copyright (c) 2021 Advanced Micro Devices, Inc. (MIT license).
"""
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "hw/xbox/hle/vk/swapchain.c"
src = open(path, encoding="utf-8").read()

if "P_EASU" in src:
    sys.exit("swapchain.c already has FSR (P_EASU found): nothing to do")


def cstr(name, glsl):
    lines = []
    for line in glsl.strip("\n").split("\n"):
        line = line.replace("\\", "\\\\").replace('"', '\\"')
        lines.append('    "%s\\n"' % line)
    return "static const char %s[] =\n%s;\n" % (name, "\n".join(lines))


EASU = r"""
// FidelityFX Super Resolution 1.0, EASU. Copyright (c) 2021 Advanced Micro
// Devices, Inc. (MIT license). extra = 1/w, 1/h, w, h of the input frame.
void tap(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
  vec2 v = vec2(off.x * dir.x + off.y * dir.y, off.x * (-dir.y) + off.y * dir.x);
  v *= len;
  float d2 = min(v.x * v.x + v.y * v.y, clp);
  float wB = 2.0 / 5.0 * d2 - 1.0;
  float wA = lob * d2 - 1.0;
  wB *= wB;
  wA *= wA;
  wB = 25.0 / 16.0 * wB - (25.0 / 16.0 - 1.0);
  float w = wB * wA;
  aC += c * w;
  aW += w;
}
void setd(inout vec2 dir, inout float len, vec2 pp, bool biS, bool biT, bool biU, bool biV,
          float lA, float lB, float lC, float lD, float lE) {
  float w = 0.0;
  if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);
  if (biT) w = pp.x * (1.0 - pp.y);
  if (biU) w = (1.0 - pp.x) * pp.y;
  if (biV) w = pp.x * pp.y;
  float lenX = max(abs(lD - lC), abs(lC - lB));
  float dirX = lD - lB;
  dir.x += dirX * w;
  lenX = clamp(abs(dirX) / max(lenX, 1.0 / 65536.0), 0.0, 1.0);
  lenX *= lenX;
  len += lenX * w;
  float lenY = max(abs(lE - lC), abs(lC - lA));
  float dirY = lE - lA;
  dir.y += dirY * w;
  lenY = clamp(abs(dirY) / max(lenY, 1.0 / 65536.0), 0.0, 1.0);
  lenY *= lenY;
  len += lenY * w;
}
vec3 cc(vec4 r, vec4 g, vec4 b, int i) { return vec3(r[i], g[i], b[i]); }
void main() {
  vec4 vi = pc.extra;
  vec2 pp = v_uv * vi.zw - 0.5;
  vec2 fp = floor(pp);
  pp -= fp;
  vec2 p0 = (fp + vec2(1.0, -1.0)) * vi.xy;
  vec2 p1 = p0 + vec2(-1.0, 2.0) * vi.xy;
  vec2 p2 = p0 + vec2(1.0, 2.0) * vi.xy;
  vec2 p3 = p0 + vec2(0.0, 4.0) * vi.xy;
  vec4 bczzR = textureGather(u_tex, p0, 0), bczzG = textureGather(u_tex, p0, 1), bczzB = textureGather(u_tex, p0, 2);
  vec4 ijfeR = textureGather(u_tex, p1, 0), ijfeG = textureGather(u_tex, p1, 1), ijfeB = textureGather(u_tex, p1, 2);
  vec4 klhgR = textureGather(u_tex, p2, 0), klhgG = textureGather(u_tex, p2, 1), klhgB = textureGather(u_tex, p2, 2);
  vec4 zzonR = textureGather(u_tex, p3, 0), zzonG = textureGather(u_tex, p3, 1), zzonB = textureGather(u_tex, p3, 2);
  vec4 bczzL = bczzB * 0.5 + (bczzR * 0.5 + bczzG);
  vec4 ijfeL = ijfeB * 0.5 + (ijfeR * 0.5 + ijfeG);
  vec4 klhgL = klhgB * 0.5 + (klhgR * 0.5 + klhgG);
  vec4 zzonL = zzonB * 0.5 + (zzonR * 0.5 + zzonG);
  float bL = bczzL.x, cL = bczzL.y;
  float iL = ijfeL.x, jL = ijfeL.y, fL = ijfeL.z, eL = ijfeL.w;
  float kL = klhgL.x, lL = klhgL.y, hL = klhgL.z, gL = klhgL.w;
  float oL = zzonL.z, nL = zzonL.w;
  vec2 dir = vec2(0.0);
  float len = 0.0;
  setd(dir, len, pp, true, false, false, false, bL, eL, fL, gL, jL);
  setd(dir, len, pp, false, true, false, false, cL, fL, gL, hL, kL);
  setd(dir, len, pp, false, false, true, false, fL, iL, jL, kL, nL);
  setd(dir, len, pp, false, false, false, true, gL, jL, kL, lL, oL);
  vec2 dir2 = dir * dir;
  float dirR = dir2.x + dir2.y;
  bool zro = dirR < 1.0 / 32768.0;
  dirR = inversesqrt(max(dirR, 1.0 / 32768.0));
  dirR = zro ? 1.0 : dirR;
  dir.x = zro ? 1.0 : dir.x;
  dir *= dirR;
  len = len * 0.5;
  len *= len;
  float stretch = (dir.x * dir.x + dir.y * dir.y) / max(abs(dir.x), abs(dir.y));
  vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
  float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
  float clp = 1.0 / lob;
  vec3 fC = cc(ijfeR, ijfeG, ijfeB, 2), gC = cc(klhgR, klhgG, klhgB, 3);
  vec3 jC = cc(ijfeR, ijfeG, ijfeB, 1), kC = cc(klhgR, klhgG, klhgB, 0);
  vec3 min4 = min(min(fC, gC), min(jC, kC));
  vec3 max4 = max(max(fC, gC), max(jC, kC));
  vec3 aC = vec3(0.0);
  float aW = 0.0;
  tap(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, cc(bczzR, bczzG, bczzB, 0));
  tap(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, cc(bczzR, bczzG, bczzB, 1));
  tap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, cc(ijfeR, ijfeG, ijfeB, 0));
  tap(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, jC);
  tap(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, fC);
  tap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, cc(ijfeR, ijfeG, ijfeB, 3));
  tap(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, kC);
  tap(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, cc(klhgR, klhgG, klhgB, 1));
  tap(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, cc(klhgR, klhgG, klhgB, 2));
  tap(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, gC);
  tap(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, cc(zzonR, zzonG, zzonB, 2));
  tap(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, cc(zzonR, zzonG, zzonB, 3));
  o = vec4(min(max4, max(min4, aC / aW)), 1.0);
}
"""

RCAS = r"""
// FidelityFX Super Resolution 1.0, RCAS (with denoise). Copyright (c) 2021
// Advanced Micro Devices, Inc. (MIT license). extra.x = exp2(-sharpness).
void main() {
  ivec2 sz = textureSize(u_tex, 0);
  ivec2 ip = ivec2(v_uv * vec2(sz));
  ivec2 hi = sz - 1;
  vec3 b = texelFetch(u_tex, clamp(ip + ivec2(0, -1), ivec2(0), hi), 0).rgb;
  vec3 d = texelFetch(u_tex, clamp(ip + ivec2(-1, 0), ivec2(0), hi), 0).rgb;
  vec3 e = texelFetch(u_tex, clamp(ip, ivec2(0), hi), 0).rgb;
  vec3 f = texelFetch(u_tex, clamp(ip + ivec2(1, 0), ivec2(0), hi), 0).rgb;
  vec3 h = texelFetch(u_tex, clamp(ip + ivec2(0, 1), ivec2(0), hi), 0).rgb;
  float bL = b.b * 0.5 + (b.r * 0.5 + b.g);
  float dL = d.b * 0.5 + (d.r * 0.5 + d.g);
  float eL = e.b * 0.5 + (e.r * 0.5 + e.g);
  float fL = f.b * 0.5 + (f.r * 0.5 + f.g);
  float hL = h.b * 0.5 + (h.r * 0.5 + h.g);
  float nz = 0.25 * bL + 0.25 * dL + 0.25 * fL + 0.25 * hL - eL;
  float rng = max(max(bL, dL), max(eL, max(fL, hL))) - min(min(bL, dL), min(eL, min(fL, hL)));
  nz = clamp(abs(nz) / max(rng, 1.0 / 65536.0), 0.0, 1.0);
  nz = -0.5 * nz + 1.0;
  vec3 mn4 = min(min(b, d), min(f, h));
  vec3 mx4 = max(max(b, d), max(f, h));
  vec3 hitMin = mn4 / (4.0 * max(mx4, vec3(1.0 / 1024.0)));
  vec3 hitMax = (1.0 - mx4) / min(4.0 * mn4 - 4.0, -1.0 / 256.0);
  vec3 lobeRGB = max(-hitMin, hitMax);
  float lobe = max(-0.1875, min(max(lobeRGB.r, max(lobeRGB.g, lobeRGB.b)), 0.0)) * pc.extra.x;
  lobe *= nz;
  float rcpL = 1.0 / (4.0 * lobe + 1.0);
  vec3 pix = (lobe * (b + d + h + f) + e) * rcpL;
  o = ramp(pix);
}
"""

FSR_TARGET = r"""
/* FSR's EASU target: the frame scaled up to its place in the window. */
static bool fsr_target(uint32_t w, uint32_t h)
{
    if (g_ps.fs_img && g_ps.fs_w == w && g_ps.fs_h == h) {
        return true;
    }
    if (g_ps.fs_img) {
        wait_value(g_ps.submitted);
        T.vkDestroyImageView(g_hvk.dev, g_ps.fs_view, NULL);
        T.vkDestroyImage(g_hvk.dev, g_ps.fs_img, NULL);
        T.vkFreeMemory(g_hvk.dev, g_ps.fs_mem, NULL);
        g_ps.fs_img = VK_NULL_HANDLE;
    }
    VkImageCreateInfo ici = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8G8B8A8_UNORM, .extent = { w, h, 1 }, .mipLevels = 1, .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT, .tiling = VK_IMAGE_TILING_OPTIMAL,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
        .initialLayout = VK_IMAGE_LAYOUT_UNDEFINED };
    if (T.vkCreateImage(g_hvk.dev, &ici, NULL, &g_ps.fs_img) != VK_SUCCESS) {
        g_ps.fs_img = VK_NULL_HANDLE;
        return false;
    }
    VkMemoryRequirements mr;
    T.vkGetImageMemoryRequirements(g_hvk.dev, g_ps.fs_img, &mr);
    int mt = hle_vk_memtype(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
                                 .allocationSize = mr.size, .memoryTypeIndex = (uint32_t)mt };
    VkImageViewCreateInfo vci = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO, .image = g_ps.fs_img,
        .viewType = VK_IMAGE_VIEW_TYPE_2D, .format = VK_FORMAT_R8G8B8A8_UNORM,
        .subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 } };
    if (mt < 0 || T.vkAllocateMemory(g_hvk.dev, &mai, NULL, &g_ps.fs_mem) != VK_SUCCESS ||
        T.vkBindImageMemory(g_hvk.dev, g_ps.fs_img, g_ps.fs_mem, 0) != VK_SUCCESS ||
        T.vkCreateImageView(g_hvk.dev, &vci, NULL, &g_ps.fs_view) != VK_SUCCESS) {
        hle_log("hakuX-hle-back", "present: no %ux%u FSR image: FSR off", w, h);
        T.vkDestroyImage(g_hvk.dev, g_ps.fs_img, NULL);
        g_ps.fs_img = VK_NULL_HANDLE;
        g_ps.pipe[P_EASU] = VK_NULL_HANDLE;
        return false;
    }
    g_ps.fs_w = w;
    g_ps.fs_h = h;
    g_ps.fs_serial = 0;
    return true;
}

"""

FSR_PASS = r"""    /* FSR 1.0 (HAKUX_FSR): EASU scales the frame up to its place in the
     * window into an image of ours (once per frame, however many refreshes
     * show it), then the window pass runs RCAS over it, through the ramp. */
    bool use_fsr = false;
#if HAKUX_FSR
    if (g_ps.have && upscale == HLE_UPSCALE_SGSR && g_ps.pipe[P_EASU] && g_ps.pipe[P_RCAS]) {
        const float fvw = sx * (float)ww, fvh = sy * (float)wh;
        const bool fdown = (g_ps.f.w > fvw * 1.01f || g_ps.f.h > fvh * 1.01f) && average && g_ps.pipe[P_AREA];
        const bool fup = (float)g_ps.f.w < fvw * 0.99f || (float)g_ps.f.h < fvh * 0.99f;
        const uint32_t ow = (uint32_t)(fvw + 0.5f), oh = (uint32_t)(fvh + 0.5f);
        if (!fdown && fup && ow >= 2 && oh >= 2 && ow <= 8192 && oh <= 8192 && fsr_target(ow, oh)) {
            if (g_ps.fs_serial != g_ps.serial || g_ps.fs_fxaa != fxaa_done) {
                barrier(cb, g_ps.fs_img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0,
                        VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
                rendering(cb, g_ps.fs_view, ow, oh, false);
                const float easu[12] = { -1.0f, -1.0f, 1.0f, 1.0f,
                                         1.0f / (float)g_ps.f.w, 1.0f / (float)g_ps.f.h,
                                         (float)g_ps.f.w, (float)g_ps.f.h,
                                         1.0f, 0.0f, 0.0f, 1.0f };
                quad(cb, f, 2, g_ps.pipe[P_EASU], src, easu, ow, oh);
                T.vkCmdEndRendering(cb);
                barrier(cb, g_ps.fs_img, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT,
                        VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
                        VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                g_ps.fs_serial = g_ps.serial;
                g_ps.fs_fxaa = fxaa_done;
            }
            use_fsr = true;
        }
    }
#endif
"""

FSR_SELECT = r"""} else if (use_fsr) {
            p = P_RCAS;
            src = g_ps.fs_view;
            pc[4] = 0.8706f;            /* RCAS sharpness 0.2 stops: exp2(-0.2) */
        } else if ((rx < 0.99f || ry < 0.99f) && upscale == HLE_UPSCALE_SGSR && g_ps.pipe[P_SGSR]) {"""

edits = [
    # (anchor, replacement)
    ("enum { P_BILINEAR, P_AREA, P_SGSR, P_FXAA, P_COUNT };",
     "/* FSR 1.0 on, replacing the SGSR scaling path (0: SGSR as before) */\n"
     "#ifndef HAKUX_FSR\n#define HAKUX_FSR 1\n#endif\n\n"
     "/* P_RCAS draws into the window (its format), P_EASU into an RGBA8 image */\n"
     "enum { P_BILINEAR, P_AREA, P_SGSR, P_RCAS, P_FXAA, P_EASU, P_COUNT };"),
    ("VkDescriptorSet set[2];", "VkDescriptorSet set[3];"),
    ("    /* the display's gamma ramp */\n    uint8_t ramp[3][256];",
     "    /* FSR's EASU image, at the frame's place in the window */\n"
     "    VkImage fs_img;\n    VkDeviceMemory fs_mem;\n    VkImageView fs_view;\n"
     "    uint32_t fs_w, fs_h;\n    uint64_t fs_serial;         /* the frame it holds */\n"
     "    bool fs_fxaa;               /* ... made from FXAA's image */\n"
     "    /* the display's gamma ramp */\n    uint8_t ramp[3][256];"),
    ("static VkShaderModule module(HleShaderStage stage",
     cstr("k_fs_easu", EASU) + cstr("k_fs_rcas", RCAS) + "\n"
     "static VkShaderModule module(HleShaderStage stage"),
    ("VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, PS_FRAMES * 2 }",
     "VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, PS_FRAMES * 3 }"),
    ("VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, PS_FRAMES * 2 }",
     "VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, PS_FRAMES * 3 }"),
    (".maxSets = PS_FRAMES * 2,", ".maxSets = PS_FRAMES * 3,"),
    ("VkDescriptorSetLayout l[2] = { g_ps.dsl, g_ps.dsl };",
     "VkDescriptorSetLayout l[3] = { g_ps.dsl, g_ps.dsl, g_ps.dsl };"),
    (".descriptorPool = g_ps.dp, .descriptorSetCount = 2,",
     ".descriptorPool = g_ps.dp, .descriptorSetCount = 3,"),
    ("static const char *const body[P_COUNT] = { k_fs_bilinear, k_fs_area, k_fs_sgsr, k_fs_fxaa };",
     "static const char *const body[P_COUNT] = { k_fs_bilinear, k_fs_area, k_fs_sgsr, k_fs_rcas, k_fs_fxaa,\n"
     "                                                k_fs_easu };"),
    ("    g_ps.pipe[P_FXAA] = pipeline(g_ps.fs[P_FXAA], VK_FORMAT_R8G8B8A8_UNORM);\n",
     "    g_ps.pipe[P_FXAA] = pipeline(g_ps.fs[P_FXAA], VK_FORMAT_R8G8B8A8_UNORM);\n"
     "    g_ps.pipe[P_EASU] = pipeline(g_ps.fs[P_EASU], VK_FORMAT_R8G8B8A8_UNORM);\n"),
    ("static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,",
     FSR_TARGET.lstrip("\n") + "static void barrier(VkCommandBuffer cb, VkImage img, VkImageLayout from, VkImageLayout to,"),
    ("    VkImage dst = g_ps.img[g_ps.idx];\n", FSR_PASS + "    VkImage dst = g_ps.img[g_ps.idx];\n"),
    ("} else if ((rx < 0.99f || ry < 0.99f) && upscale == HLE_UPSCALE_SGSR && g_ps.pipe[P_SGSR]) {",
     FSR_SELECT),
    ('p == P_AREA ? "averaged down" : p == P_SGSR ? "scaled up by SGSR" : "bilinear");',
     'p == P_AREA ? "averaged down" : p == P_RCAS ? "scaled up by FSR 1" :\n'
     '            p == P_SGSR ? "scaled up by SGSR" : "bilinear");'),
]

for anchor, repl in edits:
    n = src.count(anchor)
    if n != 1:
        sys.exit("ANCHOR %s (found %d times, need 1):\n%s" % ("NOT FOUND" if n == 0 else "AMBIGUOUS", n, anchor))
    src = src.replace(anchor, repl)

open(path, "w", encoding="utf-8").write(src)
print("FSR 1.0 added to", path, "(%d edits)" % len(edits))
