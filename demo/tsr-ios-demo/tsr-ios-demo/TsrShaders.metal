#include <metal_stdlib>
using namespace metal;

float fastLanczos2(float x)
{
    float wA = x - 4.0;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

float2 weightY(float dx, float dy, float c, float std)
{
    float x = ((dx * dx) + (dy * dy)) * 0.55 + clamp(abs(c) * std, 0.0, 1.0);
    float w = fastLanczos2(x);
    return float2(w, w * c);
}

struct VertexOut {
    float4 position [[position]];
    float2 texCoord;
};

vertex VertexOut TsrVertexShader(uint vertexID [[vertex_id]]) {
    float2 positions[3] = {float2(-1, -1), float2(3, -1), float2(-1, 3)};
    float2 texCoords[3] = {float2(0, 1), float2(2, 1), float2(0, -1)};
    
    VertexOut out;
    out.position = float4(positions[vertexID], 0, 1);
    out.texCoord = texCoords[vertexID];
    
    return out;
}

// 片段着色器
fragment float4 TsrFragmentShader(VertexOut inVertex [[stage_in]], texture2d<float> texture [[texture(0)]], constant float4 &ViewportInfo [[buffer(0)]]) {
    constexpr sampler sampler0(filter::linear, address::clamp_to_edge);
    
    const int mode = 1;
    float edgeThreshold = 8.0/255.0;
    float edgeSharpness = 2.0;

    float4 color;
    if(mode == 1)
        color.xyz = texture.sample(sampler0, inVertex.texCoord).xyz;
    else
        color.xyzw = texture.sample(sampler0, inVertex.texCoord).xyzw;

//    float xCenter = abs(inVertex.texCoord.x - 0.5);
//    float yCenter = abs(inVertex.texCoord.y - 0.5);

    // Todo: config the SR region based on needs
    // if ( mode!=4 && xCenter*xCenter+yCenter*yCenter<=0.4 * 0.4)
    if ( mode!=4) {
        float2 imgCoord = ((inVertex.texCoord.xy * ViewportInfo.zw) + float2(-0.5, 0.5));
        float2 imgCoordPixel = floor(imgCoord);
        float2 coord = (imgCoordPixel * ViewportInfo.xy);
        float2 pl = (imgCoord + (-imgCoordPixel));
        float4 left = texture.gather(sampler0, coord, int2(0), component(mode));

        float edgeVote = abs(left.z - left.y) + abs(color[mode] - left.y)  + abs(color[mode] - left.z) ;
        if(edgeVote > edgeThreshold) {
            coord.x += ViewportInfo.x;

            float4 right = texture.gather(sampler0, coord + float2(ViewportInfo.x, 0.0), int2(0), component(mode));
            float4 upDown;
            upDown.xy = texture.gather(sampler0, coord + float2(0.0, -ViewportInfo.y), int2(0), component(mode)).wz;
            upDown.zw  = texture.gather(sampler0, coord + float2(0.0, ViewportInfo.y), int2(0), component(mode)).yx;

            float mean = (left.y+left.z+right.x+right.w)*0.25;
            left = left - float4(mean);
            right = right - float4(mean);
            upDown = upDown - float4(mean);
            color.w =color[mode] - mean;

            float sum = (((((abs(left.x)+abs(left.y))+abs(left.z))+abs(left.w))+(((abs(right.x)+abs(right.y))+abs(right.z))+abs(right.w)))+(((abs(upDown.x)+abs(upDown.y))+abs(upDown.z))+abs(upDown.w)));
            float std = 2.181818/sum;
            
            float2 aWY = weightY(pl.x, pl.y+1.0, upDown.x,std);
            aWY += weightY(pl.x-1.0, pl.y+1.0, upDown.y,std);
            aWY += weightY(pl.x-1.0, pl.y-2.0, upDown.z,std);
            aWY += weightY(pl.x, pl.y-2.0, upDown.w,std);
            aWY += weightY(pl.x+1.0, pl.y-1.0, left.x,std);
            aWY += weightY(pl.x, pl.y-1.0, left.y,std);
            aWY += weightY(pl.x, pl.y, left.z,std);
            aWY += weightY(pl.x+1.0, pl.y, left.w,std);
            aWY += weightY(pl.x-1.0, pl.y-1.0, right.x,std);
            aWY += weightY(pl.x-2.0, pl.y-1.0, right.y,std);
            aWY += weightY(pl.x-2.0, pl.y, right.z,std);
            aWY += weightY(pl.x-1.0, pl.y, right.w,std);

            float finalY = aWY.y/aWY.x;

            float maxY = max(max(left.y,left.z),max(right.x,right.w));
            float minY = min(min(left.y,left.z),min(right.x,right.w));
            finalY = clamp(edgeSharpness*finalY, minY, maxY);
                    
            float deltaY = finalY - color.w;
            
            //smooth high contrast input
            deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);

            color.x = clamp((color.x+deltaY),0.0,1.0);
            color.y = clamp((color.y+deltaY),0.0,1.0);
            color.z = clamp((color.z+deltaY),0.0,1.0);
        }
    }

    color.w = 1.0;  //assume alpha channel is not used
    return color;
}
