package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

/**
 * OES 外部纹理工具：创建一张供 {@link android.graphics.SurfaceTexture} 绑定的
 * {@code GL_TEXTURE_EXTERNAL_OES} 纹理，并设置默认采样参数（LINEAR + CLAMP_TO_EDGE）。
 *
 * <p>本类是 {@code com.tencent.mps.srplayer.common.gl} 下的 GL 原语，仅依赖 Android GLES，不引用任何 app 业务代码，可被多条增强链路复用。</p>
 */
public final class OesTextureFactory {
    private OesTextureFactory() {}

    /** 创建一个 OES 纹理并设置默认采样参数。必须在 GL 线程调用。 */
    public static int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int id = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return id;
    }
}
