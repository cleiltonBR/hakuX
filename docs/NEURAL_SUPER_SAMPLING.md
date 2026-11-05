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
4. The HLE renderer owns guest color/depth surfaces, but the exact depth resource, coordinate convention, and lifetime suitable for NSS still need to be traced and validated. A guest depth buffer is not automatically a valid NSS depth input.
5. The SDK requires Vulkan 1.3+, VK_ARM_tensors, VK_ARM_data_graph, synchronization2, and shader/device features selected by the shader path. The SDK documentation describes Arm's ML Emulation Layer for systems without native ML extensions.
6. Turnip provides the Vulkan driver path used by this project, but does not itself imply that the Arm ML extensions exist. The Arm emulation layer must be packaged, discovered, enabled in instance creation, and validated against the exact Turnip build.
7. The project creates its own Vulkan instance/device in hw/xbox/hle/vk/vkdev.c. Android's app packages the native core as a prebuilt library; the native CI/build pipeline must be extended rather than only changing the Android Gradle module.
8. A software/emulated ML graph path can execute on Vulkan compute hardware, but its performance on Adreno/Turnip is unknown and must be measured. Do not promise a speedup or assume that successful compilation means the runtime path works.

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
