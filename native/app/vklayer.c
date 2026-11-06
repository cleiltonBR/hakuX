/*
 * hakuX native core, app front end — Vulkan layers over the custom driver
 * (debug). adrenotools hands the core Turnip's own vkGetInstanceProcAddr,
 * past Android's Vulkan loader, so the GPU debug-layer settings never reach
 * it. With debug.hakux.vkvalidation=1 at launch the Khronos validation
 * layer (debug APKs ship it beside the core) goes in front of the driver
 * here, which does the loader's part: the layer's link in the instance and
 * device create infos. A loader also writes its dispatch table into the
 * first word of each dispatchable object, which the layer files its state
 * under; Turnip reads that word itself (a key written there crashed its
 * vkGetInstanceProcAddr), so the driver's value stays: one for every object,
 * which the layer's separate instance and device maps take, the core making
 * one instance and one device.
 */
#ifndef VK_NO_PROTOTYPES                /* volk's targets define it */
#define VK_NO_PROTOTYPES
#endif
#include "native/app/app.h"

#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include "hw/xbox/hle/prop.h"
#include <vulkan/vulkan.h>
#include <vulkan/vk_layer.h>

#define LAYER_LIB "libVkLayer_khronos_validation.so"

static PFN_vkGetInstanceProcAddr g_drv_gipa, g_layer_gipa;
static VkInstance g_inst;

/* objects the layer makes itself keep the driver's word too */
static VkResult VKAPI_CALL set_instance_data(VkInstance inst, void *obj)
{
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL set_device_data(VkDevice dev, void *obj)
{
    return VK_SUCCESS;
}

static VkResult VKAPI_CALL create_instance(const VkInstanceCreateInfo *ci,
                                           const VkAllocationCallbacks *a, VkInstance *out)
{
    VkLayerInstanceLink link = { .pfnNextGetInstanceProcAddr = g_drv_gipa };
    VkLayerInstanceCreateInfo li = {
        .sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO, .pNext = ci->pNext,
        .function = VK_LAYER_LINK_INFO, .u.pLayerInfo = &link };
    VkLayerInstanceCreateInfo ld = {
        .sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO, .pNext = &li,
        .function = VK_LOADER_DATA_CALLBACK, .u.pfnSetInstanceLoaderData = set_instance_data };
    VkInstanceCreateInfo c = *ci;
    c.pNext = &ld;
    VkResult r = ((PFN_vkCreateInstance)g_layer_gipa(NULL, "vkCreateInstance"))(&c, a, out);
    if (r == VK_SUCCESS) {
        g_inst = *out;
    }
    return r;
}

static VkResult VKAPI_CALL create_device(VkPhysicalDevice pd, const VkDeviceCreateInfo *ci,
                                         const VkAllocationCallbacks *a, VkDevice *out)
{
    VkLayerDeviceLink link = {
        .pfnNextGetInstanceProcAddr = g_drv_gipa,
        .pfnNextGetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)g_drv_gipa(g_inst, "vkGetDeviceProcAddr") };
    VkLayerDeviceCreateInfo li = {
        .sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, .pNext = ci->pNext,
        .function = VK_LAYER_LINK_INFO, .u.pLayerInfo = &link };
    VkLayerDeviceCreateInfo ld = {
        .sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO, .pNext = &li,
        .function = VK_LOADER_DATA_CALLBACK, .u.pfnSetDeviceLoaderData = set_device_data };
    VkDeviceCreateInfo c = *ci;
    c.pNext = &ld;
    return ((PFN_vkCreateDevice)g_layer_gipa(g_inst, "vkCreateDevice"))(pd, &c, a, out);
}

static PFN_vkVoidFunction VKAPI_CALL get_instance_proc(VkInstance inst, const char *name)
{
    if (!strcmp(name, "vkGetInstanceProcAddr")) {
        return (PFN_vkVoidFunction)get_instance_proc;
    }
    if (!strcmp(name, "vkCreateInstance")) {
        return (PFN_vkVoidFunction)create_instance;
    }
    if (!strcmp(name, "vkCreateDevice")) {
        return (PFN_vkVoidFunction)create_device;
    }
    if (!inst) {                        /* the driver's extensions, layers, version */
        return g_drv_gipa(NULL, name);
    }
    return g_layer_gipa(inst, name);
}


