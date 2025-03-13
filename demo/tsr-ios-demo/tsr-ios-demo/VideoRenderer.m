//
//  VideoRenderer.m
//  tsr-ios-demo
//
//  Created by Junfeng Gao on 2025/2/25.
//

#import "VideoRenderer.h"
#import <OpenGLES/ES3/glext.h>

@interface VideoRenderer()

@property (nonatomic) GLuint shaderProgram;
@property (nonatomic) GLuint vertexBuffer;
@property (nonatomic) GLuint texCoordBuffer;
@property (nonatomic) GLint positionAttribute;
@property (nonatomic) GLint texCoordAttribute;
@property (nonatomic) GLint textureUniform;

@end

@implementation VideoRenderer

- (instancetype)initWithContext:(EAGLContext *)context inputWidth: (int)inputWidth inputHeight: (int)inputHeight outputWidth: (int)outputWidth outputHeight: (int)outputHeight {
    self = [super init];
    if (self) {
        _glContext = context;
        _inputWidth = inputWidth;
        _inputHeight = inputHeight;
        _outputWidth = outputWidth;
        _outputHeight = outputHeight;
    }
    return self;
}

- (void)setupGL {
    [EAGLContext setCurrentContext:_glContext];
    [self setupShaders];
    [self setupBuffers];
}

- (void)render:(GLuint)texture {
    [EAGLContext setCurrentContext:_glContext];
    
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
        
    glUseProgram(_shaderProgram);
    
    // 绑定纹理
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texture);
    glUniform1i(_textureUniform, 0);
    glViewport(0, 0, _outputWidth, _outputHeight);
    
    // 设置纹理参数
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    
    // 绑定顶点数据
    glBindBuffer(GL_ARRAY_BUFFER, _vertexBuffer);
    glEnableVertexAttribArray(_positionAttribute);
    glVertexAttribPointer(_positionAttribute, 3, GL_FLOAT, GL_FALSE, 0, 0);
    
    glBindBuffer(GL_ARRAY_BUFFER, _texCoordBuffer);
    glEnableVertexAttribArray(_texCoordAttribute);
    glVertexAttribPointer(_texCoordAttribute, 2, GL_FLOAT, GL_FALSE, 0, 0);
    
    // 绘制
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    
    // 清理
    glBindTexture(GL_TEXTURE_2D, 0);    

}

- (void)cleanupGL {
    if (_shaderProgram) {
        glDeleteProgram(_shaderProgram);
        _shaderProgram = 0;
    }
    if (_vertexBuffer) {
        glDeleteBuffers(1, &_vertexBuffer);
        _vertexBuffer = 0;
    }
    if (_texCoordBuffer) {
        glDeleteBuffers(1, &_texCoordBuffer);
        _texCoordBuffer = 0;
    }
}

- (void)setupShaders {
    // 顶点着色器
    NSString *vertexShaderSource = @"attribute vec3 position;\n"
                                   "attribute vec2 texCoord;\n"
                                   "varying vec2 texCoordVarying;\n"
                                   "void main() {\n"
                                   "    gl_Position = vec4(position, 1.0);\n"
                                   "    texCoordVarying = texCoord;\n"
                                   "}\n";
    
    // 片段着色器
    NSString *fragmentShaderSource = @"precision mediump float;\n"
                                     "varying vec2 texCoordVarying;\n"
                                     "uniform sampler2D texture;\n"
                                     "void main() {\n"
                                     "    gl_FragColor = texture2D(texture, texCoordVarying);\n"
                                     "}\n";
    
    // 编译着色器
    GLuint vertexShader = [self compileShader:vertexShaderSource withType:GL_VERTEX_SHADER];
    GLuint fragmentShader = [self compileShader:fragmentShaderSource withType:GL_FRAGMENT_SHADER];
    
    // 创建着色器程序
    _shaderProgram = glCreateProgram();
    glAttachShader(_shaderProgram, vertexShader);
    glAttachShader(_shaderProgram, fragmentShader);
    glLinkProgram(_shaderProgram);
    
    // 获取属性位置
    _positionAttribute = glGetAttribLocation(_shaderProgram, "position");
    _texCoordAttribute = glGetAttribLocation(_shaderProgram, "texCoord");
    _textureUniform = glGetUniformLocation(_shaderProgram, "texture");
    
    // 清理
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
}

- (GLuint)compileShader:(NSString *)shaderSource withType:(GLenum)shaderType {
    GLuint shader = glCreateShader(shaderType);
    const char *source = [shaderSource UTF8String];
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    
    GLint compileSuccess;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compileSuccess);
    if (compileSuccess == GL_FALSE) {
        GLchar messages[256];
        glGetShaderInfoLog(shader, sizeof(messages), 0, &messages[0]);
        NSString *messageString = [NSString stringWithUTF8String:messages];
        NSLog(@"Error compiling shader: %@", messageString);
        return 0;
    }
    return shader;
}

- (void)setupBuffers {
    // 顶点数据
    const GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f,  // 左下
        1.0f, -1.0f, 0.0f,   // 右下
        -1.0f, 1.0f, 0.0f,   // 左上
        1.0f, 1.0f, 0.0f     // 右上
    };
    
    // 纹理坐标
    const GLfloat texCoords[] = {
        0.0f, 1.0f,  // 左下
        1.0f, 1.0f,  // 右下
        0.0f, 0.0f,  // 左上
        1.0f, 0.0f   // 右上
    };
    
    // 创建并绑定顶点缓冲区
    glGenBuffers(1, &_vertexBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, _vertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    
    // 创建并绑定纹理坐标缓冲区
    glGenBuffers(1, &_texCoordBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, _texCoordBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(texCoords), texCoords, GL_STATIC_DRAW);
}

- (void)dealloc {
    [self cleanupGL];
}

@end

