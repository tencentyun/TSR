package com.tencent.mps.srplayer.pass;

import android.util.Log;
import com.tencent.mps.srplayer.SRApplication;
import com.tencent.mps.srplayer.opengl.GlUtils;
import com.tencent.mps.srplayer.opengl.RenderPipeLine;
import com.tencent.mps.srplayer.opengl.RenderState;
import com.tencent.mps.srplayer.opengl.Texture;
import java.io.IOException;
import java.util.Collections;

public class TexOESToTex2DPass {
    public static final String TAG = "TexOESToTex2DPass";

    /**
     * Shaders
     */
    private static final String VERTEX_SHADER_NAME = "shaders/videoquad.vert";
    private static final String FRAGMENT_SHADER_NAME = "shaders/videoTexOES.frag";
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

    public TexOESToTex2DPass(Texture inputTexture) {
        int destWidth = inputTexture.getWidth();
        int destHeight = inputTexture.getHeight();
        Log.i(TAG, "creating Texture2DRenderPass: src resolution = "
                + inputTexture.getWidth() + "x" + inputTexture.getHeight() + ", dest resolution = "
                + destWidth + "x" + destHeight);
        // create the target texture
        mTexture = new Texture(destWidth, destHeight);

        // create FBO and bind it to the target texture.
        mRenderState = new RenderState(mTexture);

        try {
            final String VERTEX_SHADER_2D = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), VERTEX_SHADER_NAME);
            final String FRAGMENT_SHADER_2D = GlUtils.readShaderFileFromAssets(SRApplication.getContext(), FRAGMENT_SHADER_NAME);
            mRenderPipeLine = new RenderPipeLine(Collections.singletonList(inputTexture),
                    VERTEX_SHADER_2D, FRAGMENT_SHADER_2D);
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }
    }

    /**
     * Convert TextureOES to Texture2D
     *
     * @return The id of output texture
     */
    public int render() {
        if (mRenderPipeLine != null) {
            mRenderPipeLine.render(mRenderState);
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
