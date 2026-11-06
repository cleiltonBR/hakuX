# Neural Super Sampling (NSS) — HakuX integration plan

Status: research and integration preparation; NSS is not yet implemented.

## Goal

Evaluate Arm Neural Graphics SDK Neural Super Sampling as an optional Vulkan post-processing path in HakuX. Keep NIS, FSR, SGSR, CAS, and anisotropic filtering independent and available. Bloom is out of scope.

## Upstream components

- Arm Neural Graphics SDK: https://github.com/arm/neural-graphics-sdk-for-game-engines
- Arm Vulkan ML Emulation Layer: https://github.com/arm/ai-ml-emulation-layer-for-vulkan
- NSS model: https://huggingface.co/Arm/neural-super-sampling

Pin exact SDK, model, and emulation-layer revisions before importing binaries or source into the app. Do not track upstream main at build time.

## Findings

1. The SDK exposes a Vulkan backend and Android AArch64 build target. Its current integration guide documents NSS and NFRU.
2. NSS is temporal super-resolution, not a spatial-only drop-in shader. Its dispatch contract requires current color, depth, motion vectors, jitter offset, frame time, exposure/camera parameters, output, and temporal history.
3. The HakuX presenter currently receives a completed color frame and performs post-processing. The existing NIS integration is spatial. It does not currently provide NSS with a verified per-frame motion-vector buffer, camera jitter, near/far planes, or vertical FOV.
4. Depth-resource tracing is now partially confirmed in the HLE Vulkan backend: `vk/surf.c` creates depth images with depth/stencil-attachment, sampled, and transfer usage; `vk/back.c` binds the active `HleVkSurf` as the render pass depth attachment and stores it; `vk/zread.c` creates depth/stencil image views and can sample the depth image in a conversion pass. `vk/draw.c` also identifies guest depth surfaces used as shadow-buffer textures. This proves that real depth resources exist and are used by rendering, but does not yet prove that a stable, correctly normalized depth image can be handed to NSS at the presenter boundary. The depth format, reversed-Z convention, near/far reconstruction, frame association, and synchronization/lifetime still need validation. The existing ZREAD path converts depth into a guest color texture; it is not itself an NSS input bridge.
5. The SDK requires Vulkan 1.3+, VK_ARM_tensors, VK_ARM_data_graph, synchronization2, and shader/device features selected by the shader path. The SDK documentation describes Arm's ML Emulation Layer for systems without native ML extensions.
6. Turnip provides the Vulkan driver path used by this project, but does not itself imply that the Arm ML extensions exist. The Arm emulation layer must be packaged, discovered, enabled in instance creation, and validated against the exact Turnip build.
7. The project creates its own Vulkan instance/device in hw/xbox/hle/vk/vkdev.c. Android's app packages the native core as a prebuilt library; the native CI/build pipeline must be extended rather than only changing the Android Gradle module.
8. A software/emulated ML graph path can execute on Vulkan compute hardware, but its performance on Adreno/Turnip is unknown and must be measured. Do not promise a speedup or assume that successful compilation means the runtime path works.



### Confirmed HLE depth path (2026-11-05)

- `hw/xbox/hle/vk/surf.c`: HLE-owned depth surfaces are allocated as Vulkan depth/stencil images and include `VK_IMAGE_USAGE_SAMPLED_BIT`, `VK_IMAGE_USAGE_TRANSFER_SRC_BIT`, and `VK_IMAGE_USAGE_TRANSFER_DST_BIT`.
- `hw/xbox/hle/vk/back.c`: the active render-target/depth pair is acquired before dynamic rendering; the depth surface is transitioned to attachment layout, bound through `VkRenderingInfo.pDepthAttachment`, and stored after the pass. The depth surface's write generation is tracked.
- `hw/xbox/hle/vk/zread.c`: depth and stencil aspect views are created and sampled in a depth-to-color conversion pass. This path is used for guest ZREAD semantics, not a temporal upscaler interface.
- `hw/xbox/hle/vk/draw.c`: guest depth surfaces can be recognized as shadow-buffer textures, and depth-write barriers exist for local framebuffer reads.
- Remaining blocker: the presenter in `swapchain.c` consumes the completed color frame, not a synchronized color+depth+motion bundle. We have not yet found a verified per-pixel motion-vector source or camera jitter/near/far/FOV metadata. Therefore NSS still must not be dispatched.

## Optical-flow motion-vector route (2026-11-06)

A promising alternative to reconstructing guest motion vectors has been identified: Arm ML Emulation Layer **v0.10.0** adds the `VK_ARM_data_graph_optical_flow` API. Its optical-flow graph node estimates forward 2D displacement between a current input image and a reference image, and exposes flow-vector and optional cost outputs. This may provide a real motion field from consecutive completed HakuX color frames, without pretending that the Xbox guest vertex shader already supplies previous-frame positions.

