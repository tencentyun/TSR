precision mediump float;
varying vec2 vCoordinate;
uniform sampler2D uEnhancedY;
uniform sampler2D uOriginalRgba;

// BT.601 常量
const vec3 RGB_TO_U = vec3(-0.169, -0.331, 0.500);
const vec3 RGB_TO_V = vec3(0.500, -0.419, -0.081);

void main() {
    // 获取增强后的 Y 值（存储在 R 通道）
    float enhancedY = texture2D(uEnhancedY, vCoordinate).r;

    // 获取原始 RGBA 颜色
    vec4 originalColor = texture2D(uOriginalRgba, vCoordinate);
    vec3 rgb = originalColor.rgb;

    // 使用 BT.601 公式从原始 RGB 计算 U、V 分量
    float u = dot(rgb, RGB_TO_U) + 0.5;
    float v = dot(rgb, RGB_TO_V) + 0.5;

    // 使用增强后的 Y 和原始 U、V 转回 RGB（BT.601 逆变换）
    float r = enhancedY + 1.403 * (v - 0.5);
    float g = enhancedY - 0.344 * (u - 0.5) - 0.714 * (v - 0.5);
    float b = enhancedY + 1.770 * (u - 0.5);

    gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), originalColor.a);
}
