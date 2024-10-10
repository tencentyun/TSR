package com.tencent.mps.srplayer.pass;

import android.opengl.GLES20;
import android.util.Log;

import com.tencent.mps.srplayer.SRApplication;
import com.tencent.mps.srplayer.opengl.GlShader;
import com.tencent.mps.srplayer.opengl.GlUtils;
import com.tencent.mps.srplayer.opengl.Texture;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class OffScreenRenderPass {
    public static final String TAG = "OffScreenRenderPass";

    /**
     * Shaders
     */
    private static final String VERTEX_SHADER_NAME = "shaders/videoOffScreenRender.vert";
    /**
     * The texture that this pass render to.
     */
    private Texture mTexture;
    /**
     * 帧缓冲ID
     */
    private int mFrameBufferId;
    /**
     * OpenGL的渲染器和程序对象
     */
    private GlShader mShader;
    /**
     * 顶点缓冲区索引
     */
    private int mVertexBuffer;
    /**
     * 存储纹理uniform
     */
    private int mInputTextureLocation;
    /**
     * 顶点坐标位置
     */
    private int inPosLocation;
    /**
     * 纹理坐标位置
     */
    private int inTcLocation;
    /**
     * transform matrix
     */
    private int mTransformMatrixHandle;

    public OffScreenRenderPass() {

    }

    public void init (int outputTextureType, int destWidth, int destHeight, String assetsFragShaderPath) {
        Log.i(TAG, "creating OffScreenRenderPass: dest resolution = "
                + destWidth + "x" + destHeight);
        // create the target texture
        mTexture = new Texture(false, outputTextureType, 0, destWidth, destHeight);

        // create FBO and bind it to the target texture.
        // 创建一个帧缓冲
        if (mFrameBufferId == 0) {
            final int[] frameBuffers = new int[1];
            GLES20.glGenFramebuffers(1, frameBuffers, 0);
            mFrameBufferId = frameBuffers[0];
        }

        // 将渲染目标添加到帧缓冲上
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBufferId);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, mTexture.getType(), mTexture.getTextureId(),
                0);

        // 检查帧缓冲的状态。
        final int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete, status: " + status);
            return;
        }

        // 解绑帧缓冲
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        try {
            // 加载着色器
            final String vertexShader = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), VERTEX_SHADER_NAME);
            final String fragmentShader = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), assetsFragShaderPath);
            mShader = new GlShader(vertexShader, fragmentShader);
            mShader.useProgram();

            // 顶点着色器中的Attribute变量
            final String INPUT_VERTEX_COORDINATE_NAME = "aPosition";
            final String INPUT_TEXTURE_COORDINATE_NAME = "aCoordinate";

            // 获取Attribute变量位置
            inPosLocation = mShader.getAttribLocation(INPUT_VERTEX_COORDINATE_NAME);
            inTcLocation = mShader.getAttribLocation(INPUT_TEXTURE_COORDINATE_NAME);

            // 创建一个顶点缓冲对象
            int[] buffer = new int[1];
            GLES20.glGenBuffers(1, buffer, 0);
            mVertexBuffer = buffer[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);

            // 矩形平面的顶点和纹理坐标数据
            float[] RECTANGLE_VERTEX_DATA = {
                    -1.0f, 1.0f, 0.0f, 1.0f,
                    1.0f,  1.0f, 1.0f, 1.0f,
                    -1.0f, -1.0f, 0.0f, 0.0f,
                    1.0f,  -1.0f, 1.0f, 0.0f,
            };
            ByteBuffer mVertexByteBuffer = ByteBuffer.allocateDirect(RECTANGLE_VERTEX_DATA.length * 4);
            mVertexByteBuffer.order(ByteOrder.nativeOrder());
            FloatBuffer fb = mVertexByteBuffer.asFloatBuffer();
            fb.put(RECTANGLE_VERTEX_DATA);
            fb.position(0);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, RECTANGLE_VERTEX_DATA.length * 4, fb, GLES20.GL_STATIC_DRAW);

            // 获取Uniform变量位置
            mInputTextureLocation = mShader.getUniformLocation("uTexture");
            mTransformMatrixHandle = mShader.getUniformLocation("uSTMatrix");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * render to mTexture
     *
     * @return The id of output texture
     */
    public int render(int textureId, int textureType) {
        return render(textureId, textureType, null);
    }

    /**
     * render to mTexture
     *
     * @return The id of output texture
     */
    public int render(int textureId, int textureType, float[] transformMatrix) {
        mShader.useProgram();
        // 绑定帧缓冲
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBufferId);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);
        GlUtils.checkNoGLES2Error("Bind framebuffer failed!");
        // 清空颜色缓冲
        GLES20.glClearColor(0 /* red */, 0 /* green */, 0 /* blue */, 0 /* alpha */);

        // 关闭面剔除:
        // 面剔除会根据三角形的顶点顺序剔除背面, 而我们使用GL_TRIANGLE_STRIP的方式的矩形由正面和背面两个三角形构成
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        // 禁用裁剪区域:
        // 我们绘制的是整个视口区域, 不需要裁剪
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        // 设置OpenGL渲染区域(视口)
        Texture renderTarget = mTexture;
        GLES20.glViewport(0, 0, renderTarget.getWidth(), renderTarget.getHeight());

        if (mTransformMatrixHandle > -1) {
            if (transformMatrix != null && transformMatrix.length == 16) {
                GLES20.glUniformMatrix4fv(mTransformMatrixHandle, 1, false, transformMatrix, 0);
            } else {
                float[] identityMatrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
                GLES20.glUniformMatrix4fv(mTransformMatrixHandle, 1, false, identityMatrix, 0);
            }
        }

        // 设置绘制顶点位置的属性
        GLES20.glVertexAttribPointer(inPosLocation, 2, GLES20.GL_FLOAT, false, 4 * 4, 0);
        GLES20.glEnableVertexAttribArray(inPosLocation);

        // 设置纹理位置的属性
        GLES20.glVertexAttribPointer(inTcLocation, 2, GLES20.GL_FLOAT, false, 4 * 4, 2 * 4);
        GLES20.glEnableVertexAttribArray(inTcLocation);

        // 激活纹理单元 & 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(textureType, textureId);
        GLES20.glUniform1i(mInputTextureLocation, 0);

        GlUtils.checkNoGLES2Error("Pass uniform failed!");

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        GlUtils.checkNoGLES2Error("DrawArrays failed!");
        return mTexture.getTextureId();
    }

    public void release() {
        // 释放帧缓冲
        if (mFrameBufferId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{mFrameBufferId}, 0);
            mFrameBufferId = 0;
        }

        // 释放纹理
        if (mTexture != null) {
            mTexture.release();
            mTexture = null;
        }

        // 释放着色器
        if (mShader != null) {
            mShader.release();
            mShader = null;
        }

        // 释放顶点缓冲对象
        if (mVertexBuffer != 0) {
            GLES20.glDeleteBuffers(1, new int[]{mVertexBuffer}, 0);
            mVertexBuffer = 0;
        }

        Log.i(TAG, "OffScreenRenderPass resources released.");
    }

    public Texture getOutputTexture() {
        return mTexture;
    }
}