#define ARM_GRAPH_LAYER_LIB "libVkLayer_Graph.so"
#define ARM_TENSOR_LAYER_LIB "libVkLayer_Tensor.so"
#define ARM_GRAPH_LAYER_NAME "VK_LAYER_ML_Graph_Emulation"
#define ARM_TENSOR_LAYER_NAME "VK_LAYER_ML_Tensor_Emulation"

typedef struct HleArmLayer {
    void *handle;
    PFN_vkGetInstanceProcAddr gipa;
    PFN_vkGetDeviceProcAddr gdpa;
    PFN_GetPhysicalDeviceProcAddr gpdpa;
} HleArmLayer;

static HleArmLayer g_arm_graph, g_arm_tensor;
static PFN_vkGetInstanceProcAddr g_arm_driver_gipa;
static PFN_vkGetDeviceProcAddr g_arm_driver_gdpa;
static PFN_GetPhysicalDeviceProcAddr g_arm_driver_gpdpa;
static VkInstance g_arm_instance;
static uint32_t g_arm_app_api_version = VK_API_VERSION_1_0;

static bool debug_switch(const char *env_name, const char *property_name)
{
    const char *e = getenv(env_name);
    if (e && !strcmp(e, "1")) {
        return true;
    }
    char value[PROP_VALUE_MAX] = "";
    return hle_prop_get(property_name, value) > 0 && !strcmp(value, "1");
}

static bool arm_layer_open(HleArmLayer *layer, const char *library, const char *label)
{
    layer->handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    PFN_vkNegotiateLoaderLayerInterfaceVersion negotiate =
        layer->handle ? (PFN_vkNegotiateLoaderLayerInterfaceVersion)
            dlsym(layer->handle, "vkNegotiateLoaderLayerInterfaceVersion") : NULL;
    VkNegotiateLayerInterface ni = {
        .sType = LAYER_NEGOTIATE_INTERFACE_STRUCT,
        .loaderLayerInterfaceVersion = 2
    };
    if (!negotiate || negotiate(&ni) != VK_SUCCESS ||
        !ni.pfnGetInstanceProcAddr || !ni.pfnGetDeviceProcAddr) {
        nc_log("hakuX-app", "NSS layers: %s unavailable (%s)", label,
               layer->handle ? "layer negotiation failed" : dlerror());
        return false;
    }
    layer->gipa = ni.pfnGetInstanceProcAddr;
    layer->gdpa = ni.pfnGetDeviceProcAddr;
    layer->gpdpa = ni.pfnGetPhysicalDeviceProcAddr;
    return true;
}

