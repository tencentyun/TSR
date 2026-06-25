package com.tencent.mps.srplayer.demo.oestexture;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.common.decoder.Decode2Surface;
import com.tencent.mps.srplayer.common.playback.FrameMetrics;
import com.tencent.mps.srplayer.common.playback.FrameScheduler;
import com.tencent.mps.srplayer.common.TieSdkHelper;
import com.tencent.mps.srplayer.common.Threads;
import com.tencent.mps.srplayer.common.Fullscreen;
import com.tencent.mps.srplayer.common.VideoSource;
import com.tencent.mps.tie.api.TieEngine;
import com.tencent.mps.tie.api.TieEngine.InitResult;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OES 纹理路径 Demo Activity。负责解码器、SurfaceTexture、帧调度、UI、性能统计等 app 侧职责，
 * 增强链路委托给 {@link VideoEnhancePipeline}。
 *
 * <p>架构说明见 {@code oestexture/OES纹理路径Demo架构说明.md}。</p>
 */
public class OesTexturePathActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "OesTexturePathActivity";

    /// UI / 渲染基础
    private GLSurfaceView mGlSurfaceView;
    private TextView mTvStatus1, mTvStatus2;
    private ToggleButton mToggleEnhance;
    /** 视频来源（本地 URI 或内置路径），在 {@link #onCreate} 中解析。 */
    private VideoSource mVideoSource;
    /** 视频文件名 / 可读名称，主要用于日志与性能记录。 */
    private String mVideoFileName = "";

    private int mViewWidth, mViewHeight, mVideoWidth, mVideoHeight;

    /// 解码 + Surface（OES 纹理由 pipeline 创建并持有）
    private Decode2Surface mDecoder;
    private int mOesTextureId = -1;
    private SurfaceTexture mSurfaceTexture;
    private Surface mDecoderSurface;
    private final float[] mStMatrix = new float[16];

    /// 视频流增强门面，封装 OES→Y/UV→TieEngine 增强→上屏 的完整链路。
    private VideoEnhancePipeline mPipeline;
    private boolean mEnhance = false;
    private volatile boolean mTieInitSubmitted = false;
    private volatile boolean mTieInitReady = false;
    private volatile boolean mTieSizeSupported = true; // 视频分辨率是否能被 TieEngine 的任一模型档位覆盖

    /// 帧率调度与性能统计（与 ByteBuffer 路径共用 perf 子包下的轻量组件）
    private FrameScheduler mScheduler;
    private final FrameMetrics mMetrics = new FrameMetrics();
    /// 周期边界判定的上一刻时间戳；与 mMetrics 配对
    private long mLastSnapshotMs = 0L;
    private volatile boolean mPlaying = false;

    // ============================================================
    // Activity 生命周期
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_simple_demo);
        Fullscreen.enable(this);

        // 解析视频来源：优先本地 URI，其次内置文件名
        mVideoSource = VideoSource.from(this);
        mVideoFileName = mVideoSource.displayName;
        Log.i(TAG, "use video: " + mVideoSource.displayName);

        // 资源不可读则提示并退出，避免后续 MediaCodec 抛异常
        if (!mVideoSource.exists(this)) {
            Toast.makeText(this, "视频不可读取：" + mVideoSource.displayName, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
        // GLSurfaceView 已绑定，构造调度器
        mScheduler = new FrameScheduler(() -> mGlSurfaceView.requestRender());
    }

    private void initViews() {
        mGlSurfaceView = findViewById(R.id.glSurfaceView);
        mTvStatus1 = findViewById(R.id.tvStatus1);
        mTvStatus2 = findViewById(R.id.tvStatus2);
        mTvStatus2.setMovementMethod(ScrollingMovementMethod.getInstance());
        findViewById(R.id.btnPlayPause).setVisibility(View.GONE);
        mToggleEnhance = findViewById(R.id.toggleEnhance);
        mToggleEnhance.setVisibility(View.VISIBLE);
        mToggleEnhance.setChecked(mEnhance);
        mToggleEnhance.setOnCheckedChangeListener((btn, isChecked) -> {
            mEnhance = isChecked;
            Log.i(TAG, "toggleEnhance -> " + isChecked);
        });

        mGlSurfaceView.setEGLContextClientVersion(3);
        mGlSurfaceView.setRenderer(this);
        mGlSurfaceView.setKeepScreenOn(true);
        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");
        mGlSurfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause()");
        // 停止调度，避免 GL 线程暂停后还在 post requestRender
        if (mScheduler != null) {
            mScheduler.stop();
        }
        mPlaying = false;
        mGlSurfaceView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");

        if (mScheduler != null) {
            mScheduler.stop();
        }

        // 主线程释放：MediaCodec；TieEngine 必须投递到后台线程释放
        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }
        final VideoEnhancePipeline pipeline = mPipeline;
        if (pipeline != null) {
            Threads.getSingleExecutor().submit(pipeline::releaseEngine);
        }

        // GL 资源在 GL 线程释放
        if (mGlSurfaceView != null) {
            mGlSurfaceView.queueEvent(this::releaseGlResources);
        }
    }

    // ============================================================
    // GLSurfaceView.Renderer
    // ============================================================

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated()");

        // 把 GL 线程（process() / 渲染都在这里跑）的 nice 抬到 DISPLAY 级（-4），
        // 减少被后台普通线程抢占；setThreadPriority 不传 tid 时作用于当前线程。
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);

        // 1) 用 VideoSizeProbe 探测视频尺寸，避免循环依赖（详见架构文档）
        Size videoSize;
        try {
            videoSize = (mVideoSource != null && mVideoSource.isLocalUri())
                    ? VideoSizeProbe.probe(this, mVideoSource.uri)
                    : VideoSizeProbe.probe(mVideoSource.filePath);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "probe video size failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "解析视频尺寸失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }
        mVideoWidth = videoSize.getWidth();
        mVideoHeight = videoSize.getHeight();
        Log.i(TAG, "video " + mVideoWidth + "x" + mVideoHeight);

        // 2) 构造增强门面
        mPipeline = new VideoEnhancePipeline(TieEngine.Type.IE_Y, mVideoWidth, mVideoHeight);

        // 3) pipeline.initGl → OES 纹理 → SurfaceTexture → Surface → 解码器
        try {
            mOesTextureId = mPipeline.initGl(mVideoWidth, mVideoHeight);
            mSurfaceTexture = new SurfaceTexture(mOesTextureId);
            // 不在 OnFrameAvailableListener 里 requestRender：渲染节奏仅由 PTS 调度驱动（见
            // FrameScheduler），否则会跟着"解码器吐帧"走成 60fps，而不是视频原始 25fps。
            mDecoderSurface = new Surface(mSurfaceTexture);

            mDecoder = new Decode2Surface();
            if (mVideoSource != null && mVideoSource.isLocalUri()) {
                mDecoder.init(this, mVideoSource.uri, mDecoderSurface);
            } else {
                mDecoder.init(mVideoSource.filePath, mDecoderSurface);
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to init pipeline GL / decoder", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "GL 初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }

        // 4) 通知主线程做布局缩放 + 提交 TieEngine init
        runOnUiThread(() -> {
            adjustGlSurfaceSize(mVideoWidth, mVideoHeight);
            tryInitTieY(mVideoWidth, mVideoHeight);
        });

        // 5) 自动开始播放
        startPlay();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
        Log.d(TAG, "onSurfaceChanged() " + width + "x" + height);
        if (mPipeline != null) {
            mPipeline.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (!mPlaying || mDecoder == null || mPipeline == null) {
            return;
        }

        // 在最入口打点；末尾累加到 mMetrics。dequeue 失败/drawFrame 失败的早返回路径不计入，
        // 与帧计数保持配对，避免分母不一致。
        long frameStartMs = SystemClock.elapsedRealtime();

        // 1) dequeue 一帧到 Surface
        if (!mDecoder.dequeueOutputFrameToSurface()) {
            mScheduler.onFrameUnavailable();
            return;
        }

        // 2) 把 Surface 上的最新帧绑到 OES 纹理
        long t0 = SystemClock.elapsedRealtime();
        try {
            mSurfaceTexture.updateTexImage();
        } catch (RuntimeException e) {
            Log.e(TAG, "updateTexImage failed", e);
            return;
        }
        mSurfaceTexture.getTransformMatrix(mStMatrix);
        long updateTexMs = SystemClock.elapsedRealtime() - t0;

        // 3) 门面一把搞定：OES → Y/UV → 增强 → 上屏
        boolean enhance = mEnhance && mTieInitReady && mTieSizeSupported;
        boolean ok = mPipeline.drawFrame(mOesTextureId, mStMatrix, enhance);
        if (!ok) {
            Log.w(TAG, "pipeline.drawFrame failed, skip this frame");
            // 失败时仍要推进调度，避免卡住
            mScheduler.onFrameRendered(mDecoder.getCurrentPresentationTimeUs());
            return;
        }

        // 4) 从门面读取本帧细分耗时
        VideoEnhancePipeline.FrameInfo info = mPipeline.getLastFrameInfo();
        long splitMs = info.splitMs;
        long processMs = info.enhanced ? (info.preMs + info.runMs + info.postMs) : 0L;
        mMetrics.processInfo = String.format(Locale.getDefault(),
                "[pre=%dms run=%dms post=%dms]", info.preMs, info.runMs, info.postMs);

        // 5) 累计本帧耗时与分段
        long frameMs = SystemClock.elapsedRealtime() - frameStartMs;
        LinkedHashMap<String, Long> segments = new LinkedHashMap<>();
        segments.put("updTex", updateTexMs);
        segments.put("split", splitMs);
        segments.put("process", processMs);
        mMetrics.addFrame(frameMs, segments);

        // 6) 周期边界：聚合一次 + 输出日志 + 刷新状态栏
        long now = SystemClock.elapsedRealtime();
        if (mLastSnapshotMs == 0L) {
            mLastSnapshotMs = now;
        }
        long interval = now - mLastSnapshotMs;
        if (interval >= FrameMetrics.FPS_CAL_INTERVAL_MS) {
            String breakdown = mMetrics.snapshotAndReset(interval);
            String pinfo = mMetrics.processInfo;
            Log.d(TAG, String.format(Locale.getDefault(),
                    "耗时: fps=%d total=%dms%s%s",
                    mMetrics.fps, mMetrics.totalMs, breakdown,
                    pinfo.isEmpty() ? "" : " ProcessInfo" + pinfo));
            updateStatusText();
            mLastSnapshotMs = now;
        }

        // 7) 调度下一帧
        mScheduler.onFrameRendered(mDecoder.getCurrentPresentationTimeUs());
    }

    // ============================================================
    // 播放控制（自动播放，不暴露按钮，与 ByteBufferPathBaseActivity controlPanel=gone 一致）
    // ============================================================

    private void startPlay() {
        mPlaying = true;
        mMetrics.reset();
        mLastSnapshotMs = 0L;
        if (mScheduler != null) {
            mScheduler.start();
        }
    }

    // ============================================================
    // TieEngine 接入（通过门面）
    // ============================================================

    private void tryInitTieY(int width, int height) {
        if (mTieInitSubmitted || mPipeline == null) {
            return;
        }
        // 选档/支持性判定下沉到 SDK init：直接提交，不在此预判分辨率是否支持。
        mTieInitSubmitted = true;
        final VideoEnhancePipeline pipeline = mPipeline;
        Threads.getSingleExecutor().submit(() -> {
            if (isFinishing()) {
                return;
            }
            long t0 = SystemClock.elapsedRealtime();
            InitResult result = pipeline.initEngineBlocking(this);
            long initMs = SystemClock.elapsedRealtime() - t0;
            boolean ok = result.code == InitResult.CODE_OK;
            Log.i(TAG, "TieY init result in " + initMs + "ms"
                    + ", code=" + result.code
                    + ", inferMs=" + result.inferMs
                    + ", out=" + result.outputWidth + "x" + result.outputHeight
                    + ", msg=" + result.msg);

            // 分辨率不支持：单独处理，关闭增强（与原 pickModelSize==null 等价）
            if (result.code == InitResult.CODE_UNSUPPORTED_SIZE) {
                mTieSizeSupported = false;
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        mToggleEnhance.setChecked(false);
                        mToggleEnhance.setEnabled(false);
                        mEnhance = false;
                        Toast.makeText(OesTexturePathActivity.this, "当前分辨率 " + width + "x" + height
                                + " 超出 TieY 支持范围，已关闭增强", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            if (TieSdkHelper.isTieEngineInitReady(result)) {
                mTieInitReady = true;
                // init 成功后切回主线程 enable 开关
                runOnUiThread(() -> {
                    if (!isFinishing() && mTieSizeSupported) {
                        mToggleEnhance.setEnabled(true);
                    }
                });
            } else {
                // init 失败 / 性能不达标：弹 Toast 让用户感知，按钮保持 disable
                final String reason = !ok
                        ? ("init 失败 code=" + result.code + " msg=" + result.msg)
                        : ("init 推理耗时过高 inferMs=" + result.inferMs + "ms");
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(OesTexturePathActivity.this,
                                "TieY 初始化失败，已关闭增强：" + reason,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ============================================================
    // 状态栏刷新
    // ============================================================

    private void updateStatusText() {
        runOnUiThread(() -> {
            mTvStatus1.setText(String.format(Locale.getDefault(), "total=%dms, process=%dms",
                    mMetrics.totalMs, mMetrics.processMs));
            mTvStatus2.setText(String.format(Locale.getDefault(),
                    "增强：OES路径 | FPS=%d | 视频=%dx%d, 视图=%dx%d, TieY=%s",
                    mMetrics.fps, mVideoWidth, mVideoHeight, mViewWidth, mViewHeight,
                    mTieInitReady ? "ready" : (mTieSizeSupported ? "init中" : "不支持该分辨率")));
        });
    }

    // ============================================================
    // 杂项
    // ============================================================

    /** 视频按等比缩放贴合 GLSurfaceView（参考 ByteBufferPathBaseActivity#onGetVideoSize） */
    private void adjustGlSurfaceSize(int videoWidth, int videoHeight) {
        int viewWidth = mGlSurfaceView.getWidth();
        int viewHeight = mGlSurfaceView.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }
        float videoRatio = (float) videoWidth / videoHeight;
        float viewRatio = (float) viewWidth / viewHeight;
        if (viewRatio > videoRatio) {
            int newWidth = (int) (viewHeight * videoRatio);
            mGlSurfaceView.getLayoutParams().width = newWidth;
            mGlSurfaceView.getLayoutParams().height = viewHeight;
        } else {
            int newHeight = (int) (viewWidth / videoRatio);
            mGlSurfaceView.getLayoutParams().width = viewWidth;
            mGlSurfaceView.getLayoutParams().height = newHeight;
        }
        mGlSurfaceView.requestLayout();
    }

    /** 在 GL 线程释放 GL 资源 */
    private void releaseGlResources() {
        if (mPipeline != null) {
            mPipeline.releaseGl();
            // 注意：releaseEngine 已在 onDestroy 投递到后台线程，这里只释放 GL 侧。
            mPipeline = null;
        }
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mDecoderSurface != null) {
            mDecoderSurface.release();
            mDecoderSurface = null;
        }
        if (mOesTextureId != -1) {
            GLES20.glDeleteTextures(1, new int[]{mOesTextureId}, 0);
            mOesTextureId = -1;
        }
    }
}
