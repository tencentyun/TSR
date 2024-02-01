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
     * uniform lineY
     */
    private int mLineXLocation = -1;

    /**
     * X pos
     */
    private volatile float mTouchXPos = 0;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTextureBuffer;

    int mViewportWidth;
    int mViewportHeight;

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
            mLineXLocation = GLES20.glGetUniformLocation(mProgram, "lineX");
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
            int viewportInfoLocation = GLES20.glGetUniformLocation(mProgram, "ViewportInfo");
            GLES30.glUniform4fv(viewportInfoLocation, 1, viewportInfo, 0);
        }

        if (mLineXLocation > -1) {
            if (TapHelper.getInstance().getDownX() >= 0) {
                mTouchXPos = TapHelper.getInstance().getDownX();
            }
            GLES30.glUniform1f(mLineXLocation, mTouchXPos);
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

    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight) {
        ByteBuffer textureByteBuffer = ByteBuffer.allocateDirect(mTextureCoors.length * 4);
        textureByteBuffer.order(ByteOrder.nativeOrder());

        mTextureBuffer = textureByteBuffer.asFloatBuffer();
        mTextureBuffer.put(mTextureCoors);
        mTextureBuffer.position(0);

        mViewportWidth = surfaceWidth;
        mViewportHeight = surfaceHeight;

        mTouchXPos = mViewportWidth / 2.0f;
    }
}
