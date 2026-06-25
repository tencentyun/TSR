package com.tencent.mps.srplayer.demo.tsrpass;

import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.common.decoder.Decode2Surface;
import com.tencent.mps.srplayer.common.playback.FrameScheduler;
import com.tencent.mps.srplayer.common.Fullscreen;
import com.tencent.mps.srplayer.common.VideoSource;
import com.tencent.mps.tie.api.TsrPass;
import com.tencent.mps.tie.api.TsrPass.Config;
import com.tencent.mps.tie.api.TsrPass.InitResult;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * <b>TsrPass demo</b>：MediaCodec Surface 解码 → OES SurfaceTexture →
 * {@link OesToRgbaBlit} 把 OES 纹理转成 RGBA8 → {@link TsrPass} 单 pass 上采样到 SDK 内部输出纹理 →
 * {@link Texture2dBlit} 把 TsrPass 输出纹理 blit 到 GLSurfaceView。
 *
 * <p>TsrPass 是<b>无状态空间超分</b>：每帧只看当前一张 RGBA 输入纹理，不需要 depth/motion/jitter。
 * 因此特别适合视频流（解码出来的画面没有几何信号）。</p>
 *
 * <p>ToggleButton 切换两种模式：</p>
 * <ul>
 *   <li><b>关</b>：OES 纹理直接 blit 到 GLSurfaceView（GL 驱动 bilinear 拉伸，做直通基线）；</li>
 *   <li><b>开</b>：OES → RGBA8 → TsrPass（输出到内部纹理）→ Texture2dBlit → GLSurfaceView。</li>
 * </ul>
 *
 * <p>两种模式的 GLSurfaceView 显示尺寸完全一致（等于父容器按视频宽高比铺满后的最大值），
 * 这样切换 toggle 时 layout 不变，方便人眼对比"普通 bilinear 上采样" vs "TsrPass 边缘锐化上采样"
 * 在同一像素密度下的画质差异。</p>
 *
 * <p>所有 GL 资源（{@link OesToRgbaBlit}、{@link Texture2dBlit}、{@link TsrPass}、SurfaceTexture/Surface/OES tex）
 * 都在 GL 线程（{@link GLSurfaceView.Renderer} 三个回调）创建与释放。TsrPass 没有
 * "后台 license / 鉴权"约束，与 {@code TieEngine} 不同。</p>
 */
