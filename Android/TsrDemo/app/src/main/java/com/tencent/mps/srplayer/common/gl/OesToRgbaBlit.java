package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.util.Log;

/**
 * OES → RGBA8 的一次性 blit pass。
 *
 * <p>把 MediaCodec/SurfaceTexture 提供的 {@code samplerExternalOES} 纹理
 * 用一次全屏三角形采样，写到内部 {@code GL_TEXTURE_2D RGBA8} 纹理上，
 * 让普通 sampler2D 也能消费视频帧（例如喂 TiePass 系列 Shader 原语）。</p>
 *
 * <p>纹理尺寸固定为 init 时传入的 (width, height)，每帧不重建；
 * 调用 {@link #blit(int, float[])} 后 {@link #getOutputTextureId()} 即可拿到 RGBA8 纹理 id。
 * 所有 GL 调用必须在持有目标 GL context 的同一线程上。</p>
 */
public class OesToRgbaBlit {

    private static final String TAG = "OesToRgbaBlit";

    private static final String VS_SRC =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "uniform mat4 uStMatrix;\n"
                    + "out vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    vec2 pos;\n"
                    + "    vec2 uv;\n"
                    + "    if (gl_VertexID == 0) { pos = vec2(-1.0,-1.0); uv = vec2(0.0,0.0); }\n"
                    + "    else if (gl_VertexID == 1) { pos = vec2( 3.0,-1.0); uv = vec2(2.0,0.0); }\n"
                    + "    else { pos = vec2(-1.0, 3.0); uv = vec2(0.0,2.0); }\n"
                    + "    vTexCoord = (uStMatrix * vec4(uv, 0.0, 1.0)).xy;\n"
                    + "    gl_Position = vec4(pos, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FS_SRC =
            "#version 300 es\n"
                    + "#extension GL_OES_EGL_image_external_essl3 : require\n"
                    + "precision mediump float;\n"
                    + "uniform samplerExternalOES uOes;\n"
                    + "in vec2 vTexCoord;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    fragColor = texture(uOes, vTexCoord);\n"
                    + "}\n";

    private int mWidth;
    private int mHeight;

    private int mProgram = 0;
    private int mLocStMatrix = -1;
    private int mLocOes = -1;

    private int mFbo = 0;
    private int mOutputTex = 0;
    private int mEmptyVao = 0;

    /** 创建 program / FBO / 输出 RGBA8 纹理。 */
    public void init(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("invalid size " + width + "x" + height);
        }
        mWidth = width;
        mHeight = height;

        mProgram = Nv12Shaders.buildProgram(VS_SRC, FS_SRC, "OesBlit");
        mLocStMatrix = GLES30.glGetUniformLocation(mProgram, "uStMatrix");
        mLocOes = GLES30.glGetUniformLocation(mProgram, "uOes");
        if (mLocStMatrix < 0 || mLocOes < 0) {
            release();
            throw new RuntimeException("missing uniform locations: stMatrix=" + mLocStMatrix
                    + " oes=" + mLocOes);
        }

        // 输出纹理：RGBA8，linear filter（保证下游 SDK 在子像素位置采样时的质量与稳定性）
        int[] tex = new int[1];
        GLES30.glGenTextures(1, tex, 0);
        mOutputTex = tex[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mOutputTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, width, height);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);

        // FBO
        int[] fbo = new int[1];
        GLES30.glGenFramebuffers(1, fbo, 0);
        mFbo = fbo[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, mOutputTex, 0);
        int status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            release();
            throw new RuntimeException("FBO incomplete: 0x" + Integer.toHexString(status));
        }

        // 空 VAO
        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        mEmptyVao = vao[0];

        Log.i(TAG, "init " + width + "x" + height);
    }

    /**
     * 把 OES 纹理 blit 到内部 RGBA8 纹理。调用方需保证 GL context current。
     * 不会改动调用方的 framebuffer / viewport — 退出前会恢复 viewport。
     *
     * @param oesTextureId OES 纹理 id（{@code GL_TEXTURE_EXTERNAL_OES}）
     * @param stMatrix     SurfaceTexture.getTransformMatrix() 的 4x4 矩阵
     */
    public void blit(int oesTextureId, float[] stMatrix) {
        if (mProgram == 0) {
            throw new IllegalStateException("not initialized");
        }
        // 备份 viewport（仅这一项可能被外部依赖；fbo/vao/program 不备份，按"退出前清零绑定"处理）
        int[] vp = new int[4];
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, vp, 0);

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mFbo);
        GLES30.glViewport(0, 0, mWidth, mHeight);

        GLES30.glUseProgram(mProgram);
        GLES30.glBindVertexArray(mEmptyVao);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES30.glUniform1i(mLocOes, 0);
        GLES30.glUniformMatrix4fv(mLocStMatrix, 1, false, stMatrix, 0);

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES30.glBindVertexArray(0);
        GLES30.glUseProgram(0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        GLES30.glViewport(vp[0], vp[1], vp[2], vp[3]);
    }

    /** 当前帧的 RGBA8 输出纹理 id（{@code GL_TEXTURE_2D}）。 */
    public int getOutputTextureId() {
        return mOutputTex;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public void release() {
        if (mEmptyVao != 0) {
            GLES30.glDeleteVertexArrays(1, new int[]{mEmptyVao}, 0);
            mEmptyVao = 0;
        }
        if (mFbo != 0) {
            GLES30.glDeleteFramebuffers(1, new int[]{mFbo}, 0);
            mFbo = 0;
        }
        if (mOutputTex != 0) {
            GLES30.glDeleteTextures(1, new int[]{mOutputTex}, 0);
            mOutputTex = 0;
        }
        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }

    /**
     * 把 OES 纹理直接 blit 到调用方当前绑定的 framebuffer / viewport。
     * 用于 1× 直通对照模式（不经过 RGBA8 离屏中转，节省一次 fillrate）。
     *
     * <p>调用方需在调用前自行 {@code glBindFramebuffer} / {@code glViewport}。</p>
     */
    public void blitToCurrentFramebuffer(int oesTextureId, float[] stMatrix) {
        if (mProgram == 0) {
            throw new IllegalStateException("not initialized");
        }
        GLES30.glUseProgram(mProgram);
        GLES30.glBindVertexArray(mEmptyVao);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES30.glUniform1i(mLocOes, 0);
        GLES30.glUniformMatrix4fv(mLocStMatrix, 1, false, stMatrix, 0);

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES30.glBindVertexArray(0);
        GLES30.glUseProgram(0);
    }
}
