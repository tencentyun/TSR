package com.tencent.mps.srplayer.pass;

import android.util.Log;
import com.tencent.mps.srplayer.SRApplication;
import com.tencent.mps.srplayer.opengl.GlUtils;
import com.tencent.mps.srplayer.opengl.RenderPipeLine;
import com.tencent.mps.srplayer.opengl.RenderState;
import com.tencent.mps.srplayer.opengl.Texture;
import java.io.IOException;
import java.util.Collections;

public class BilinearRenderPass {
    public static final String TAG = "BilinearRenderPass";
    /**
     * Shaders
     */
    private static final String VERTEX_SHADER_NAME = "shaders/videoquad.vert";
    private static final String FRAGMENT_SHADER_NAME = "shaders/videoTex2D.frag";

    /**
     * The texture that this pass render to.
     */
    private Texture mBilinearTexture;
    /**
     * Handle render
     */
    private RenderPipeLine mBilinearRenderPipeLine;
    /**
     * Create FBO
     */
    private RenderState mBilinearRenderState;

    public BilinearRenderPass(Texture inputTexture, float srRatio) {
        int destWidth = (int) (inputTexture.getWidth() * srRatio);
        int destHeight = (int) (inputTexture.getHeight() * srRatio);
        Log.i(TAG, "creating LERPRenderPass: src resolution = "
                + inputTexture.getWidth() + "x" + inputTexture.getHeight() + ", dest resolution = "
                + destWidth + "x" + destHeight);
        // 创建目标texture
        mBilinearTexture = new Texture(destWidth, destHeight);

        // 创建FBO，将mSuperResolutionTexture与FBO绑定
        mBilinearRenderState = new RenderState(mBilinearTexture);

        try {
            final String VERTEX_SHADER_2D = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), VERTEX_SHADER_NAME);
            final String FRAGMENT_SHADER_2D = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), FRAGMENT_SHADER_NAME);
            mBilinearRenderPipeLine = new RenderPipeLine(Collections.singletonList(inputTexture),
                    VERTEX_SHADER_2D, FRAGMENT_SHADER_2D);
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * 将输入的纹理进行超分处理后绘制到FBO绑定的纹理中
     */
    public int render() {
        if (mBilinearRenderPipeLine != null) {
            mBilinearRenderPipeLine.render(mBilinearRenderState);
        }
        return mBilinearTexture.getTextureId();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (mBilinearRenderPipeLine != null) {
            mBilinearRenderPipeLine.release();
            mBilinearRenderPipeLine = null;
        }
        if (mBilinearRenderState != null) {
            mBilinearRenderState.release();
            mBilinearRenderState = null;
        }
        if (mBilinearTexture != null) {
            mBilinearTexture.release();
            mBilinearTexture = null;
        }
    }


    /**
     * 返回超分处理后的Texture
     *
     * @return 超分处理后的Texture
     */
    public Texture getOutputTexture() {
        return mBilinearTexture;
    }
}
