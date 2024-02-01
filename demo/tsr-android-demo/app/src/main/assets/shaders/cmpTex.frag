#extension GL_OES_EGL_image_external : require

precision mediump float;
varying vec2 vCoordinate;
uniform sampler2D uTexture0;
uniform sampler2D uTexture1;
uniform vec4 ViewportInfo[1];
// 比对线的Y轴坐标
uniform float lineX;

void main() {
    float lineXCoord = lineX / ViewportInfo[0].z;
    if (vCoordinate.x > lineXCoord + 0.01) {
        gl_FragColor = texture2D(uTexture1, vCoordinate);
    } else if (vCoordinate.x < lineXCoord - 0.01) {
        gl_FragColor = texture2D(uTexture0, vCoordinate);
    } else {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
    }
}
