package com.tencent.mps.srplayer.demo.oestexture;

import android.content.Context;
import android.util.Log;
import android.util.Size;

import com.tencent.mps.srplayer.common.gl.Nv12GpuRenderer;
import com.tencent.mps.srplayer.common.gl.OesTextureFactory;
import com.tencent.mps.srplayer.common.gl.OesYuvSplitter;
import com.tencent.mps.tie.api.TieEngine;
import com.tencent.mps.tie.api.TieEngine.Config;
import com.tencent.mps.tie.api.TieEngine.InitResult;
import com.tencent.mps.tie.api.TieEngine.ProcessInfo;

import java.nio.ByteBuffer;

/**
 * OES 路径增强链路编排：OES 纹理 → 拆 Y/UV → TieEngine 增强 → NV12 合成上屏。
 * 本类是 {@code common.gl} 原语的推荐组合范例，真正的可复用组件在 common.gl 包下。
 *
 * <p>详细架构与用法见 {@code oestexture/OES纹理路径Demo架构说明.md}。</p>
 */
public class VideoEnhancePipeline {

    private static final String TAG = "VideoEnhancePipeline";

    // ============================================================
    // 不可变配置
    // ============================================================

    private final TieEngine.Type mType;
    private final int mVideoWidth;
    private final int mVideoHeight;

    // ============================================================
    // 组件（GL 线程持有）
    // ============================================================

    private final TieEngine mEngine = new TieEngine();
    private OesYuvSplitter mSplitter;
    private Nv12GpuRenderer mRenderer;

    private boolean mGlInited = false;
    private volatile boolean mEngineReady = false;
    private volatile boolean mSizeSupported = true;

    // 有效增强输出尺寸（=视频×倍率），由 InitResult 回传。
    private volatile int mOutputWidth;
    private volatile int mOutputHeight;
    // process buffer 行 stride（=模型档位宽×倍率，可能 > mOutputWidth），由 InitResult.outputStride 回传。
    private volatile int mOutputStride;

    // 上一次 drawFrame 的运行信息（供调用方做性能统计）
    private volatile FrameInfo mLastFrameInfo = FrameInfo.EMPTY;

    /**
     * @param type        处理意图：{@link TieEngine.Type#IE_Y} 同尺寸增强，{@link TieEngine.Type#SR_Y} 超分
     * @param videoWidth  视频实际宽（像素，正偶数）
     * @param videoHeight 视频实际高（像素，正偶数）
     */
    public VideoEnhancePipeline(TieEngine.Type type, int videoWidth, int videoHeight) {
        if (type == null) {
            throw new IllegalArgumentException("type is null");
        }
        this.mType = type;
        this.mVideoWidth = videoWidth;
        this.mVideoHeight = videoHeight;
    }

    /** 当前视频分辨率是否被某一模型档位覆盖（init 成功或尚未判定时为 true，init 返回不支持后为 false）。 */
    public boolean isModelSizeSupported() {
        return mSizeSupported;
    }

    // ============================================================
    // GL 线程：生命周期
    // ============================================================

    /**
     * GL 线程：初始化 GL 资源，返回 OES 纹理 ID 供调用方建 SurfaceTexture。
     */
    public int initGl(int width, int height) {
        if (mGlInited) {
            Log.w(TAG, "initGl() already inited");
            // 已初始化时返回已有 OES 纹理 ID 由调用方自管，这里不重复创建
            throw new IllegalStateException("initGl() called twice");
        }
        int oesTextureId = OesTextureFactory.createOesTexture();
        mSplitter = new OesYuvSplitter();
        mSplitter.init(width, height);
        mRenderer = new Nv12GpuRenderer();
        mRenderer.init(width, height);
        mGlInited = true;
        Log.i(TAG, "initGl() ok " + width + "x" + height + " oesTex=" + oesTextureId
                + " type=" + mType);
        return oesTextureId;
    }

    /** 通知输出窗口尺寸变化（GL 线程）。 */
    public void onSurfaceChanged(int surfaceWidth, int surfaceHeight) {
        if (mRenderer != null) {
            mRenderer.onSurfaceChanged(surfaceWidth, surfaceHeight);
        }
    }

    /**
     * 设置 render 时视口子矩形（左下角原点），用于按纵横比居中贴合。
     * {@code w<=0 || h<=0} 表示使用全窗。
     */
    public void setRenderRect(int x, int y, int w, int h) {
        if (mRenderer != null) {
            mRenderer.setRenderRect(x, y, w, h);
        }
    }

