// Copyright 2022-2024 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

#version 450 core
#extension GL_ARB_separate_shader_objects : enable

layout (location = 0) in vec2 frag_tex_coord;
layout (location = 0) out vec4 color;

layout (push_constant, std140) uniform DrawInfo {
    mat4 modelview_matrix;
    vec4 i_resolution;
    vec4 o_resolution;
    int screen_id_l;
    int screen_id_r;
    int layer;
    int reverse_interlaced;
    vec4 texcoord_bounds;
};

layout (set = 0, binding = 0) uniform sampler2D screen_textures[3];

#ifdef FXAA
vec2 HalfTexel(int screen_id) {
#ifdef ARRAY_DYNAMIC_INDEX
    return 0.5 / vec2(textureSize(screen_textures[screen_id], 0));
#else
    switch (screen_id) {
    case 0: return 0.5 / vec2(textureSize(screen_textures[0], 0));
    case 1: return 0.5 / vec2(textureSize(screen_textures[1], 0));
    case 2: return 0.5 / vec2(textureSize(screen_textures[2], 0));
    }
    return vec2(0.0);
#endif
}
#endif

vec4 GetScreen(int screen_id, vec2 uv) {
#ifdef ARRAY_DYNAMIC_INDEX
    return texture(screen_textures[screen_id], uv);
#else
    switch (screen_id) {
    case 0:
        return texture(screen_textures[0], uv);
    case 1:
        return texture(screen_textures[1], uv);
    case 2:
        return texture(screen_textures[2], uv);
    }
    return vec4(0.0);
#endif
}

#ifdef FXAA
vec4 SampleBounded(int screen_id, vec2 uv, vec4 bounds) {
    return GetScreen(screen_id, clamp(uv, bounds.xy, bounds.zw));
}

vec4 FilterScreen(int screen_id) {
    // Work in output pixels: the framebuffer may be cropped, rotated or supersampled.
    vec2 dx = dFdx(frag_tex_coord);
    vec2 dy = dFdy(frag_tex_coord);
    vec2 inset = HalfTexel(screen_id);
    vec4 bounds = texcoord_bounds + vec4(inset, -inset);
    vec4 center = SampleBounded(screen_id, frag_tex_coord, bounds);
    const vec3 luma = vec3(0.299, 0.587, 0.114);
    float nw = dot(SampleBounded(screen_id, frag_tex_coord - dx - dy, bounds).rgb, luma);
    float ne = dot(SampleBounded(screen_id, frag_tex_coord + dx - dy, bounds).rgb, luma);
    float sw = dot(SampleBounded(screen_id, frag_tex_coord - dx + dy, bounds).rgb, luma);
    float se = dot(SampleBounded(screen_id, frag_tex_coord + dx + dy, bounds).rgb, luma);
    float mid = dot(center.rgb, luma);
    float lo = min(mid, min(min(nw, ne), min(sw, se)));
    float hi = max(mid, max(max(nw, ne), max(sw, se)));
    if (hi - lo < max(0.0312, hi * 0.125)) {
        return center;
    }
    vec2 direction = vec2(sw + se - nw - ne, nw + sw - ne - se);
    float reduce = max((nw + ne + sw + se) * (0.25 / 8.0), 1.0 / 128.0);
    direction = clamp(direction / (min(abs(direction.x), abs(direction.y)) + reduce),
                      vec2(-8.0), vec2(8.0));
    vec2 step_uv = direction.x * dx + direction.y * dy;
    vec3 a = 0.5 * (SampleBounded(screen_id, frag_tex_coord - step_uv / 6.0, bounds).rgb +
                    SampleBounded(screen_id, frag_tex_coord + step_uv / 6.0, bounds).rgb);
    vec3 b = a * 0.5 +
             0.25 * (SampleBounded(screen_id, frag_tex_coord - step_uv * 0.5, bounds).rgb +
                     SampleBounded(screen_id, frag_tex_coord + step_uv * 0.5, bounds).rgb);
    float filtered_luma = dot(b, luma);
    return vec4(filtered_luma < lo || filtered_luma > hi ? a : b, center.a);
}
#endif

void main() {
#ifdef FXAA
    color = FilterScreen(screen_id_l);
#else
    color = GetScreen(screen_id_l, frag_tex_coord);
#endif
}
