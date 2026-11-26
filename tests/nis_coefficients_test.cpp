#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>

#include "hw/xbox/hle/vk/nis_coefficients.h"
#include "third_party/NVIDIAImageScaling/NIS_Config.h"

static bool check(bool condition, const char *message)
{
    if (!condition) {
        std::fprintf(stderr, "NIS host test failed: %s\n", message);
    }
    return condition;
}

int main()
{
    static_assert(sizeof(NISConfig) == 256, "NIS uniform ABI must remain 256 bytes");

    std::array<float, HLE_NIS_COEFFICIENT_FLOATS> scaler{};
    std::array<float, HLE_NIS_COEFFICIENT_FLOATS> usm{};
    hle_nis_copy_coefficients(scaler.data(), scaler.size(), usm.data(), usm.size());

    const bool scaler_valid = std::all_of(scaler.begin(), scaler.end(),
        [](float value) { return std::isfinite(value); });
    const bool usm_valid = std::all_of(usm.begin(), usm.end(),
        [](float value) { return std::isfinite(value); });
    const bool scaler_populated = std::any_of(scaler.begin(), scaler.end(),
        [](float value) { return value != 0.0f; });
    const bool usm_populated = std::any_of(usm.begin(), usm.end(),
        [](float value) { return value != 0.0f; });

    if (!check(scaler_valid && usm_valid, "coefficient banks contain non-finite values") ||
        !check(scaler_populated && usm_populated, "coefficient banks were not copied")) {
        return 1;
    }

    NISConfig config{};
    if (!check(hle_nis_update_scaler_config(&config, sizeof(config), 0.5f,
                                            1280, 720, 2560, 1440) == 1,
               "valid 2x scaler configuration was rejected") ||
        !check(config.kInputViewportWidth == 1280 && config.kInputViewportHeight == 720,
               "input viewport dimensions do not match") ||
        !check(config.kOutputViewportWidth == 2560 && config.kOutputViewportHeight == 1440,
               "output viewport dimensions do not match") ||
        !check(std::fabs(config.kScaleX - 0.5f) < 0.000001f &&
               std::fabs(config.kScaleY - 0.5f) < 0.000001f,
               "SDK scale constants do not match the 2x target")) {
        return 1;
    }

    if (!check(hle_nis_update_scaler_config(&config, sizeof(config), 0.0f,
                                            1280, 720, 1920, 1080) == 1,
               "0% sharpness configuration was rejected") ||
        !check(hle_nis_update_scaler_config(&config, sizeof(config), 1.0f,
                                            1280, 720, 1920, 1080) == 1,
               "100% sharpness configuration was rejected") ||
        !check(hle_nis_update_scaler_config(&config, sizeof(config) - 1, 0.5f,
                                            1280, 720, 1920, 1080) == 0,
               "undersized uniform buffer was accepted") ||
        !check(hle_nis_update_scaler_config(&config, sizeof(config), 0.5f,
                                            0, 720, 1920, 1080) == 0,
               "zero input dimension was accepted") ||
        !check(hle_nis_update_scaler_config(&config, sizeof(config), 0.5f,
                                            1280, 720, 3840, 2160) == 0,
               "unsupported 3x scale was accepted")) {
        return 1;
    }

    uint32_t groups_x = 0, groups_y = 0;
    hle_nis_get_dispatch_size(1920, 1080, &groups_x, &groups_y);
    if (!check(groups_x == 60 && groups_y == 45,
               "1920x1080 dispatch must use 60x45 groups")) {
        return 1;
    }
    hle_nis_get_dispatch_size(1, 1, &groups_x, &groups_y);
    if (!check(groups_x == 1 && groups_y == 1,
               "small non-zero output must use one workgroup")) {
        return 1;
    }
    hle_nis_get_dispatch_size(0, 0, &groups_x, &groups_y);
    if (!check(groups_x == 0 && groups_y == 0,
               "zero-sized output must not dispatch")) {
        return 1;
    }

    std::puts("NIS host validation passed: SDK coefficients, 256-byte config, sharpness bounds and 32x24 dispatch sizing.");
    return 0;
}
