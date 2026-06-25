package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES30;

/**
 * 通用 sampler2D RGBA 纹理 → 当前 framebuffer/viewport 的全屏三角形 blit。
 *
 * <p>与 {@link OesToRgbaBlit} 的差异：</p>
 * <ul>
 *   <li>输入是普通 {@code GL_TEXTURE_2D}（不是 OES），无需 stMatrix；</li>
 *   <li>仅写当前 framebuffer + viewport，不创建任何离屏 FBO；</li>
 *   <li>shader 极薄：vertex 用 {@code gl_VertexID} 生成全屏三角形 + 标准 UV，fragment 一次 {@code texture()} 即可。</li>
 * </ul>
 *
 * <p>所有 GL 调用必须在持有目标 GL context 的同一线程上。</p>
 */
public class Texture2dBlit {

    private static final String VS_SRC =
            "#version 300 es\n"
                    + "precision highp float;\n"
                    + "out vec2 vTexCoord;\n"
                    + "void main() {\n"
                    + "    vec2 pos;\n"
                    + "    if (gl_VertexID == 0) { pos = vec2(-1.0,-1.0); vTexCoord = vec2(0.0, 0.0); }\n"
                    + "    else if (gl_VertexID == 1) { pos = vec2( 3.0,-1.0); vTexCoord = vec2(2.0, 0.0); }\n"
                    + "    else { pos = vec2(-1.0, 3.0); vTexCoord = vec2(0.0, 2.0); }\n"
                    + "    gl_Position = vec4(pos, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FS_SRC =
            "#version 300 es\n"
                    + "precision mediump float;\n"
                    + "uniform sampler2D uTex;\n"
                    + "in vec2 vTexCoord;\n"
                    + "out vec4 fragColor;\n"
                    + "void main() {\n"
                    + "    fragColor = texture(uTex, vTexCoord);\n"
                    + "}\n";

    private int mProgram = 0;
    private int mLocTex = -1;
    private int mEmptyVao = 0;

    /** 创建 program / 空 VAO。 */
    public void init() {
        mProgram = Nv12Shaders.buildProgram(VS_SRC, FS_SRC, "TexBlit");
        mLocTex = GLES30.glGetUniformLocation(mProgram, "uTex");
        if (mLocTex < 0) {
            release();
            throw new RuntimeException("missing uniform location: uTex=" + mLocTex);
        }

        int[] vao = new int[1];
        GLES30.glGenVertexArrays(1, vao, 0);
        mEmptyVao = vao[0];
    }

    /**
     * 把 {@code GL_TEXTURE_2D} RGBA 纹理 blit 到调用方当前绑定的 framebuffer / viewport。
     * 调用方需自行 {@code glBindFramebuffer} / {@code glViewport}。
     *
     * @param texId 输入 {@code GL_TEXTURE_2D} RGBA 纹理 id
     */
    public void blitToCurrentFramebuffer(int texId) {
        if (mProgram == 0) {
            throw new IllegalStateException("not initialized");
        }
        GLES30.glUseProgram(mProgram);
        GLES30.glBindVertexArray(mEmptyVao);

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId);
        GLES30.glUniform1i(mLocTex, 0);

        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0);
        GLES30.glBindVertexArray(0);
        GLES30.glUseProgram(0);
    }

    public void release() {
        if (mEmptyVao != 0) {
            GLES30.glDeleteVertexArrays(1, new int[]{mEmptyVao}, 0);
            mEmptyVao = 0;
        }
        if (mProgram != 0) {
            GLES30.glDeleteProgram(mProgram);
            mProgram = 0;
        }
    }
}
