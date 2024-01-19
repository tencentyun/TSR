package com.tencent.mps.srplayer.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Pair;
import androidx.annotation.NonNull;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个绘制到平面上的可定制渲染管线。<br>
 * 使用方可通过{@link RenderPipeLine#RenderPipeLine(List, String, String)} 的{@code fragmentShader} 参数指定片段着色器，
 * 决定如何渲染每一个像素。
 */
public class RenderPipeLine {

    /**
     * 存储纹理和Uniform变量位置的映射
     */
    private final List<Pair<Texture, Integer>> mInputTextures = new ArrayList<>();

    /**
     * OpenGL的渲染器和程序对象
     */
    private GlShader mShader;
    /**
     * 顶点缓冲区索引
     */
    private final int mVertexBuffer;
    /**
     * （超分）Viewport在shader中uniform变量的位置
     */
    private final int mViewportInfoLocation;


    /**
     * 构造一个渲染管线实例。
     * @param inputTextures  该渲染管线的输入纹理，纹理的数量需要和{@code fragmentShader}中tex纹理变量数量一致。
     *                       被传入的{@link Texture}对象不需要手动调用{@link Texture#release()}。
     * @param fragmentShader 着色器代码，其中的纹理需要命名为tex0、tex1、tex2...
     */
    public RenderPipeLine(@NonNull List<Texture> inputTextures, @NonNull String vertexShader, @NonNull String fragmentShader) {
        mShader = new GlShader(vertexShader, fragmentShader);
        mShader.useProgram();

        // 顶点着色器中的Attribute变量
        final String INPUT_VERTEX_COORDINATE_NAME = "aPosition";
        final String INPUT_TEXTURE_COORDINATE_NAME = "aCoordinate";

        // 获取Attribute变量位置
        int inPosLocation = mShader.getAttribLocation(INPUT_VERTEX_COORDINATE_NAME);
        int inTcLocation = mShader.getAttribLocation(INPUT_TEXTURE_COORDINATE_NAME);

        // 创建一个顶点缓冲对象
        int[] buffer = new int[1];
        GLES20.glGenBuffers(1, buffer, 0);
        mVertexBuffer = buffer[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);

        // 矩形平面的顶点和纹理坐标数据
        // 顶点顺序: 左下, 左上, 右下, 右上
        float[] RECTANGLE_VERTEX_DATA = {
                -1.0f, -1.0f, 0.0f, 0.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,
                1.0f, -1.0f, 1.0f, 0.0f,
                1.0f,  1.0f, 1.0f, 1.0f,
        };
        ByteBuffer mVertexByteBuffer = ByteBuffer.allocateDirect(RECTANGLE_VERTEX_DATA.length * 4);
        mVertexByteBuffer.order(ByteOrder.nativeOrder());
        FloatBuffer fb = mVertexByteBuffer.asFloatBuffer();
        fb.put(RECTANGLE_VERTEX_DATA);
        fb.position(0);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, RECTANGLE_VERTEX_DATA.length * 4, fb, GLES20.GL_STATIC_DRAW);

        // 设置绘制顶点位置的属性
        GLES20.glVertexAttribPointer(inPosLocation, 2, GLES20.GL_FLOAT, false, 4 * 4, 0);
        GLES20.glEnableVertexAttribArray(inPosLocation);

        // 设置纹理位置的属性
        GLES20.glVertexAttribPointer(inTcLocation, 2, GLES20.GL_FLOAT, false, 4 * 4, 2 * 4);
        GLES20.glEnableVertexAttribArray(inTcLocation);

        // 获取Uniform变量位置
        for (int i = 0; i < inputTextures.size(); i++) {
            String uniformLocation = "tex" + i;
            mInputTextures.add(new Pair<>(inputTextures.get(0), mShader.getUniformLocation(uniformLocation)));
        }
        // 获取Uniform变量的ViewportInfo位置
        mViewportInfoLocation = mShader.getUniformLocation("ViewportInfo");
    }

    /**
     * 将该管线渲染到渲染状态上。
     */
    public void render(RenderState renderState){
        mShader.useProgram();

        // 绑定帧缓冲
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, renderState.getFrameBufferId());
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mVertexBuffer);

        // 清空颜色缓冲
        GLES20.glClearColor(0 /* red */, 0 /* green */, 0 /* blue */, 0 /* alpha */);

        // 关闭面剔除:
        // 面剔除会根据三角形的顶点顺序剔除背面, 而我们使用GL_TRIANGLE_STRIP的方式的矩形由正面和背面两个三角形构成
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        // 禁用裁剪区域:
        // 我们绘制的是整个视口区域, 不需要裁剪
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        // 设置OpenGL渲染区域(视口)
        Texture renderTarget = renderState.getRenderTarget();
        GLES20.glViewport(0, 0, renderTarget.getWidth(), renderTarget.getHeight());

        // 设置Uniform变量ViewportInfo
        if (mViewportInfoLocation > -1) {
            float viewportWidth = mInputTextures.get(0).first.getWidth();
            float viewportHeight = mInputTextures.get(0).first.getHeight();
            float viewportWidthInverse = (float) (1.0 / viewportWidth);
            float viewportHeightInverse = (float) (1.0 / viewportHeight);
            float[] viewportInfo = new float[] {viewportWidthInverse, viewportHeightInverse, viewportWidth, viewportHeight};
            int viewportInfoLocation = mShader.getUniformLocation("ViewportInfo");
            GLES30.glUniform4fv(viewportInfoLocation, 1, viewportInfo, 0);
        }

        // 激活纹理单元 & 绑定纹理
        for (int i = 0; i < mInputTextures.size(); i++) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
            Texture texture = mInputTextures.get(i).first;
            GLES20.glBindTexture(texture.getTarget(), texture.getTextureId());
            GLES20.glUniform1i(mInputTextures.get(i).second, i);
        }

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    /**
     * 释放实例并且回收资源。<br>
     * 这个调用会释放构造函数传入的{@link Texture}。
     */
    public void release() {
        if (mShader != null) {
            mShader.release();
            mShader = null;
        }
        for (Pair<Texture, Integer> texture: mInputTextures) {
            texture.first.release();
        }
        mInputTextures.clear();
    }
}