    /**
     * GL 线程：处理并上屏一帧。调用前调用方需已完成 updateTexImage / getTransformMatrix。
     *
     * @param enhance false 或引擎未就绪时透传原画
     */
    public boolean drawFrame(int oesTextureId, float[] stMatrix, boolean enhance) {
        if (!mGlInited) {
            Log.e(TAG, "drawFrame() before initGl()");
            return false;
        }

        // 1) OES -> Y(ByteBuffer) + UV(GPU 纹理)
        if (!mSplitter.splitFrame(oesTextureId, stMatrix)) {
            return false;
        }
        long splitMs = (mSplitter.getLastYDrawNs()
                + mSplitter.getLastReadYNs()
                + mSplitter.getLastUvDrawNs()) / 1_000_000L;

        ByteBuffer yBuffer = mSplitter.getYBuffer();
        int uvTextureId = mSplitter.getUvTextureId();

        // 2) 增强（条件满足时）
        ByteBuffer yToUse = yBuffer;
        int yStrideToUse = mVideoWidth;     // splitter 输出是紧凑 videoW×videoH
        int preMs = 0, runMs = 0, postMs = 0;
        boolean enhanced = false;
        if (enhance && mEngineReady) {
            try {
                ByteBuffer out = mEngine.process(yBuffer, mVideoWidth, mVideoHeight);
                ProcessInfo info = mEngine.getLastProcessInfo();
                preMs = info.preMs;
                runMs = info.runMs;
                postMs = info.postMs;
                // buffer 行宽=mOutputStride（模型档位×倍率，>= 有效宽）；有效区左上角 mOutputWidth×mOutputHeight。
                // 校验读取有效区最后一行不越界，再用 stride 让渲染器 GL_UNPACK_ROW_LENGTH 抠左上角，避免 CPU 裁剪。
                int needed = mOutputStride * (mOutputHeight - 1) + mOutputWidth;
                if (out != null && out.capacity() >= needed) {
                    yToUse = out;
                    yStrideToUse = mOutputStride;
                    enhanced = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "engine.process threw, fallback to raw Y", t);
            }
        }

        // 3) 上屏
        try {
            mRenderer.render(yToUse, yStrideToUse, uvTextureId);
        } catch (RuntimeException e) {
            Log.e(TAG, "render failed", e);
            return false;
        }

        mLastFrameInfo = new FrameInfo(splitMs, preMs, runMs, postMs, enhanced);
        return true;
    }

    /** 在 GL 线程释放 GL 资源（拆分器、渲染器）。不释放 OES 纹理（由调用方自管）。幂等。 */
    public void releaseGl() {
        if (mSplitter != null) {
            mSplitter.release();
            mSplitter = null;
        }
        if (mRenderer != null) {
            mRenderer.release();
            mRenderer = null;
        }
        mGlInited = false;
        Log.i(TAG, "releaseGl()");
    }

    // ============================================================
    // 后台线程：TieEngine 生命周期
    // ============================================================

    /**
     * 后台线程：同步初始化 TieEngine（可能长时间阻塞）。内部完成选档；
     * 分辨率不支持时返回 CODE_UNSUPPORTED_SIZE。
     */
    public InitResult initEngineBlocking(Context context) {
        InitResult result = mEngine.init(context, new Config(mType, mVideoWidth, mVideoHeight));
        boolean ok = result.code == InitResult.CODE_OK;
        if (ok) {
            mOutputWidth = result.outputWidth;
            mOutputHeight = result.outputHeight;
            mOutputStride = result.outputStride;
        }
        mSizeSupported = (result.code != InitResult.CODE_UNSUPPORTED_SIZE);
        mEngineReady = ok;
        Log.i(TAG, "initEngineBlocking() code=" + result.code
                + " inferMs=" + result.inferMs
                + " out=" + result.outputWidth + "x" + result.outputHeight
                + " msg=" + result.msg);
        return result;
    }

    /** 引擎是否已就绪（init 成功）。 */
    public boolean isEngineReady() {
        return mEngineReady;
    }

    /** 在后台线程释放 {@link TieEngine}。幂等。 */
    public void releaseEngine() {
        mEngineReady = false;
        mEngine.release();
        Log.i(TAG, "releaseEngine()");
    }

    // ============================================================
    // 查询
    // ============================================================

    public TieEngine.Type getType() {
        return mType;
    }

    public int getVideoWidth() {
        return mVideoWidth;
    }

    public int getVideoHeight() {
        return mVideoHeight;
    }

    /** 增强输出宽（init 成功后有效，IE 同尺寸即模型档位宽）；未 init 时为 0。 */
    public int getOutputWidth() {
        return mOutputWidth;
    }

    /** 增强输出高（init 成功后有效）；未 init 时为 0。 */
    public int getOutputHeight() {
        return mOutputHeight;
    }

    /** 上一次 {@link #drawFrame} 的运行信息 snapshot（不可变值对象）。 */
    public FrameInfo getLastFrameInfo() {
        return mLastFrameInfo;
    }

    /**
     * 单帧运行信息（毫秒），供调用方做性能统计/状态栏展示。immutable。
     */
    public static final class FrameInfo {
        public static final FrameInfo EMPTY = new FrameInfo(0, 0, 0, 0, false);

        /** OES 拆帧总耗时（Y draw + readPixels + UV draw 合并，ms）。 */
        public final long splitMs;
        /** 推理前处理耗时（ms）。 */
        public final int preMs;
        /** 推理耗时（ms）。 */
        public final int runMs;
        /** 推理后处理耗时（ms）。 */
        public final int postMs;
        /** 本帧是否实际走了增强路径。 */
        public final boolean enhanced;

        public FrameInfo(long splitMs, int preMs, int runMs, int postMs, boolean enhanced) {
            this.splitMs = splitMs;
            this.preMs = preMs;
            this.runMs = runMs;
            this.postMs = postMs;
            this.enhanced = enhanced;
        }

        @Override
        public String toString() {
            return "FrameInfo{split=" + splitMs + "ms, pre=" + preMs
                    + "ms, run=" + runMs + "ms, post=" + postMs
                    + "ms, enhanced=" + enhanced + '}';
        }
    }
}