static VkResult VKAPI_CALL arm_tensor_next_enumerate_device_extensions(
    VkPhysicalDevice physical, const char *layer_name, uint32_t *count,
    VkExtensionProperties *properties)
{
    PFN_vkEnumerateDeviceExtensionProperties lower =
        (PFN_vkEnumerateDeviceExtensionProperties)
            g_arm_driver_gipa(g_arm_instance, "vkEnumerateDeviceExtensionProperties");
    PFN_vkEnumerateDeviceExtensionProperties tensor =
        (PFN_vkEnumerateDeviceExtensionProperties)
            g_arm_tensor.gipa(g_arm_instance, "vkEnumerateDeviceExtensionProperties");
    if (!lower || !tensor) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (layer_name) {
        /* The Tensor layer owns only its own layer-name query. Preserve the
         * driver's answers for all other explicit layer names; Graph handles
         * its own name before reaching this next-layer bridge. */
        return !strcmp(layer_name, ARM_TENSOR_LAYER_NAME)
            ? tensor(physical, layer_name, count, properties)
            : lower(physical, layer_name, count, properties);
    }

    /* The system loader normally merges extensions exposed by enabled layers
     * into the NULL-layer query. Our custom Turnip chain bypasses that loader,
     * so reproduce the Tensor-layer part for Graph's requiredExtensions check. */
    uint32_t lower_count = 0, tensor_count = 0;
    VkResult result = lower(physical, NULL, &lower_count, NULL);
    if (result != VK_SUCCESS) {
        return result;
    }
    result = tensor(physical, ARM_TENSOR_LAYER_NAME, &tensor_count, NULL);
    if (result != VK_SUCCESS) {
        return result;
    }

    VkExtensionProperties *all =
        (VkExtensionProperties *)calloc((size_t)lower_count + tensor_count + 4,
                                        sizeof(VkExtensionProperties));
    if (!all) {
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    uint32_t lower_written = lower_count;
    result = lower(physical, NULL, &lower_written, all);
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
        free(all);
        return result;
    }
    uint32_t tensor_written = tensor_count;
    result = tensor(physical, ARM_TENSOR_LAYER_NAME, &tensor_written,
                    all + lower_written);
    if (result != VK_SUCCESS && result != VK_INCOMPLETE) {
        free(all);
        return result;
    }

    /* Avoid duplicate names if a future driver implements the extension natively. */
    uint32_t total = lower_written;
    for (uint32_t i = 0; i < tensor_written; i++) {
        bool found = false;
        for (uint32_t j = 0; j < total; j++) {
            if (!strcmp(all[j].extensionName, all[lower_written + i].extensionName)) {
                found = true;
                break;
            }
        }
        if (!found) {
            all[total++] = all[lower_written + i];
        }
    }

    /* The SDK sample asks for these promoted names even when the driver
     * exposes their functionality through Vulkan core. Advertise them to the
     * upper Graph layer, then strip synthetic names before driver creation. */
    PFN_vkGetPhysicalDeviceProperties get_props =
        (PFN_vkGetPhysicalDeviceProperties)
            g_arm_driver_gipa(g_arm_instance, "vkGetPhysicalDeviceProperties");
    if (get_props) {
        VkPhysicalDeviceProperties physical_props;
        get_props(physical, &physical_props);
        const struct {
            const char *name;
            uint32_t core_version;
        } promoted[] = {
            { VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME, VK_API_VERSION_1_3 },
            { VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME, VK_API_VERSION_1_3 },
            { "VK_KHR_maintenance5", VK_API_VERSION_1_4 }
        };
        for (uint32_t e = 0; e < sizeof(promoted) / sizeof(promoted[0]); e++) {
            bool found = false;
            for (uint32_t i = 0; i < total; i++) {
                found |= !strcmp(all[i].extensionName, promoted[e].name);
            }
            if (!found && g_arm_app_api_version >= promoted[e].core_version &&
                physical_props.apiVersion >= promoted[e].core_version) {
                VkExtensionProperties property = { .specVersion = 1 };
                snprintf(property.extensionName, sizeof(property.extensionName), "%s",
                         promoted[e].name);
                all[total++] = property;
            }
        }
    }

    if (!properties) {
        *count = total;
        free(all);
        return VK_SUCCESS;
    }
    uint32_t capacity = *count;
    uint32_t copied = capacity < total ? capacity : total;
    if (copied) {
        memcpy(properties, all, (size_t)copied * sizeof(*properties));
    }
    *count = copied;
    free(all);
    return copied < total ? VK_INCOMPLETE : VK_SUCCESS;
}

