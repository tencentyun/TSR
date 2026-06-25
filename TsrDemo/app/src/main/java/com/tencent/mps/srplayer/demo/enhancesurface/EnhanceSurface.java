package com.tencent.mps.srplayer.demo.enhancesurface;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.IntDef;

import com.tencent.mps.srplayer.common.TieSdkHelper;
import com.tencent.mps.srplayer.common.Threads;
import com.tencent.mps.srplayer.common.gl.Nv12GpuRenderer;
import com.tencent.mps.srplayer.common.gl.OesTextureFactory;
import com.tencent.mps.srplayer.common.gl.OesYuvSplitter;
import com.tencent.mps.tie.api.TieEngine.Config;
import com.tencent.mps.tie.api.TieEngine;
import com.tencent.mps.tie.api.TieEngine.InitResult;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;

/**
 * Surface-in / Surface-out 的增强组件，按策略注入选择具体能力族。
 *
 * <p>把 OES → Y/UV 拆分 → {@link TieEngine} 处理 → NV12 上屏的全流程封装成一个黑盒：</p>
 * <pre>
 *     生产者(MediaPlayer/MediaCodec)  ──写入──►  inputSurface
 *                                                      │
 *                                                      ▼  内部 GL 线程
 *                                              SurfaceTexture(OES)
 *                                                      │
 *                                                      ▼
 *                                              OesYuvSplitter
 *                                                ├ Y(ByteBuffer) ──► TieEngine.process()
 *                                                └ UV(GPU 纹理)        │
 *                                                                      ▼
 *                                              Nv12GpuRenderer ──► outputSurface(SurfaceView)
 * </pre>
 *
 * <p>构造时通过 {@link TieEngine.Type} 选择处理意图：</p>
 * <ul>
 *   <li>{@link TieEngine.Type#IE_Y}：同尺寸 Y 通道增强（renderer Y 纹理 W×H，路径切换不重建）；</li>
 *   <li>{@link TieEngine.Type#SR_Y}：超分。<b>实际倍率（2×/3×）由
 *       {@link TieEngine#init} 内部按视频尺寸 + 设备能力决策</b>，输出尺寸与 stride 经 {@link InitResult} 回传。
 *       增强开启帧 renderer Y 纹理使用 outputW×outputH，关闭帧回退 W×H，路径切换按需重建，切换瞬间允许丢一帧。</li>
 * </ul>
 *
 * <p>线程模型：内部启动一个 HandlerThread "EnhanceSurface-GL" 作为 GL 线程，公开方法均可在主线程调用，
 * 实际操作通过 Handler 投递到 GL 线程执行；TieEngine 的 init/release 投递到
 * {@link Threads#getSingleExecutor()} 的后台线程（强约束必须后台线程）。</p>
 *
 * <p>视频分辨率不被任一模型档位覆盖（init 返回 {@code CODE_UNSUPPORTED_SIZE}）或 init 失败时，自动回退到
 * "原 Y 直通上屏" 模式，不抛异常。</p>
 */
public class EnhanceSurface {

    private static final String TAG = "EnhanceSurface";

    /** 状态回调，在主线程触发。 */
    public interface Listener {
        /**
         * 增强状态回调：后续帧是否会真正调用 TieEngine.process。
         *
         * <p>触发时机，每次{@link #setInputSize(int, int)} 后。</p>
         * <ul>
         *     <li>分辨率不支持：立即回调 {@code (false, REASON_UNSUPPORTED_SIZE)}；引擎不会被 init。</li>
         *     <li>分辨率支持且 init 成功：回调 {@code (true, REASON_NONE)}；后续帧才会真正调用 process。</li>
         *     <li>分辨率支持但 init 失败：回调 {@code (false, REASON_INIT_FAILED)}。</li>
         * </ul>
         *
         * @param canEnhance 后续帧是否会真正调用 TieEngine.process。
         * @param reason     原因码：{@link #REASON_NONE} / {@link #REASON_UNSUPPORTED_SIZE} /
         *                   {@link #REASON_INIT_FAILED} / {@link #REASON_INFER_TOO_SLOW}。
         */
        void onEnhanceState(boolean canEnhance, @Reason int reason);
    }

