package com.tencent.mps.srplayer.demo.bytebuffer;

import android.media.MediaFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.common.playback.FrameMetrics;
import com.tencent.mps.srplayer.common.playback.FrameScheduler;
import com.tencent.mps.srplayer.common.gl.Nv12Renderer;
import com.tencent.mps.srplayer.common.gl.Nv12Shaders;
import com.tencent.mps.srplayer.common.Fullscreen;
import com.tencent.mps.srplayer.common.VideoSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Locale;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * "ByteBuffer 路径" 的基线 demo 基类：
 * MediaCodec ByteBuffer 模式解码 → CPU 拿到 NV12 → 子类对 Y 通道做可选处理 → {@link Nv12Renderer} 上屏。
 *
 * <p>本类本身是一个可独立运行的 1x 渲染基线 demo，不感知任何"增强 / 超分"概念。
 * 子类如需做 Y 通道处理，重写 {@link #process(ByteBuffer, int, int)} 即可。
 * 子类如需替换或叠加渲染器（例如超分场景需要不同的 Y/UV 纹理尺寸），可重写以下钩子：</p>
 * <ul>
 *     <li>{@link #initRenderers(int, int)}：首帧分配 GL 资源</li>
 *     <li>{@link #onRendererSurfaceChanged(int, int)}：Surface 尺寸变化</li>
 *     <li>{@link #renderFrame(ByteBuffer, int, int, ByteBuffer, int, int, int, ByteBuffer)}：上屏</li>
 *     <li>{@link #releaseRenderers()}：销毁 GL 资源（在 GL 线程中调用）</li>
 * </ul>
 */
public class ByteBufferPathBaseActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "ByteBufferPathBaseActivity";

    protected int mViewWidth, mViewHeight, mVideoWidth, mVideoHeight;
    protected GLSurfaceView glSurfaceView;
    private ImageButton btnPlayPause;
    protected TextView mTvStatus1, mTvStatus2;
    private PlaybackState currentState = PlaybackState.IDLE;
    // ... 解码器和 NV12 渲染器
    private Decode2Yuv mDecoder;
    /** 基类自带的 1x 渲染器。子类如需替换，请通过 {@link #initRenderers}/{@link #renderFrame} 等钩子接管。 */
    private Nv12Renderer mNv12Renderer;
    /** 视频文件路径（仅内置视频有效）。本地 URI 场景下为 null，请使用 {@link #mVideoSource} 统一访问。 */
    protected String mVideoFilePath;
    /** 视频文件名（不含路径）；主要用于日志与性能记录。本地视频下会被赋为 {@link VideoSource#displayName}。 */
    protected String mVideoFileName = "";
    /** 统一的视频来源描述（本地 URI / 内置路径）。 */
    protected VideoSource mVideoSource;

    /// 性能统计与帧率调度
    protected final FrameMetrics mMetrics = new FrameMetrics();
    private FrameScheduler mScheduler;

    /// 周期边界判定的上一刻时间戳；由 onDrawFrame 维护，与 mMetrics 配对
    private long mLastSnapshotMs = 0L;

    /// 子类返回的处理后 Y buffer 的行跨度（字节）。0 表示未设置，基类默认按 mVideoWidth（紧凑）处理。
    /// 当模型档位 > 视频尺寸时，子类可设为 modelW，让 GL 通过 GL_UNPACK_ROW_LENGTH
    /// 直接抠左上角 videoW×videoH，避免 CPU 一次按行 memcpy 裁剪。
    protected int mEnhancedYStride;

    // ---- 暂停预览（pause-toggle-preview）相关 ----
    // 编译期开关：性能/功耗测试包默认关闭，开发演示包改 true。
    // 关闭时 JIT 会把所有 ENABLE_PAUSE_PREVIEW 分支当作 dead code 消除，运行时零开销。
    private static final boolean ENABLE_PAUSE_PREVIEW =  true;

    // 主线程 pauseVideo() 设为 true，GL 线程在下一次 onDrawFrame 末尾完成缓存后清回 false。
    private volatile boolean mNeedCachePauseFrame = false;
    // 缓存是否就绪：GL 线程拷贝完成后置 true；play/stop 时清 false。
    private volatile boolean mPauseCacheReady = false;
    // 暂停时缓存的 NV12 数据（direct buffer，由 GL 线程读写）。
    private ByteBuffer mPauseYBuffer;
    private ByteBuffer mPauseUvBuffer;
    private int mPauseYStride;
    private int mPauseSliceHeight;
    private int mPauseHeight;

    // ---- 通用 ToggleButton 开关相关 ----
    protected ToggleButton mToggleButton;
    protected boolean mToggleButtonOn = false;
    // 是否允许把 toggle 设为 enabled；被 markToggleUnsupported() 置 false 后，markToggleReady() 不再启用。
    private boolean mToggleButtonEnable = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        setContentView(R.layout.activity_simple_demo);
        Fullscreen.enable(this);

        // 解析视频来源：优先本地 URI，其次内置文件名，都没有则回退默认
        mVideoSource = VideoSource.from(this);
        mVideoFilePath = mVideoSource.filePath; // 本地 URI 时为 null，子类请使用 mVideoSource
        mVideoFileName = mVideoSource.displayName;
        Log.i(TAG, "use video: " + mVideoSource.displayName);

        // 资源不可读则提示并退出，避免后续 MediaCodec 抛异常
        if (!mVideoSource.exists(this)) {
            Toast.makeText(this, "视频不可读取：" + mVideoSource.displayName, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
        // glSurfaceView 已在 initViews 中绑定，这里构造调度器，渲染触发器即 requestRender
        mScheduler = new FrameScheduler(() -> glSurfaceView.requestRender());

        // 接管通用 ToggleButton 的初始化与监听：默认显示并设初值，注册一次性监听器统一维护勾选状态。
        // 基类不感知开关的业务语义，子类通过 mToggleButtonChecked / onToggleButtonCheckedChanged 等钩子使用。
        mToggleButton = findViewById(R.id.toggleEnhance);
        if (mToggleButton != null) {
            mToggleButton.setVisibility(View.VISIBLE);
            mToggleButton.setChecked(mToggleButtonOn);
            mToggleButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
                mToggleButtonOn = isChecked;
                Log.i(TAG, "toggleButton -> " + isChecked);
                onToggleButtonCheckedChanged(isChecked);
                // 暂停期间切换开关时主动请求一次重绘，让画面立刻按新状态刷新；
                // 非 PAUSED / 缓存未就绪 / 编译期开关关闭等情况由钩子内部判定后早返回。
                requestPausePreviewIfNeeded();
            });
        }
    }

    private void initViews() {
        glSurfaceView = findViewById(R.id.glSurfaceView);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        mTvStatus1 = findViewById(R.id.tvStatus1);
        mTvStatus2 = findViewById(R.id.tvStatus2);
        mTvStatus2.setMovementMethod(ScrollingMovementMethod.getInstance());

        // 单按钮在 PLAYING ↔ PAUSED 间切换；IDLE 状态按下即播放
        btnPlayPause.setOnClickListener(v -> {
            if (currentState == PlaybackState.PLAYING) {
                pauseVideo();
            } else {
                playVideo();
            }
        });
        updateControls();

        glSurfaceView.setEGLContextClientVersion(3);// 设置 OpenGL ES 3.0 上下文（需要 GL_R8/GL_RG8 纹理格式）
        glSurfaceView.setRenderer(this);// 设置渲染器
        glSurfaceView.setKeepScreenOn(true);
        glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);// 按需渲染
    }

    private void playVideo() {
        if (currentState == PlaybackState.PLAYING) {
            return;
        }
        currentState = PlaybackState.PLAYING;
        updateControls();
        mMetrics.reset();
        mLastSnapshotMs = 0L;
        // 进入 PLAYING 时清空暂停缓存标志，让 onDrawFrame 回到正常主路径。
        // direct buffer 本身保留以便下次暂停时复用，不释放。
        if (ENABLE_PAUSE_PREVIEW) {
            mNeedCachePauseFrame = false;
            mPauseCacheReady = false;
        }
        if (mScheduler != null) {
            mScheduler.start();
        }
    }

    private void pauseVideo() {
        if (currentState == PlaybackState.PLAYING) {
            currentState = PlaybackState.PAUSED;
            updateControls();
            // 关键时序：必须在 mScheduler.stop() 之前 requestRender，让挂起的 GL 回调
            // 在主路径渲染末尾顺手把当前帧拷到缓存。stop() 之后再 requestRender 也能触发，
            // 但放在前面语义更明确。
            if (ENABLE_PAUSE_PREVIEW) {
                mNeedCachePauseFrame = true;
                glSurfaceView.requestRender();
            }
            if (mScheduler != null) {
                mScheduler.stop();
            }
        }
    }

    private void updateControls() {
        runOnUiThread(() -> {
            Log.i(TAG, "updateControls() currentState=" + currentState);
            // PLAYING -> 显示暂停图标；其余状态显示播放图标
            int iconRes = (currentState == PlaybackState.PLAYING)
                    ? android.R.drawable.ic_media_pause
                    : android.R.drawable.ic_media_play;
            btnPlayPause.setImageResource(iconRes);
        });
    }

    // GLSurfaceView.Renderer 方法实现
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated()");

        // 把 GL 线程（process() / 渲染都在这里跑）的 nice 抬到 DISPLAY 级（-4），
        // 减少被后台普通线程抢占；setThreadPriority 不传 tid 时作用于当前线程。
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);

        // 初始化 MediaCodec 解码器
        if (mDecoder != null) {
            mDecoder.release();
        }
        mDecoder = new Decode2Yuv();
        try {
            if (mVideoSource != null && mVideoSource.isLocalUri()) {
                mDecoder.init(this, mVideoSource.uri);
            } else {
                mDecoder.init(mVideoFilePath);
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to init Decode2Yuv", e);
            runOnUiThread(() -> Toast.makeText(this, "解码器初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }

        // 获取视频尺寸并通知子类
        mVideoWidth = mDecoder.getWidth();
        mVideoHeight = mDecoder.getHeight();
        Log.i(TAG, "onSurfaceCreated() video " + mVideoWidth + "x" + mVideoHeight);
        if (mVideoWidth > 0 && mVideoHeight > 0) {
            runOnUiThread(() -> onGetVideoSize(mVideoWidth, mVideoHeight));
        }

        // 初始化渲染器（需要先获取一帧来确定 stride 等参数，这里先创建对象，首帧再做 GL 资源 init）
        mNv12Renderer = new Nv12Renderer();
        // 重置 GL 资源 init 标志：从后台切回前台时，GLSurfaceView 会丢弃 EGL Context 并回调
        // onSurfaceCreated()，此时之前创建的纹理/program 已失效，必须让首帧 onDrawFrame 重新
        // 走 initRenderers 分支，否则使用未初始化的 mNv12Renderer 会黑屏。
        mRendererInitialized = false;
        mEnhancedYStride = 0;

        // 自动开始播放
        playVideo();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
        Log.d(TAG, "onSurfaceChanged() mViewWidth=" + mViewWidth + ", mViewHeight=" + mViewHeight);

        if (mNv12Renderer != null) {
            mNv12Renderer.onSurfaceChanged(mViewWidth, mViewHeight);
        }
        onRendererSurfaceChanged(mViewWidth, mViewHeight);
    }

    private volatile boolean mRendererInitialized = false; // Nv12Renderer 是否已初始化
    /** 首帧检测出的色彩空间。子类可读取同步给自己的渲染器。在 initRenderers() 之前赋值。 */
    protected Nv12Shaders.YuvColorSpace mDetectedColorSpace = Nv12Shaders.YuvColorSpace.BT601_LIMITED;

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mDecoder == null) {
            return;
        }
        // 非 PLAYING 状态下：
        //   - 暂停后第一帧（mNeedCachePauseFrame=true）：必须走主路径完成 dequeue + 缓存，
        //     这次渲染会把最后一帧上屏，但不计入 mMetrics（避免污染暂停瞬间的统计）。
        //   - 暂停中且缓存就绪（mPauseCacheReady=true）：走 drawPausePreview 预览分支。
        //   - 其它情况一律 return。
        boolean cachingTailFrame = false;
        if (currentState != PlaybackState.PLAYING) {
            if (ENABLE_PAUSE_PREVIEW && currentState == PlaybackState.PAUSED) {
                if (mNeedCachePauseFrame) {
                    cachingTailFrame = true; // 落到下面主路径
                } else if (mPauseCacheReady) {
                    drawPausePreview();
                    return;
                } else {
                    return;
                }
            } else {
                return;
            }
        }
        // 在最入口打点；末尾累加到 mMetrics。注意：dequeue 失败/yuvData==null 等早返回路径
        // 不计入帧耗时（与帧计数保持配对，避免分母不一致）。
        long frameStartMs = SystemClock.elapsedRealtime();

        // 从解码器获取一帧 NV12 数据
        if (!mDecoder.dequeueOutputFrame()) {
            // 无帧可用（正在解码中或循环播放重置），短暂延迟后重试
            mScheduler.onFrameUnavailable();
            return;
        }

        ByteBuffer yuvData = mDecoder.getCurrentOutputBuffer();
        int stride = mDecoder.getStride();
        int sliceHeight = mDecoder.getSliceHeight();
        int height = mDecoder.getHeight();

        if (yuvData == null) {
            mDecoder.releaseOutputFrame();
            mScheduler.onFrameUnavailable();
            return;
        }

        // 首帧时初始化渲染器（需要知道实际的 stride）
        if (!mRendererInitialized) {
            int videoW = mDecoder.getWidth();
            // 警告：该路径直接消费 MediaCodec NV12 输出，需要按视频实际色彩空间选一档。
            // 优先从 MediaFormat 读出明示字段，读不到时按分辨率启发式推断（>=720 走 BT.709，否则 BT.601 limited）。
            // OES 路径（OesYuvSplitter）不走这里，不会被影响。
            Nv12Shaders.YuvColorSpace cs = detectColorSpace(
                    mDecoder.getColorRange(), mDecoder.getColorStandard(), videoW);
            Log.i(TAG, "detect color space -> " + cs
                    + " (range=" + mDecoder.getColorRange()
                    + ", standard=" + mDecoder.getColorStandard()
                    + ", width=" + videoW + ")");
            if (mNv12Renderer != null) {
                // 1x 路径：Y 与 UV 严格满足 NV12 比例（UV = Y/2）
                mNv12Renderer.init(videoW, height, videoW / 2, height / 2);
                mNv12Renderer.setColorSpace(cs);
                mNv12Renderer.onSurfaceChanged(mViewWidth, mViewHeight);
            }
            // 子类可能自持额外的渲染器，同步告知色彩空间
            mDetectedColorSpace = cs;
            initRenderers(videoW, height);
            onRendererSurfaceChanged(mViewWidth, mViewHeight);
            mRendererInitialized = true;
        }

        // 处理 Y 通道（子类可重写 processYuv 进行 NPU 推理等增强处理）
        // 子类只需返回紧凑的增强 Y 平面（size = mVideoWidth * mVideoHeight），UV 由基类直接嗂给渲染器。
        long processStartMs = SystemClock.elapsedRealtime();
        ByteBuffer enhancedY = null;
        try {
            enhancedY = process(yuvData, stride, sliceHeight);
        } catch (Throwable t) {
            Log.e(TAG, "processYuv() threw, fallback to original Y", t);
        }
        long processMs = SystemClock.elapsedRealtime() - processStartMs;

        // 默认按"同尺寸 Y 处理"规则选择 Y 路参数。子类如需走超分等不同尺寸路径，请重写 renderFrame。
        ByteBuffer yRender;
        int yStrideRender;
        int yHeightRender;
        if (enhancedY != null) {
            int eStride = mEnhancedYStride > 0 ? mEnhancedYStride : mVideoWidth;
            int needed = eStride * (mVideoHeight - 1) + mVideoWidth;
            if (enhancedY.capacity() >= needed) {
                yRender = enhancedY;
                yStrideRender = eStride;
                yHeightRender = height;
            } else {
                Log.e(TAG, "process() returned buffer capacity=" + enhancedY.capacity()
                        + " < expected=" + needed + " (stride=" + eStride + "), fallback");
                yRender = yuvData;
                yStrideRender = stride;
                yHeightRender = height;
                enhancedY = null;
            }
        } else {
            yRender = yuvData;
            yStrideRender = stride;
            yHeightRender = height;
        }

        // 渲染 NV12：Y 路来自 yRender，UV 路从原 yuvData 的 stride*sliceHeight 偏移读取。
        // 关键时序约束：glTexSubImage2D 上传 UV 必须发生在 mDecoder.releaseOutputFrame() 之前，
        // 否则 yuvData 所引用的 MediaCodec buffer 已被归还，数据会失效。
        long renderStartMs = SystemClock.elapsedRealtime();
        renderFrame(yRender, yStrideRender, yHeightRender,
                yuvData, stride * sliceHeight, stride, height / 2,
                enhancedY);
        long renderMs = SystemClock.elapsedRealtime() - renderStartMs;

        // 暂停预览缓存：在 releaseOutputFrame() 之前拷贝 Y/UV，避免 buffer 已归还。
        // 仅当主线程 pauseVideo() 设了该标志才执行，正常播放阶段零开销。
        if (ENABLE_PAUSE_PREVIEW && mNeedCachePauseFrame) {
            cacheCurrentFrameForPause(yuvData, stride, sliceHeight, height);
            mNeedCachePauseFrame = false;
            mPauseCacheReady = true;
        }

        // UV 已上传纹理，可以安全归还解码器 buffer
        mDecoder.releaseOutputFrame();

        // 暂停尾帧分支：渲染 + 缓存已完成，跳过 mMetrics / 周期日志 / 调度，避免污染统计与重启调度。
        if (cachingTailFrame) {
            return;
        }

        // 累计本帧耗时与分段
        long frameMs = SystemClock.elapsedRealtime() - frameStartMs;
        LinkedHashMap<String, Long> segments = new LinkedHashMap<>();
        segments.put("process", processMs);
        segments.put("render", renderMs);
        mMetrics.addFrame(frameMs, segments);

        // 周期边界：聚合一次 + 输出日志 + 刷新状态栏
        long now = SystemClock.elapsedRealtime();
        if (mLastSnapshotMs == 0L) {
            mLastSnapshotMs = now;
        }
        long interval = now - mLastSnapshotMs;
        if (interval >= FrameMetrics.FPS_CAL_INTERVAL_MS) {
            String breakdown = mMetrics.snapshotAndReset(interval);
            String info = mMetrics.processInfo;
            Log.d(TAG, String.format(Locale.getDefault(),
                    "耗时: fps=%d total=%dms%s%s",
                    mMetrics.fps, mMetrics.totalMs, breakdown,
                    info.isEmpty() ? "" : " ProcessInfo" + info));
            updateStatusText();
            mLastSnapshotMs = now;
        }

        // 调度下一帧
        long currentPtsUs = mDecoder.getCurrentPresentationTimeUs();
        mScheduler.onFrameRendered(currentPtsUs);
    }

    /**
     * 每秒 FPS 统计周期里调用一次，降频刷新屏幕上的状态文案。
     *
     * <p>基类只输出播放器自身能感知的信息（FPS/耗时/尺寸），不感知"增强"等子类概念。
     * 子类如需附加信息（如"增强=开"），可直接 override 本方法自行组装。</p>
     *
     */
    protected void updateStatusText() {
        runOnUiThread(() -> {
            mTvStatus1.setText(String.format(Locale.getDefault(), "耗时=%dms process=%dms",
                    mMetrics.totalMs, mMetrics.processMs));
            mTvStatus2.setText(String.format(Locale.getDefault(),
                    "普通播放路径 | FPS=%d | 视频=%dx%d, 视图=%dx%d",
                    mMetrics.fps, mVideoWidth, mVideoHeight, mViewWidth, mViewHeight));
        });
    }

    /**
     * 每帧 Y 通道处理回调。子类可重写此方法进行 NPU 推理等增强处理。
     *
     * <p><b>契约：</b>本方法只负责 Y 通道增强，UV 由基类直接从原 {@code yBuffer} 读取并渲染。</p>
     *
     * <p>子类如需修改 Y：</p>
     * <ol>
     *     <li>自行持有并复用一个可写的 direct {@link ByteBuffer}；</li>
     *     <li>把增强后的 Y 平面写入该 buffer，可以是紧凑的（{@code stride == videoWidth}），
     *         也可以带行跨度（{@code stride > videoWidth}，例如模型档位 > 视频尺寸时直接复用模型输出 buffer）；</li>
     *     <li>若 buffer 带行跨度，必须设置 {@code mEnhancedYStride} 字段告诉基类；紧凑情况无需设置；</li>
     *     <li>返回该 buffer。基类用 {@code GL_UNPACK_ROW_LENGTH} 让 GL 自己抠左上角 videoW×videoH 上传。</li>
     * </ol>
     *
     * <p>返回 {@code null} 表示不做增强，基类会直接用原始 yBuffer 的 Y 部分（带 stride）渲染。</p>
     *
     * <p>buffer 容量需满足 {@code mEnhancedYStride * (videoH - 1) + videoW}，否则基类会打印错误并 fallback。</p>
     *
     * @param yBuffer      完整 NV12 buffer（可只读；本方法只读取 Y 部分）
     * @param yStride      Y 平面每行字节跨度（含 padding）
     * @param yHeight      Y 平面可安全读取的总行数（含 padding）
     * @return 增强 Y 平面 buffer；不处理返回 {@code null}
     */
    protected ByteBuffer process(ByteBuffer yBuffer, int yStride, int yHeight) {
        // 默认不做任何处理
        return null;
    }

    /**
     * 当播放器拿到视频实际宽高时回调。
     */
    protected void onGetVideoSize(int videoWidth, int videoHeight) {
        mVideoWidth = videoWidth;
        mVideoHeight = videoHeight;
        int viewWidth = glSurfaceView.getWidth();
        int viewHeight = glSurfaceView.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            Log.e(TAG, "onGetVideoSize() size invalid. viewWidth=" + viewWidth + ", viewHeight=" + viewHeight
                    + ", videoWidth=" + videoWidth + ", videoHeight=" + videoHeight);
            return;
        }

        float videoRatio = (float) videoWidth / videoHeight;
        float viewRatio = (float) viewWidth / viewHeight;

        // 更新 GLSurfaceView 的大小
        if (viewRatio > videoRatio) {
            // 视图更宽，按高度缩放
            int newWidth = (int) (viewHeight * videoRatio);
            glSurfaceView.getLayoutParams().width = newWidth;
            glSurfaceView.getLayoutParams().height = viewHeight;
        } else {
            // 视图更高，按宽度缩放
            int newHeight = (int) (viewWidth / videoRatio);
            glSurfaceView.getLayoutParams().width = viewWidth;
            glSurfaceView.getLayoutParams().height = newHeight;
        }

        glSurfaceView.requestLayout();// 会触发 onSurfaceChanged()
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause()");
        pauseVideo();
        glSurfaceView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume()");
        glSurfaceView.onResume();
        //playVideo();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 清理帧率调度，避免 Activity 销毁后还在 post requestRender
        if (mScheduler != null) {
            mScheduler.stop();
        }

        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }
        // 渲染器的 release 需要在 GL 线程执行
        glSurfaceView.queueEvent(() -> {
            if (mNv12Renderer != null) {
                mNv12Renderer.release();
                mNv12Renderer = null;
            }
            releaseRenderers();
            // 释放暂停预览缓存（GL 线程内安全释放，避免被 drawPausePreview 引用时被回收）
            if (ENABLE_PAUSE_PREVIEW) {
                mPauseYBuffer = null;
                mPauseUvBuffer = null;
                mPauseCacheReady = false;
                mNeedCachePauseFrame = false;
            }
        });
    }

    // ---- 渲染器钩子（子类可重写以替换/叠加渲染器） ----

    /**
     * 首帧拿到视频实际尺寸后调用。基类已自行 init 了内置的 {@link Nv12Renderer}，
     * 子类如需额外创建并 init 自己的渲染器（例如用 {@link Nv12Renderer} 配置 Y=2W×2H 的超分实例），可在此重写。
     *
     * <p>调用线程：GL 线程。</p>
     */
    protected void initRenderers(int videoWidth, int videoHeight) {
        // 默认无操作
    }

    /**
     * Surface 尺寸变化时调用。基类已自行通知内置 {@link Nv12Renderer}，
     * 子类如有额外渲染器，可在此重写转发尺寸。
     *
     * <p>调用线程：GL 线程。</p>
     */
    protected void onRendererSurfaceChanged(int viewWidth, int viewHeight) {
        // 默认无操作
    }

    /**
     * 把本帧上屏。默认实现使用基类内置的 {@link Nv12Renderer} 走 1x 路径渲染。
     * 子类如需根据 {@code processedY} 是否为空切换到不同的渲染器（例如超分），可重写本方法：
     * 当 {@code processedY != null} 时走自己的渲染器，否则 fallback 到 {@code super.renderFrame(...)}。
     *
     * @param yBuffer    要上传到 Y 纹理的 buffer
     * @param yStride    Y 行字节跨度
     * @param yHeight    Y 有效行数
     * @param uvBuffer   UV 所在的 buffer（基类传入原始 NV12 yuvData）
     * @param uvOffset   UV 起始偏移
     * @param uvStride   UV 行字节跨度
     * @param uvHeight   UV 有效行数
     * @param processedY 子类 {@link #process} 返回的 buffer；可能与 {@code yBuffer} 相同，也可能为 null
     */
    protected void renderFrame(ByteBuffer yBuffer, int yStride, int yHeight,
                               ByteBuffer uvBuffer, int uvOffset, int uvStride, int uvHeight,
                               ByteBuffer processedY) {
        if (mNv12Renderer != null) {
            mNv12Renderer.render(yBuffer, yStride, yHeight, uvBuffer, uvOffset, uvStride, uvHeight);
        }
    }

    /**
     * 释放子类自行持有的 GL 资源。基类内置的 {@link Nv12Renderer} 由基类负责释放。
     *
     * <p>调用线程：GL 线程。</p>
     */
    protected void releaseRenderers() {
        // 默认无操作
    }

    /**
     * 根据 {@link MediaFormat#KEY_COLOR_RANGE} / {@link MediaFormat#KEY_COLOR_STANDARD} 与视频宽度，
     * 推断 NV12→RGB 反向公式应该用的色彩空间。
     *
     * <p>策略：</p>
     * <ol>
     *     <li>codec 写了 KEY_COLOR_STANDARD，按 BT709/BT601 选；</li>
     *     <li>没写时按宽度启发式：≥1280 视为 BT.709（HD/FHD），否则 BT.601；</li>
     *     <li>codec 写了 KEY_COLOR_RANGE 且为 FULL，则匹配到 *_FULL 档；否则 limited 档。</li>
     * </ol>
     *
     * <p>这两个 key 对应的常量值（按 Android 平台定义）：</p>
     * <pre>
     *   COLOR_RANGE_LIMITED=2, COLOR_RANGE_FULL=1
     *   COLOR_STANDARD_BT709=1, COLOR_STANDARD_BT601_PAL=2, COLOR_STANDARD_BT601_NTSC=4, COLOR_STANDARD_BT2020=6
     * </pre>
     *
     * <p>BT.2020 暂统一回退到 BT.709，避免 HDR 场景因色彩管线不完整产生更大偏差。</p>
     */
    private static Nv12Shaders.YuvColorSpace detectColorSpace(int colorRange, int colorStandard, int videoWidth) {
        boolean is709;
        if (colorStandard == MediaFormat.COLOR_STANDARD_BT709
                || colorStandard == MediaFormat.COLOR_STANDARD_BT2020 /* 暂按 709 兜底，避免 HDR 流水线缺失放大偏差 */) {
            is709 = true;
        } else if (colorStandard == MediaFormat.COLOR_STANDARD_BT601_PAL
                || colorStandard == MediaFormat.COLOR_STANDARD_BT601_NTSC) {
            is709 = false;
        } else {
            // 没读到合法值（codec 未写 VUI）：按分辨率启发式
            is709 = videoWidth >= 1280;
        }
        boolean full = (colorRange == MediaFormat.COLOR_RANGE_FULL);
        if (is709) {
            return full ? Nv12Shaders.YuvColorSpace.BT709_FULL : Nv12Shaders.YuvColorSpace.BT709_LIMITED;
        } else {
            return full ? Nv12Shaders.YuvColorSpace.BT601_FULL : Nv12Shaders.YuvColorSpace.BT601_LIMITED;
        }
    }

    // ---- 通用 ToggleButton 钩子与业务时机封装 ----

    /**
     * toggle 勾选状态变化钩子。基类在监听回调里更新 {@link #mToggleButtonOn} 之后调用，默认空实现；
     * 子类如需在状态变化时同步业务字段或写日志可重写本方法。
     *
     * <p>调用线程：主线程。</p>
     */
    protected void onToggleButtonCheckedChanged(boolean isChecked) {
        // 默认无操作
    }

    /**
     * 标记 toggle 为"业务不支持"：关闭勾选、禁用控件、并禁止后续 {@link #markToggleReady()} 启用。
     * 不弹 Toast、不写业务日志，业务文案由子类自行处理。
     *
     * <p>调用线程：主线程。</p>
     */
    protected void markToggleUnsupported() {
        mToggleButtonEnable = false;
        if (mToggleButton != null) {
            mToggleButton.setChecked(false);
            mToggleButton.setEnabled(false);
        }
    }

    /**
     * 标记 toggle 为"业务可用"：在主线程把控件启用。
     * 已被 {@link #markToggleUnsupported()} 标记或 Activity 正在 finish 时不会启用，避免被错误恢复。
     *
     * <p>调用线程：任意线程（内部切回主线程执行）。</p>
     */
    protected void markToggleReady() {
        runOnUiThread(() -> {
            if (isFinishing() || !mToggleButtonEnable || mToggleButton == null) {
                return;
            }
            mToggleButton.setEnabled(true);
        });
    }

    // ---- 暂停预览：缓存与渲染 ----

    /**
     * 在 GL 线程把当前 NV12 帧整块拷贝到持久 direct buffer，供暂停期间预览使用。
     * 仅在主线程 pauseVideo() 设置 mNeedCachePauseFrame=true 后、对应一次 onDrawFrame 末尾调用。
     *
     * <p>拷贝时机必须在 mDecoder.releaseOutputFrame() 之前，否则源 buffer 已归还给 MediaCodec。</p>
     *
     * @param src         完整 NV12 buffer
     * @param stride      Y 行字节跨度（同时也是 NV12 中 UV 行跨度）
     * @param sliceHeight Y 平面行数（含 padding）
     * @param height      视频实际高度
     */
    private void cacheCurrentFrameForPause(ByteBuffer src, int stride, int sliceHeight, int height) {
        // Google Pixel 9 手机输出的 ByteBuffer capacity 会比理论值stride * sliceHeight + stride * (height/2) 略小（最后一行的 padding 被截掉）。
        // 例如 720P 视频，stride=768，src.capacity()只有1474512，最后一行数据没有 48 字节的 padding。
        // 这里所有访问都按 src.capacity() clamp，避免 buffer.limit() 抛 IllegalArgumentException。
        int srcCap = src.capacity();
        int yBytes = Math.min(stride * height, srcCap);                 // Y 实际拷贝字节
        int uvOffset = Math.min(stride * sliceHeight, srcCap);          // UV 起点（不超 capacity）
        int uvBytes = Math.min(stride * (height / 2), srcCap - uvOffset); // UV 实际可读字节
        if (uvBytes < 0) {
            uvBytes = 0;
        }

        // 容量不足或首次：按"实际拷贝字节"分配，后续 drawPausePreview 才能安全读到 limit。
        if (mPauseYBuffer == null || mPauseYBuffer.capacity() < yBytes) {
            mPauseYBuffer = ByteBuffer.allocateDirect(yBytes).order(java.nio.ByteOrder.nativeOrder());
        }
        if (uvBytes > 0 && (mPauseUvBuffer == null || mPauseUvBuffer.capacity() < uvBytes)) {
            mPauseUvBuffer = ByteBuffer.allocateDirect(uvBytes).order(java.nio.ByteOrder.nativeOrder());
        }

        // 通过 duplicate() 操作 position/limit，避免破坏调用方对 src 的 position 状态
        ByteBuffer view = src.duplicate();
        view.order(src.order());

        // 拷贝 Y 平面（含 stride padding，原样保留）
        view.position(0).limit(yBytes);
        mPauseYBuffer.position(0);
        mPauseYBuffer.put(view);
        mPauseYBuffer.position(0);

        // 拷贝 UV 平面：起点 stride * sliceHeight，长度 stride * (height/2)；
        // 上面已 clamp，此处 limit/position 不会越界。极端情况 uvBytes==0 时跳过。
        if (uvBytes > 0) {
            view.limit(uvOffset + uvBytes);
            view.position(uvOffset);
            mPauseUvBuffer.position(0);
            mPauseUvBuffer.put(view);
            mPauseUvBuffer.position(0);
        }

        mPauseYStride = stride;
        mPauseSliceHeight = sliceHeight;
        mPauseHeight = height;
        Log.d(TAG, "cacheCurrentFrameForPause() y=" + yBytes + " uv=" + uvBytes
                + " stride=" + stride + " sliceH=" + sliceHeight + " h=" + height
                + " srcCap=" + srcCap);
    }

    /**
     * 暂停期间的预览渲染：复用缓存的 NV12 数据按当前 mEnhance 状态走一遍 process + renderFrame。
     * 不累计 mMetrics、不更新 mLastSnapshotMs，避免污染性能统计。
     *
     * <p>调用线程：GL 线程。前置条件：mPauseCacheReady == true。</p>
     */
    private void drawPausePreview() {
        ByteBuffer enhancedY = null;
        try {
            enhancedY = process(mPauseYBuffer, mPauseYStride, mPauseSliceHeight);
        } catch (Throwable t) {
            Log.e(TAG, "drawPausePreview process() threw, fallback to original Y", t);
        }

        ByteBuffer yRender;
        int yStrideRender;
        int yHeightRender;
        if (enhancedY != null) {
            int eStride = mEnhancedYStride > 0 ? mEnhancedYStride : mVideoWidth;
            int needed = eStride * (mVideoHeight - 1) + mVideoWidth;
            if (enhancedY.capacity() >= needed) {
                yRender = enhancedY;
                yStrideRender = eStride;
                yHeightRender = mPauseHeight;
            } else {
                Log.e(TAG, "drawPausePreview() enhanced capacity=" + enhancedY.capacity()
                        + " < expected=" + needed + ", fallback");
                yRender = mPauseYBuffer;
                yStrideRender = mPauseYStride;
                yHeightRender = mPauseHeight;
                enhancedY = null;
            }
        } else {
            yRender = mPauseYBuffer;
            yStrideRender = mPauseYStride;
            yHeightRender = mPauseHeight;
        }

        // UV 平面在缓存中是从 0 开始的紧凑布局（uvOffset = 0），行跨度等于 mPauseYStride。
        renderFrame(yRender, yStrideRender, yHeightRender,
                mPauseUvBuffer, 0, mPauseYStride, mPauseHeight / 2,
                enhancedY);
    }

    /**
     * 子类钩子：在 toggle 回调里更新 mEnhance 之后调用，让暂停期间能立刻看到效果。
     *
     * <p>当 ENABLE_PAUSE_PREVIEW=false / 非 PAUSED / 缓存未就绪时直接返回，不触发 requestRender。</p>
     */
    protected void requestPausePreviewIfNeeded() {
        if (!ENABLE_PAUSE_PREVIEW) {
            return;
        }
        if (currentState != PlaybackState.PAUSED) {
            return;
        }
        if (!mPauseCacheReady) {
            return;
        }
        glSurfaceView.requestRender();
    }

    // 播放状态
    private enum PlaybackState { IDLE, PLAYING, PAUSED }
}
