package com.tencent.mps.srplayer.pass;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import com.tencent.mps.srplayer.SRApplication;
import com.tencent.mps.srplayer.opengl.GlShader;
import com.tencent.mps.srplayer.opengl.GlUtils;
import com.tencent.mps.tie.api.v2.TiePro;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * TiePro 在 GL 纹理管线中的桥接处理器。
 * <p>
 * 封装了 GL 纹理 ↔ ByteBuffer 之间的转换逻辑，使 TiePro（NPU Y 通道增强）
 * 能够无缝集成到基于 GL 纹理 ID 传递的渲染管线中。
 * <p>
 * 数据流：输入 RGBA 纹理 → GPU shader 提取 Y 通道 → glReadPixels 读取到 ByteBuffer
 * → TiePro NPU 处理 → glTexSubImage2D 上传增强 Y → GPU shader 合成 RGBA 输出
 * <p>
 * 注意：{@link #init(int, int)} 和 {@link #process(int)} 和 {@link #release()}
 * 必须在 GL 线程中调用。
 */
public class TieProGlProcessor {

    private static final String TAG = "TieProGlProcessor";

    // Shader 文件路径
    private static final String VERTEX_SHADER_PATH = "shaders/videoOffScreenRender.vert";
    private static final String RGBA_TO_Y_FRAG_PATH = "shaders/rgbaToY.frag";
    private static final String Y_REPLACE_FRAG_PATH = "shaders/yChannelReplace.frag";

    // TiePro 实例引用（外部传入，不由本类管理生命周期）
    private final TiePro mTiePro;

    // 宽高
    private int mWidth;
    private int mHeight;

    // ========== 阶段一：RGBA → Y 提取 ==========
    // FBO 和 R8 附件纹理，用于将 RGBA 纹理渲染为 Y 通道
    private int mYExtractFboId;
    private int mYExtractTextureId;
    private GlShader mYExtractShader;
    private int mYExtractInputTexLoc;
    private int mYExtractTransformLoc;
    private int mYExtractPosLoc;
    private int mYExtractTcLoc;

    // ========== 阶段二：Y 通道上传 ==========
    // R8 格式纹理，用于通过 glTexSubImage2D 上传增强后的 Y 数据
    private int mYUploadTextureId;

    // ========== 阶段三：Y 通道合成 ==========
    // FBO 和 RGBA 附件纹理，用于将增强 Y 与原始 RGBA 合成为最终输出
    private int mComposeFboId;
    private int mComposeTextureId;
    private GlShader mComposeShader;
    private int mComposeEnhancedYLoc;
    private int mComposeOriginalRgbaLoc;
    private int mComposeTransformLoc;
    private int mComposePosLoc;
    private int mComposeTcLoc;

    // ========== 共享资源 ==========
    // 矩形顶点缓冲（两个 FBO 渲染共用）
    private int mVertexBuffer;
    // 预分配的 ByteBuffer，用于 glReadPixels 和 TiePro 处理
    private ByteBuffer mYBuffer;

    // 初始化标志
    private boolean mInitialized = false;

    /**
     * 构造 TieProGlProcessor。
     *
     * @param tiePro TiePro 实例引用（外部创建和管理生命周期）
     */
    public TieProGlProcessor(TiePro tiePro) {
        mTiePro = tiePro;
    }

    /**
     * 在 GL 线程中初始化所有 OpenGL 资源。
     * 必须在有效的 GL 上下文所在的 GL 线程中调用。
     *
     * @param width  输入纹理宽度
     * @param height 输入纹理高度
     */
    public void init(int width, int height) {
        Log.i(TAG, "init() " + width + "x" + height);
        mWidth = width;
        mHeight = height;

        try {
            // 加载顶点着色器（共用）
            String vertexShaderSrc = GlUtils.readShaderFileFromAssets(
                    SRApplication.getAppContext(), VERTEX_SHADER_PATH);

            // ========== 创建共享 VBO ==========
            createVertexBuffer();

            // ========== 阶段一：RGBA → Y 提取资源 ==========
            initYExtractPass(vertexShaderSrc);

            // ========== 阶段二：Y 通道上传纹理 ==========
            initYUploadTexture();

            // ========== 阶段三：Y 通道合成资源 ==========
            initComposePass(vertexShaderSrc);

            // ========== 预分配 ByteBuffer ==========
            mYBuffer = ByteBuffer.allocateDirect(width * height);
            mYBuffer.order(ByteOrder.nativeOrder());

            mInitialized = true;
            Log.i(TAG, "init() success");
        } catch (IOException e) {
            Log.e(TAG, "init() failed: " + e.getMessage());
        }
    }

    /**
     * 处理输入纹理，返回增强后的纹理 ID。
     * 必须在 GL 线程中调用。
     * <p>
     * 如果未初始化或已释放，安全降级为透传输入纹理。
     *
     * @param inputTextureId 输入 RGBA 纹理 ID
     * @return 处理后的 RGBA 纹理 ID
     */
    public int process(int inputTextureId) {
        if (!mInitialized) {
            Log.w(TAG, "process() called but not initialized, fallback to passthrough");
            return inputTextureId;
        }

        // 阶段一：GPU 端 RGBA → Y 提取，渲染到 R8 FBO
        extractYChannel(inputTextureId);

        // 阶段一续：从 R8 FBO 读取 Y 通道数据到 ByteBuffer
        readYToBuffer();

        // 阶段二：NPU 处理 Y 通道（原地更新 ByteBuffer）
        mTiePro.process(mYBuffer, mWidth, mHeight);
        mYBuffer.position(0);

        // 阶段二续：将增强后的 Y 数据上传到 GL 纹理
        uploadEnhancedY();

        // 阶段三：合成增强 Y 与原始 RGBA，输出最终 RGBA 纹理
        return composeOutput(inputTextureId);
    }

    /**
     * 释放所有 OpenGL 资源。
     * 必须在 GL 线程中调用。
     */
    public void release() {
        Log.i(TAG, "release()");
        mInitialized = false;

        // 释放阶段一资源
        if (mYExtractFboId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{mYExtractFboId}, 0);
            mYExtractFboId = 0;
        }
        if (mYExtractTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{mYExtractTextureId}, 0);
            mYExtractTextureId = 0;
        }
        if (mYExtractShader != null) {
            mYExtractShader.release();
            mYExtractShader = null;
        }

        // 释放阶段二资源
        if (mYUploadTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{mYUploadTextureId}, 0);
            mYUploadTextureId = 0;
        }

        // 释放阶段三资源
        if (mComposeFboId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{mComposeFboId}, 0);
            mComposeFboId = 0;
        }
        if (mComposeTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{mComposeTextureId}, 0);
            mComposeTextureId = 0;
        }
        if (mComposeShader != null) {
            mComposeShader.release();
            mComposeShader = null;
        }

        // 释放共享 VBO
        if (mVertexBuffer != 0) {
            GLES20.glDeleteBuffers(1, new int[]{mVertexBuffer}, 0);
            mVertexBuffer = 0;
        }

        // 释放 ByteBuffer
        mYBuffer = null;

        Log.i(TAG, "release() done");
    }

    // ==================== 私有方法 ====================

    /**
     * 创建共享的矩形顶点缓冲对象。
     */
    private void createVertexBuffer() {
        float[] vertexData = {
                -1.0f, 1.0f, 0.0f, 1.0f,
                1.0f, 1.0f, 1.0f, 1.0f,
                -1.0f, -1.0f, 0.0f, 0.0f,
                1.0f, -1.0f, 1.0f, 0.0f,
        };
        int[] buffer = new int[1];
        GLES20.glGenBuffers(1, buffer, 0);
        mVertexBuffer = buffer[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);

        ByteBuffer bb = ByteBuffer.allocateDirect(vertexData.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(vertexData);
        fb.position(0);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexData.length * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    /**
     * 初始化阶段一：RGBA → Y 提取的 FBO、R8 纹理和 shader。
     */
    private void initYExtractPass(String vertexShaderSrc) throws IOException {
        // 创建 R8 格式纹理
        mYExtractTextureId = createR8Texture(mWidth, mHeight);

        // 创建 FBO 并绑定 R8 纹理
        mYExtractFboId = createFbo(mYExtractTextureId);

        // 加载 shader
        String fragSrc = GlUtils.readShaderFileFromAssets(
                SRApplication.getAppContext(), RGBA_TO_Y_FRAG_PATH);
        mYExtractShader = new GlShader(vertexShaderSrc, fragSrc);
        mYExtractShader.useProgram();

        mYExtractPosLoc = mYExtractShader.getAttribLocation("aPosition");
        mYExtractTcLoc = mYExtractShader.getAttribLocation("aCoordinate");
        mYExtractInputTexLoc = mYExtractShader.getUniformLocation("uTexture");
        mYExtractTransformLoc = mYExtractShader.getUniformLocation("uSTMatrix");
    }

    /**
     * 初始化阶段二：Y 通道上传纹理（R8 格式）。
     */
    private void initYUploadTexture() {
        mYUploadTextureId = createR8Texture(mWidth, mHeight);
    }

    /**
     * 初始化阶段三：Y 通道合成的 FBO、RGBA 纹理和 shader。
     */
    private void initComposePass(String vertexShaderSrc) throws IOException {
        // 创建 RGBA 格式的输出纹理
        mComposeTextureId = GlUtils.generateTexture(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mComposeTextureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mWidth, mHeight,
                0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        // 创建 FBO 并绑定 RGBA 纹理
        mComposeFboId = createFbo(mComposeTextureId);

        // 加载 shader
        String fragSrc = GlUtils.readShaderFileFromAssets(
                SRApplication.getAppContext(), Y_REPLACE_FRAG_PATH);
        mComposeShader = new GlShader(vertexShaderSrc, fragSrc);
        mComposeShader.useProgram();

        mComposePosLoc = mComposeShader.getAttribLocation("aPosition");
        mComposeTcLoc = mComposeShader.getAttribLocation("aCoordinate");
        mComposeEnhancedYLoc = mComposeShader.getUniformLocation("uEnhancedY");
        mComposeOriginalRgbaLoc = mComposeShader.getUniformLocation("uOriginalRgba");
        mComposeTransformLoc = mComposeShader.getUniformLocation("uSTMatrix");
    }

    /**
     * 创建 R8 格式（单通道）的纹理。
     */
    private int createR8Texture(int width, int height) {
        int textureId = GlUtils.generateTexture(GLES20.GL_TEXTURE_2D);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height,
                0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return textureId;
    }

    /**
     * 创建 FBO 并绑定指定纹理作为颜色附件。
     */
    private int createFbo(int textureId) {
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, textureId, 0);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete, status: " + status);
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return fbo[0];
    }

    /**
     * 阶段一：使用 GPU shader 将输入 RGBA 纹理渲染为 R8 格式的 Y 通道纹理。
     */
    private void extractYChannel(int inputTextureId) {
        mYExtractShader.useProgram();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mYExtractFboId);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0, 0, 0, 0);

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        // 设置单位矩阵（输入已经是 2D 纹理，不需要变换）
        float[] identityMatrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        GLES20.glUniformMatrix4fv(mYExtractTransformLoc, 1, false, identityMatrix, 0);

        // 设置顶点属性
        GLES20.glVertexAttribPointer(mYExtractPosLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, 0);
        GLES20.glEnableVertexAttribArray(mYExtractPosLoc);
        GLES20.glVertexAttribPointer(mYExtractTcLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, 2 * 4);
        GLES20.glEnableVertexAttribArray(mYExtractTcLoc);

        // 绑定输入纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId);
        GLES20.glUniform1i(mYExtractInputTexLoc, 0);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        // 注意：不解绑 FBO，因为紧接着要 glReadPixels
    }

    /**
     * 阶段一续：从 R8 FBO 读取 Y 通道数据到预分配的 ByteBuffer。
     */
    private void readYToBuffer() {
        mYBuffer.position(0);
        GLES30.glReadPixels(0, 0, mWidth, mHeight, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, mYBuffer);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        mYBuffer.position(0);
    }

    /**
     * 阶段二续：将增强后的 Y 数据通过 glTexSubImage2D 上传到 R8 纹理。
     */
    private void uploadEnhancedY() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYUploadTextureId);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, mWidth, mHeight,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, mYBuffer);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    /**
     * 阶段三：使用合成 shader 将增强 Y 通道与原始 RGBA 合成为最终 RGBA 输出。
     *
     * @param inputTextureId 原始输入 RGBA 纹理 ID
     * @return 合成后的 RGBA 纹理 ID
     */
    private int composeOutput(int inputTextureId) {
        mComposeShader.useProgram();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mComposeFboId);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        GLES20.glClearColor(0, 0, 0, 0);

        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        // 设置单位矩阵
        float[] identityMatrix = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        GLES20.glUniformMatrix4fv(mComposeTransformLoc, 1, false, identityMatrix, 0);

        // 设置顶点属性
        GLES20.glVertexAttribPointer(mComposePosLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, 0);
        GLES20.glEnableVertexAttribArray(mComposePosLoc);
        GLES20.glVertexAttribPointer(mComposeTcLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, 2 * 4);
        GLES20.glEnableVertexAttribArray(mComposeTcLoc);

        // 绑定增强后的 Y 通道纹理到纹理单元 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mYUploadTextureId);
        GLES20.glUniform1i(mComposeEnhancedYLoc, 0);

        // 绑定原始 RGBA 纹理到纹理单元 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId);
        GLES20.glUniform1i(mComposeOriginalRgbaLoc, 1);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        return mComposeTextureId;
    }
}
