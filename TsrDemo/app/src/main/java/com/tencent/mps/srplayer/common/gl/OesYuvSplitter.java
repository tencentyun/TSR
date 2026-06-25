package com.tencent.mps.srplayer.common.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * OES 帧分离器：把一帧 OES 外部纹理（来自 SurfaceTexture）分离成两路：
 *
 * <ul>
 *     <li><b>Y 通道</b>（{@code GL_R8 W×H}）：渲到 FBO 后通过 {@code glReadPixels} 读到一块预分配复用的
 *         direct {@link ByteBuffer}（紧凑布局），可直接喂给 NPU 推理增强器。</li>
 *     <li><b>UV 通道</b>（{@code GL_RG8 W/2×H/2}）：留在 GPU 上不回 CPU；外部渲染器通过
 *         {@link #getUvTextureId()} 拿到纹理 ID 直接采样上屏。</li>
 * </ul>
 *
 * <p><b>为什么 Y 回 CPU、UV 留 GPU？</b> {@link com.tencent.mps.tie.api.TieEngine#process} 的输入是
 * Y 平面 {@link ByteBuffer}，所以 Y 需要回读；UV 只参与最终合成，不需要进入推理，保留在 GPU 可省去
 * 一次 UV 回读和一次 UV 上传。</p>
 *
 * <p><b>为什么拆成两个 pass？</b> 4:2:0 子采样下 UV 附件分辨率是 W/2×H/2，与 Y 附件 W×H 不同，
 * 这里用两个 FBO + 两个 draw call 分别输出 Y 与 UV，便于客户直接复用这段拆帧逻辑。</p>
 *
 * <p><b>BT.601 limited range 正向系数</b>（与 {@link Nv12GpuRenderer} 反向严格配对）：</p>
 * <pre>
 *   Y =  0.257*R + 0.504*G + 0.098*B + 16/255
 *   U = -0.148*R - 0.291*G + 0.439*B + 128/255
 *   V =  0.439*R - 0.368*G - 0.071*B + 128/255
 * </pre>
 *
 * <p>本类是 {@code com.tencent.mps.srplayer.common.gl} 下的 GL 原语，仅依赖 Android GLES，不引用任何 app 业务代码，可被多条增强链路复用。</p>
 */
public class OesYuvSplitter {
    private static final String TAG = "OesYuvSplitter";

    private static final String VERTEX_SHADER =
            "#version 300 es\n" +
            "in vec4 aPosition;\n" +
            "in vec2 aTexCoord;\n" +
            "uniform mat4 uStMatrix;\n" +
            "out vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_Y =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sOes;\n" +
            "out float outY;\n" +
            "void main() {\n" +
            "    vec3 rgb = texture(sOes, vTexCoord).rgb;\n" +
            "    float y = 0.257*rgb.r + 0.504*rgb.g + 0.098*rgb.b + 16.0/255.0;\n" +
            "    outY = y;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_UV =
            "#version 300 es\n" +
            "#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\n" +
            "in vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sOes;\n" +
            "out vec2 outUv;\n" +
            "void main() {\n" +
            "    vec3 rgb = texture(sOes, vTexCoord).rgb;\n" +
            "    float u = -0.148*rgb.r - 0.291*rgb.g + 0.439*rgb.b + 128.0/255.0;\n" +
            "    float v =  0.439*rgb.r - 0.368*rgb.g - 0.071*rgb.b + 128.0/255.0;\n" +
            "    outUv = vec2(u, v);\n" +
            "}\n";

    private static final float[] VERTEX_COORDS = {
            -1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f
    };

    private static final float[] TEXTURE_COORDS = {
            0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f
    };

    private int mWidth;
    private int mHeight;

    private int mProgramY = -1;
    private int mProgramUv = -1;
    private int mPositionHandleY, mTexCoordHandleY, mStMatrixHandleY, mOesSamplerHandleY;
    private int mPositionHandleUv, mTexCoordHandleUv, mStMatrixHandleUv, mOesSamplerHandleUv;

    private int mFboY = -1;
    private int mFboUv = -1;
    private int mYTextureId = -1;
    private int mUvTextureId = -1;

    private FloatBuffer mVertexBuffer;
    private FloatBuffer mTexCoordBuffer;

    private ByteBuffer mYBuffer;

    private boolean mInitialized = false;

    private volatile long mLastYDrawNs;
    private volatile long mLastReadYNs;
    private volatile long mLastUvDrawNs;

    /**
     * 初始化 GL 资源。必须在 GL 线程调用。
     *
     * @param width  视频宽度（像素，必须为正偶数）
     * @param height 视频高度（像素，必须为正偶数）
     */
    public void init(int width, int height) {
        if (mInitialized) {
            Log.w(TAG, "init() already initialized");
            return;
        }
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("width/height must be positive and even, got "
                    + width + "x" + height);
        }
        mWidth = width;
        mHeight = height;

        mVertexBuffer = createFloatBuffer(VERTEX_COORDS);
        mTexCoordBuffer = createFloatBuffer(TEXTURE_COORDS);

        mProgramY = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_Y);
        mPositionHandleY = GLES20.glGetAttribLocation(mProgramY, "aPosition");
        mTexCoordHandleY = GLES20.glGetAttribLocation(mProgramY, "aTexCoord");
        mStMatrixHandleY = GLES20.glGetUniformLocation(mProgramY, "uStMatrix");
        mOesSamplerHandleY = GLES20.glGetUniformLocation(mProgramY, "sOes");

        mProgramUv = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_UV);
        mPositionHandleUv = GLES20.glGetAttribLocation(mProgramUv, "aPosition");
        mTexCoordHandleUv = GLES20.glGetAttribLocation(mProgramUv, "aTexCoord");
        mStMatrixHandleUv = GLES20.glGetUniformLocation(mProgramUv, "uStMatrix");
        mOesSamplerHandleUv = GLES20.glGetUniformLocation(mProgramUv, "sOes");

        mYTextureId = createFboColorTexture(width, height, GLES30.GL_R8, GLES30.GL_RED);
        mFboY = createFbo(mYTextureId);

        mUvTextureId = createFboColorTexture(width / 2, height / 2, GLES30.GL_RG8, GLES30.GL_RG);
        mFboUv = createFbo(mUvTextureId);

        mYBuffer = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder());

        mInitialized = true;
        Log.i(TAG, "init() ok: " + width + "x" + height
                + ", yTex=" + mYTextureId + ", uvTex=" + mUvTextureId);
    }

    /**
     * 处理一帧：把 OES 纹理分离到 Y(回读到 ByteBuffer) 和 UV(留在 GPU 纹理) 两路。
     *
     * @param oesTextureId OES 纹理 ID（{@code GL_TEXTURE_EXTERNAL_OES} 类型）
     * @param stMatrix     SurfaceTexture.getTransformMatrix(...) 给出的 4×4 矩阵
     * @return true 处理成功；false 表示失败（已记日志）
     */
    public boolean splitFrame(int oesTextureId, float[] stMatrix) {
        if (!mInitialized) {
            Log.e(TAG, "splitFrame() not initialized");
            return false;
        }
        if (stMatrix == null || stMatrix.length < 16) {
            Log.e(TAG, "splitFrame() invalid stMatrix");
            return false;
        }

        int[] savedVp = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, savedVp, 0);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);

        // ---- Pass 1: 渲 Y 平面 -> mFboY (W×H, GL_R8) ----
        long t0 = SystemClock.elapsedRealtimeNanos();
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFboY);
        GLES20.glViewport(0, 0, mWidth, mHeight);
        drawWithProgram(mProgramY, mPositionHandleY, mTexCoordHandleY,
                mStMatrixHandleY, mOesSamplerHandleY, stMatrix);
        long t1 = SystemClock.elapsedRealtimeNanos();

        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1);
        mYBuffer.position(0);
        GLES20.glReadPixels(0, 0, mWidth, mHeight,
                GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, mYBuffer);
        mYBuffer.position(0);
        mYBuffer.limit(mWidth * mHeight);
        long t2 = SystemClock.elapsedRealtimeNanos();

        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "splitFrame() glReadPixels Y err=0x" + Integer.toHexString(err));
        }

        // ---- Pass 2: 渲 UV 平面 -> mFboUv (W/2×H/2, GL_RG8) ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFboUv);
        GLES20.glViewport(0, 0, mWidth / 2, mHeight / 2);
        drawWithProgram(mProgramUv, mPositionHandleUv, mTexCoordHandleUv,
                mStMatrixHandleUv, mOesSamplerHandleUv, stMatrix);
        long t3 = SystemClock.elapsedRealtimeNanos();

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glViewport(savedVp[0], savedVp[1], savedVp[2], savedVp[3]);

        mLastYDrawNs = t1 - t0;
        mLastReadYNs = t2 - t1;
        mLastUvDrawNs = t3 - t2;

        return err == GLES20.GL_NO_ERROR;
    }

    /** 拿到回读后的 Y 平面 ByteBuffer（紧凑 W*H 字节，position=0, limit=W*H） */
    public ByteBuffer getYBuffer() {
        return mYBuffer;
    }

    /** UV 纹理 ID（GL_TEXTURE_2D，GL_RG8，W/2×H/2，全程留在 GPU） */
    public int getUvTextureId() {
        return mUvTextureId;
    }

    /** 上一次 {@link #splitFrame} 中 Y pass draw call 提交的 CPU 耗时（纳秒） */
    public long getLastYDrawNs() {
        return mLastYDrawNs;
    }

    /** 上一次 {@link #splitFrame} 中 glReadPixels Y 的耗时（纳秒，隐式同步 GPU） */
    public long getLastReadYNs() {
        return mLastReadYNs;
    }

    /** 上一次 {@link #splitFrame} 中 UV pass draw call 提交的 CPU 耗时（纳秒） */
    public long getLastUvDrawNs() {
        return mLastUvDrawNs;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    /** 在 GL 线程释放所有 GL 资源 */
    public void release() {
        if (mProgramY != -1) {
            GLES20.glDeleteProgram(mProgramY);
            mProgramY = -1;
        }
        if (mProgramUv != -1) {
            GLES20.glDeleteProgram(mProgramUv);
            mProgramUv = -1;
        }
        if (mFboY != -1) {
            GLES20.glDeleteFramebuffers(1, new int[]{mFboY}, 0);
            mFboY = -1;
        }
        if (mFboUv != -1) {
            GLES20.glDeleteFramebuffers(1, new int[]{mFboUv}, 0);
            mFboUv = -1;
        }
        if (mYTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mYTextureId}, 0);
            mYTextureId = -1;
        }
        if (mUvTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mUvTextureId}, 0);
            mUvTextureId = -1;
        }
        mYBuffer = null;
        mVertexBuffer = null;
        mTexCoordBuffer = null;
        mInitialized = false;
        Log.i(TAG, "release()");
    }

    // ============================================================
    // 私有辅助
    // ============================================================

    private void drawWithProgram(int program, int posHandle, int texHandle,
                                 int stMatrixHandle, int samplerHandle, float[] stMatrix) {
        GLES20.glUseProgram(program);
        GLES20.glUniform1i(samplerHandle, 0);
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0);

        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer);
        GLES20.glEnableVertexAttribArray(posHandle);

        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer);
        GLES20.glEnableVertexAttribArray(texHandle);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
    }

    private static int createFboColorTexture(int width, int height, int internalFormat, int format) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int id = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id);
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internalFormat,
                width, height, 0, format, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        return id;
    }

    private static int createFbo(int colorTextureId) {
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        int id = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, colorTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glDeleteFramebuffers(1, new int[]{id}, 0);
            throw new RuntimeException("FBO not complete, status=0x" + Integer.toHexString(status));
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return id;
    }

    private static int createProgram(String vsSource, String fsSource) {
        return Nv12Shaders.linkProgram(vsSource, fsSource);
    }

    private static FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }
}