This is now the preferred feasibility prototype, ahead of instrumenting the guest vertex pipeline, but it is **not yet an NSS-ready motion-vector source**. Before using it, HakuX must:
- Build and package both Arm Graph and Tensor emulation layers; enable Graph before Tensor.
- Confirm that the selected Vulkan loader/Turnip stack exposes the optical-flow extension after the layers are enabled.
- Query the graph operation, queue-family properties, image formats, grid size, and required usage flags instead of assuming formats or dimensions.
- Feed correctly ordered current/reference frames and validate the API's forward-flow direction against the motion-vector convention expected by NSS.
- Track flow confidence/cost, disocclusion, camera cuts, scaling, and frame timing; reject low-confidence or stale vectors.
- Measure the compute cost on Adreno/Turnip. The emulation layer implements ML extensions using Vulkan compute, so availability does not imply hardware optical-flow acceleration or a performance win.

The passive Vulkan capability log now reports `VK_ARM_data_graph_optical_flow` separately from `VK_ARM_tensors` and `VK_ARM_data_graph`. This is only an extension-presence probe; until the layers are packaged and explicitly enabled, a negative result does not prove that the emulated extension is unavailable.

Arm documents the optical-flow API in Emulation Layer v0.10.0, and the Vulkan specification defines its output as forward displacement from the input image to the reference image. These details make it a plausible motion-vector source, not a substitute for end-to-end validation.

## Integration stages

### Stage 1 — capability and packaging proof

- Pin a compatible Arm Neural Graphics SDK release, NSS model revision, and ML Emulation Layer release.
- Build the Android AArch64 ML emulation layers using the upstream Android packaging workflow.
- Package the graph and tensor layers into a debug-only HakuX APK (arm64-v8a), with required manifests and libraries.
- Add opt-in instance-layer activation before vkCreateInstance; keep it disabled by default.
- Log layer discovery, enabled layers, Vulkan API version, required extension names, queried feature bits, and explicit failure reasons.
- Verify that the HLE-created Vulkan device can be created with the layer enabled on Turnip. Do not change normal Vulkan device creation when NSS is off.

### Stage 2 — NSS input feasibility

- Trace the guest color and depth surface resources and validate formats, layouts, dimensions, and synchronization.
- Determine whether reliable motion vectors can be sourced from the emulator. If not, prototype a separate optical-flow estimation pass from consecutive frames and measure its cost/quality; never pass a zero-filled or fabricated vector buffer as if it were valid.
- Determine whether the emulated title can provide meaningful camera near/far/FOV and jitter. If unavailable, test only documented NSS configurations that can validly operate without those inputs; do not silently invent camera parameters.
- Implement history ping-pong, reset on title/scene changes, and correct Vulkan resource barriers.

### Stage 3 — experimental NSS path

- Integrate the pinned SDK using its Vulkan backend and component API.
- Add an independent experimental NSS toggle and quality presets.
- Dispatch NSS only when every required capability and input is valid; otherwise fall back to the existing presentation path with a visible reason.
- Add diagnostics for context creation, dispatch result, actual output image, input/output dimensions, history reset, and GPU completion.
- Compare image quality, frame time, GPU time, memory, and stutter against NIS and SGSR on the RedMagic 11 Air with Turnip.

### Stage 4 — NFRU evaluation

NFRU is out of scope until NSS is validated. It needs additional optical-flow support, frame-generation preparation/dispatch, and presentation pacing; it must not be enabled by merely adding the SDK library.

## Acceptance criteria

- NSS disabled: existing HakuX rendering and all current filters behave unchanged.
- NSS enabled: layer and required capabilities are positively verified; all required input resources are valid; SDK context creation and dispatch return success; the NSS output is the actual image presented.
- GPU completion is verified separately from command recording.
- Debug view and diagnostics can distinguish unavailable capability, invalid input, context failure, dispatch failure, and successful output.
- No release is published and no pull request is merged without explicit user authorization.

### Depth-format and motion-vector findings (2026-11-06)

The HLE surface-format mapping in `hw/xbox/hle/vk/surf.c` now narrows the depth-format investigation:

| Guest surface format | Vulkan backing format selected by HLE | Notes |
|---|---|---|
| D24S8 / LIN_D24S8 | D24_UNORM_S8_UINT when `g_hvk.caps.d24s8` is true; otherwise D32_SFLOAT_S8_UINT | The capability flag is a backend decision; the fallback changes precision/representation. |
| F24S8 / LIN_F24S8 | D32_SFLOAT_S8_UINT | Guest F24S8 is backed by a 32-bit floating-point depth/stencil format. |
| D16 / LIN_D16 | D16_UNORM | 16-bit normalized depth. |
| F16 / LIN_F16 | D32_SFLOAT | Guest F16 is backed by a 32-bit floating-point depth format. |

