precision mediump float;
varying vec2 vCoordinate;
uniform sampler2D uTexture;

// BT.601 RGB -> Y 转换
void main() {
    vec4 rgba = texture2D(uTexture, vCoordinate);
    float y = 0.299 * rgba.r + 0.587 * rgba.g + 0.114 * rgba.b;
    gl_FragColor = vec4(y, 0.0, 0.0, 1.0);
}
