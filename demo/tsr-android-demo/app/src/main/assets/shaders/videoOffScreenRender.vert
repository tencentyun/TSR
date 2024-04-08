attribute vec4 aPosition;
attribute vec2 aCoordinate;
uniform mat4 uSTMatrix;
varying vec2 vCoordinate;

void main() {
    gl_Position = aPosition;
    vCoordinate = (uSTMatrix * vec4(aCoordinate, 0.0, 1.0)).xy;
}