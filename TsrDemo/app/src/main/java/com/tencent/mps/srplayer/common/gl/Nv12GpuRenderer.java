package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

/**
 * NV12 GPU 渲染器：把"增强后的 Y 平面（CPU ByteBuffer）+ 已在 GPU 上的 UV 纹理"合成 RGB 上屏。
 *
 * <p>Y 从 {@link ByteBuffer} 上传；UV 直接复用 {@link OesYuvSplitter} 生成的 GPU 纹理（{@code GL_RG8}），
 * 不再走 CPU 路径，省一次 UV 回读和一次 UV 上传。</p>
 *
 * <p>BT.601 limited range 反向（与 {@link OesYuvSplitter} 写出的 Y/UV 配对）：</p>
 * <pre>
 *   Y' = (Y - 16/255) * 1.164
 *   R = Y' + 1.596 * (V - 128/255)
 *   G = Y' - 0.392 * (U - 128/255) - 0.813 * (V - 128/255)
 *   B = Y' + 2.017 * (U - 128/255)
 * </pre>
 *
 * <p>本类是 {@code com.tencent.mps.srplayer.common.gl} 下的 GL 原语，仅依赖 Android GLES，不引用任何 app 业务代码，可被多条增强链路复用。</p>
 */
public class Nv12GpuRenderer {
    private static final String TAG = "Nv12GpuRenderer";

    private static final float[] VERTEX_COORDS = {
            -1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f
    };

    // FBO 纹理坐标系原点在左下（GL 约定），采样时 V 翻一下使上屏画面方向正常。
    private static final float[] TEXTURE_COORDS = {
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
    };

    private int mProgram = -1;
    private int mYTextureId = -1;
    private int mPositionHandle;
    private int mTexCoordHandle;
    private int mYSamplerHandle;
    private int mUvSamplerHandle;
    private int mYuv2RgbHandle;
    private int mYuvOffsetHandle;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    // OES 路径（OesYuvSplitter 写出）严格要求与 BT.601 limited 配对。
    private Nv12Shaders.YuvColorSpace mColorSpace = Nv12Shaders.YuvColorSpace.BT601_LIMITED;
    private boolean mColorSpaceDirty = true;

    private int mVideoWidth;
    private int mVideoHeight;
    private int mSurfaceWidth;
    private int mSurfaceHeight;
    private int mViewportX = 0;
    private int mViewportY = 0;
    private int mViewportW = 0;
    private int mViewportH = 0;
    private boolean mInitialized = false;

