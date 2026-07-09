//
//  VideoRendererMetal.metal
//  tsr-ios-demo
//
//  Metal shaders for VideoRendererMetal.
//  NV12 YUV → RGB conversion + BGRA passthrough.
//  通过 YuvColorParams uniform 支持 BT.601/BT.709 × Limited/Full 四档。
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

/// YUV → RGB 转换参数（对齐 Android Nv12Shaders）
struct YuvColorParams {
    float3x3 matrix;   ///< YUV→RGB 3×3 列优先矩阵
    float3   offset;   ///< Y/U/V 各通道偏移（Limited 非零，Full 为零）
};

// ── NV12 YUV → RGB shaders ──

vertex VMRVertexOut vmr_yuv_vertex(VMRVertexIn in [[stage_in]]) {
    VMRVertexOut out;
    out.position = in.position;
    out.texCoord = in.texCoord;
    return out;
}

fragment float4 vmr_yuv_fragment(VMRVertexOut in            [[stage_in]],
                                  texture2d<float> yTex     [[texture(0)]],
                                  texture2d<float> uvTex    [[texture(1)]],
                                  constant YuvColorParams & params [[buffer(0)]]) {
    constexpr sampler s(filter::linear, address::clamp_to_edge);

    float y   = yTex.sample(s, in.texCoord).r;
    float2 uv = uvTex.sample(s, in.texCoord).rg;

    float3 yuv = float3(y, uv.r, uv.g) + params.offset;
    float3 rgb = params.matrix * yuv;

    return float4(clamp(rgb, 0.0, 1.0), 1.0);
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
