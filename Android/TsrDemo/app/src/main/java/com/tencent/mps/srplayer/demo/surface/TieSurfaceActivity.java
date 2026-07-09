package com.tencent.mps.srplayer.demo.surface;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.common.Fullscreen;
import com.tencent.mps.srplayer.common.PassTimingMeter;
import com.tencent.mps.srplayer.common.video.ColorSpaceUtil;
import com.tencent.mps.srplayer.common.video.VideoProbe;
import com.tencent.mps.srplayer.common.video.VideoSource;
import com.tencent.mps.tie.api.TieBufferEnhancer.EnhancerType;
import com.tencent.mps.tie.api.TieSurface;
import com.tencent.mps.tie.api.TieTextureEnhancer;
import com.tencent.mps.tie.api.TieTextureEnhancer.Path;

import java.io.IOException;
import java.util.Locale;

/**
 * <b>TieSurface 全自动增强管线 Demo</b>：演示 {@link TieSurface} 组件的使用方式。
 *
 * <p>串联：MediaPlayer 解码 → {@code TieSurface.getInputSurface()} 接收 → 自动增强 → SurfaceView 显示。</p>
 *
 * <p>通过 Intent extra {@link #EXTRA_ENGINE_TYPE} 选择处理意图（{@code "IE_Y"} 同尺寸增强 →
 * {@link EnhancerType#IE_Y}；{@code "SR_Y"} 超分 → {@link EnhancerType#SR_Y}），
 * 缺省同尺寸增强。超分意图下实际倍率由 SDK 自动决策。</p>
 */
public class TieSurfaceActivity extends AppCompatActivity {

    private static final String TAG = "EnhanceSurfacePathAct";

    /** Intent extra：引擎类型字符串，对应 {@link EnhancerType#name()}（"IE_Y" / "SR_Y"），仅用于区分处理意图。 */
    public static final String EXTRA_ENGINE_TYPE = "engine_type";

    /** 处理意图（由 extra 解析）。 */
    private EnhancerType mEnhancerType;
    private SurfaceView mSurfaceView;
    private ImageButton mBtnPlayPause;
    private TextView mTvStatus1, mTvStatus2;
    private ToggleButton mToggleEnhance;

    private MediaPlayer mMediaPlayer;
    private TieSurface mEnhanceSurface;

    /** 视频来源（本地 URI 或内置路径）。 */
    private VideoSource mVideoSource;

    private int mVideoWidth, mVideoHeight;
    private int mViewWidth, mViewHeight;
    private boolean mPlaying = false;

    /** 当前增强能力档位（NPU/SHADER/NONE），由 InitCallback 回调设置，用于状态展示。 */
    private volatile TieTextureEnhancer.InitResult mInitResult;

    /** 渲染性能仪表（fps + SDK 耗时，GL 线程写、UI 线程读）。 */
    private final PassTimingMeter mTimingMeter = new PassTimingMeter();

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStatusTick = new Runnable() {
        @Override
        public void run() {
            // 性能指标由 GL 线程帧回调节流刷新 tvStatus1，这里只兜底刷新 tvStatus2（静态信息）
            refreshStatusText();
            mUiHandler.postDelayed(this, 1000L);
        }
    };

    // ============================================================
    // Activity 生命周期
    // ============================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }
        setContentView(R.layout.activity_enhance_surface_demo);
        Fullscreen.enable(this);

