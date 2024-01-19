package com.tencent.mps.srplayer.pass;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLES30;

import com.tencent.mps.srplayer.helper.TapHelper;
import com.tencent.mps.srplayer.opengl.GlUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class CompareTexDrawer {
    private static final String TAG = "CompareTexDrawer";

    /**
     * shaders
     */
    private static final String VERTEX_SHADER_NAME = "shaders/cmpTex.vert";
    private static final String FRAGMENT_SHADER_NAME = "shaders/cmpTex.frag";

    private final float[] mVertexCoors =  new float[]{
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
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
     * uniform ViewportLocation
     */
    private int mViewportInfoLocation = -1;

    /**
     * uniform linePos
     */
    private int mLineLocation = -1;

    /**
     * uniform rotation
     */
    private int mRotationLocation = -1;

    /**
     * X pos
     */
    private volatile float mTouchXPos = 0;
    /**
     * Y pos
     */
    private volatile float mTouchYPos = 0;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTextureBuffer;

    int mViewportWidth;
    int mViewportHeight;
    int mRotation;

    public CompareTexDrawer(){

    }

    /**
     * In the GLThread, initialize the Drawer by sequentially performing the following steps: bind the texture, load the
     * shader, create the GLProgram, add the shader to the GLProgram, and connect them.
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
                    GlUtils.loadGLShader(TAG, mContext, GLES30.GL_VERTEX_SHADER, VERTEX_SHADER_NAME);
            int fragmentShader =
                    GlUtils.loadGLShader(TAG, mContext, GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_NAME);

            mProgram = GLES20.glCreateProgram();
            GLES20.glAttachShader(mProgram, vertexShader);
            GLES20.glAttachShader(mProgram, fragmentShader);
            GLES20.glLinkProgram(mProgram);

            mVertexPosHandler = GLES20.glGetAttribLocation(mProgram, "aPosition");
            mTexturePosHandler = GLES20.glGetAttribLocation(mProgram, "aCoordinate");

            mViewportInfoLocation = GLES20.glGetUniformLocation(mProgram, "ViewportInfo");
            mLineLocation = GLES20.glGetUniformLocation(mProgram, "linePos");
            mRotationLocation = GLES20.glGetUniformLocation(mProgram, "rotation");
            GlUtils.checkGLError(TAG, "CompareImgDrawer");
        }
        GLES20.glUseProgram(mProgram);
    }

    /**
     * render SurfaceTexture
     */
    public void draw(int texId0, int texId1) {
        GlUtils.checkGLError(TAG, "CompareImgDrawer");
        GLES30.glUseProgram(mProgram);

        GLES30.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, texId0);
        int textureLocation0 = GLES20.glGetUniformLocation(mProgram, "uTexture0");
        GLES20.glUniform1i(textureLocation0, 0);

        GLES30.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, texId1);
        int textureLocation1 = GLES20.glGetUniformLocation(mProgram, "uTexture1");
        GLES20.glUniform1i(textureLocation1, 1);

        GLES20.glViewport(0, 0, mViewportWidth, mViewportHeight);

        if (mViewportInfoLocation > -1) {
            float viewportWidthInverse = (float) (1.0 / mViewportWidth);
            float viewportHeightInverse = (float) (1.0 / mViewportHeight);
            float[] viewportInfo = new float[] {viewportWidthInverse, viewportHeightInverse, mViewportWidth,
                    mViewportHeight};
            GLES30.glUniform4fv(mViewportInfoLocation, 1, viewportInfo, 0);
        }

        if (mLineLocation > -1) {
            if (TapHelper.getInstance().getDownX() >= 0) {
                mTouchXPos = TapHelper.getInstance().getDownX();
            }
            if (TapHelper.getInstance().getDownY() >= 0) {
                mTouchYPos = TapHelper.getInstance().getDownY();
            }
            float[] lineLocationInfo = new float[] {mTouchXPos, mTouchYPos};
            GLES30.glUniform2fv(mLineLocation, 1, lineLocationInfo, 0);
        }

        if (mRotationLocation > -1) {
            GLES30.glUniform1i(mRotationLocation, mRotation);
        }

        GlUtils.checkGLError(TAG, "CompareImgDrawer");

        GLES20.glVertexAttribPointer(mVertexPosHandler, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glVertexAttribPointer(mTexturePosHandler, 2, GLES20.GL_FLOAT, false, 0, mTextureBuffer);
        GlUtils.checkGLError(TAG, "CompareImgDrawer");

        GLES20.glEnableVertexAttribArray(mVertexPosHandler);
        GLES20.glEnableVertexAttribArray(mTexturePosHandler);
        GlUtils.checkGLError(TAG, "CompareImgDrawer");

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0,4);
        GlUtils.checkGLError(TAG, "CompareImgDrawer");
    }

    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight, int rotation) {
        resolveRotate(rotation);

        ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(mVertexCoors.length * 4);
        vertexByteBuffer.order(ByteOrder.nativeOrder());

        mVertexBuffer = vertexByteBuffer.asFloatBuffer();
        mVertexBuffer.put(mVertexCoors);
        mVertexBuffer.position(0);

        mViewportWidth = surfaceWidth;
        mViewportHeight = surfaceHeight;

        mTouchXPos = mViewportWidth / 2.0f;
        mTouchYPos = mViewportHeight / 2.0f;

        mRotation = rotation;
    }

    private void resolveRotate(int rotation) {
        float x;
        float y;
        switch (rotation) {
            case 90:
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
            case 180:
                swap(mVertexCoors, 0, 6);
                swap(mVertexCoors, 1, 7);
                swap(mVertexCoors, 2, 4);
                swap(mVertexCoors, 3, 5);
                break;
            case 270:
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