These are the selected Vulkan storage formats, not proof of the game's projection convention or of a normalized linear-depth signal. The NSS bridge must inspect the actual active surface format and dimensions per frame, preserve the depth aspect, and explicitly handle the selected backing format. It must not assume that all games use the same depth representation.

The NV2A vertex-program translator in `hw/xbox/hle/shader/vsh.c` confirms that the guest program's `oPos` (R12) is screen-space position for the current draw. The translator maps that position to Vulkan `gl_Position`; its output interface carries diffuse/specular colors, fog, point size, and texture coordinates. This path does not expose a previous-frame clip position or a dedicated per-pixel motion-vector semantic. Therefore current `oPos` cannot be repurposed as motion vectors, and the existing translated vertex shader is not sufficient to synthesize temporal motion without additional previous-frame state and reliable correspondence.

#### Updated implementation decision

1. Treat the depth backing-format mapping as known, but keep depth convention, linearization, per-frame association, and synchronization as unresolved runtime validations.
2. Do not add a fake motion-vector attachment or infer motion from current-frame position alone.
3. Before a temporal NSS dispatch, prototype one of two real data paths: (a) instrument guest transform/state history to derive previous-to-current screen-space motion where the guest pipeline provides enough information, or (b) evaluate optical flow from consecutive completed color frames as a separate compute pass. Both paths need confidence/validity handling, disocclusion treatment, and measured GPU cost.
4. Keep NSS disabled and preserve the existing NIS/FSR/SGSR/CAS/anisotropic-filter paths until a complete input bundle and actual presented NSS output are verified.


## SDK dependency and build integration (2026-11-06)

- Added Arm Neural Graphics SDK **v1.1.1** as a Git submodule at `third_party/arm-neural-graphics-sdk`, pinned to upstream commit `019e99a62ea3a331fe112516190d77befc779a53`. Initialize it with `git submodule update --init --recursive`.
- Added opt-in CMake switch `HAKUX_NSS_SDK` (default `OFF`). With Android `arm64-v8a` and CMake 3.23+, enabling it adds the SDK's `ffx-api` subproject with `vk_android_arm`, NSS enabled, frame interpolation, optical flow, and capture disabled. The Android platform variables are set explicitly because HakuX embeds the `ffx-api` subproject rather than the SDK's all-components top-level build. The resulting API/backend target is linked to `hakux_hle`.
- This is a dependency/build integration milestone, **not a working NSS renderer**. The Arm Vulkan ML Emulation Layer, its Graph/Tensor libraries and manifests, the NSS VGF model files are present in the pinned SDK under `sdk/src/backends/vk/data_graphs/nss/`, but model loading and Android runtime packaging are not yet integrated. No third-party layer is enabled in `vkCreateInstance`.
- The SDK's top-level CMake project also builds an optional Arm NG Vulkan layer; HakuX currently includes only `ffx-api`, so that layer is not built or packaged by this integration.
- Arm documents Android AArch64 support, but this does not certify Adreno/Turnip as a production target. The ML Emulation Layer is useful for functional testing on Vulkan compute devices; emulation performance is not representative of production performance.
- NSS remains disabled. The presenter still lacks a verified per-pixel motion-vector source and the temporal camera/jitter metadata needed for a valid dispatch. Never dispatch with fabricated/zero motion vectors.

### Next gates

1. Initialize the SDK submodule and its nested dependencies recursively, then run the Android ARM64 build with `-DHAKUX_NSS_SDK=ON`; fix any toolchain/API integration errors before adding runtime code.
2. Pin/build the Arm Vulkan ML Emulation Layer for Android ARM64 and package its Graph/Tensor libraries/manifests in debug builds. Preserve Graph-before-Tensor ordering.
3. Use the SDK-pinned quantized NSS VGF assets and implement safe model asset loading/packaging.
4. Add opt-in Vulkan layer activation and query the actual enabled extension/feature chain; extension-name enumeration alone is insufficient.
5. Build the color/depth/motion/history bridge and only then dispatch NSS. Keep existing NIS/FSR/SGSR paths unchanged until the NSS output is verified on-screen.


### v1.1.1 selection note

The upstream v1.1.1 release (30 October 2026) supersedes v1.1.0 and includes NSS LDR-input support plus optional motion-vector jitter cancellation. It also changes the NSS/NFRU source and ABI interfaces relative to v1.1.0, so HakuX pins and targets v1.1.1 before any API bridge is written. The SDK repository includes the quantized NSS VGF model files; they still need an explicit runtime asset-loading and packaging path in HakuX.