static bool arm_driver_has_extension(VkPhysicalDevice physical, const char *name)
{
    PFN_vkEnumerateDeviceExtensionProperties enumerate =
        (PFN_vkEnumerateDeviceExtensionProperties)
            g_arm_driver_gipa(g_arm_instance, "vkEnumerateDeviceExtensionProperties");
    if (!enumerate) {
        return false;
    }
    uint32_t count = 0;
    if (enumerate(physical, NULL, &count, NULL) != VK_SUCCESS || !count) {
        return false;
    }
    VkExtensionProperties *extensions =
        (VkExtensionProperties *)calloc(count, sizeof(VkExtensionProperties));
    if (!extensions) {
        return false;
    }
    uint32_t written = count;
    VkResult result = enumerate(physical, NULL, &written, extensions);
    bool found = false;
    if (result == VK_SUCCESS || result == VK_INCOMPLETE) {
        for (uint32_t i = 0; i < written; i++) {
            if (!strcmp(extensions[i].extensionName, name)) {
                found = true;
                break;
            }
        }
    }
    free(extensions);
    return found;
}

static VkResult VKAPI_CALL arm_tensor_next_create_device(
    VkPhysicalDevice physical, const VkDeviceCreateInfo *ci,
    const VkAllocationCallbacks *allocator, VkDevice *device)
{
    PFN_vkCreateDevice create =
        (PFN_vkCreateDevice)g_arm_tensor.gipa(g_arm_instance, "vkCreateDevice");
    if (!create) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    PFN_vkGetPhysicalDeviceProperties get_props =
        (PFN_vkGetPhysicalDeviceProperties)
            g_arm_driver_gipa(g_arm_instance, "vkGetPhysicalDeviceProperties");
    if (!get_props) {
        return create(physical, ci, allocator, device);
    }

    VkPhysicalDeviceProperties physical_props;
    get_props(physical, &physical_props);
    const struct {
        const char *name;
        uint32_t core_version;
    } promoted[] = {
        { VK_KHR_SYNCHRONIZATION_2_EXTENSION_NAME, VK_API_VERSION_1_3 },
        { VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME, VK_API_VERSION_1_3 },
        { "VK_KHR_maintenance5", VK_API_VERSION_1_4 }
    };
    bool remove[3] = { false, false, false };
    for (uint32_t e = 0; e < sizeof(promoted) / sizeof(promoted[0]); e++) {
        remove[e] = g_arm_app_api_version >= promoted[e].core_version &&
                    physical_props.apiVersion >= promoted[e].core_version &&
                    !arm_driver_has_extension(physical, promoted[e].name);
    }
    uint32_t kept = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        bool synthetic = false;
        for (uint32_t e = 0; e < sizeof(promoted) / sizeof(promoted[0]); e++) {
            synthetic |= remove[e] &&
                         !strcmp(ci->ppEnabledExtensionNames[i], promoted[e].name);
        }
        if (!synthetic) {
            kept++;
        }
    }
    if (kept == ci->enabledExtensionCount) {
        return create(physical, ci, allocator, device);
    }
    const char **names = (const char **)calloc(kept ? kept : 1, sizeof(char *));
    if (!names) {
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    uint32_t at = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        bool synthetic = false;
        for (uint32_t e = 0; e < sizeof(promoted) / sizeof(promoted[0]); e++) {
            synthetic |= remove[e] &&
                         !strcmp(ci->ppEnabledExtensionNames[i], promoted[e].name);
        }
        if (!synthetic) {
            names[at++] = ci->ppEnabledExtensionNames[i];
        }
    }
    VkDeviceCreateInfo adjusted = *ci;
    adjusted.enabledExtensionCount = kept;
    adjusted.ppEnabledExtensionNames = names;
    VkResult result = create(physical, &adjusted, allocator, device);
    free(names);
    return result;
}

static PFN_vkVoidFunction VKAPI_CALL arm_tensor_next_gipa(VkInstance instance,
                                                          const char *name)
{
    if (!strcmp(name, "vkGetInstanceProcAddr")) {
        return (PFN_vkVoidFunction)arm_tensor_next_gipa;
    }
    if (!strcmp(name, "vkEnumerateDeviceExtensionProperties")) {
        return (PFN_vkVoidFunction)arm_tensor_next_enumerate_device_extensions;
    }
    if (!strcmp(name, "vkCreateDevice")) {
        return (PFN_vkVoidFunction)arm_tensor_next_create_device;
    }
    return g_arm_tensor.gipa(instance, name);
}

