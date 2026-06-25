package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * NV12 数据渲染器：把 Y 平面 + UV 平面（都来自 CPU ByteBuffer）合成 RGB 上屏。
 *
 * <p>在一个 shader pass 内完成纹理上传 + NV12→RGB 色彩转换 + 渲染到屏幕，无需中间 FBO。</p>
 *
 * <p><b>Y/UV 尺寸独立配置</b>：{@link #init(int, int, int, int)} 同时接收 Y 纹理与 UV 纹理的像素尺寸，
 * 因此既能服务普通 NV12（Y=W×H, UV=W/2×H/2），也能服务 Y 通道超分场景
 * （Y=outputW×outputH, UV=W/2×H/2）。shader 用归一化采样坐标，硬件按 GL_LINEAR 自动把 UV 上采样到 Y 的画面尺寸。</p>
 *
 * <p>典型使用流程（GL 线程）：</p>
 * <ol>
 *     <li>{@link #init(int, int, int, int)} 初始化 GL 资源</li>
 *     <li>Surface 尺寸变化时调用 {@link #onSurfaceChanged(int, int)}</li>
 *     <li>每帧调用 {@link #render} 渲染到屏幕</li>
 *     <li>不再使用时调用 {@link #release()} 释放</li>
 * </ol>
 *
 * <p><b>Y/UV 分路设计：</b>Y 和 UV 允许来自不同 buffer（也可以是同一 buffer 的不同偏移），
 * 这样在 Y 通道超分场景下，增强后的紧凑 Y 平面可以与解码器原始 UV 平面零拷贝拼接渲染。</p>
 */
public class Nv12Renderer {
    private static final String TAG = "Nv12Renderer";

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
    private int mYuv2RgbHandle;
    private int mYuvOffsetHandle;
    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;
    private int mSurfaceWidth;
    private int mSurfaceHeight;

    // 当前色彩空间；默认 BT.601 limited，保持历史行为不变。
    // setColorSpace() 仅打 dirty 标志，真正上传发生在 render() 里（必须在 GL 线程）。
    private Nv12Shaders.YuvColorSpace mColorSpace = Nv12Shaders.YuvColorSpace.BT601_LIMITED;
    private boolean mColorSpaceDirty = true;

    // Y 纹理实际像素尺寸（不含 stride padding）。普通 NV12 = videoW/videoH；超分 = outputW/outputH。
    private int mYTexWidth;
    private int mYTexHeight;
    // UV 纹理实际像素尺寸（NV12 半采样）。普通 NV12 与超分路径都使用原视频 UV：videoW/2 × videoH/2。
    private int mUvTexWidth;
    private int mUvTexHeight;

    private boolean mInitialized = false;

    /**
     * 初始化 GL 资源：编译 shader、创建 program、按指定尺寸预分配 Y/UV 纹理存储。GL 线程调用。
     *
     * <p>典型尺寸：</p>
     * <ul>
     *     <li>普通 NV12 1x：{@code init(W, H, W/2, H/2)}</li>
     *     <li>Y 通道超分：{@code init(outputW, outputH, W/2, H/2)}，outputW/outputH 来自 TieBufferEnhancer.InitResult</li>
     * </ul>
     *
     * @param yWidth   Y 纹理像素宽（不含 stride padding）
     * @param yHeight  Y 纹理像素高
     * @param uvWidth  UV 纹理像素宽（NV12 下 = videoW/2）
     * @param uvHeight UV 纹理像素高（NV12 下 = videoH/2）
     */
    public void init(int yWidth, int yHeight, int uvWidth, int uvHeight) {
        mYTexWidth = yWidth;
        mYTexHeight = yHeight;
        mUvTexWidth = uvWidth;
        mUvTexHeight = uvHeight;

        mVertexBuffer = Nv12Shaders.createFloatBuffer(VERTEX_COORDS);
        mTexCoordBuffer = Nv12Shaders.createFloatBuffer(TEXTURE_COORDS);

        mProgram = Nv12Shaders.linkProgram(
                Nv12Shaders.VERTEX_SHADER, Nv12Shaders.FRAGMENT_SHADER_NV12_TO_RGB);

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mYTextureHandle = GLES20.glGetUniformLocation(mProgram, "yTexture");
        mUvTextureHandle = GLES20.glGetUniformLocation(mProgram, "uvTexture");
        mYuv2RgbHandle = GLES20.glGetUniformLocation(mProgram, "uYuv2Rgb");
        mYuvOffsetHandle = GLES20.glGetUniformLocation(mProgram, "uYuvOffset");
        // 重新 init 时强制下一帧重新写入色彩空间 uniform（program 改变了 location 失效）
        mColorSpaceDirty = true;

        // Y 纹理：单通道 GL_R8
        mYTextureId = Nv12Shaders.createNv12Texture(GLES30.GL_R8, GLES30.GL_RED,
                mYTexWidth, mYTexHeight);
        // UV 纹理：双通道交错 GL_RG8
        mUvTextureId = Nv12Shaders.createNv12Texture(GLES30.GL_RG8, GLES30.GL_RG,
                mUvTexWidth, mUvTexHeight);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        mInitialized = true;
        Log.i(TAG, "init() yTex=" + mYTexWidth + "x" + mYTexHeight
                + " uvTex=" + mUvTexWidth + "x" + mUvTexHeight);
    }

    /**
     * 设置 NV12→RGB 反向公式使用的色彩空间。可在任意线程调用：本方法只标 dirty，
     * 真正的 glUniform 上传发生在下一次 GL 线程的 {@link #render} 内。
     *
     * <p>默认 {@link Nv12Shaders.YuvColorSpace#BT601_LIMITED}，与历史行为一致。</p>
     */
    public void setColorSpace(Nv12Shaders.YuvColorSpace cs) {
        if (cs == null || cs == mColorSpace) {
            return;
        }
        mColorSpace = cs;
        mColorSpaceDirty = true;
        Log.i(TAG, "setColorSpace() " + cs);
    }

    /**
     * 当 Surface 尺寸变化时调用，记录屏幕尺寸用于 glViewport 设置。
     */
    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight) {
        mSurfaceWidth = surfaceWidth;
        mSurfaceHeight = surfaceHeight;
        Log.i(TAG, "onSurfaceChanged() " + surfaceWidth + "x" + surfaceHeight);
    }

    /**
     * 渲染 NV12 数据到屏幕。Y 平面与 UV 平面分开传入，允许来自不同 buffer。
     * 在一个 shader pass 内完成：纹理上传 + NV12→RGB 色彩转换 + 绘制到默认帧缓冲。
     *
     * @param yBuffer   Y 平面 ByteBuffer（position 不限，内部会重置到 0）
     * @param yStride   Y 平面每行字节跨度（单位 byte）；紧凑时等于 yWidth，带 padding 时大于 yWidth
     * @param yHeight   Y 平面上传的有效行数（通常等于 init 时的 yHeight）
     * @param uvBuffer  UV 平面 ByteBuffer（可以与 yBuffer 是同一个对象，靠 uvOffset 定位）
     * @param uvOffset  UV 平面在 uvBuffer 中的起始字节偏移
     * @param uvStride  UV 平面每行字节跨度（单位 byte，每像素 2 字节）
     * @param uvHeight  UV 平面上传的有效行数
     */
    public void render(ByteBuffer yBuffer, int yStride, int yHeight,
                       ByteBuffer uvBuffer, int uvOffset, int uvStride, int uvHeight) {
        if (!mInitialized) {
            Log.e(TAG, "render() not initialized");
            return;
        }

        if (yBuffer == null || uvBuffer == null) {
            Log.e(TAG, "render() yBuffer or uvBuffer is null");
            return;
        }

        // 防御：部分机型 MediaCodec 输出 buffer 的 capacity 会比 stride*sliceHeight 略小，最后一行数据无 padding。
        // 直接 yBuffer.position(0) / uvBuffer.position(uvOffset) 在 uvOffset > capacity 时会抛
        // IllegalArgumentException。这里提前校验并丢帧，避免 GL 线程 crash。
        if (uvOffset < 0 || uvOffset > uvBuffer.capacity()) {
            Log.e(TAG, "render() uvOffset out of range: uvOffset=" + uvOffset
                    + " uvCap=" + uvBuffer.capacity() + ", skip frame");
            return;
        }

        // 设置像素存储对齐
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);

        // 上传 Y 平面：GL_R8（每像素 1 字节），行跨度 = yStride 像素
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, yStride);
        yBuffer.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                mYTexWidth, yHeight, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, yBuffer);

        // 上传 UV 平面：GL_RG8（每像素 2 字节），每行有 uvStride 字节，对应 uvStride / 2 个 UV 像素
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, uvStride / 2);
        uvBuffer.position(uvOffset);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mUvTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                mUvTexWidth, uvHeight, GLES30.GL_RG, GLES20.GL_UNSIGNED_BYTE, uvBuffer);

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
        GLES20.glUniform1i(mUvTextureHandle, 1);  // GL_TEXTURE1

        // 仅在色彩空间变化时上传矩阵 + 偏移；首帧必传一次（mColorSpaceDirty 初值为 true）
        if (mColorSpaceDirty) {
            float[] m = Nv12Shaders.getYuv2RgbMatrixColumnMajor(mColorSpace);
            float[] off = Nv12Shaders.getYuvOffset(mColorSpace);
            GLES20.glUniformMatrix3fv(mYuv2RgbHandle, 1, false, m, 0);
            GLES20.glUniform3fv(mYuvOffsetHandle, 1, off, 0);
            mColorSpaceDirty = false;
        }

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
}
