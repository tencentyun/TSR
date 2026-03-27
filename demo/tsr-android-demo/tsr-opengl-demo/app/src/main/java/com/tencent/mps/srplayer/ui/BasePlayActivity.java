package com.tencent.mps.srplayer.ui;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.pass.Nv12Renderer;
import com.tencent.mps.srplayer.utils.AssetsFileCopier;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// 演示一个基本的播放页面：使用 MediaCodec ByteBuffer 模式解码 mp4 文件，输出 NV12 数据，渲染到 GLSurfaceView 上。
public class BasePlayActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "BasePlayActivity";
    private static final long FPS_CAL_INTERVAL = 1000L; // 计算一次FPS的时间间隔
    private static final float LOW_FPS_THRESHOLD = 20.0f; // FPS阈值
    private static final long LOW_FPS_DURATION = 3000L; // 连续低FPS持续时间（3秒）

    protected int mViewWidth, mViewHeight, mVideoWidth, mVideoHeight;
    protected volatile boolean mEnableSimulationLowFps = false; // 开启模拟低帧率，false 为不开启
    protected GLSurfaceView glSurfaceView;
    private Button btnPlay, btnPause, btnStop;
    protected TextView mTvStatus1, mTvStatus2;
    private PlaybackState currentState = PlaybackState.IDLE;

    // MediaCodec 解码器和 NV12 渲染器
    private Decode2Yuv mDecoder;
    private Nv12Renderer mNv12Renderer;
    private String mVideoFilePath; // 视频文件路径

    /// 性能计算相关变量
    private long mLastFpsUpdateTime;// 上次更新 FPS 的时间
    private int mFrameCount;// 帧计数，用于计算 FPS
    protected float mCurrentFps;// 当前FPS
    private long mLowFpsStartTime; // 开始低FPS的时间点
    private boolean mOnLowFpsCalled; // 是否已经显示过低FPS提示
    private long mProcessTimeAccumulatedMs; // 累计处理耗时（毫秒）
    protected long mProcessAverageTimeMs; // 每秒统计的平均耗时（毫秒）

    /// 帧率调度相关变量
    private Handler mRenderHandler; // 主线程 Handler，用于定时调度 requestRender()
    private long mPlayStartTimeMs = -1; // 播放起始墙钟时间（毫秒）
    private long mFirstFramePtsUs = -1; // 首帧 PTS（微秒）
    private long mLastFramePtsUs = -1; // 上一帧 PTS（微秒），用于检测循环播放
    private volatile boolean mSchedulingActive = false; // 帧率调度是否激活
    private static final long RETRY_DELAY_MS = 5L; // 无帧可用时的重试延迟（毫秒）

    /** 渲染调度 Runnable：检查调度标志位，若激活则触发 requestRender() */
    private final Runnable mRenderRunnable = () -> {
        if (mSchedulingActive) {
            glSurfaceView.requestRender();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        setContentView(R.layout.activity_simple_demo);

        // 将视频文件从 assets 拷贝到外部存储
        File videoFile = AssetsFileCopier.copyAssetToExternalFilesDir(this, "720x1280.mp4");
        mVideoFilePath = videoFile.getAbsolutePath();

        initViews();
    }

    private void initViews() {
        glSurfaceView = findViewById(R.id.glSurfaceView);
        btnPlay = findViewById(R.id.btnPlay);
        btnPause = findViewById(R.id.btnPause);
        btnStop = findViewById(R.id.btnStop);
        mTvStatus1 = findViewById(R.id.tvStatus1);
        mTvStatus2 = findViewById(R.id.tvStatus2);
        mTvStatus2.setMovementMethod(ScrollingMovementMethod.getInstance());

        btnPlay.setOnClickListener(v -> playVideo());
        btnPause.setOnClickListener(v -> pauseVideo());
        btnStop.setOnClickListener(v -> stopVideo());
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
        resetMetric();

        // 启动帧率调度
        mSchedulingActive = true;
        mPlayStartTimeMs = -1;
        mFirstFramePtsUs = -1;
        mLastFramePtsUs = -1;
        ensureRenderHandler();
        mRenderHandler.post(mRenderRunnable);
    }

    private void pauseVideo() {
        if (currentState == PlaybackState.PLAYING) {
            currentState = PlaybackState.PAUSED;
            updateControls();
            // 停止帧率调度
            stopScheduling();
        }
    }

    private void stopVideo() {
        if (currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED) {
            currentState = PlaybackState.STOPPED;
            updateControls();
            // 停止帧率调度
            stopScheduling();
        }
    }

    private void updateControls() {
        runOnUiThread(() -> {
            Log.i(TAG, "updateControls() currentState=" + currentState);
            btnPlay.setEnabled(currentState != PlaybackState.PLAYING && currentState != PlaybackState.PREPARING);
            btnPause.setEnabled(currentState == PlaybackState.PLAYING);
            btnStop.setEnabled(currentState == PlaybackState.PLAYING || currentState == PlaybackState.PAUSED);
        });
    }

    // 重置性能指标的计算
    private void resetMetric() {
        // 重置FPS计算状态
        mLastFpsUpdateTime = 0L;
        mFrameCount = 0;
        mCurrentFps = 0;

        // 重置低FPS的检测状态
        mLowFpsStartTime = 0;
        mOnLowFpsCalled = false;

        // 重置处理耗时计算状态
        mProcessTimeAccumulatedMs = 0L;
        mProcessAverageTimeMs = 0L;
    }

    // GLSurfaceView.Renderer 方法实现
    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated()");

        // 初始化 MediaCodec 解码器
        if (mDecoder != null) {
            mDecoder.release();
        }
        mDecoder = new Decode2Yuv();
        try {
            mDecoder.init(mVideoFilePath);
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

        // 初始化 Nv12Renderer（需要先获取一帧来确定 stride 等参数，这里先用 width 初始化，后续在首帧时重新初始化）
        mNv12Renderer = new Nv12Renderer();

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
    }

    private volatile boolean mRendererInitialized = false; // Nv12Renderer 是否已初始化

    @Override
    public void onDrawFrame(GL10 gl) {
        if (currentState != PlaybackState.PLAYING || mDecoder == null) {
            return;
        }

        // 从解码器获取一帧 NV12 数据
        if (!mDecoder.dequeueOutputFrame()) {
            // 无帧可用（正在解码中或循环播放重置），短暂延迟后重试
            scheduleRetryRender();
            return;
        }

        ByteBuffer yuvData = mDecoder.getCurrentOutputBuffer();
        int stride = mDecoder.getStride();
        int sliceHeight = mDecoder.getSliceHeight();
        int height = mDecoder.getHeight();

        if (yuvData == null) {
            mDecoder.releaseOutputFrame();
            scheduleRetryRender();
            return;
        }

        // 首帧时初始化 Nv12Renderer（需要知道实际的 stride）
        if (!mRendererInitialized && mNv12Renderer != null) {
            mNv12Renderer.init(mDecoder.getWidth(), height);
            mNv12Renderer.onSurfaceChanged(mViewWidth, mViewHeight);
            mRendererInitialized = true;
        }

        // 计算FPS
        calculateFps();

        // 处理 YUV 数据（子类可重写 processYuv 进行 NPU 推理等处理）
        long processStart = System.currentTimeMillis();
        processYuv(yuvData, stride, height);
        mProcessTimeAccumulatedMs += System.currentTimeMillis() - processStart;

        // 渲染 NV12 数据到屏幕
        if (mNv12Renderer != null) {
            mNv12Renderer.render(yuvData, stride, sliceHeight, height);
        }

        // 释放解码器输出 buffer
        mDecoder.releaseOutputFrame();

        // 根据当前帧 PTS 调度下一帧渲染
        long currentPtsUs = mDecoder.getCurrentPresentationTimeUs();

        // 检测循环播放：当前帧 PTS 小于上一帧 PTS，说明视频已循环，需要重置时间基准
        if (mLastFramePtsUs >= 0 && currentPtsUs < mLastFramePtsUs) {
            Log.i(TAG, "检测到循环播放，重置时间基准。lastPts=" + mLastFramePtsUs + " currentPts=" + currentPtsUs);
            mPlayStartTimeMs = -1;
            mFirstFramePtsUs = -1;
        }
        mLastFramePtsUs = currentPtsUs;

        scheduleNextRender(currentPtsUs);

        // 模拟低FPS情况，仅用于测试
        simulateLowFpsForTesting();
    }

    /**
     * 计算并更新FPS
     */
    private void calculateFps() {
        mFrameCount++;
        if (mLastFpsUpdateTime == 0L) {
            mLastFpsUpdateTime = System.currentTimeMillis();
        }
        long currentTime = System.currentTimeMillis();
        long timeInterval = currentTime - mLastFpsUpdateTime;

        if (timeInterval >= FPS_CAL_INTERVAL) {
            // 计算FPS
            mCurrentFps = (float) mFrameCount * 1000 / timeInterval;
            int framesThisInterval = mFrameCount;
            mProcessAverageTimeMs = mProcessTimeAccumulatedMs / framesThisInterval;

            // 检测低FPS情况
            checkLowFps(currentTime);

            // 重置计数器和时间
            mFrameCount = 0;
            mLastFpsUpdateTime = currentTime;
            mProcessTimeAccumulatedMs = 0L;

            //Log.i(TAG, "帧率=" + mCurrentFps + ", 帧耗时=" + mProcessAverageTimeMs + " ms");
        }
    }

    /**
     * 每帧 YUV 数据处理回调。子类可重写此方法进行 NPU 推理等处理。
     * 处理是原地更新的，直接修改 yuvData 中的 Y 平面数据即可。
     *
     * @param yuvData NV12 格式的完整 YUV 数据
     * @param stride  Y 平面每行的字节跨度
     * @param height  视频高度
     */
    protected void processYuv(ByteBuffer yuvData, int stride, int height) {
        // 默认不做任何处理
    }

    // 模拟低FPS情况（仅用于测试）
    // 注意：通过增大下一帧调度延迟来模拟低帧率，不再使用 Thread.sleep() 阻塞 GL 线程
    private int mSimulatedExtraDelayMs = 0; // 模拟低帧率时附加的额外延迟

    private void simulateLowFpsForTesting() {
        if (!mEnableSimulationLowFps || currentState != PlaybackState.PLAYING) {
            mSimulatedExtraDelayMs = 0;
            return;
        }

        // 随机决定是否模拟低FPS（50%概率）
        if (Math.random() < 0.5) {
            mSimulatedExtraDelayMs = 0;
            return; // 50%的概率不增加延迟，保持正常FPS
        }

        // 随机额外延迟：50-100毫秒，这会将FPS降低到10-20帧
        mSimulatedExtraDelayMs = 50 + (int) (Math.random() * 50);
        Log.v(TAG, "模拟低FPS: 额外延迟 " + mSimulatedExtraDelayMs + "ms");
    }

    /**
     * 检测低FPS情况
     */
    private void checkLowFps(long currentTime) {
        // 只有在播放状态下才检测低FPS
        if (currentState != PlaybackState.PLAYING) {
            return;
        }

        if (mCurrentFps < LOW_FPS_THRESHOLD) {
            // FPS低于阈值
            if (mLowFpsStartTime == 0) {
                // 第一次检测到低FPS，记录开始时间
                mLowFpsStartTime = currentTime;
                Log.d(TAG, "开始检测到低FPS: " + mCurrentFps);
            } else {
                // 持续低FPS，检查持续时间
                long lowFpsDuration = currentTime - mLowFpsStartTime;
                if (lowFpsDuration >= LOW_FPS_DURATION && !mOnLowFpsCalled) {
                    // 连续低FPS超过3秒，显示Toast提示
                    onLowFps();
                    mOnLowFpsCalled = true;
                    Log.w(TAG, "连续低FPS超过3秒: " + mCurrentFps);
                }
            }
        } else {
            // FPS恢复正常，重置检测状态
            if (mLowFpsStartTime != 0) {
                Log.d(TAG, "FPS恢复正常: " + mCurrentFps);
                mLowFpsStartTime = 0;
                mOnLowFpsCalled = false;
            }
        }
    }

    /**
     * 当检测到帧率过低时回调。
     * 子类可以重写此方法，处理低FPS情况。
     */
    protected void onLowFps() {
        runOnUiThread(() -> Toast.makeText(BasePlayActivity.this,
                String.format(Locale.getDefault(), "帧率过低！当前FPS: %d", Math.round(mCurrentFps)), Toast.LENGTH_SHORT).show());
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

        // 清理帧率调度相关资源，避免内存泄漏
        stopScheduling();
        if (mRenderHandler != null) {
            mRenderHandler.removeCallbacksAndMessages(null);
            mRenderHandler = null;
        }

        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }
        // Nv12Renderer 的 release 需要在 GL 线程执行
        if (mNv12Renderer != null) {
            glSurfaceView.queueEvent(() -> {
                if (mNv12Renderer != null) {
                    mNv12Renderer.release();
                    mNv12Renderer = null;
                }
            });
        }
    }

    // ---- 帧率调度相关方法 ----

    /** 确保 mRenderHandler 已初始化 */
    private void ensureRenderHandler() {
        if (mRenderHandler == null) {
            mRenderHandler = new Handler(Looper.getMainLooper());
        }
    }

    /** 停止帧率调度 */
    private void stopScheduling() {
        mSchedulingActive = false;
        if (mRenderHandler != null) {
            mRenderHandler.removeCallbacks(mRenderRunnable);
        }
    }

    /**
     * 根据当前帧 PTS 调度下一帧渲染。
     * 计算当前帧 PTS 对应的墙钟时间与实际已过时间的差值，得出下一帧应该在何时渲染。
     *
     * @param currentPtsUs 当前帧的 PTS（微秒）
     */
    private void scheduleNextRender(long currentPtsUs) {
        if (!mSchedulingActive) {
            return;
        }

        // 首帧：记录时间基准，立即调度下一帧
        if (mPlayStartTimeMs < 0) {
            mPlayStartTimeMs = System.currentTimeMillis();
            mFirstFramePtsUs = currentPtsUs;
            ensureRenderHandler();
            mRenderHandler.post(mRenderRunnable);
            return;
        }

        // 计算当前帧 PTS 对应的理论播放时间点（相对于首帧）
        long framePtsMs = (currentPtsUs - mFirstFramePtsUs) / 1000;
        // 计算实际已过去的墙钟时间
        long elapsedMs = System.currentTimeMillis() - mPlayStartTimeMs;
        // 延迟 = 理论时间 - 实际时间
        long delayMs = framePtsMs - elapsedMs;

        // 加上模拟低帧率的额外延迟（仅测试用）
        if (mSimulatedExtraDelayMs > 0) {
            delayMs += mSimulatedExtraDelayMs;
        }

        ensureRenderHandler();
        if (delayMs <= 0) {
            // 已经落后于预期时间，立即渲染
            mRenderHandler.post(mRenderRunnable);
        } else {
            mRenderHandler.postDelayed(mRenderRunnable, delayMs);
        }
    }

    /**
     * 当 dequeueOutputFrame() 返回 false（无帧可用）时，短暂延迟后重试 requestRender()。
     */
    private void scheduleRetryRender() {
        if (!mSchedulingActive) {
            return;
        }
        ensureRenderHandler();
        mRenderHandler.postDelayed(mRenderRunnable, RETRY_DELAY_MS);
    }

    // 播放状态
    private enum PlaybackState {
        IDLE, PREPARING, PREPARED, PLAYING, PAUSED, STOPPED, ERROR
    }
}