static VkResult VKAPI_CALL arm_nss_create_instance(const VkInstanceCreateInfo *ci,
                                                    const VkAllocationCallbacks *a,
                                                    VkInstance *out)
{
    /* Graph is the first layer; its next link is Tensor, then the driver. */
    VkLayerInstanceLink links[2] = {
        { .pNext = &links[1],
          .pfnNextGetInstanceProcAddr = arm_tensor_next_gipa,
          .pfnNextGetPhysicalDeviceProcAddr = g_arm_tensor.gpdpa },
        { .pNext = NULL,
          .pfnNextGetInstanceProcAddr = g_arm_driver_gipa,
          .pfnNextGetPhysicalDeviceProcAddr = g_arm_driver_gpdpa }
    };
    VkLayerInstanceCreateInfo link_info = {
        .sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,
        .pNext = ci->pNext,
        .function = VK_LAYER_LINK_INFO,
        .u.pLayerInfo = &links[0]
    };
    VkLayerInstanceCreateInfo data_info = {
        .sType = VK_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO,
        .pNext = &link_info,
        .function = VK_LOADER_DATA_CALLBACK,
        .u.pfnSetInstanceLoaderData = set_instance_data
    };
    g_arm_app_api_version = ci->pApplicationInfo
        ? ci->pApplicationInfo->apiVersion : VK_API_VERSION_1_0;
    VkInstanceCreateInfo chained = *ci;
    chained.pNext = &data_info;
    PFN_vkCreateInstance create =
        (PFN_vkCreateInstance)g_arm_graph.gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!create) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult result = create(&chained, a, out);
    if (result == VK_SUCCESS) {
        g_arm_instance = *out;
        g_arm_driver_gdpa = (PFN_vkGetDeviceProcAddr)
            g_arm_driver_gipa(g_arm_instance, "vkGetDeviceProcAddr");
    }
    return result;
}

static VkResult VKAPI_CALL arm_nss_create_device(VkPhysicalDevice physical,
                                                  const VkDeviceCreateInfo *ci,
                                                  const VkAllocationCallbacks *a,
                                                  VkDevice *out)
{
    if (!g_arm_driver_gdpa) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkLayerDeviceLink links[2] = {
        { .pNext = &links[1],
          .pfnNextGetInstanceProcAddr = arm_tensor_next_gipa,
          .pfnNextGetDeviceProcAddr = g_arm_tensor.gdpa },
        { .pNext = NULL,
          .pfnNextGetInstanceProcAddr = g_arm_driver_gipa,
          .pfnNextGetDeviceProcAddr = g_arm_driver_gdpa }
    };
    VkLayerDeviceCreateInfo link_info = {
        .sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
        .pNext = ci->pNext,
        .function = VK_LAYER_LINK_INFO,
        .u.pLayerInfo = &links[0]
    };
    VkLayerDeviceCreateInfo data_info = {
        .sType = VK_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO,
        .pNext = &link_info,
        .function = VK_LOADER_DATA_CALLBACK,
        .u.pfnSetDeviceLoaderData = set_device_data
    };
    VkDeviceCreateInfo chained = *ci;
    chained.pNext = &data_info;
    PFN_vkCreateDevice create =
        (PFN_vkCreateDevice)g_arm_graph.gipa(g_arm_instance, "vkCreateDevice");
    if (!create) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    return create(physical, &chained, a, out);
}

static PFN_vkVoidFunction VKAPI_CALL arm_nss_get_instance_proc(VkInstance instance,
                                                               const char *name)
{
    if (!strcmp(name, "vkGetInstanceProcAddr")) {
        return (PFN_vkVoidFunction)arm_nss_get_instance_proc;
    }
    if (!instance) {
        if (!strcmp(name, "vkCreateInstance")) {
            return (PFN_vkVoidFunction)arm_nss_create_instance;
        }
        /* Keep global loader queries on the driver; only instance creation is
         * intercepted before an instance exists. */
        return g_arm_driver_gipa(VK_NULL_HANDLE, name);
    }
    if (!strcmp(name, "vkCreateDevice")) {
        return (PFN_vkVoidFunction)arm_nss_create_device;
    }
    return g_arm_graph.gipa(instance, name);
}