    /** 初始化 GL 资源。GL 线程调用。 */
    public void init(int videoWidth, int videoHeight) {
        if (mInitialized) {
            Log.w(TAG, "init() already initialized");
            return;
        }
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;

        mVertexBuffer = Nv12Shaders.createFloatBuffer(VERTEX_COORDS);
        mTexCoordBuffer = Nv12Shaders.createFloatBuffer(TEXTURE_COORDS);

        mProgram = Nv12Shaders.linkProgram(
                Nv12Shaders.VERTEX_SHADER, Nv12Shaders.FRAGMENT_SHADER_NV12_TO_RGB);

        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord");
        mYSamplerHandle = GLES20.glGetUniformLocation(mProgram, "yTexture");
        mUvSamplerHandle = GLES20.glGetUniformLocation(mProgram, "uvTexture");
        mYuv2RgbHandle = GLES20.glGetUniformLocation(mProgram, "uYuv2Rgb");
        mYuvOffsetHandle = GLES20.glGetUniformLocation(mProgram, "uYuvOffset");
        mColorSpaceDirty = true;

        mYTextureId = Nv12Shaders.createNv12Texture(GLES30.GL_R8, GLES30.GL_RED,
                videoWidth, videoHeight);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        mInitialized = true;
        Log.i(TAG, "init() ok: " + videoWidth + "x" + videoHeight + ", yTex=" + mYTextureId);
    }

    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight) {
        mSurfaceWidth = surfaceWidth;
        mSurfaceHeight = surfaceHeight;
        Log.i(TAG, "onSurfaceChanged() " + surfaceWidth + "x" + surfaceHeight);
    }

    /**
     * 设置 NV12&rarr;RGB 色彩空间。真正 glUniform 上传发生在下一次 GL 线程的 render() 内。
     *
     * <p><b>注意</b>：OES 路径（{@link OesYuvSplitter} 输出的 UV 纹理）严格要求 BT.601 limited，
     * 不要为该路径修改色彩空间，否则画面会偏色。</p>
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
     * 设置 render 时使用的视口子矩形（左下角原点）。典型用途：把视频按纵横比居中贴合到输出窗口。
     * {@code w<=0 || h<=0} 表示恢复为使用 onSurfaceChanged 传入的全窗尺寸。
     */
    public void setRenderRect(int x, int y, int w, int h) {
        mViewportX = x;
        mViewportY = y;
        mViewportW = w;
        mViewportH = h;
    }

    /** 渲染一帧到屏幕（紧凑 Y 输入版本）。 */
    public void render(ByteBuffer yPlane, int uvTextureId) {
        render(yPlane, mVideoWidth, uvTextureId);
    }

    /**
     * 渲染一帧到屏幕（支持 Y 平面带 stride）。
     *
     * @param yPlane      Y 平面 buffer（容量 &gt;= yStride * (videoH-1) + videoW 字节）
     * @param yStride     Y 平面每行字节跨度；紧凑时传 videoWidth，带 stride 时传实际行跨度
     * @param uvTextureId UV 纹理 ID
     */
    public void render(ByteBuffer yPlane, int yStride, int uvTextureId) {
        if (!mInitialized) {
            Log.e(TAG, "render() not initialized");
            return;
        }
        if (yStride < mVideoWidth) {
            Log.e(TAG, "render() yStride=" + yStride + " < videoWidth=" + mVideoWidth);
            return;
        }
        int needed = yStride * (mVideoHeight - 1) + mVideoWidth;
        if (yPlane == null || yPlane.capacity() < needed) {
            Log.e(TAG, "render() yPlane invalid, cap=" + (yPlane == null ? -1 : yPlane.capacity())
                    + " expect>=" + needed + " (yStride=" + yStride + ")");
            return;
        }
        if (uvTextureId <= 0) {
            Log.e(TAG, "render() invalid uvTextureId=" + uvTextureId);
            return;
        }

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH,
                yStride == mVideoWidth ? 0 : yStride);
        yPlane.position(0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYTextureId);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0,
                mVideoWidth, mVideoHeight, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, yPlane);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uvTextureId);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, mSurfaceWidth, mSurfaceHeight);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        int vx = mViewportX;
        int vy = mViewportY;
        int vw = (mViewportW > 0) ? mViewportW : mSurfaceWidth;
        int vh = (mViewportH > 0) ? mViewportH : mSurfaceHeight;
        GLES20.glViewport(vx, vy, vw, vh);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mYSamplerHandle, 0);
        GLES20.glUniform1i(mUvSamplerHandle, 1);

        if (mColorSpaceDirty) {
            float[] m = Nv12Shaders.getYuv2RgbMatrixColumnMajor(mColorSpace);
            float[] off = Nv12Shaders.getYuvOffset(mColorSpace);
            GLES20.glUniformMatrix3fv(mYuv2RgbHandle, 1, false, m, 0);
            GLES20.glUniform3fv(mYuvOffsetHandle, 1, off, 0);
            mColorSpaceDirty = false;
        }

        GLES20.glVertexAttribPointer(mPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glEnableVertexAttribArray(mTexCoordHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(mPositionHandle);
        GLES20.glDisableVertexAttribArray(mTexCoordHandle);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    public void release() {
        if (mProgram != -1) {
            GLES20.glDeleteProgram(mProgram);
            mProgram = -1;
        }
        if (mYTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mYTextureId}, 0);
            mYTextureId = -1;
        }
        mVertexBuffer = null;
        mTexCoordBuffer = null;
        mInitialized = false;
        Log.i(TAG, "release()");
    }
}