    /** 增强可用：当 {@code canEnhance=true} 时使用。 */
    public static final int REASON_NONE = 0;
    /** 当前分辨率不被任一模型档位覆盖（init 返回 {@code CODE_UNSUPPORTED_SIZE}）。 */
    public static final int REASON_UNSUPPORTED_SIZE = 1;
    /** TieEngine 异步 init 失败（错误码非 OK）。 */
    public static final int REASON_INIT_FAILED = 2;
    /** init 成功但首帧推理耗时过高，性能不达标，禁用增强避免拖慢播放。 */
    public static final int REASON_INFER_TOO_SLOW = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({REASON_NONE, REASON_UNSUPPORTED_SIZE, REASON_INIT_FAILED, REASON_INFER_TOO_SLOW})
    public @interface Reason {}

    /// 状态


    private final Context mAppContext;
    /** 处理意图，构造时确定。超分意图下实际倍率（2×/3×）由 {@link TieEngine#init} 按视频尺寸 + 设备能力动态决策。 */
    private final TieEngine.Type mMode;
    /** 引擎实例，无参构造，倍率/档位在 init 内部决策。一次创建复用到 release。 */
    private final TieEngine mEngine = new TieEngine();
    /** 实际倍率（由 InitResult 反推：outputW/videoW），init 成功后赋值；增强渲染按此搭 Y 纹理。 */
    private volatile int mUpscale;

    private final HandlerThread mGlThread;
    private final Handler mGlHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // GL 资源（仅 GL 线程访问）
    private EglCore mEglCore;
    private int mOesTextureId = -1;
    private SurfaceTexture mSurfaceTexture;
    private Surface mInputSurface; // 暴露给外部生产者的 Surface
    private final float[] mStMatrix = new float[16];

    private Surface mOutputSurface; // 当前 output Surface（可空）
    private OesYuvSplitter mSplitter;
    private Nv12GpuRenderer mRenderer;
    /** 当前 mRenderer 是否按"增强路径"（Y 纹理 upscale*W × upscale*H）初始化；用于检测路径切换是否需要重建。 */
    private boolean mRendererForEnhance;
    private int mVideoWidth;
    private int mVideoHeight;
    private boolean mPipelineReady; // splitter 已按当前 video size 初始化（renderer 按需切换路径）

    // 引擎状态
    private volatile boolean mEngineInitSubmitted;
    private volatile boolean mEngineInitReady;
    private volatile boolean mEngineSizeSupported = true;
    // 有效增强输出尺寸（=视频×倍率），由 InitResult 回传。renderer 纹理与上屏区域按此。
    private volatile int mOutputW;
    private volatile int mOutputH;
    // process buffer 行 stride（=模型档位宽×倍率，可能 > mOutputW），由 InitResult.outputStride 回传。
    private volatile int mOutputStride;

    // 增强开关（由调用方控制）
    private volatile boolean mEnhanceEnabled = false;

    private volatile boolean mReleased = false;

    // 监听器（主线程）
    private volatile Listener mListener;

    /// 公开接口


    /**
     * @param context 任意 Context；内部仅持有其 ApplicationContext，用于 TieEngine.init 读取 assets。
     * @param type    处理意图（同尺寸增强 / 超分）；超分的实际倍率在拿到视频尺寸后动态决策。
     *                同实例不允许切换 type，切换需新建实例。
     */
    public EnhanceSurface(Context context, TieEngine.Type type) {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }
        if (type == null) {
            throw new IllegalArgumentException("type == null");
        }
        mAppContext = context.getApplicationContext();
        mMode = type;

        mGlThread = new HandlerThread("EnhanceSurface-GL");
        mGlThread.start();
        mGlHandler = new Handler(mGlThread.getLooper());

