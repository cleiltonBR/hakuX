# hakuX native core: the HLE GPU layer (hw/xbox/hle) on its host interface
# (hw/xbox/hle/host.h, native/hle) and the GLib subset (native/compat/glib);
# the MCPX APU (native/dev/apu).
#
# Vulkan dependencies come from NC_DEPS (same revisions as the app build):
#   volk, glslang, spirv_reflect, vma, root/subprojects/nv2a_vsh_cpu.
set(NC_DEPS "D:/tools/native-deps" CACHE PATH "native core third-party sources")

add_library(nc_volk STATIC "${NC_DEPS}/volk/volk.c")
target_include_directories(nc_volk PUBLIC "${NC_DEPS}/volk")
target_compile_definitions(nc_volk PUBLIC VK_NO_PROTOTYPES VK_USE_PLATFORM_ANDROID_KHR)

add_library(nc_spirv_reflect STATIC "${NC_DEPS}/spirv_reflect/spirv_reflect.c")
target_include_directories(nc_spirv_reflect PUBLIC "${NC_DEPS}/spirv_reflect")

set(ENABLE_OPT OFF CACHE BOOL "" FORCE)
set(ENABLE_HLSL OFF CACHE BOOL "" FORCE)
set(ENABLE_GLSLANG_BINARIES OFF CACHE BOOL "" FORCE)
set(ENABLE_SPVREMAPPER OFF CACHE BOOL "" FORCE)
set(ENABLE_CTEST OFF CACHE BOOL "" FORCE)
set(GLSLANG_TESTS OFF CACHE BOOL "" FORCE)
set(GLSLANG_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
add_subdirectory("${NC_DEPS}/glslang" glslang-build EXCLUDE_FROM_ALL)

set(NC_HLE "${NC_REPO}/hw/xbox/hle")

# Embed the upstream NIS GLSL sources so Android can resolve the SDK include
# without depending on a filesystem path at runtime.
set(NC_NIS_GENERATED "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${NC_NIS_GENERATED}")
file(READ "${NC_REPO}/third_party/NVIDIAImageScaling/NIS_Main.glsl" NC_NIS_MAIN_SOURCE)
file(READ "${NC_REPO}/third_party/NVIDIAImageScaling/NIS_Scaler.h" NC_NIS_SCALER_SOURCE)
file(WRITE "${NC_NIS_GENERATED}/nis_shader_sources.h"
  "#pragma once\n"
  "static const char k_hle_nis_main[] = R\"HAKUXNISMAIN(${NC_NIS_MAIN_SOURCE})HAKUXNISMAIN\";\n"
  "static const char k_hle_nis_scaler[] = R\"HAKUXNISSCALER(${NC_NIS_SCALER_SOURCE})HAKUXNISSCALER\";\n"
)

# The GLib subset the HLE and the APU use.
add_library(nc_glib STATIC "${CMAKE_CURRENT_SOURCE_DIR}/compat/glib/glib.c")
target_include_directories(nc_glib PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}/compat/glib")
set(NC_VSH "${NC_DEPS}/root/subprojects/nv2a_vsh_cpu/src")

# Every HLE GPU source.
add_library(hakux_hle STATIC
  ${NC_HLE}/hle.c
  ${NC_HLE}/hitch.c
  ${NC_HLE}/core/title.c
  ${NC_HLE}/core/probe.c
  ${NC_HLE}/core/xbe_guest.c
  ${NC_HLE}/front/arena.c
  ${NC_HLE}/front/fe.c
  ${NC_HLE}/front/ring.c
  ${NC_HLE}/mem/barrier.c
  ${NC_HLE}/mem/guestmem.c
  ${NC_HLE}/mem/pageuse.c
  ${NC_HLE}/pb/kelvin.c
  ${NC_HLE}/pb/pbdraw.c
  ${NC_HLE}/pb/pbmode.c
  ${NC_HLE}/pb/pbres.c
  ${NC_HLE}/pb/pbstate.c
  ${NC_HLE}/pb/pusher.c
  ${NC_HLE}/shader/ff_gen.c
  ${NC_HLE}/shader/ffhw.c
  ${NC_HLE}/shader/glsl_compile.c
  ${NC_HLE}/shader/psh.c
  ${NC_HLE}/shader/vsh.c
  ${NC_HLE}/tex/xdk_res.c
  ${NC_HLE}/tex/xg_swizzle.c
  ${NC_HLE}/uma/uma.c
  ${NC_HLE}/util/thread.c
  ${NC_HLE}/vk/back.c
  ${NC_HLE}/vk/display.c
  ${NC_HLE}/vk/swapchain.c
  ${NC_HLE}/vk/nis_coefficients.cpp
  ${NC_HLE}/vk/batch.c
  ${NC_HLE}/vk/compat.c
  ${NC_HLE}/vk/draw.c
  ${NC_HLE}/vk/quirks.c
  ${NC_HLE}/vk/gamma.c
  ${NC_HLE}/vk/guest_ram.c
  ${NC_HLE}/vk/pcache.c
  ${NC_HLE}/vk/pipeasync.c
  ${NC_HLE}/vk/surf.c
  ${NC_HLE}/vk/timeline.c
  ${NC_HLE}/vk/vkdev.c
  ${NC_HLE}/vk/zread.c
  ${NC_VSH}/nv2a_vsh_disassembler.c
  ${NC_VSH}/nv2a_vsh_cpu.c
  ${NC_VSH}/nv2a_vsh_emulator.c
  ${NC_VSH}/nv2a_vsh_emulator_execution_state.c
  ${CMAKE_CURRENT_SOURCE_DIR}/hle/glue.c
  ${CMAKE_CURRENT_SOURCE_DIR}/hle/host_native.c
  ${CMAKE_CURRENT_SOURCE_DIR}/hle/dirty.c
)
target_include_directories(hakux_hle PUBLIC "${NC_REPO}" "${NC_DEPS}/root" "${NC_DEPS}/vma/include"
                           "${NC_DEPS}/glslang"
                    PRIVATE "${NC_NIS_GENERATED}")
# xxhash (single header, FEX's copy): pb/pbdraw.c's span hashes
target_include_directories(hakux_hle PRIVATE "${HAKUX_FEX_DIR}/External/xxhash")
target_compile_definitions(hakux_hle PUBLIC CONFIG_VULKAN=1 HAKUX_HLE_GPU=1 HAKUX_NATIVE_CORE=1)
target_compile_options(hakux_hle PRIVATE -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare)
target_link_libraries(hakux_hle PUBLIC nc_glib nc_volk nc_spirv_reflect glslang SPIRV
                      glslang-default-resource-limits android log)

# Optional Arm Neural Graphics SDK NSS API/backend. Kept OFF by default:
# ordinary HakuX builds do not acquire a new Vulkan/SDK dependency.
option(HAKUX_NSS_SDK "Build and link the pinned Arm NSS SDK for Android ARM64" OFF)
if(HAKUX_NSS_SDK)
  if(NOT ANDROID OR NOT CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "HAKUX_NSS_SDK requires the Android arm64-v8a toolchain")
  endif()
  if(CMAKE_VERSION VERSION_LESS 3.21)
    message(FATAL_ERROR "Arm Neural Graphics SDK v1.1.0 requires CMake 3.21 or newer")
  endif()

  set(HAKUX_ARM_NGSDK "${NC_REPO}/third_party/arm-neural-graphics-sdk")
  if(NOT EXISTS "${HAKUX_ARM_NGSDK}/ffx-api/CMakeLists.txt")
    message(FATAL_ERROR
      "Arm Neural Graphics SDK missing. Initialize submodules recursively: "
      "git submodule update --init --recursive")
  endif()

  # Build only the NSS component (not frame interpolation or optical flow).
  # The separate ML Emulation Layer and quantized NSS model are runtime
  # dependencies and are intentionally not assumed to be present here.
  set(FFX_API_BACKEND vk_android_arm CACHE STRING "" FORCE)
  set(FFX_NSS ON CACHE BOOL "" FORCE)
  set(FFX_FI OFF CACHE BOOL "" FORCE)
  set(FFX_OF OFF CACHE BOOL "" FORCE)
  set(FFX_ALL OFF CACHE BOOL "" FORCE)
  add_subdirectory("${HAKUX_ARM_NGSDK}/ffx-api"
                   "${CMAKE_CURRENT_BINARY_DIR}/arm_ngsdk_ffx_api"
                   EXCLUDE_FROM_ALL)
  if(NOT TARGET ngsdk_android_arm)
    message(FATAL_ERROR "Arm NSS SDK did not define ngsdk_android_arm")
  endif()
  target_link_libraries(hakux_hle PUBLIC ngsdk_android_arm)
  target_compile_definitions(hakux_hle PUBLIC HAKUX_NSS_SDK_AVAILABLE=1)
  message(STATUS "hakuX: Arm Neural Graphics SDK v1.1.0 API/backend linked; NSS runtime remains gated")
endif()

# The MCPX APU: xemu's device (hw/xbox/mcpx/apu) ported onto the core's own
# services (native/dev/apu/apu_base.h).
set(NC_APU "${CMAKE_CURRENT_SOURCE_DIR}/dev/apu")
add_library(hakux_apu STATIC
  ${NC_APU}/apu.c
  ${NC_APU}/out.c
  ${NC_APU}/state.c
  ${NC_APU}/dsp/debug.c
  ${NC_APU}/dsp/dsp.c
  ${NC_APU}/dsp/dsp_cpu.c
  ${NC_APU}/dsp/dsp_dma.c
  ${NC_APU}/dsp/gp_ep.c
  ${NC_APU}/vp/resample.c
  ${NC_APU}/vp/vp.c
)
target_include_directories(hakux_apu PRIVATE "${NC_REPO}")
target_compile_options(hakux_apu PRIVATE -Wno-unused-parameter -Wno-unused-function -Wno-sign-compare
                       -Wno-unused-variable)
target_link_libraries(hakux_apu PUBLIC hakux_hle aaudio log)