        // 解析引擎类型 extra → 处理意图 EnhancerType（缺省同尺寸增强；解析失败 finish）。
        // IE_Y → ENHANCE；SR_Y → SUPER_RESOLUTION（实际倍率由 EnhanceSurface/SDK 动态决策）。
        Intent intent = getIntent();
        String typeName = intent != null ? intent.getStringExtra(EXTRA_ENGINE_TYPE) : null;
        EnhancerType intentType;
        if (typeName == null || typeName.isEmpty()) {
            intentType = EnhancerType.IE_Y;
        } else {
            try {
                intentType = EnhancerType.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "不支持的增强模式：" + typeName, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        mEnhancerType = intentType;
        Log.i(TAG, "engine type=" + mEnhancerType);

        // 视频来源解析：优先本地 URI，其次内置文件名
        mVideoSource = VideoSource.from(this);
        Log.i(TAG, "use video: " + mVideoSource.displayName);

        if (!mVideoSource.exists(this)) {
            Toast.makeText(this, "无法读取视频：" + mVideoSource.displayName, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
    }

    private void initViews() {
        mSurfaceView = findViewById(R.id.surfaceView);
        mBtnPlayPause = findViewById(R.id.btnPlayPause);
        mTvStatus1 = findViewById(R.id.tvStatus1);
        mTvStatus2 = findViewById(R.id.tvStatus2);

        mBtnPlayPause.setOnClickListener(v -> {
            if (mPlaying) {
                if (mMediaPlayer != null) mMediaPlayer.pause();
                mPlaying = false;
                mBtnPlayPause.setImageResource(android.R.drawable.ic_media_play);
                Log.i(TAG, "pause");
            } else {
                if (mMediaPlayer != null) mMediaPlayer.start();
                mPlaying = true;
                mBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                Log.i(TAG, "resume");
            }
        });
        mToggleEnhance = findViewById(R.id.toggleEnhance);
        mToggleEnhance.setVisibility(View.VISIBLE);
        // 按意图设置开关文案：处理意图用"增强"、超分意图用"超分"
        mToggleEnhance.setTextOn(mEnhancerType != EnhancerType.IE_Y ? "超分 开" : "增强 开");
        mToggleEnhance.setTextOff(mEnhancerType != EnhancerType.IE_Y ? "超分 关" : "增强 关");
        // ToggleButton 文案需要在 set/Off 后重新刷一次显示文本
        mToggleEnhance.setChecked(false);
        mToggleEnhance.setOnCheckedChangeListener((btn, isChecked) -> {
            if (mEnhanceSurface != null) {
                mEnhanceSurface.setEnhanceEnabled(isChecked);
            }
            if (!isChecked) mTimingMeter.reset();
            refreshStatusText();
            Log.i(TAG, "toggleEnhance -> " + isChecked);
        });

        // SurfaceView 生命周期与 MediaPlayer / EnhanceSurface 绑定
        mSurfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Log.i(TAG, "surfaceCreated");
                startPlay(holder);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                mViewWidth = width;
                mViewHeight = height;
                Log.i(TAG, "surfaceChanged " + width + "x" + height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                Log.i(TAG, "surfaceDestroyed");
                if (mEnhanceSurface != null) {
                    mEnhanceSurface.setOutputSurface(null);
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        mUiHandler.removeCallbacks(mStatusTick);
        if (mMediaPlayer != null) {
            try {
                mMediaPlayer.stop();
            } catch (IllegalStateException ignore) {
            }
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        if (mEnhanceSurface != null) {
            mEnhanceSurface.release();
            mEnhanceSurface = null;
        }
    }

    // ============================================================
    // 播放启动：surfaceCreated 时调用
    // ============================================================

    private void startPlay(SurfaceHolder holder) {
        // 0) 探测视频颜色空间（用 MediaExtractor 轻量读出 color range/standard，不创建 codec）
        TieTextureEnhancer.ColorStandard colorStd = TieTextureEnhancer.ColorStandard.BT601_LIMITED;
        int probeW = 0, probeH = 0;
        try {
            VideoProbe.ColorInfo info = (mVideoSource != null && mVideoSource.isLocalUri())
                    ? VideoProbe.probe(this, mVideoSource.uri)
                    : VideoProbe.probe(mVideoSource.filePath);
            probeW = info.width;
            probeH = info.height;
            colorStd = ColorSpaceUtil.detectForSdk(info.colorRange, info.colorStandard, probeW);
        } catch (IOException | IllegalStateException e) {
            Log.w(TAG, "probe video color info failed, fallback to BT601_LIMITED", e);
        }
        Log.i(TAG, "video " + probeW + "x" + probeH + " colorStd=" + colorStd);

        // 1) 创建 EnhanceSurface（构造时同步在内部 GL 线程创建 EGL/OES/SurfaceTexture/inputSurface）
        mEnhanceSurface = new TieSurface(getApplicationContext(), mEnhancerType, colorStd);
        mEnhanceSurface.setInitCallback(result -> {
            mInitResult = result;
            if (canEnhance()) {
                mToggleEnhance.setEnabled(true);
            } else {
                Toast.makeText(TieSurfaceActivity.this,
                        "当前分辨率 " + mVideoWidth + "x" + mVideoHeight + " 不支持 "
                                + (mEnhancerType != EnhancerType.IE_Y ? "超分" : "增强") + " (" + pathText(result.path) + ")",
                        Toast.LENGTH_LONG).show();
                mToggleEnhance.setChecked(false);
                mToggleEnhance.setEnabled(false);
                if (mEnhanceSurface != null) {
                    mEnhanceSurface.setEnhanceEnabled(false);
                }
            }
            refreshStatusText();
        });
        // 帧处理回调（GL 线程）：记录帧 + SDK 耗时 + 路径，节流刷新 tvStatus1
        mEnhanceSurface.setOnFrameListener(processMs -> {
            mTimingMeter.recordFrame();
            if (processMs > 0) {
                mTimingMeter.recordMs(processMs);
            }
            if (mTimingMeter.shouldUpdateUi()) {
                final String text = buildPerfText();
                runOnUiThread(() -> {
                    if (mTvStatus1 != null && !isFinishing()) mTvStatus1.setText(text);
                });
            }
        });
        mEnhanceSurface.setEnhanceEnabled(mToggleEnhance.isChecked());

        // 2) 设置 output Surface
        mEnhanceSurface.setOutputSurface(holder.getSurface());

        // 3) 创建并启动 MediaPlayer：把它的输出 Surface 设为 EnhanceSurface 的 inputSurface
        mMediaPlayer = new MediaPlayer();
        try {
            if (mVideoSource != null && mVideoSource.isLocalUri()) {
                mMediaPlayer.setDataSource(this, mVideoSource.uri);
            } else {
                mMediaPlayer.setDataSource(mVideoSource.filePath);
            }
            mMediaPlayer.setSurface(mEnhanceSurface.getInputSurface());
            mMediaPlayer.setLooping(true);
            mMediaPlayer.setOnVideoSizeChangedListener((mp, w, h) -> {
                Log.i(TAG, "OnVideoSizeChanged " + w + "x" + h);
                mVideoWidth = w;
                mVideoHeight = h;
                adjustSurfaceViewSize(w, h);
                if (mEnhanceSurface != null) {
                    mEnhanceSurface.setInputSize(w, h);
                }
                refreshStatusText();
            });
            mMediaPlayer.setOnPreparedListener(mp -> {
                Log.i(TAG, "MediaPlayer onPrepared");
                mp.start();
                mPlaying = true;
                runOnUiThread(() -> mBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause));
            });
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer onError what=" + what + " extra=" + extra);
                runOnUiThread(() -> Toast.makeText(this,
                        "播放出错，请重试", Toast.LENGTH_LONG).show());
                return true;
            });
            mMediaPlayer.prepareAsync();
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "MediaPlayer init failed", e);
            Toast.makeText(this, "MediaPlayer 初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 启动周期状态刷新
        mUiHandler.removeCallbacks(mStatusTick);
        mUiHandler.post(mStatusTick);
    }

    // ============================================================
    // 视图与状态栏
    // ============================================================

    /**
     * 按视频纵横比把 SurfaceView 缩到合适大小。横屏时 layout_gravity=start 贴左，竖屏时居中。
     */
    private void adjustSurfaceViewSize(int videoWidth, int videoHeight) {
        int viewWidth = mSurfaceView.getWidth();
        int viewHeight = mSurfaceView.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            Log.w(TAG, "adjustSurfaceViewSize() invalid sizes view=" + viewWidth + "x" + viewHeight
                    + " video=" + videoWidth + "x" + videoHeight);
            return;
        }
        float videoRatio = (float) videoWidth / videoHeight;
        float viewRatio = (float) viewWidth / viewHeight;
        if (viewRatio > videoRatio) {
            mSurfaceView.getLayoutParams().width = (int) (viewHeight * videoRatio);
            mSurfaceView.getLayoutParams().height = viewHeight;
        } else {
            mSurfaceView.getLayoutParams().width = viewWidth;
            mSurfaceView.getLayoutParams().height = (int) (viewWidth / videoRatio);
        }
        mSurfaceView.requestLayout();
    }

    /** 把能力档位 Path 翻译成状态栏上的简短人类可读文案。 */
    private static String pathText(Path path) {
        if (path == null) return "init中";
        switch (path) {
            case NPU:
                return "NPU ready";
            case SHADER:
                return "GPU ready";
            case NONE:
                return "init失败";
            default:
                return "init中";
        }
    }

    /** 刷新 tvStatus2（静态信息）。 */
    private void refreshStatusText() {
        runOnUiThread(() -> {
            if (mTvStatus2 != null && !isFinishing()) mTvStatus2.setText(buildStaticText());
        });
    }

    /** tvStatus2：静态信息。init 失败（不支持/失败）时优先显示原因。 */
    private String buildStaticText() {
        String typeLabel;
        if (mEnhancerType == EnhancerType.IE_Y) {
            typeLabel = "IE_Y";
        } else {
            float upscale = getUpscale();
            typeLabel = (upscale > 0) ? ("SR_Y x" + upscale) : "SR_Y";
        }
        if (!canEnhance() && mInitResult != null) {
            return String.format(Locale.getDefault(),
                    "TieSurface[%s] | video=%dx%d | view=%dx%d | %s",
                    typeLabel, mVideoWidth, mVideoHeight, mViewWidth, mViewHeight,
                    pathText(mInitResult.path));
        }
        return String.format(Locale.getDefault(),
                "TieSurface[%s] | video=%dx%d | view=%dx%d | engine=%s | enhance=%s",
                typeLabel, mVideoWidth, mVideoHeight, mViewWidth, mViewHeight,
                pathText(getEnhancePath()), mToggleEnhance.isChecked() ? "ON" : "OFF");
    }

    /** tvStatus1：动态性能指标（fps + SDK 耗时 + 能力档位，四舍五入到整数）。 */
    private String buildPerfText() {
        long fps = mTimingMeter.getFpsRounded();
        long avg = mTimingMeter.getAvgMsRounded();
        String fpsStr = fps < 0 ? "—" : String.valueOf(fps);
        String timing = avg < 0 ? "—" : avg + "ms";
        Path path = getEnhancePath();
        String pathStr = path == null ? "—" : path.name();
        return "fps=" + fpsStr + " | 耗时=" + timing + " | 路径=" + pathStr;
    }

    // ============================================================
    // InitResult 派生辅助方法
    // ============================================================

    /** 当前是否可增强（InitResult 已设置且路径非 NONE）。 */
    private boolean canEnhance() {
        return mInitResult != null && mInitResult.path != Path.NONE;
    }

    /** 获取当前增强路径，未初始化返回 null。 */
    private Path getEnhancePath() {
        return mInitResult != null ? mInitResult.path : null;
    }

    /** 获取当前增强倍率（outputWidth / videoWidth），不可用时返回 0。 */
    private float getUpscale() {
        if (mInitResult == null || mInitResult.path == Path.NONE) return 0;
        if (mVideoWidth <= 0 || mInitResult.outputWidth <= 0) return 0;
        return mInitResult.outputWidth * 1.0f / mVideoWidth;
    }
}
