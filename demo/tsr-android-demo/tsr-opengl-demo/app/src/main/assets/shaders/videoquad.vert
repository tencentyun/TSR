// 四维表示顶点坐标
attribute vec4 aPosition;
attribute vec2 aCoordinate;
varying vec2 vCoordinate;

void main(){
    gl_Position = aPosition;
    vCoordinate = aCoordinate;
}