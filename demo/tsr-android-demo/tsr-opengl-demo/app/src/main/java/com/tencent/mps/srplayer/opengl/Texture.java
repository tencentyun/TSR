package com.tencent.mps.srplayer.opengl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;

public class Texture {

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
     * 纹理对象的类型（OES或2D）
     */
    private int mType;



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
    public Texture(boolean external, int type, int textureId, int width, int height) {
        mTextureId = textureId;
        mWidth = width;
        mHeight = height;
        mType = type;

        if (!external) {
            mTextureId = GlUtils.generateTexture(type);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(type, mTextureId);

            // 为纹理对象分配内存空间
            int PIXEL_FORMAT = GLES20.GL_RGBA;
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, PIXEL_FORMAT, width, height, 0, PIXEL_FORMAT,
                    GLES20.GL_UNSIGNED_BYTE, null);

            GLES20.glBindTexture(type, 0);
        }
    }

    /**
     * 构造一个类型为TEXTURE_2D的内部纹理。
     * @param width  该纹理的宽。
     * @param height 该纹理的高。
     */
    public Texture(int width, int height) {
        this(false, GLES20.GL_TEXTURE_2D, 0, width, height);
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

    public int getType() {
        return mType;
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
                + ", mTextureId=" + mTextureId
                + ", mWidth=" + mWidth
                + ", mHeight=" + mHeight
                + '}';
    }
}
