#version 450
layout(set = 0, binding = 0) uniform sampler2D source_texture;
layout(location = 0) in vec2 v_uv;
layout(location = 0) out vec4 out_color;
layout(push_constant) uniform RetroArchPush {
    vec4 tint;
} pc;
void main()
{
    out_color = texture(source_texture, v_uv) * pc.tint;
}
