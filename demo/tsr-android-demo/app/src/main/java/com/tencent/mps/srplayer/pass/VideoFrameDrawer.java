package com.tencent.mps.srplayer.pass;

import android.content.Context;
import android.opengl.GLES20;

import com.tencent.mps.srplayer.opengl.GlUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class VideoFrameDrawer {
    private static final String TAG = "VideoFrameDrawer";

    /**
     * Shaders
     */
    private static final String VERTEX_SHADER_NAME = "shaders/videoquad.vert";
    private static final String FRAGMENT_SHADER_NAME = "shaders/videoTex2D.frag";

    private final float[] mVertexCoors =  new float[]{
            -1f, 1f,
            1f, 1f,
            -1f, -1f,
            1f, -1f
    };
    private final float[] mTextureCoors = new float[]{
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
    };

    /**
     * application context
     */
    private Context mContext;

    /**
     * OpenGL program
     */
    private int mProgram = -1;

    /**
     * vertex
     */
    private int mVertexPosHandler = -1;

    /**
     * coords
     */
    private int mTexturePosHandler = -1;

    /**
     * tex
     */
    private int mTextureHandler = -1;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTextureBuffer;

    private int[] textures;

    int mWidth;
    int mHeight;

    public VideoFrameDrawer(){

    }

    /**
     * In the GLThread, initialize the Drawer by sequentially performing the following steps: bind the texture, load
     * the shader, create the GLProgram, add the shader to the GLProgram, and connect them.
     * @param context applicationContext
     * @throws IOException IOException
     */
    public void createOnGLThread(Context context) throws IOException {
        mContext = context.getApplicationContext();

        ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(mVertexCoors.length * 4);
        vertexByteBuffer.order(ByteOrder.nativeOrder());

        mVertexBuffer = vertexByteBuffer.asFloatBuffer();
        mVertexBuffer.put(mVertexCoors);
        mVertexBuffer.position(0);

        ByteBuffer textureByteBuffer = ByteBuffer.allocateDirect(mTextureCoors.length * 4);
        textureByteBuffer.order(ByteOrder.nativeOrder());

        mTextureBuffer = textureByteBuffer.asFloatBuffer();
        mTextureBuffer.put(mTextureCoors);
        mTextureBuffer.position(0);

        createGLProgram();
    }

    private void createGLProgram() throws IOException {
        if (mProgram == -1) {
            int vertexShader =
                    GlUtils.loadGLShader(TAG, mContext, GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
            int fragmentShader =
                    GlUtils.loadGLShader(TAG, mContext, GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME);

            mProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(mProgram, vertexShader);
            GLES20.glAttachShader(mProgram, fragmentShader);
            GLES20.glLinkProgram(mProgram);

            mVertexPosHandler = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTexturePosHandler = GLES20.glGetAttribLocation(mProgram, "aCoordinate");
            mTextureHandler = GLES20.glGetUniformLocation(mProgram, "uTexture");
            GlUtils.checkGLError(TAG, "VideoFrameDrawer");
        }
        GLES20.glUseProgram(mProgram);
    }

    /**
     * 进行SurfaceTexture的绘制
     */
    public void draw(int textureId) {
        GlUtils.checkGLError(TAG, "VideoFrameDrawer");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);

        GLES20.glUseProgram(mProgram);
        GLES20.glUniform1i(mTextureHandler, 0);
        GLES20.glViewport(0, 0, mWidth, mHeight);

        GLES20.glVertexAttribPointer(mVertexPosHandler, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glVertexAttribPointer(mTexturePosHandler, 2, GLES20.GL_FLOAT, false, 0, mTextureBuffer);

        GLES20.glEnableVertexAttribArray(mVertexPosHandler);
        GLES20.glEnableVertexAttribArray(mTexturePosHandler);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0,4);
        GlUtils.checkGLError(TAG, "VideoFrameDrawer");
    }

    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight) {

        ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(mVertexCoors.length * 4);
        vertexByteBuffer.order(ByteOrder.nativeOrder());

        mVertexBuffer = vertexByteBuffer.asFloatBuffer();
        mVertexBuffer.put(mVertexCoors);
        mVertexBuffer.position(0);

        mWidth = surfaceWidth;
        mHeight = surfaceHeight;
    }

    private void resolveRotate(int rotation) {
        float x;
        float y;
        switch (rotation) {
            case 90:
                x = mVertexCoors[0];
                y = mVertexCoors[1];
                mVertexCoors[0] = mVertexCoors[2];
                mVertexCoors[1] = mVertexCoors[3];
                mVertexCoors[2] = mVertexCoors[6];
                mVertexCoors[3] = mVertexCoors[7];
                mVertexCoors[6] = mVertexCoors[4];
                mVertexCoors[7] = mVertexCoors[5];
                mVertexCoors[4] = x;
                mVertexCoors[5] = y;
                break;
            case 180:
                swap(mVertexCoors, 0, 6);
                swap(mVertexCoors, 1, 7);
                swap(mVertexCoors, 2, 4);
                swap(mVertexCoors, 3, 5);
                break;
            case 270:
                x = mVertexCoors[0];
                y = mVertexCoors[1];
                mVertexCoors[0] = mVertexCoors[4];
                mVertexCoors[1] = mVertexCoors[5];
                mVertexCoors[4] = mVertexCoors[6];
                mVertexCoors[5] = mVertexCoors[7];
                mVertexCoors[6] = mVertexCoors[2];
                mVertexCoors[7] = mVertexCoors[3];
                mVertexCoors[2] = x;
                mVertexCoors[3] = y;
                break;
            case 0:
            default:
                break;
        }
    }

    private void swap(float []nums, int i, int j) {
        float tmp = nums[i];
        nums[i] = nums[j];
        nums[j] = tmp;
    }
}
