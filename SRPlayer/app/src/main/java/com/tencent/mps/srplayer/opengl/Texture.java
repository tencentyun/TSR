package com.tencent.mps.srplayer.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import androidx.annotation.NonNull;

public class Texture {
    public enum Type {
        TEXTURE_2D,
        TEXTURE_OSE
    }

    /**
     * 纹理类型
     */
    private final Type mType;

    /**
     * 该纹理的纹理ID
     */
    private int mTextureId;


    /**
     * 纹理对象的宽
     */
    private int mWidth;

    /**
     * 纹理对象的高
     */
    private int mHeight;

    /**
     * 构造一个纹理。
     * @param external  该纹理是否由外部创建。若false则内部会创建纹理并分配空间。
     * @param type      该纹理的类型。取值为:<br>
     *                                  {@link GLES20#GL_TEXTURE_2D} <br>
     *                                  {@link GLES11Ext#GL_TEXTURE_EXTERNAL_OES}
     * @param textureId 纹理的ID, 只有外部纹理该值才有意义。
     * @param width     该纹理的宽。
     * @param height    该纹理的高。
     */
    public Texture(boolean external, Type type, int textureId, int width, int height) {
        mType = type;
        mTextureId = textureId;
        mWidth = width;
        mHeight = height;

        int target = getTarget();

        if (!external) {
            mTextureId = GlUtils.generateTexture(target);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, mTextureId);

            // 为纹理对象分配内存空间
            int PIXEL_FORMAT = GLES20.GL_RGBA;
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, PIXEL_FORMAT, width, height, 0, PIXEL_FORMAT,
                    GLES20.GL_UNSIGNED_BYTE, null);

            GLES20.glBindTexture(target, 0);
        }
    }

    /**
     * 构造一个类型为{@link Type#TEXTURE_2D} 的内部纹理。
     * @param width  该纹理的宽。
     * @param height 该纹理的高。
     */
    public Texture(int width, int height) {
        this(false, Type.TEXTURE_2D, 0, width, height);
    }

    /**
     * 返回纹理对象的宽。
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * 返回纹理对象的高。
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * 返回纹理对象的纹理ID。
     */
    public int getTextureId() {
        return mTextureId;
    }

    /**
     * 返回纹理对象的纹理目标。<br>
     * <br>
     * {@link GLES20#GL_TEXTURE_2D}
     * {@link GLES11Ext#GL_TEXTURE_EXTERNAL_OES}
     */
    public int getTarget() {
        return mType == Type.TEXTURE_2D ?
                GLES20.GL_TEXTURE_2D : GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
    }

    /**
     * 释放纹理对象。
     */
    public void release() {
        if (mTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[] {mTextureId}, 0);
            mTextureId = 0;
            mWidth = 0;
            mHeight = 0;
        }
    }

    @NonNull
    @Override
    public String toString() {
        return "Texture{"
                + "mType=" + mType
                + ", mTextureId=" + mTextureId
                + ", mWidth=" + mWidth
                + ", mHeight=" + mHeight
                + '}';
    }
}
