package com.tencent.mps.srplayer.demo.texture;

import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;

import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.common.video.VideoProbe;
import com.tencent.mps.srplayer.common.video.ColorSpaceUtil;
import com.tencent.mps.srplayer.common.video.Decode2Surface;
import com.tencent.mps.srplayer.common.video.FrameScheduler;
import com.tencent.mps.srplayer.common.Fullscreen;
import com.tencent.mps.srplayer.common.PassTimingMeter;
import com.tencent.mps.srplayer.common.video.VideoSource;
import com.tencent.mps.srplayer.common.gl.Texture2dBlit;
import com.tencent.mps.srplayer.common.gl.OesToRgbaBlit;
import com.tencent.mps.tie.api.TieTextureEnhancer;
import com.tencent.mps.tie.api.TieTextureEnhancer.Config;


import com.tencent.mps.tie.api.TieBufferEnhancer.EnhancerType;
import java.io.IOException;
import java.util.Locale;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * OUTPUT_TEXTURE 模式验证 Activity。
 * 与 {@link TextureOesActivity} 相同 OES 输入路径，但 TieTextureEnhancer 改用
 * {@link TieTextureEnhancer.OutputMode#OUTPUT_TEXTURE}：增强结果渲染到 SDK 内部 FBO，
 * 返回输出纹理 ID，再由 {@link Texture2dBlit} 上屏。
 */
public class TextureOutputActivity extends AppCompatActivity implements GLSurfaceView.Renderer {

    private static final String TAG = "OesTextureOutputAct";

    private GLSurfaceView mGlSurfaceView;
    private ImageButton mBtnPlayPause;
    private TextView mTvStatus1, mTvStatus2;
    private ToggleButton mToggleEnhance;
    private volatile boolean mToggleEnhanceOn = false;
    private VideoSource mVideoSource;

    private int mViewWidth, mViewHeight, mVideoWidth, mVideoHeight;

    private Decode2Surface mDecoder;
    private int mOesTextureId = -1;
    private SurfaceTexture mSurfaceTexture;
    private Surface mDecoderSurface;
    private final float[] mStMatrix = new float[16];

    /** 关闭增强时的 OES→RGBA→屏幕直通 blit（不经过 SDK）。 */
    private OesToRgbaBlit mOesBlit;

    private TieTextureEnhancer mTieTextureEnhancer;

    /** SDK 初始化结果（含能力档位与输出尺寸），由 InitCallback 设置，null=未完成。 */
    private volatile TieTextureEnhancer.InitResult mInitResult;

    /** 渲染性能仪表（fps + SDK 耗时，GL 线程写、UI 线程读）。 */
    private final PassTimingMeter mTimingMeter = new PassTimingMeter();

    /** 将 TieTextureEnhancer 输出的 RGBA 纹理 blit 到屏幕 */
    private Texture2dBlit mTexBlit;

    private FrameScheduler mScheduler;
    private volatile boolean mPlaying = false;

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
            Toast.makeText(this, "无法读取视频：" + mVideoSource.displayName, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        initViews();
        mScheduler = new FrameScheduler(() -> mGlSurfaceView.requestRender());
    }

    private void initViews() {
        mGlSurfaceView = findViewById(R.id.glSurfaceView);
        mBtnPlayPause = findViewById(R.id.btnPlayPause);
        mTvStatus1 = findViewById(R.id.tvStatus1);
        mTvStatus2 = findViewById(R.id.tvStatus2);
        mTvStatus2.setMovementMethod(ScrollingMovementMethod.getInstance());

        mBtnPlayPause.setOnClickListener(v -> {
            if (mPlaying) {
                pausePlayback();
            } else {
                resumePlayback();
            }
        });

        mToggleEnhance = findViewById(R.id.toggleEnhance);
        mToggleEnhance.setVisibility(View.VISIBLE);
        mToggleEnhance.setChecked(mToggleEnhanceOn);
        mToggleEnhance.setOnCheckedChangeListener((btn, isChecked) -> {
            mToggleEnhanceOn = isChecked;
            if (mTieTextureEnhancer != null) mTieTextureEnhancer.setEnabled(isChecked);
            if (!isChecked) mTimingMeter.reset();
            Log.i(TAG, "toggleEnhance -> " + isChecked);
            if (!mPlaying) {
                mGlSurfaceView.requestRender();
            }
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

        Size videoSize;
        VideoProbe.ColorInfo info;
        try {
            info = (mVideoSource != null && mVideoSource.isLocalUri())
                    ? VideoProbe.probe(this, mVideoSource.uri)
                    : VideoProbe.probe(mVideoSource.filePath);
            videoSize = new Size(info.width, info.height);
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "probe video size failed", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "解析视频尺寸失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }
        mVideoWidth = videoSize.getWidth();
        mVideoHeight = videoSize.getHeight();
        Log.i(TAG, "video " + mVideoWidth + "x" + mVideoHeight);

        // 0) 初始化关闭直通 blit
        mOesBlit = new OesToRgbaBlit();
        mOesBlit.init(mVideoWidth, mVideoHeight);

        // 1) 初始化 Texture2dBlit（用于后将输出纹理上屏）
        mTexBlit = new Texture2dBlit();
        mTexBlit.init();

        // 2) 构造 TieTextureEnhancer — OUTPUT_TEXTURE 模式
        //    色彩标准按 codec VUI 探测结果传入
        TieTextureEnhancer.ColorStandard colorStd = ColorSpaceUtil.detectForSdk(
                info.colorRange, info.colorStandard, mVideoWidth);
        Log.i(TAG, "color std -> " + colorStd
                + " (range=" + info.colorRange + ", standard=" + info.colorStandard + ")");
        Config cfg = new Config(EnhancerType.IE_Y, mVideoWidth, mVideoHeight,
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                TieTextureEnhancer.OutputMode.OUTPUT_TEXTURE,
                colorStd);
        mTieTextureEnhancer = new TieTextureEnhancer();
        mOesTextureId = mTieTextureEnhancer.init(this, cfg, result -> {
            runOnUiThread(() -> {
                mInitResult = result;
                boolean canEnhance = result.path != TieTextureEnhancer.Path.NONE;
                mToggleEnhance.setEnabled(canEnhance);
                if (!canEnhance) {
                    Log.w(TAG, "init 失败");
                } else if (result.path == TieTextureEnhancer.Path.SHADER) {
                    Log.i(TAG, "使用 SHADER");
                } else {
                    Log.i(TAG, "ready (OUTPUT_TEXTURE): " + result.outputWidth + "x" + result.outputHeight);
                }
                refreshStatusText();
            });
        });

        // 3) SurfaceTexture → Surface → 解码器
        mSurfaceTexture = new SurfaceTexture(mOesTextureId);
        mDecoderSurface = new Surface(mSurfaceTexture);

        try {
            mDecoder = new Decode2Surface();
            if (mVideoSource != null && mVideoSource.isLocalUri()) {
                mDecoder.init(this, mVideoSource.uri, mDecoderSurface);
            } else {
                mDecoder.init(mVideoSource.filePath, mDecoderSurface);
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to init decoder", e);
            runOnUiThread(() -> Toast.makeText(this,
                    "解码器初始化失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            return;
        }

        runOnUiThread(() -> {
            adjustGlSurfaceSize(mVideoWidth, mVideoHeight);
        });

        startPlay();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        mViewWidth = width;
        mViewHeight = height;
        Log.d(TAG, "onSurfaceChanged() " + width + "x" + height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (mDecoder == null || (mTieTextureEnhancer == null && mOesBlit == null)) {
            return;
        }

        if (mPlaying) {
            if (!mDecoder.dequeueOutputFrameToSurface()) {
                mScheduler.onFrameUnavailable();
                return;
            }

            try {
                mSurfaceTexture.updateTexImage();
            } catch (RuntimeException e) {
                Log.e(TAG, "updateTexImage failed", e);
                return;
            }
            mSurfaceTexture.getTransformMatrix(mStMatrix);
            mTimingMeter.recordFrame();
        }

        // 按视频纵横比设置居中视口
        applyCenteredViewport();

        boolean useEnhance = mToggleEnhanceOn && mInitResult != null
                && mInitResult.path != TieTextureEnhancer.Path.NONE
                && mTieTextureEnhancer != null;
        if (useEnhance) {
            // TieTextureEnhancer.process() → OUTPUT_TEXTURE：增强结果渲染到 FBO，返回纹理 ID
            // 注意：compositeToTexture() 内部会 glViewport(0,0,fboW,fboH)，破坏外面的居中视口
            long tProcess = SystemClock.elapsedRealtime();
            int outTexId = mTieTextureEnhancer.process(mOesTextureId, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mStMatrix);
            mTimingMeter.recordMs(SystemClock.elapsedRealtime() - tProcess);

            // 恢复居中视口后，将输出纹理 blit 到屏幕
            applyCenteredViewport();
            if (outTexId > 0 && mTexBlit != null) {
                mTexBlit.blitToCurrentFramebuffer(outTexId);
            }
        } else {
            // 关闭直通：OES → RGBA8 → 屏幕（不经过 SDK）
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
            applyCenteredViewport();
            mOesBlit.blitToCurrentFramebuffer(mOesTextureId, mStMatrix);
        }

        if (!mPlaying) {
            return;
        }

        // 节流刷新性能指标到 tvStatus1，约 1s 一次
        if (mTimingMeter.shouldUpdateUi()) {
            final String text = buildPerfText();
            runOnUiThread(() -> {
                if (mTvStatus1 != null && !isFinishing()) mTvStatus1.setText(text);
            });
        }

        mScheduler.onFrameRendered(mDecoder.getCurrentPresentationTimeUs());
    }

    // ============================================================
    // 播放控制
    // ============================================================

    private void startPlay() {
        mPlaying = true;
        mTimingMeter.reset();
        if (mScheduler != null) {
            mScheduler.start();
        }
        runOnUiThread(() -> mBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause));
    }

    private void pausePlayback() {
        mPlaying = false;
        if (mScheduler != null) {
            mScheduler.stop();
        }
        mBtnPlayPause.setImageResource(android.R.drawable.ic_media_play);
        mGlSurfaceView.requestRender();
    }

    private void resumePlayback() {
        mPlaying = true;
        mTimingMeter.reset();
        if (mScheduler != null) {
            mScheduler.start();
        }
        mBtnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
    }

    // ============================================================
    // 状态栏 & 布局
    // ============================================================

    /** 刷新 tvStatus2（静态信息）。init 完成（成功/失败）时调用一次。 */
    private void refreshStatusText() {
        if (mTvStatus2 == null) return;
        mTvStatus2.setText(buildStaticText());
    }

    /** tvStatus2：静态信息。init 失败/降级时优先显示原因。 */
    private String buildStaticText() {
        if (mInitResult != null && mInitResult.path == TieTextureEnhancer.Path.NONE) {
            return "OES→Tex | init 失败";
        }
        if (mInitResult != null && mInitResult.path == TieTextureEnhancer.Path.SHADER) {
            return "OES→Tex | 使用 SHADER";
        }
        return String.format(Locale.getDefault(),
                "OES→OUTPUT_TEXTURE | video=%dx%d | view=%dx%d | ready=%s",
                mVideoWidth, mVideoHeight, mViewWidth, mViewHeight,
                mInitResult != null ? "yes" : "init中");
    }

    /** tvStatus1：动态性能指标（fps + SDK 耗时 + 路径，四舍五入到整数）。 */
    private String buildPerfText() {
        long fps = mTimingMeter.getFpsRounded();
        long avg = mTimingMeter.getAvgMsRounded();
        String fpsStr = fps < 0 ? "—" : String.valueOf(fps);
        String timing = avg < 0 ? "—" : avg + "ms";
        String path = mToggleEnhanceOn && mInitResult != null ? mInitResult.path.name() : "NONE";
        return "fps=" + fpsStr + " | 耗时=" + timing + " | 路径=" + path;
    }

    private void adjustGlSurfaceSize(int videoWidth, int videoHeight) {
        int viewWidth = mGlSurfaceView.getWidth();
        int viewHeight = mGlSurfaceView.getHeight();
        if (viewWidth == 0 || viewHeight == 0 || videoWidth == 0 || videoHeight == 0) {
            return;
        }
        float videoRatio = (float) videoWidth / videoHeight;
        float viewRatio = (float) viewWidth / viewHeight;
        if (viewRatio > videoRatio) {
            mGlSurfaceView.getLayoutParams().width = (int) (viewHeight * videoRatio);
            mGlSurfaceView.getLayoutParams().height = viewHeight;
        } else {
            mGlSurfaceView.getLayoutParams().width = viewWidth;
            mGlSurfaceView.getLayoutParams().height = (int) (viewWidth / videoRatio);
        }
        mGlSurfaceView.requestLayout();
    }

    private void applyCenteredViewport() {
        if (mVideoWidth <= 0 || mVideoHeight <= 0 || mViewWidth <= 0 || mViewHeight <= 0) return;
        float videoRatio = (float) mVideoWidth / mVideoHeight;
        float viewRatio = (float) mViewWidth / mViewHeight;
        int drawW, drawH;
        if (viewRatio > videoRatio) {
            drawH = mViewHeight;
            drawW = (int) (mViewHeight * videoRatio);
        } else {
            drawW = mViewWidth;
            drawH = (int) (mViewWidth / videoRatio);
        }
        int drawX = (mViewWidth - drawW) / 2;
        int drawY = (mViewHeight - drawH) / 2;
        GLES20.glViewport(drawX, drawY, drawW, drawH);
    }

    private void releaseGlResources() {
        if (mTieTextureEnhancer != null) {
            mTieTextureEnhancer.release();
            mTieTextureEnhancer = null;
        }
        if (mOesBlit != null) {
            mOesBlit.release();
            mOesBlit = null;
        }
        if (mTexBlit != null) {
            mTexBlit.release();
            mTexBlit = null;
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
