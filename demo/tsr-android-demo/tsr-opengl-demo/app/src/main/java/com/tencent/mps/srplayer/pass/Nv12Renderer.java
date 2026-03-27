package com.tencent.mps.srplayer.pass;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * NV12 数据渲染器：在一个 shader pass 内完成 NV12 数据上传 GPU、NV12→RGB 色彩空间转换和渲染到屏幕。
 *
 * <p>合并了原 Nv12ToRgbPass（YUV 上传 + 色彩转换）和 VideoFrameDrawer（视口适配 + 渲染到屏幕）的职责，
 * 无需中间 FBO，直接渲染到默认帧缓冲（屏幕）。</p>
 *
 * <p>典型使用流程：</p>
 * <ol>
 *     <li>在 GL 线程调用 {@link #init(int, int)} 初始化 GL 资源</li>
 *     <li>在 Surface 尺寸变化时调用 {@link #onSurfaceChanged(int, int)}</li>
 *     <li>每帧调用 {@link #render(ByteBuffer, int, int)} 渲染 NV12 数据到屏幕</li>
 *     <li>不再使用时调用 {@link #release()} 释放 GL 资源</li>
 * </ol>
 */
public class Nv12Renderer {
    private static final String TAG = "Nv12Renderer";

    // Vertex shader：全屏四边形 + 纹理坐标传递
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    // Fragment shader：从 Y 纹理（GL_R8）和 UV 纹理（GL_RG8）采样，完成 NV12→RGB 转换（BT.601）
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D yTexture;\n" +
            "uniform sampler2D uvTexture;\n" +
            "void main() {\n" +
            "    float y = texture2D(yTexture, vTexCoord).r;\n" +
            "    vec2 uv = texture2D(uvTexture, vTexCoord).rg;\n" + // NV12: GL_RG8 → U在R通道, V在G通道
            "    float u = uv.r - 0.5;\n" +
            "    float v = uv.g - 0.5;\n" +
            "    // BT.601 转换\n" +
            "    float r = y + 1.402 * v;\n" +
            "    float g = y - 0.344136 * u - 0.714136 * v;\n" +
            "    float b = y + 1.772 * u;\n" +
            "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n";

    // 全屏四边形顶点坐标
    private static final float[] VERTEX_COORDS = {
            -1f, 1f,   // 左上
            1f, 1f,    // 右上
            -1f, -1f,  // 左下
            1f, -1f    // 右下
    };

    // 纹理坐标（Y 轴翻转，适配 Android 坐标系）
    private static final float[] TEXTURE_COORDS = {
            0f, 0f,    // 左上
            1f, 0f,    // 右上
            0f, 1f,    // 左下
            1f, 1f     // 右下
    };

    private int mProgram = -1;
    private int mYTextureId = -1;
    private int mUvTextureId = -1;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mYTextureHandle;
    private int mUvTextureHandle;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mVideoWidth;   // 视频实际宽度（不含 stride padding）
    private int mVideoHeight;  // 视频实际高度
    private boolean mInitialized = false;

    /**
     * 初始化 GL 资源：编译 shader、创建 program、创建 Y/UV 纹理。
     * 必须在 GL 线程调用。
     *
     * @param videoWidth  视频实际宽度（像素，不含 stride padding）
     * @param videoHeight 视频实际高度（像素）
     */
    public void init(int videoWidth, int videoHeight) {
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;

        // 创建顶点缓冲
        mVertexBuffer = createFloatBuffer(VERTEX_COORDS);
        mTexCoordBuffer = createFloatBuffer(TEXTURE_COORDS);

        // 编译 shader 并创建 program
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        mProgram = GLES20.glCreateProgram();
        GLES20.glAttachShader(mProgram, vertexShader);
        GLES20.glAttachShader(mProgram, fragmentShader);
        GLES20.glLinkProgram(mProgram);

        // 检查链接状态
        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] == 0) {
            String error = GLES20.glGetProgramInfoLog(mProgram);
            GLES20.glDeleteProgram(mProgram);
            throw new RuntimeException("Error linking program: " + error);
        }

        // 删除已链接的 shader 对象
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        // 获取 attribute 和 uniform 位置
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mYTextureHandle = GLES20.glGetUniformLocation(mProgram, "yTexture");
        mUvTextureHandle = GLES20.glGetUniformLocation(mProgram, "uvTexture");

        // 创建 Y 纹理（GL_R8 格式，单通道）
        mYTextureId = createTexture();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTextureId);
        // 预分配纹理存储（使用 stride 作为宽度，后续 render 时会用实际 stride）
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R8,
                videoWidth, videoHeight, 0, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, null);

        // 创建 UV 纹理（GL_RG8 格式，双通道交错）
        mUvTextureId = createTexture();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUvTextureId);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RG8,
                videoWidth / 2, videoHeight / 2, 0, GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        mInitialized = true;
        Log.i(TAG, "init() videoWidth=" + videoWidth + " videoHeight=" + videoHeight);
    }

    /**
     * 当 Surface 尺寸变化时调用，记录屏幕尺寸用于 glViewport 设置。
     *
     * @param surfaceWidth  Surface 宽度
     * @param surfaceHeight Surface 高度
     */
    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight) {
        mSurfaceWidth = surfaceWidth;
        mSurfaceHeight = surfaceHeight;
        Log.i(TAG, "onSurfaceChanged() " + surfaceWidth + "x" + surfaceHeight);
    }

    /**
     * 渲染 NV12 数据到屏幕。在一个 shader pass 内完成：
     * 1. 通过 glTexSubImage2D 上传 Y 平面和 UV 平面数据到 GPU 纹理
     * 2. 在 fragment shader 中完成 NV12→RGB 色彩空间转换
     * 3. 直接渲染到默认帧缓冲（屏幕）
     *
     * @param yuvData     NV12 格式的完整 YUV 数据（Y 平面 + UV 平面）
     * @param stride      Y 平面每行的字节跨度（含 padding）
     * @param sliceHeight Y 平面的总行数（含 padding，UV 平面从 stride * sliceHeight 偏移开始）
     * @param height      视频实际高度（像素行数，用于上传有效数据）
     */
    public void render(ByteBuffer yuvData, int stride, int sliceHeight, int height) {
        if (!mInitialized) {
            Log.e(TAG, "render() not initialized");
            return;
        }

        if (yuvData == null || yuvData.remaining() == 0) {
            Log.e(TAG, "render() yuvData is null or empty");
            return;
        }

        // 设置像素存储对齐
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);

        // 上传 Y 平面数据
        // GL_UNPACK_ROW_LENGTH 单位是像素数。Y 平面格式为 GL_R8（每像素 1 字节），所以行跨度 = stride 像素
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, stride);
        yuvData.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                mVideoWidth, height, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, yuvData);

        // 上传 UV 平面数据（紧跟在 Y 平面之后，偏移 = stride * sliceHeight）
        // UV 平面格式为 GL_RG8（每像素 2 字节），GL_UNPACK_ROW_LENGTH 单位是像素数
        // UV 平面每行有 stride 字节，每个 UV 像素占 2 字节，所以行跨度 = stride / 2 像素
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, stride / 2);
        yuvData.position(stride * sliceHeight);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUvTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                mVideoWidth / 2, height / 2, GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, yuvData);

        // 恢复默认行跨度
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);

        // 绑定默认帧缓冲（屏幕）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 使用 shader program
        GLES20.glUseProgram(mProgram);

        // 设置纹理 uniform
        GLES20.glUniform1i(mYTextureHandle, 0);   // GL_TEXTURE0
        GLES20.glUniform1i(mUvTextureHandle, 1);   // GL_TEXTURE1

        // 设置顶点属性
        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionHandle);

        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        // 绘制全屏四边形
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 清理状态
        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * 释放所有 GL 资源。
     */
    public void release() {
        if (mProgram != -1) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = -1;
        }
        if (mYTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mYTextureId}, 0);
            mYTextureId = -1;
        }
        if (mUvTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mUvTextureId}, 0);
            mUvTextureId = -1;
        }
        mVertexBuffer = null;
        mTexCoordBuffer = null;
        mInitialized = false;
        Log.i(TAG, "release()");
    }

    // ---- 私有辅助方法 ----

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compileStatus = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0);
        if (compileStatus[0] == 0) {
            String error = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RuntimeException("Error compiling shader: " + error);
        }
        return shader;
    }

    private static int createTexture() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        int textureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return textureId;
    }

    private static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
}