        // 同步在 GL 线程创建 EGL context + OES 纹理 + SurfaceTexture + input Surface，
        // 让 getInputSurface() 立刻可用（满足"input Surface 在生产者写入前就已就绪"）
        runOnGlThreadSync(this::initInputOnGl);
    }

    /** 设置生命周期监听器（可空）；回调在主线程触发。 */
    public void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * 拿到 input Surface（供 MediaPlayer.setSurface / MediaCodec.configure 使用）。
     * release 之后返回 null。
     */
    public Surface getInputSurface() {
        if (mReleased) {
            return null;
        }
        return mInputSurface;
    }

    /**
     * 设置 output Surface。在 GL 线程异步切换 EGL window surface。
     *
     * <p>{@code surface == null} 时停止上屏；非空时新 Surface 必须 {@code isValid()}。</p>
     */
    public void setOutputSurface(Surface surface) {
        if (mReleased) {
            return;
        }
        mGlHandler.post(() -> doSetOutputSurfaceOnGl(surface));
    }

    /** 增强开关。仅当 TieEngine init 完成且尺寸支持时生效。 */
    public void setEnhanceEnabled(boolean enabled) {
        mEnhanceEnabled = enabled;
    }

    /** 构造时指定的处理意图。 */
    public TieEngine.Type getMode() {
        return mMode;
    }

    /**
     * 当前实际选型的可读标签（如 {@code "IE_Y"} / {@code "SR_Y x2"} / {@code "SR_Y x3"}）；
     * 倍率在 init 成功后才确定，之前返回 {@code null}。供状态栏展示。
     */
    public String getResolvedLabel() {
        int upscale = mUpscale;
        if (!mEngineInitReady || upscale <= 0) {
            return null;
        }
        return (mMode == TieEngine.Type.IE_Y) ? "IE_Y" : ("SR_Y x" + upscale);
    }

    /**
     * 释放全部资源。幂等。释放后所有公开方法 no-op。
     *
     * <p>TieEngine 必须后台线程释放，故投递到 {@link Threads#getSingleExecutor()}；其余 GL 资源在 GL 线程释放，
     * 最后退出 GL 线程。</p>
     */
    public void release() {
        if (mReleased) {
            return;
        }
        mReleased = true;
        // 先把 TieEngine 释放投到后台线程（不阻塞 GL 线程退出）。未 init 时 release 幂等。
        Threads.getSingleExecutor().submit(() -> {
            try {
                mEngine.release();
            } catch (Throwable t) {
                Log.w(TAG, "release engine", t);
            }
        });
        // GL 资源在 GL 线程释放，然后 quit
        mGlHandler.post(() -> {
            releaseGlResourcesOnGl();
            mGlThread.quitSafely();
        });
    }

    /// 内部：GL 线程

    private void initInputOnGl() {
        try {
            mEglCore = new EglCore();
        } catch (RuntimeException e) {
            Log.e(TAG, "EglCore creation failed", e);
            throw e;
        }
        // 没有 output Surface 时，先用 surfaceless current 创建 OES 纹理与 SurfaceTexture
        if (!mEglCore.makeCurrentSurfaceless()) {
            Log.w(TAG, "makeCurrentSurfaceless failed; some devices may need a dummy pbuffer");
        }
        mOesTextureId = OesTextureFactory.createOesTexture();
        mSurfaceTexture = new SurfaceTexture(mOesTextureId);
        mSurfaceTexture.setOnFrameAvailableListener(st -> mGlHandler.post(this::drawFrameOnGl));
        mInputSurface = new Surface(mSurfaceTexture);
        Log.i(TAG, "input ready, mode=" + mMode + " oesTex=" + mOesTextureId);
    }

    /// output Surface 切换


    private void doSetOutputSurfaceOnGl(Surface surface) {
        if (mReleased) {
            return;
        }
        // 先销毁旧 window surface（无论新 surface 是否有效）
        if (mEglCore != null) {
            mEglCore.releaseWindowSurface();
        }

        if (surface == null) {
            mOutputSurface = null;
            Log.i(TAG, "outputSurface set to null");
            return;
        }
        if (!surface.isValid()) {
            Log.w(TAG, "outputSurface !isValid, ignore");
            mOutputSurface = null;
            return;
        }
        try {
            mEglCore.createWindowSurface(surface);
            mOutputSurface = surface;
            // 切换后立刻 makeCurrent，方便后续帧渲染直接用
            mEglCore.makeCurrent();
            // 若 renderer 已存在则同步一下视口尺寸
            if (mRenderer != null) {
                mRenderer.onSurfaceChanged(
                        mEglCore.getWindowSurfaceWidth(), mEglCore.getWindowSurfaceHeight());
            }
            Log.i(TAG, "outputSurface set, size=" + mEglCore.getWindowSurfaceWidth() + "x" + mEglCore.getWindowSurfaceHeight());
        } catch (RuntimeException e) {
            Log.e(TAG, "createWindowSurface failed", e);
            mOutputSurface = null;
        }
    }

    // ============================================================
    // GL 线程：每帧驱动循环
    // ============================================================

    private void drawFrameOnGl() {
        if (mReleased || mSurfaceTexture == null) {
            return;
        }

        // 必须有一个 current 的 EGL context 才能 updateTexImage（OES 纹理是 GL 资源）
        // 若 output 未设置，临时用 surfaceless current 把 BufferQueue 消化掉，避免阻塞生产者
        boolean hasOutput = (mOutputSurface != null && mEglCore != null);
        if (hasOutput) {
            if (!mEglCore.makeCurrent()) {
                Log.w(TAG, "makeCurrent failed, drop frame");
                return;
            }
        } else if (mEglCore != null) {
            mEglCore.makeCurrentSurfaceless();
        }

        // 1) 把 input Surface 上的最新帧绑到 OES 纹理（无论 output 是否就绪都要消费 BufferQueue）
        try {
            mSurfaceTexture.updateTexImage();
        } catch (RuntimeException e) {
            Log.e(TAG, "updateTexImage failed", e);
            return;
        }

        // 2) output 未就绪：仅消化 BufferQueue 即可，立即返回（早返：避免后续路径嵌套）
        if (!hasOutput) {
            return;
        }
        mSurfaceTexture.getTransformMatrix(mStMatrix);

        // 3) 第一次拿到视频尺寸后，惰性 init splitter / TieEngine
        ensurePipelineLazy();
        if (!mPipelineReady) {
            return;
        }

        // 4) OES → Y(ByteBuffer) + UV(GPU 纹理)
        boolean ok;
        try {
            ok = mSplitter.splitFrame(mOesTextureId, mStMatrix);
        } catch (RuntimeException e) {
            Log.e(TAG, "splitFrame threw", e);
            return;
        }
        if (!ok) {
            Log.w(TAG, "splitFrame failed, skip");
            return;
        }
        ByteBuffer yBuffer = mSplitter.getYBuffer();
        int uvTextureId = mSplitter.getUvTextureId();

        // 5) 决定本帧路径：增强开 + init 完成 + 尺寸支持 + engine 已建 → 走增强；否则 → 原 Y 直通
        TieEngine engine = mEngine;
        boolean wantEnhance = mEnhanceEnabled && mEngineInitReady && mEngineSizeSupported && engine != null;
        ByteBuffer yToUse = yBuffer;
        int yStrideToUse = mVideoWidth;
        if (wantEnhance) {
            try {
                ByteBuffer enhanced = engine.process(yBuffer, mVideoWidth, mVideoHeight);
                int stride = mOutputStride;
                // 有效区为左上角 mOutputW × mOutputH（=视频×倍率）；buffer 行宽=stride（>= mOutputW，含 padding 放大区）。
                // 校验读取有效区最后一行不越界，再由 GL_UNPACK_ROW_LENGTH=stride 抠出有效区，避免 CPU 裁剪。
                int needed = stride * (mOutputH - 1) + mOutputW;
                if (enhanced != null && enhanced.capacity() >= needed) {
                    yToUse = enhanced;
                    yStrideToUse = stride;
                } else {
                    // process 返回 null 或容量异常：本帧回退直通
                    wantEnhance = false;
                }
            } catch (Throwable t) {
                Log.e(TAG, "engine.process threw, fallback to raw Y", t);
                wantEnhance = false;
            }
        }

        // 6) 按当前路径确保 renderer 的 Y 纹理尺寸正确（增强=upscale*W × upscale*H, 直通=W×H）
        if (!ensureRendererForPath(wantEnhance)) {
            return; // 重建失败，丢弃本帧
        }

        // 7) 计算保持纵横比的居中视口（白边贴合）后渲染
        applyAspectFitViewport();
        try {
            mRenderer.render(yToUse, yStrideToUse, uvTextureId);
        } catch (RuntimeException e) {
            Log.e(TAG, "render failed", e);
            return;
        }

        // 8) 上屏
        if (!mEglCore.swapBuffers()) {
            Log.w(TAG, "swapBuffers failed, output surface may be invalid");
        }
    }

    /**
     * 第一帧到达后惰性初始化 splitter 与 TieEngine。renderer 不在此一次性创建，
     * 而由 {@link #ensureRendererForPath} 按当前帧路径（增强 / 直通）按需创建/重建。
     */
    private void ensurePipelineLazy() {
        if (mPipelineReady) {
            return;
        }
        if (mVideoWidth <= 0 || mVideoHeight <= 0) {
            return;
        }
        try {
            mSplitter = new OesYuvSplitter();
            mSplitter.init(mVideoWidth, mVideoHeight);
            mPipelineReady = true;
            Log.i(TAG, "pipeline (splitter) ready for " + mVideoWidth + "x" + mVideoHeight);
        } catch (RuntimeException e) {
            Log.e(TAG, "init splitter failed", e);
            return;
        }
        // 异步 init TieEngine（必须后台线程，且只提交一次）
        tryInitEngineAsync();
    }

    /**
     * 输入图像尺寸。调用方在拿到生产者输出分辨率后调用，用于驱动 SurfaceTexture
     * default buffer size 与 pipeline 初始化。
     *
     * <p>若与已初始化的 pipeline 尺寸不一致，会触发 pipeline / TieEngine 重建。</p>
     */
    public void setInputSize(int width, int height) {
        if (mReleased || width <= 0 || height <= 0) {
            return;
        }
        mGlHandler.post(() -> doSetVideoSizeOnGl(width, height));
    }

    private void doSetVideoSizeOnGl(int width, int height) {
        if (mReleased) {
            return;
        }
        if (mPipelineReady && mVideoWidth == width && mVideoHeight == height) {
            return;
        }
        if (mPipelineReady) {
            // 罕见：换源导致分辨率变化。销毁旧 pipeline，重置引擎状态后按新尺寸重新 init（init 内部会释放旧资源）。
            Log.i(TAG, "video size changed " + mVideoWidth + "x" + mVideoHeight
                    + " -> " + width + "x" + height + ", rebuild pipeline");
            destroyPipelineOnGl();
            mEngineInitSubmitted = false;
            mEngineInitReady = false;
            mEngineSizeSupported = true;
        }
        mVideoWidth = width;
        mVideoHeight = height;
        if (mSurfaceTexture != null) {
            mSurfaceTexture.setDefaultBufferSize(width, height);
        }
        // 选型/选档/倍率决策与支持性判定全部下沉到 TieEngine.init：此处只记录视频尺寸，init 异步进行。
    }

    private void tryInitEngineAsync() {
        if (mEngineInitSubmitted) {
            return;
        }
        mEngineInitSubmitted = true;
        final int videoW = mVideoWidth;
        final int videoH = mVideoHeight;
        Threads.getSingleExecutor().submit(() -> {
            if (mReleased) {
                return;
            }
            InitResult result = mEngine.init(mAppContext, new Config(mMode, videoW, videoH));
            boolean codeOk = (result.code == InitResult.CODE_OK);
            boolean canEnhance = TieSdkHelper.isTieEngineInitReady(result);
            Log.i(TAG, "engine init type=" + mMode + " video=" + videoW + "x" + videoH
                    + " code=" + result.code + ", inferMs=" + result.inferMs
                    + ", out=" + result.outputWidth + "x" + result.outputHeight
                    + ", msg=" + result.msg + ", canEnhance=" + canEnhance);

            if (canEnhance) {
                mOutputW = result.outputWidth;
                mOutputH = result.outputHeight;
                mOutputStride = result.outputStride;
                // 反推实际倍率（IE=1，SR 可为 2 或 3）：有效输出宽 / 视频宽（=整数倍）。
                mUpscale = videoW > 0 ? (result.outputWidth / videoW) : 1;
            }
            mEngineSizeSupported = (result.code != InitResult.CODE_UNSUPPORTED_SIZE);
            mEngineInitReady = canEnhance;
            // 区分原因码：分辨率不支持 / init 失败 / 推理耗时过高，便于上层文案区分。
            final int reason;
            if (canEnhance) {
                reason = REASON_NONE;
            } else if (result.code == InitResult.CODE_UNSUPPORTED_SIZE) {
                reason = REASON_UNSUPPORTED_SIZE;
            } else if (codeOk) {
                reason = REASON_INFER_TOO_SLOW;
            } else {
                reason = REASON_INIT_FAILED;
            }
            mMainHandler.post(() -> {
                Listener l = mListener;
                if (l != null) {
                    l.onEnhanceState(canEnhance, reason);
                }
            });
        });
    }

    /**
     * 确保 {@link Nv12GpuRenderer} 与当前帧路径匹配的 Y 纹理尺寸：
     * <ul>
     *   <li>增强路径：Y 纹理需为 {@code upscale*videoW × upscale*videoH}；</li>
     *   <li>直通路径：Y 纹理需为 {@code videoW × videoH}。</li>
     * </ul>
     * 路径切换时销毁旧 renderer 并按新尺寸重建。upscale=1 时增强/直通尺寸相同，不会触发重建。
     * 允许切换瞬间丢一帧。
     *
     * @return true 表示 renderer 已就绪可渲染；false 表示重建失败应丢帧。
     */
    private boolean ensureRendererForPath(boolean wantEnhance) {
        // upscale=1 时两个路径 Y 纹理尺寸一致，不需要按 wantEnhance 区分
        boolean targetForEnhance = (mUpscale > 1) && wantEnhance;
        if (mRenderer != null && mRendererForEnhance == targetForEnhance) {
            return true;
        }
        if (mRenderer != null) {
            try { mRenderer.release(); } catch (Throwable t) { Log.w(TAG, "release renderer on path switch", t); }
            mRenderer = null;
        }
        try {
            int rw = targetForEnhance ? mUpscale * mVideoWidth : mVideoWidth;
            int rh = targetForEnhance ? mUpscale * mVideoHeight : mVideoHeight;
            mRenderer = new Nv12GpuRenderer();
            mRenderer.init(rw, rh);
            if (mEglCore != null && mOutputSurface != null) {
                mRenderer.onSurfaceChanged(
                        mEglCore.getWindowSurfaceWidth(), mEglCore.getWindowSurfaceHeight());
            }
            mRendererForEnhance = targetForEnhance;
            Log.i(TAG, "renderer ready path=" + (targetForEnhance ? "enhance" : "passthrough")
                    + ", size=" + rw + "x" + rh);
            return true;
        } catch (RuntimeException e) {
            Log.e(TAG, "init Nv12GpuRenderer failed", e);
            mRenderer = null;
            return false;
        }
    }

    /** 按视频纵横比把渲染区居中贴合 output 窗口。在 GL 线程调用。 */
    private void applyAspectFitViewport() {
        if (mRenderer == null || mEglCore == null) {
            return;
        }
        int surfaceW = mEglCore.getWindowSurfaceWidth();
        int surfaceH = mEglCore.getWindowSurfaceHeight();
        if (surfaceW <= 0 || surfaceH <= 0) {
            return;
        }

        // 增强路径下 Y/UV 同比例放大，纵横比与 video 相同，沿用 video 比例即可
        float videoRatio = (float) mVideoWidth / mVideoHeight;
        float surfaceRatio = (float) surfaceW / surfaceH;
        int drawW, drawH;
        if (surfaceRatio > videoRatio) {
            drawH = surfaceH;
            drawW = (int) (surfaceH * videoRatio);
        } else {
            drawW = surfaceW;
            drawH = (int) (surfaceW / videoRatio);
        }
        int drawX = (surfaceW - drawW) / 2;
        int drawY = (surfaceH - drawH) / 2;
        mRenderer.setRenderRect(drawX, drawY, drawW, drawH);
    }

    // ============================================================
    // GL 线程：资源销毁
    // ============================================================

    private void destroyPipelineOnGl() {
        if (mSplitter != null) {
            try { mSplitter.release(); } catch (Throwable t) { Log.w(TAG, "release splitter", t); }
            mSplitter = null;
        }
        if (mRenderer != null) {
            try { mRenderer.release(); } catch (Throwable t) { Log.w(TAG, "release renderer", t); }
            mRenderer = null;
        }
        mPipelineReady = false;
        mRendererForEnhance = false;
    }

    private void releaseGlResourcesOnGl() {
        // EGL must be current to delete GL objects
        if (mEglCore != null) {
            // 优先 surfaceless current，避免 window surface 已无效时挂掉
            mEglCore.makeCurrentSurfaceless();
        }
        destroyPipelineOnGl();
        if (mSurfaceTexture != null) {
            try { mSurfaceTexture.setOnFrameAvailableListener(null); } catch (Throwable ignore) {}
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mOesTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mOesTextureId}, 0);
            mOesTextureId = -1;
        }
        if (mEglCore != null) {
            mEglCore.releaseWindowSurface();
            mEglCore.release();
            mEglCore = null;
        }
        Log.i(TAG, "GL resources released");
    }

    // ============================================================
    // 杂项
    // ============================================================

    /**
     * 投递 {@code r} 到 GL 线程并同步等待执行完毕。基于 {@link CountDownLatch} 实现，
     * Runnable 抛出未捕获异常时仍会 countDown，不会让调用方永久阻塞；调用方所在线程
     * 在等待中被 interrupt 时会恢复中断标志后立即返回。
     */
    private void runOnGlThreadSync(Runnable r) {
        final CountDownLatch latch = new CountDownLatch(1);
        mGlHandler.post(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
