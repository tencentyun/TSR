package com.tencent.mps.srplayer.opengl;

import android.opengl.GLES20;
import android.util.Log;

/**
 * 该类用于管理渲染状态，包括渲染目标和帧缓冲。
 */
public class RenderState {
    private static final String TAG = "RenderState";
    /**
     * 渲染目标
     */
    private final Texture mRenderTarget;

    /**
     * 帧缓冲ID
     */
    private int mFrameBufferId;

    /**
     * 构造一个渲染状态实例。<br>
     * 构造时会创建帧缓冲，并把渲染目标挂到帧缓冲上。
     */
    public RenderState(Texture renderTarget) {
        this.mRenderTarget = renderTarget;

        // 创建一个帧缓冲
        if (mFrameBufferId == 0) {
            final int[] frameBuffers = new int[1];
            GLES20.glGenFramebuffers(1, frameBuffers, 0);
            mFrameBufferId = frameBuffers[0];
        }

        // 将渲染目标添加到帧缓冲上
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBufferId);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, renderTarget.getTextureId(),
                0);

        // 检查帧缓冲的状态。
        final int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete, status: " + status);
            return;
        }

        // 解绑帧缓冲
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * 获取渲染目标。
     */
    public Texture getRenderTarget() {
        return mRenderTarget;
    }

    /**
     * 获取帧缓冲ID。
     */
    public int getFrameBufferId() {
        return mFrameBufferId;
    }

    /**
     * 释放帧缓冲。
     */
    public void release() {
        if (mFrameBufferId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{mFrameBufferId}, 0);
            mFrameBufferId = 0;
        }
    }
}