static void *arm_nss_layers_enable(void *drv_gipa)
{
    if (debug_switch("HAKUX_NSS_LAYERS", "debug.hakux.nsslayers") == false) {
        return drv_gipa;
    }
    if (debug_switch("HAKUX_VKVALIDATION", "debug.hakux.vkvalidation")) {
        nc_log("hakuX-app", "NSS layers: not enabled together with the custom validation layer yet");
        return drv_gipa;
    }

    memset(&g_arm_graph, 0, sizeof(g_arm_graph));
    memset(&g_arm_tensor, 0, sizeof(g_arm_tensor));
    if (!arm_layer_open(&g_arm_graph, ARM_GRAPH_LAYER_LIB, ARM_GRAPH_LAYER_NAME)) {
        return drv_gipa;
    }
    if (!arm_layer_open(&g_arm_tensor, ARM_TENSOR_LAYER_LIB, ARM_TENSOR_LAYER_NAME)) {
        return drv_gipa;
    }

    g_arm_driver_gipa = (PFN_vkGetInstanceProcAddr)drv_gipa;
    /* vkGetDeviceProcAddr is instance-scoped; resolve it after instance
     * creation, not with VK_NULL_HANDLE. */
    g_arm_driver_gdpa = NULL;
    g_arm_driver_gpdpa = (PFN_GetPhysicalDeviceProcAddr)
        g_arm_driver_gipa(VK_NULL_HANDLE, "vk_layerGetPhysicalDeviceProcAddr");
    g_arm_instance = VK_NULL_HANDLE;
    nc_log("hakuX-app", "NSS layers: Graph -> Tensor -> Turnip enabled (debug opt-in)");
    return (void *)arm_nss_get_instance_proc;
}

void *nc_vk_layers(void *drv_gipa)
{
    /* The Arm layers are an opt-in debug path. Keep the existing validation
     * path unchanged until a three-layer chain has its own device tests. */
    void *arm = arm_nss_layers_enable(drv_gipa);
    if (arm != drv_gipa) {
        return arm;
    }
    /* HAKUX_VKVALIDATION=1 too: a run that reads no properties (prop.h) */
    char v[PROP_VALUE_MAX] = "";
    const char *e = getenv("HAKUX_VKVALIDATION");
    if (!(e && !strcmp(e, "1")) && (hle_prop_get("debug.hakux.vkvalidation", v) <= 0 || !strcmp(v, "0"))) {
        return drv_gipa;
    }
    void *h = dlopen(LAYER_LIB, RTLD_NOW | RTLD_LOCAL);
    PFN_vkNegotiateLoaderLayerInterfaceVersion neg =
        h ? (PFN_vkNegotiateLoaderLayerInterfaceVersion)dlsym(h, "vkNegotiateLoaderLayerInterfaceVersion")
          : NULL;
    VkNegotiateLayerInterface ni = { .sType = LAYER_NEGOTIATE_INTERFACE_STRUCT,
                                     .loaderLayerInterfaceVersion = 2 };
    if (!neg || neg(&ni) != VK_SUCCESS || !ni.pfnGetInstanceProcAddr) {
        nc_log("hakuX-app", "Vulkan validation: %s not loaded (%s): none", LAYER_LIB,
               h ? "no layer interface" : dlerror());
        return drv_gipa;
    }
    g_drv_gipa = (PFN_vkGetInstanceProcAddr)drv_gipa;
    g_layer_gipa = ni.pfnGetInstanceProcAddr;
    nc_log("hakuX-app", "Vulkan validation: %s over the custom driver (layer interface %u)",
           LAYER_LIB, ni.loaderLayerInterfaceVersion);
    return (void *)get_instance_proc;
}
