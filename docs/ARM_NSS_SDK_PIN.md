# Arm Neural Graphics SDK dependency

HakuX targets the Arm Neural Graphics SDK v1.1.0 release, pinned to upstream commit `aba0d109ffcfb97e380ac0a68fb11683e5561c6e`.

Upstream: https://github.com/arm/neural-graphics-sdk-for-game-engines

The SDK supports Android AArch64 and includes NSS. This pin alone does not enable NSS: the Vulkan ML Emulation Layer, quantized NSS model, Android layer manifests/libraries, and temporal input bridge still need to be integrated and validated. Keep NSS disabled until those requirements are met.

The current HakuX Adreno/Turnip target is not a documented Arm production-validated GPU target. The ML Emulation Layer can be used for functional experiments on Vulkan compute devices, but emulation performance is not representative of production hardware.
