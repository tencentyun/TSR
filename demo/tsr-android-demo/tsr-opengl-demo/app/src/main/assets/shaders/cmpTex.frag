#extension GL_OES_EGL_image_external : require

precision mediump float;
varying vec2 vCoordinate;
uniform sampler2D uTexture0;
uniform sampler2D uTexture1;
uniform vec4 ViewportInfo[1];
// 比对线的Y轴坐标
uniform vec2 linePos[1];
uniform int rotation;

void main() {
    if (rotation == 0 || rotation == 180) {
        float lineXCoord = linePos[0].x / ViewportInfo[0].z;
        if (rotation == 180) {
            lineXCoord = 1.0 - lineXCoord;
        }
        if (vCoordinate.x > lineXCoord + 0.005) {
            gl_FragColor = texture2D(uTexture1, vCoordinate);
        } else if (vCoordinate.x < lineXCoord - 0.005) {
            gl_FragColor = texture2D(uTexture0, vCoordinate);
        } else {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        }
    } else {
        float lineYCoord = linePos[0].x / ViewportInfo[0].z;
        if (rotation == 90) {
            lineYCoord = 1.0 - lineYCoord;
        }
        if (vCoordinate.y > lineYCoord + 0.005) {
            gl_FragColor = texture2D(uTexture0, vCoordinate);
        } else if (vCoordinate.y < lineYCoord - 0.005) {
            gl_FragColor = texture2D(uTexture1, vCoordinate);
        } else {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        }
    }
}
