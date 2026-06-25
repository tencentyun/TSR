package com.tencent.mps.srplayer.demo.enhancesurface;

import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.srplayer.HomeActivity;
import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.common.Fullscreen;
import com.tencent.mps.srplayer.common.VideoSource;
import com.tencent.mps.tie.api.TieEngine;

import java.io.IOException;
import java.util.Locale;

/**
 * <b>EnhanceSurface 封装路径 demo</b>：演示 {@link EnhanceSurface} 组件的使用方式。
 *
 * <p>串联：MediaPlayer 解码 → {@code EnhanceSurface.getInputSurface()} 接收 → EnhanceSurface 内部
 * 完成"OES → Y/UV 拆分 → TieEngine 处理 → NV12 上屏" → SurfaceView 显示。</p>
 *
 * <p>通过 Intent extra {@link #EXTRA_ENGINE_TYPE} 选择处理意图（{@code "IE_Y"} 同尺寸增强 →
 * {@link TieEngine.Type#IE_Y}；{@code "SR_Y"} 超分 → {@link TieEngine.Type#SR_Y}），
 * 缺省同尺寸增强。<b>超分意图下实际倍率（2×/3×）由 {@link EnhanceSurface} 拿到视频尺寸后动态决策</b>，
 * 与 ByteBuffer 超分路径一致。</p>
 */
public class EnhanceSurfacePathActivity extends AppCompatActivity {

    private static final String TAG = "EnhanceSurfacePathAct";

    /** Intent extra：引擎类型字符串，对应 {@link TieEngine.Type#name()}（"IE_Y" / "SR_Y"），仅用于区分处理意图。 */
    public static final String EXTRA_ENGINE_TYPE = "engine_type";

    /** 处理意图（由 extra 解析）。 */
    private TieEngine.Type mMode;
    /** 是否超分意图，用于开关文案。 */
    private boolean mIsSr;

    private SurfaceView mSurfaceView;
    private TextView mTvStatus1, mTvStatus2;
    private ToggleButton mToggleEnhance;

    private String mVideoFilePath;

    private MediaPlayer mMediaPlayer;
    private EnhanceSurface mEnhanceSurface;

    /** 视频来源（本地 URI 或内置路径）。 */
    private VideoSource mVideoSource;