public class TsrPassDemoActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "TsrPassDemoActivity";

    /// UI / 渲染基础
    private GLSurfaceView mGlSurfaceView;
    private TextView mTvStatus2;
    private ImageButton mBtnPlayPause;
    private ToggleButton mToggleEnhance;

    private VideoSource mVideoSource;

    private int mViewWidth, mViewHeight, mVideoWidth, mVideoHeight;

    /** 当前生效的 TsrPass scale = mViewWidth / mVideoWidth，由 {@link #onSurfaceChanged} 实时推导。 */
    private float mTsrScale = 0f;

    /// 解码 + GL 资源
    private Decode2Surface mDecoder;
    private OesToRgbaBlit mOesBlit;
    private Texture2dBlit mTexBlit;
    private TsrPass mTsrPass;

    private int mOesTextureId = -1;
    private SurfaceTexture mSurfaceTexture;
    private Surface mDecoderSurface;
    private final float[] mStMatrix = new float[16];

    /// TsrPass 状态
    private volatile boolean mEnhance = false;
    private volatile boolean mTsrReady = false;

    /// 帧率调度
    private FrameScheduler mScheduler;
    private volatile boolean mPlaying = false;
    /** 帧门闩：scheduler 每次到点 +1，onDrawFrame 消费它来决定是否 dequeue 新帧。 */
    private final AtomicInteger mFrameGate = new AtomicInteger(0);

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

        mVideoSource = VideoSource.from(this);
        Log.i(TAG, "use video: " + mVideoSource.displayName);

        if (!mVideoSource.exists(this)) {
            Toast.makeText(this, "视频不可读取：" + mVideoSource.displayName, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
        mScheduler = new FrameScheduler(mFrameGate::incrementAndGet);
    }

    private void initViews() {
        mGlSurfaceView = findViewById(R.id.glSurfaceView);
        // status1 留作未来扩展；当前 demo 只用 status2 显示一行静态状态
        findViewById(R.id.tvStatus1).setVisibility(View.GONE);
        mTvStatus2 = findViewById(R.id.tvStatus2);
        mBtnPlayPause = findViewById(R.id.btnPlayPause);
        mBtnPlayPause.setOnClickListener(v -> togglePlayPause());

        mToggleEnhance = findViewById(R.id.toggleEnhance);
        mToggleEnhance.setVisibility(View.VISIBLE);
        mToggleEnhance.setTextOn("超分 开");
        mToggleEnhance.setTextOff("超分 关");
        mToggleEnhance.setChecked(false);
        mToggleEnhance.setEnabled(false); // init 完成后再 enable
        mToggleEnhance.setOnCheckedChangeListener((btn, isChecked) -> {
            mEnhance = isChecked;
            Log.i(TAG, "toggleEnhance -> " + isChecked);
            // 不再随 toggle 改 layout：两种模式都把视频按宽高比铺满父容器（典型 = 屏幕 ×2 视频
            // 分辨率），从而在视觉上同尺寸对比 OES 直接 bilinear 上采样 vs TsrPass 锐化上采样。
        });

        mGlSurfaceView.setEGLContextClientVersion(3);
        mGlSurfaceView.setRenderer(this);
        mGlSurfaceView.setKeepScreenOn(true);
        // onDrawFrame 以屏幕刷新率连续回调，这里是为了实现暂停播放时也能toggle开关刷新画面。实际业务场景应该是 RENDERMODE_WHEN_DIRTY，以节约功耗。
        mGlSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mGlSurfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mScheduler != null) {
            mScheduler.stop();
        }
        mPlaying = false;
        if (mBtnPlayPause != null) {
            mBtnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        }
        mGlSurfaceView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        if (mScheduler != null) {
            mScheduler.stop();
        }
        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);

        // 1) 创建 OES 纹理 + SurfaceTexture + Surface
        mOesTextureId = createOesTexture();
        mSurfaceTexture = new SurfaceTexture(mOesTextureId);
        mDecoderSurface = new Surface(mSurfaceTexture);

        // 2) 初始化解码器
        if (mDecoder != null) {
            mDecoder.release();
        }
        mDecoder = new Decode2Surface();
        try {
            if (mVideoSource != null && mVideoSource.isLocalUri()) {
                mDecoder.init(this, mVideoSource.uri, mDecoderSurface);
            } else {
                mDecoder.init(mVideoSource.filePath, mDecoderSurface);
            }
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "Decode2Surface init failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "解码器初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }

        mVideoWidth = mDecoder.getWidth();
        mVideoHeight = mDecoder.getHeight();
        Log.i(TAG, "video " + mVideoWidth + "x" + mVideoHeight);

        // 3) 初始化 OES→RGBA8 blit + 上屏 helper（TsrPass 引擎留到 onSurfaceChanged：
        //    那时 view 的最终尺寸才确定，可据此推导 scale = viewW / videoW）
        try {
            mOesBlit = new OesToRgbaBlit();
            mOesBlit.init(mVideoWidth, mVideoHeight);
            mTexBlit = new Texture2dBlit();
            mTexBlit.init();
        } catch (RuntimeException e) {
            Log.e(TAG, "OesToRgbaBlit / Texture2dBlit init failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "blit 初始化失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }

        // 4) 主线程做 layout 缩放（按视频宽高比铺满父容器，开关同尺寸）；
        //    完成后 GL 线程会收到 onSurfaceChanged，并在那里基于实际 view 尺寸 init TsrPass
        runOnUiThread(this::adjustGlSurfaceSize);

        // 5) 自动开始播放
        startPlay();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
        Log.d(TAG, "onSurfaceChanged() " + width + "x" + height);

        // 视图尺寸已最终确定（由 adjustGlSurfaceSize 按视频宽高比铺满父容器算出），
        // 据此推导 TsrPass scale = viewW / videoW（≡ viewH / videoH，宽高比已匹配）
        if (mVideoWidth > 0 && mVideoHeight > 0 && width > 0 && height > 0) {
            float scale = (float) width / mVideoWidth;
            initOrReinitTsrEngine(scale);
        }
    }

    /**
     * 按当前 view 尺寸推导出的 scale 初始化或重建 TsrPass；
     * 同 scale 重复调用是幂等的（SDK 内部判定）。
     */
    private void initOrReinitTsrEngine(float scale) {
        if (mTsrPass == null) {
            mTsrPass = new TsrPass();
        }
        InitResult result = mTsrPass.init(this, new Config(mVideoWidth, mVideoHeight, scale));
        if (result.code == InitResult.CODE_OK) {
            mTsrReady = true;
            mTsrScale = scale;
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    mToggleEnhance.setEnabled(true);
                    refreshStatusText();
                }
            });
        } else {
            mTsrReady = false;
            mTsrScale = 0f;
            final String msg = "code=" + result.code + " msg=" + result.msg;
            Log.w(TAG, "TsrPass init failed: " + msg);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    mToggleEnhance.setEnabled(false);
                    Toast.makeText(TsrPassDemoActivity.this,
                            "TsrPass 初始化失败：" + msg, Toast.LENGTH_LONG).show();
                    refreshStatusText();
                }
            });
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mOesTextureId < 0 || mOesBlit == null || mDecoder == null) {
            return;
        }

        if (mPlaying) {
            // 帧门闩控制步进：只有 scheduler 按 PTS 节拍发信号时才 dequeue 新帧，
            // 避免 CONTINUOUSLY 模式下以屏幕刷新率猛拉解码器导致快进。
            boolean advance = mFrameGate.getAndSet(0) > 0;
            if (advance) {
                if (!mDecoder.dequeueOutputFrameToSurface()) {
                    mScheduler.onFrameUnavailable();
                } else {
                    try {
                        mSurfaceTexture.updateTexImage();
                    } catch (RuntimeException e) {
                        Log.e(TAG, "updateTexImage failed", e);
                    }
                    mSurfaceTexture.getTransformMatrix(mStMatrix);
                    mScheduler.onFrameRendered(mDecoder.getCurrentPresentationTimeUs());
                }
            }
            // gate == 0 时跳过 dequeue，仅重新渲染当前帧，保证帧率节拍正确
        }
        // 暂停时跳过解码器，直接渲染 OES 纹理中保留的最后一帧

        // 渲染：根据 toggle 选直通 / TsrPass
        boolean useEnhance = mEnhance && mTsrReady && mTsrPass != null;
        try {
            if (useEnhance) {
                mOesBlit.blit(mOesTextureId, mStMatrix);
                int tsrOutTex = mTsrPass.process(mOesBlit.getOutputTextureId());
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glViewport(0, 0, mViewWidth, mViewHeight);
                if (tsrOutTex != 0) {
                    mTexBlit.blitToCurrentFramebuffer(tsrOutTex);
                }
            } else {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
                GLES30.glViewport(0, 0, mViewWidth, mViewHeight);
                mOesBlit.blitToCurrentFramebuffer(mOesTextureId, mStMatrix);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "render failed", e);
        }
    }

    // ============================================================
    // 内部
    // ============================================================

    private void startPlay() {
        mPlaying = true;
        if (mScheduler != null) {
            mScheduler.start();
        }
        if (mBtnPlayPause != null) {
            mBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        }
    }

    private void togglePlayPause() {
        if (mPlaying) {
            // 暂停
            if (mScheduler != null) {
                mScheduler.stop();
            }
            mPlaying = false;
            if (mBtnPlayPause != null) {
                mBtnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }
            Log.i(TAG, "paused");
        } else {
            // 恢复播放
            mPlaying = true;
            if (mScheduler != null) {
                mScheduler.start();
            }
            if (mBtnPlayPause != null) {
                mBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            }
            Log.i(TAG, "resumed");
        }
    }

    /** 写一行静态状态文案，不再随帧刷新。 */
    private void refreshStatusText() {
        if (mTvStatus2 == null) {
            return;
        }
        mTvStatus2.setText(String.format(Locale.getDefault(),
                "TsrPass 路径 | 视频=%dx%d | view=%dx%d | scale=%.2fx | TsrPass=%s",
                mVideoWidth, mVideoHeight, mViewWidth, mViewHeight, mTsrScale,
                mTsrReady ? "ready" : "init 失败"));
    }

    /**
     * 调整 GLSurfaceView 大小：TsrPass 开/关都按视频宽高比铺满父容器，让两种模式的目标像素密度
     * 完全一致，便于直接对比画质。
     *
     * <p>OES 直通模式下 GL 驱动会在 GLSurfaceView 表面做 bilinear 上采样到目标尺寸；
     * TsrPass 模式下由 SDK 把同一帧上采样到同样尺寸（scale = viewW / videoW，由
     * {@link #onSurfaceChanged} 实时推导并喂给 SDK），两者像素密度一致，便于直接对比画质。</p>
     *
     * <p>调整完成后，GL 线程会收到一次 {@link #onSurfaceChanged}，TsrPass 在那里按实际 view
     * 尺寸 init / re-init。</p>
     */
    private void adjustGlSurfaceSize() {
        ViewGroup parent = (ViewGroup) mGlSurfaceView.getParent();
        int containerWidth = parent != null ? parent.getWidth() : 0;
        int containerHeight = parent != null ? parent.getHeight() : 0;
        if (containerWidth == 0 || containerHeight == 0 || mVideoWidth == 0 || mVideoHeight == 0) {
            return;
        }
        ViewGroup.LayoutParams lp = mGlSurfaceView.getLayoutParams();
        // 按视频宽高比铺满父容器，TsrPass 开关都用同一布局
        float videoRatio = (float) mVideoWidth / mVideoHeight;
        float containerRatio = (float) containerWidth / containerHeight;
        if (containerRatio > videoRatio) {
            lp.width = (int) (containerHeight * videoRatio);
            lp.height = containerHeight;
        } else {
            lp.width = containerWidth;
            lp.height = (int) (containerWidth / videoRatio);
        }
        mGlSurfaceView.setLayoutParams(lp);
        mGlSurfaceView.requestLayout();
    }

    private void releaseGlResources() {
        if (mTsrPass != null) {
            mTsrPass.release();
            mTsrPass = null;
        }
        if (mTexBlit != null) {
            mTexBlit.release();
            mTexBlit = null;
        }
        if (mOesBlit != null) {
            mOesBlit.release();
            mOesBlit = null;
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

    private static int createOesTexture() {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int id = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        return id;
    }
}
