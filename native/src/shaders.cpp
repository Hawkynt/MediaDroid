#include "shaders.h"

#include <gpu.h>

namespace mediadroyd {

std::map<std::string, std::string> shaders;

ncnn::Pipeline* pipeline(const ncnn::VulkanDevice* vkdev, const std::string& name, const ncnn::Option& opt) {
    auto source = shaders.find(name);
    if (source == shaders.end()) return nullptr;
    std::vector<uint32_t> spirv;
    if (ncnn::compile_spirv_module(source->second.c_str(), opt, spirv) != 0) return nullptr;
    auto* made = new ncnn::Pipeline(vkdev);
    made->set_optimal_local_size_xyz();
    if (made->create(spirv.data(), spirv.size() * 4, std::vector<ncnn::vk_specialization_type>()) != 0) {
        delete made;
        return nullptr;
    }
    return made;
}

}  // namespace mediadroyd
