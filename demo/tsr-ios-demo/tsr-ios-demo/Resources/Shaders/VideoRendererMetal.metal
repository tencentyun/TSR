//
//  VideoRendererMetal.metal
//  tsr-ios-demo
//
//  Metal shaders for VideoRendererMetal.
//  NV12 YUV → RGB conversion + BGRA passthrough.
//

#include <metal_stdlib>
using namespace metal;

struct VMRVertexIn {
    float4 position [[attribute(0)]];
    float2 texCoord [[attribute(1)]];
};

struct VMRVertexOut {
    float4 position [[position]];
    float2 texCoord;
};

// ── NV12 YUV → RGB shaders ──

vertex VMRVertexOut vmr_yuv_vertex(VMRVertexIn in [[stage_in]]) {
    VMRVertexOut out;
    out.position = in.position;
    out.texCoord = in.texCoord;
    return out;
}

// BT.601 Video Range (16-235 Y, 16-240 UV)
fragment float4 vmr_yuv_fragment(VMRVertexOut in          [[stage_in]],
                                  texture2d<float> yTex    [[texture(0)]],
                                  texture2d<float> uvTex   [[texture(1)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);

    float y   = yTex.sample(s, in.texCoord).r;
    float2 uv = uvTex.sample(s, in.texCoord).rg;

    y  = (y  - 16.0 / 255.0) * (255.0 / 219.0);
    float u = uv.r - 128.0 / 255.0;
    float v = uv.g - 128.0 / 255.0;

    float r = y + 1.402   * v;
    float g = y - 0.34414 * u - 0.71414 * v;
    float b = y + 1.772   * u;

    return float4(clamp(r, 0.0, 1.0),
                  clamp(g, 0.0, 1.0),
                  clamp(b, 0.0, 1.0),
                  1.0);
}

// ── BGRA passthrough shaders ──

vertex VMRVertexOut vmr_bgra_vertex(VMRVertexIn in [[stage_in]]) {
    VMRVertexOut out;
    out.position = in.position;
    out.texCoord = in.texCoord;
    return out;
}

fragment float4 vmr_bgra_fragment(VMRVertexOut in              [[stage_in]],
                                   texture2d<float> bgraTex     [[texture(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);
    return bgraTex.sample(s, in.texCoord);
}
