// The compute shaders of app/src/main/glsl/ncnn, handed over from the app (Shaders.NCNN_*) before a network loads.
#pragma once

#include <map>
#include <string>
#include <vector>

#include <option.h>
#include <pipeline.h>

namespace mediadroid {

// The shaders by their file name without the extension ("warp", "dain-correlation").
extern std::map<std::string, std::string> shaders;

// A pipeline for the shader [name] on [vkdev], compiled for [opt]; null where it does not compile.
ncnn::Pipeline* pipeline(const ncnn::VulkanDevice* vkdev, const std::string& name, const ncnn::Option& opt);

}  // namespace mediadroid