    private int mVideoWidth, mVideoHeight;
    private int mViewWidth, mViewHeight;
    private boolean mCanEnhance = false;
    private int mReason = EnhanceSurface.REASON_NONE; // 仅当 mCanEnhance=false 时有意义
    private boolean mUiEnhance = false;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStatusTick = new Runnable() {
        @Override
        public void run() {
            updateStatusText();
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

        // 解析引擎类型 extra → 处理意图 Mode（缺省同尺寸增强；解析失败 finish）。
        // IE_Y → ENHANCE；SR_Y → SUPER_RESOLUTION（实际倍率由 EnhanceSurface/SDK 动态决策）。
        Intent intent = getIntent();
        String typeName = intent != null ? intent.getStringExtra(EXTRA_ENGINE_TYPE) : null;
        TieEngine.Type intentType;
        if (typeName == null || typeName.isEmpty()) {
            intentType = TieEngine.Type.IE_Y;
        } else {
            try {
                intentType = TieEngine.Type.valueOf(typeName);
            } catch (IllegalArgumentException e) {
                Toast.makeText(this, "未知的引擎类型：" + typeName, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }
        mIsSr = (intentType != TieEngine.Type.IE_Y);
        mMode = intentType;
        Log.i(TAG, "engine type=" + mMode);

        // 视频来源解析：优先本地 URI，其次内置文件名
        mVideoSource = VideoSource.from(this);
        mVideoFilePath = mVideoSource.filePath; // 本地 URI 时为 null
        Log.i(TAG, "use video: " + mVideoSource.displayName);

        if (!mVideoSource.exists(this)) {
            Toast.makeText(this, "视频不可读取：" + mVideoSource.displayName, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
    }

    private void initViews() {
        mSurfaceView = findViewById(R.id.surfaceView);
        mTvStatus1 = findViewById(R.id.tvStatus1);
        mTvStatus2 = findViewById(R.id.tvStatus2);
        mToggleEnhance = findViewById(R.id.toggleEnhance);
        mToggleEnhance.setVisibility(View.VISIBLE);
        // 按意图设置开关文案：处理意图用"增强"、超分意图用"超分"
        mToggleEnhance.setTextOn(mIsSr ? "超分 开" : "增强 开");
        mToggleEnhance.setTextOff(mIsSr ? "超分 关" : "增强 关");
        // ToggleButton 文案需要在 set/Off 后重新刷一次显示文本
        mToggleEnhance.setChecked(mUiEnhance);
        mToggleEnhance.setOnCheckedChangeListener((btn, isChecked) -> {
            mUiEnhance = isChecked;
            if (mEnhanceSurface != null) {
                mEnhanceSurface.setEnhanceEnabled(isChecked);
            }
            updateStatusText();
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
        // 1) 创建 EnhanceSurface（构造时同步在内部 GL 线程创建 EGL/OES/SurfaceTexture/inputSurface）
        try {
            mEnhanceSurface = new EnhanceSurface(getApplicationContext(), mMode);
        } catch (RuntimeException e) {
            Log.e(TAG, "EnhanceSurface init failed", e);
            Toast.makeText(this, "EnhanceSurface 初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mEnhanceSurface.setListener((canEnhance, reason) -> {
            mCanEnhance = canEnhance;
            mReason = reason;
            if (canEnhance) {
                mToggleEnhance.setEnabled(true);
            } else {
                Toast.makeText(EnhanceSurfacePathActivity.this,
                        "当前分辨率 " + mVideoWidth + "x" + mVideoHeight + " 不支持 "
                                + (mIsSr ? "超分" : "增强") + " (" + reasonText(reason) + ")",
                        Toast.LENGTH_LONG).show();
                mToggleEnhance.setChecked(false);
                mToggleEnhance.setEnabled(false);
                mUiEnhance = false;
                if (mEnhanceSurface != null) {
                    mEnhanceSurface.setEnhanceEnabled(false);
                }
            }
            updateStatusText();
        });
        mEnhanceSurface.setEnhanceEnabled(mUiEnhance);

        // 2) 设置 output Surface
        mEnhanceSurface.setOutputSurface(holder.getSurface());

        // 3) 创建并启动 MediaPlayer：把它的输出 Surface 设为 EnhanceSurface 的 inputSurface
        mMediaPlayer = new MediaPlayer();
        try {
            if (mVideoSource != null && mVideoSource.isLocalUri()) {
                mMediaPlayer.setDataSource(this, mVideoSource.uri);
            } else {
                mMediaPlayer.setDataSource(mVideoFilePath);
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
                updateStatusText();
            });
            mMediaPlayer.setOnPreparedListener(mp -> {
                Log.i(TAG, "MediaPlayer onPrepared");
                mp.start();
            });
            mMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "MediaPlayer onError what=" + what + " extra=" + extra);
                runOnUiThread(() -> Toast.makeText(this,
                        "播放出错: what=" + what, Toast.LENGTH_LONG).show());
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
     * 按视频纵横比把 SurfaceView 缩到合适大小。SurfaceView 在 layout 里 layout_gravity=center_horizontal，
     * 缩放后水平居中、竖直从顶部贴合。
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

    /** 把 EnhanceSurface 的 reason 码翻译成状态栏上的简短人类可读文案。 */
    private static String reasonText(@EnhanceSurface.Reason int reason) {
        switch (reason) {
            case EnhanceSurface.REASON_NONE:
                return "ready";
            case EnhanceSurface.REASON_UNSUPPORTED_SIZE:
                return "不支持该分辨率";
            case EnhanceSurface.REASON_INIT_FAILED:
                return "init失败";
            case EnhanceSurface.REASON_INFER_TOO_SLOW:
                return "推理耗时过高";
            default:
                return "init中";
        }
    }

    private void updateStatusText() {
        runOnUiThread(() -> {
            String state = mCanEnhance ? "ready" : reasonText(mReason);
            // 实际倍率在 init 成功后才确定（超分意图下可能 2×/3×），未定时显示意图占位。
            String resolved = mEnhanceSurface != null ? mEnhanceSurface.getResolvedLabel() : null;
            String typeLabel = (resolved != null) ? resolved : (mIsSr ? "SR(?)" : "IE_Y");
            mTvStatus1.setText(String.format(Locale.getDefault(),
                    "video=%dx%d  view=%dx%d", mVideoWidth, mVideoHeight, mViewWidth, mViewHeight));
            mTvStatus2.setText(String.format(Locale.getDefault(),
                    "EnhanceSurface[%s] | engine=%s | enhance=%s",
                    typeLabel, state, mUiEnhance ? "ON" : "OFF"));
        });
    }
}
