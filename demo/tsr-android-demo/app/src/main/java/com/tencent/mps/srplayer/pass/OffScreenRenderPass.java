package com.tencent.mps.srplayer.pass;

import android.util.Log;

import com.tencent.mps.srplayer.SRApplication;
import com.tencent.mps.srplayer.opengl.GlUtils;
import com.tencent.mps.srplayer.opengl.RenderPipeLine;
import com.tencent.mps.srplayer.opengl.RenderState;
import com.tencent.mps.srplayer.opengl.Texture;

import java.io.IOException;

public class OffScreenRenderPass {
    public static final String TAG = "TexOESToTex2DPass";

    /**
     * Shaders
     */
    private static final String VERTEX_SHADER_NAME = "shaders/videoOffScreenRender.vert";
    /**
     * The texture that this pass render to.
     */
    private Texture mTexture;
    /**
     * Handle render
     */
    private RenderPipeLine mRenderPipeLine;
    /**
     * Create FBO
     */
    private RenderState mRenderState;

    public OffScreenRenderPass() {

    }

    public void init (int outputTextureType, int destWidth, int destHeight, String assetsFragShaderPath) {
        Log.i(TAG, "creating Texture2DRenderPass: dest resolution = "
                + destWidth + "x" + destHeight);
        // create the target texture
        mTexture = new Texture(false, outputTextureType, 0, destWidth, destHeight);

        // create FBO and bind it to the target texture.
        mRenderState = new RenderState(mTexture);

        try {
            final String VERTEX_SHADER_2D = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), VERTEX_SHADER_NAME);
            final String FRAGMENT_SHADER_2D = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), assetsFragShaderPath);
            mRenderPipeLine = new RenderPipeLine(VERTEX_SHADER_2D, FRAGMENT_SHADER_2D);
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Convert TextureOES to Texture2D
     *
     * @return The id of output texture
     */
    public int render(int textureId, int textureType) {
        if (mRenderPipeLine != null) {
            mRenderPipeLine.render(mRenderState, null, textureId, textureType);
        }
        return mTexture.getTextureId();
    }

    /**
     * Convert TextureOES to Texture2D
     *
     * @return The id of output texture
     */
    public int render(int textureId, int textureType, float[] transformMatrix) {
        if (mRenderPipeLine != null) {
            mRenderPipeLine.render(mRenderState, transformMatrix, textureId, textureType);
        }
        return mTexture.getTextureId();
    }

    /**
     * release resource
     */
    public void release() {
        if (mRenderPipeLine != null) {
            mRenderPipeLine.release();
            mRenderPipeLine = null;
        }
        if (mRenderState != null) {
            mRenderState.release();
            mRenderState = null;
        }
        if (mTexture != null) {
            mTexture.release();
            mTexture = null;
        }
    }

    public Texture getOutputTexture() {
        return mTexture;
    }
}
