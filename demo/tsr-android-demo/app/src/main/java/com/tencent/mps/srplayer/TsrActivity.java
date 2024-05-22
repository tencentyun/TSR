package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.tencent.mps.srplayer.helper.TapHelper;
import com.tencent.mps.srplayer.opengl.Texture;
import com.tencent.mps.srplayer.pass.CompareTexDrawer;
import com.tencent.mps.srplayer.pass.OffScreenRenderPass;
import com.tencent.mps.srplayer.pass.VideoFrameDrawer;
import com.tencent.mps.tie.api.TIEPass;
import com.tencent.mps.tie.api.TSRPass;
import com.tencent.mps.tie.api.TSRSdk;
import com.tencent.mps.tie.api.TSRSdk.TSRSdkLicenseStatus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class TsrActivity extends AppCompatActivity implements GLSurfaceView.Renderer, OnFrameAvailableListener {
    private static final String TAG = "TsrActivity";

    /**--------------------------------- THE PARAMS FOR SDK VERIFICATION----------------------------------------*/
    // Modify mAppId to your APPID which can be found in Tencent Cloud account.
    private final long mAppId = -1;
    /**---------------------------------------------------------------------------------------------------------*/

    // Flag indicating if the video is currently paused
    private boolean mIsPause;
    // Flag indicating if there is a new frame
    private volatile boolean updateTexture;
    // SurfaceTexture bound to MediaPlayer
    private SurfaceTexture mSurfaceTexture;
    // Super-resolution and Image Enhance pass
    private TSRPass mTSRPassStandard;
    private TSRPass mTSRPassProfessional;
    private TIEPass mTIEPass;
    // Conversion of textureOES to texture2D
    private OffScreenRenderPass mTexOESToTex2DPass;
    // Bilinear rendering pass
    private OffScreenRenderPass mBilinearRenderPass;
    // Comparing two textures
    private CompareTexDrawer mCompareTexDrawer;
    // Drawer for rendering frames on screen
    private VideoFrameDrawer mVideoFrameDrawer;
    // Width of the original video frame
    private int mFrameWidth;
    // Height of the original video frame
    private int mFrameHeight;
    // Super-resolution upscale ratio.
    private float mSrRatio;
    // Frame processing algorithm
    private String mAlgorithm;
    // The frame processing algorithm being compared
    private String mCompareAlgorithm;
    // InputTexture
    private Texture mInputTexture;
    // Video rotation
    private int mRotation;
    private final Context mContext = this;
    private MediaExtractor mExtractor;
    private MediaCodec mMediaCodec;
    private volatile boolean mStopDecode;

    private void prepareDecoder() {
        // 初始化解码器
        try {
            Log.i(TAG, "prepareDecoder");

            for (int i = 0; i < mExtractor.getTrackCount(); i++) {
                MediaFormat format = mExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    mExtractor.selectTrack(i);
                    mFrameWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    mFrameHeight = format.getInteger(MediaFormat.KEY_HEIGHT);

                    mInputTexture = new Texture(false, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, mFrameWidth, mFrameHeight);
                    mSurfaceTexture = new SurfaceTexture(mInputTexture.getTextureId());
                    mSurfaceTexture.setOnFrameAvailableListener(this);
                    Surface surface = new Surface(mSurfaceTexture);

                    mMediaCodec = MediaCodec.createDecoderByType(mime);
                    mMediaCodec.configure(format, surface, null, 0);
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "mediacodec exception:" + e.getMessage());
        }
    }

    private void startDecode() {
        new Thread(() -> {
            // 初始化解码器
            try {
                Log.i(TAG, "start decoder");
                mMediaCodec.start();

                final long TIMEOUT_US = 1000;
                long startTime = System.currentTimeMillis();
                long pauseTime = 0;
                boolean pausing = false;
                while (!mStopDecode) {
                    if (mIsPause) {
                        if (!pausing) {
                            // 记录暂停的时间点，用于更新开始的时间
                            pauseTime = System.currentTimeMillis();
                        }
                        pausing = true;
                        continue;
                    }
                    if (pausing) {
                        pausing = false;
                        startTime += System.currentTimeMillis() - pauseTime;
                    }

                    int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
                        int sampleSize = mExtractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0,
                                    0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        } else {
                            mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
                            mExtractor.advance();
                        }
                    }
                    MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                    int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // All frames have been decoded
                        break;
                    }
                    if (outputBufferIndex >= 0) {
                        // 获取这一帧的预计显示时间（毫秒）
                        long presentationTimeMs = bufferInfo.presentationTimeUs / 1000;
                        // 计算从开始解码到现在过了多少时间
                        long elapsedTime = System.currentTimeMillis() - startTime;
                        // 如果预计显示时间大于已经过去的时间，那就等待一段时间
                        if (presentationTimeMs > elapsedTime) {
                            try {
                                Thread.sleep(presentationTimeMs - elapsedTime);
                            } catch (InterruptedException e) {
                                Log.e(TAG, "mediacodec exception:" + e.getMessage());
                            }
                        }
                        mMediaCodec.releaseOutputBuffer(outputBufferIndex, true);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "mediacodec exception:" + e.getMessage());
            } finally {
                if (mMediaCodec != null) {
                    mMediaCodec.stop();
                    mMediaCodec.release();
                    mMediaCodec = null;
                }
                if (mExtractor != null) {
                    mExtractor.release();
                    mExtractor = null;
                }
            }
        }).start();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String fileName = sharedPreferences.getString("video_title", "");
        mSrRatio = Float.parseFloat(sharedPreferences.getString("sr_ratio", "1.0"));
        mAlgorithm = sharedPreferences.getString("video_algorithm", "");
        mCompareAlgorithm = sharedPreferences.getString("compare_algorithm", "");
        boolean isFullScreenRender = sharedPreferences.getBoolean("full_screen_config", false);
        if ("专业版增强".equals(mAlgorithm)) {
            // Not use SR
            mSrRatio = 1.0f;
            if (sharedPreferences.getBoolean("compare_source", false)) {
                mCompareAlgorithm = "直接渲染";
            } else {
                mCompareAlgorithm = "不对比";
            }
        }
        if ("直接渲染".equals(mAlgorithm)) {
            mSrRatio = 1.0f;
        }
        if (mAlgorithm.equals(mCompareAlgorithm)) {
            mCompareAlgorithm = "不对比";
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        mExtractor = new MediaExtractor();
        try {
            String uriString = getIntent().getStringExtra("videoUri");
            if (uriString != null) {
                Uri videoUri = Uri.parse(uriString);
                if (videoUri != null) {
                    fileName = getIntent().getStringExtra("fileName");
                    mExtractor.setDataSource(mContext, videoUri, null);
                    retriever.setDataSource(mContext, videoUri);
                }
            } else {
                AssetFileDescriptor afd = mContext.getAssets().openFd(fileName);
                mExtractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "open source failed: " + e.getMessage());
            return;
        }

        prepareDecoder();

        mRotation = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));
        Log.i(TAG, "rotation = " + mRotation);

        // Init TSRSdk
        TSRSdk.getInstance().init(mAppId, status -> {
            if (status == TSRSdkLicenseStatus.AVAILABLE) {
                Log.i(TAG, "Online verify sdk license success: " + status);
                mTSRPassStandard = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);
                mTSRPassProfessional = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL);
                mTIEPass = new TIEPass();
                runOnUiThread(() -> initView(isFullScreenRender, mAlgorithm, mCompareAlgorithm));
            } else {
                Log.i(TAG, "Online verify sdk license failed: " + status);
                runOnUiThread(() -> {
                    Toast.makeText(mContext, "Online verify sdk license failed: " + status, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }, (logLevel, tag, msg) -> {
            switch (logLevel) {
                case Log.VERBOSE:
                    Log.v(tag, msg);
                    break;
                case Log.DEBUG:
                    Log.d(tag, msg);
                    break;
                case Log.INFO:
                    Log.i(tag, msg);
                    break;
                case Log.WARN:
                    Log.w(tag, msg);
                    break;
                case Log.ERROR:
                    Log.e(tag, msg);
                    break;
            }
        });
    }

    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
        String version = GLES30.glGetString(GL10.GL_VERSION);
        Log.i(TAG, "OpenGL ES Version: " + version );

        mVideoFrameDrawer = new VideoFrameDrawer();
        mCompareTexDrawer = new CompareTexDrawer();
        try {
            mVideoFrameDrawer.createOnGLThread(mContext);
            mCompareTexDrawer.createOnGLThread(mContext);
        } catch (IOException e) {
            Log.e(TAG, Objects.requireNonNull(e.getMessage()));
        }

        // Create the pass that convert TextureOES to Texture2D.
        mTexOESToTex2DPass = new OffScreenRenderPass();
        mTexOESToTex2DPass.init(GLES30.GL_TEXTURE_2D,
                mInputTexture.getWidth(), mInputTexture.getHeight(), "shaders/videoTexOES.frag");

        mBilinearRenderPass = new OffScreenRenderPass();
        mBilinearRenderPass.init(GLES30.GL_TEXTURE_2D, (int) (mFrameWidth * mSrRatio),
                (int) (mFrameHeight * mSrRatio), "shaders/videoTex2D.frag");

        /*-------------------------------- Step 1: init the TSRPass. -------------------------------------------*/
        mTSRPassStandard.init(mFrameWidth, mFrameHeight, mSrRatio);
        // Sets the parameters of the TSRPass.
        // These three parameters are empirical values and are only for reference. You can change their values according to your own needs.
        mTSRPassStandard.setParameters(52, 52, 58);
        mTSRPassProfessional.init(mFrameWidth, mFrameHeight, mSrRatio);
        mTSRPassProfessional.setParameters(52, 52, 58);
        mTIEPass.init(mFrameWidth, mFrameHeight);

        startDecode();
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        if (mCompareTexDrawer != null) {
            mCompareTexDrawer.onSurfaceChanged(width, height, mRotation);
        }
        if (mVideoFrameDrawer != null) {
            mVideoFrameDrawer.onSurfaceChanged(width, height, mRotation);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (mTexOESToTex2DPass == null || mCompareTexDrawer == null || mTSRPassStandard == null ||
                mTSRPassProfessional == null || mTIEPass == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }

        synchronized (this) {
            if (updateTexture) {
                mSurfaceTexture.updateTexImage();
                updateTexture = false;
            }
        }

        float[] transformMatrix = new float[16];
        mSurfaceTexture.getTransformMatrix(transformMatrix);

        /* Step 2: (Optional) If the type of your input texture is TextureOES, you must Convert TextureOES to Texture2D.*/
        int tex2dId = mTexOESToTex2DPass.render(mInputTexture.getTextureId(), mInputTexture.getType(), transformMatrix);

        if ("直接渲染".equals(mAlgorithm)) {
            mVideoFrameDrawer.draw(tex2dId);
            return;
        }

        int srTextureId = -1;
        int cmpTextureId;
        /* Step 3: Pass the input texture's id to TSRPass or TIEPass, and get the output texture's id. The output
        texture is the result of super-resolution or image enhance.*/
        switch (mAlgorithm) {
            case "标准版超分":
                if (mTSRPassStandard == null) {
                    Log.w(TAG, "mTSRPassStandard is null!");
                    break;
                }
                srTextureId = mTSRPassStandard.render(tex2dId, transformMatrix);
                break;
            case "专业版超分":
                if (mTSRPassProfessional == null) {
                    Log.w(TAG, "mTSRPassProfessional is null!");
                    break;
                }
                srTextureId = mTSRPassProfessional.render(tex2dId, transformMatrix);
                break;
            case "专业版增强":
                if (mTIEPass == null) {
                    Log.w(TAG, "mTIEPass is null!");
                    break;
                }
                srTextureId = mTIEPass.render(tex2dId, transformMatrix);
                break;
        }

        /* Step 4: Use the TSRPass's output texture to do your own render. */
        // Render the processed frames to screen
        switch (mCompareAlgorithm) {
            case "不对比":
                mVideoFrameDrawer.draw(srTextureId);
                break;
            case "直接渲染":
                mCompareTexDrawer.draw(srTextureId, tex2dId);
                break;
            case "普通播放":
                if (mBilinearRenderPass == null) {
                    Log.w(TAG, "mBilinearRenderPass is null!");
                    mVideoFrameDrawer.draw(srTextureId);
                    break;
                }
                cmpTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D, transformMatrix);
                mCompareTexDrawer.draw(srTextureId, cmpTextureId);
                break;
            case "标准版超分":
                if (mTSRPassStandard == null) {
                    Log.w(TAG, "mTSRPassStandard is null!");
                    mVideoFrameDrawer.draw(srTextureId);
                    break;
                }
                cmpTextureId = mTSRPassStandard.render(tex2dId, transformMatrix);
                mCompareTexDrawer.draw(srTextureId, cmpTextureId);
                break;
            case "专业版超分":
                if (mTSRPassProfessional == null) {
                    Log.w(TAG, "mTSRPassCmp is null!");
                    mVideoFrameDrawer.draw(srTextureId);
                    break;
                }
                cmpTextureId = mTSRPassProfessional.render(tex2dId, transformMatrix);
                mCompareTexDrawer.draw(srTextureId, cmpTextureId);
                break;
        }
    }

    @Override
    synchronized public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        updateTexture = true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBilinearRenderPass != null) {
            mBilinearRenderPass.release();
        }
        if (mTexOESToTex2DPass != null) {
            mTexOESToTex2DPass.release();
        }
        if (mTSRPassStandard != null) {
            mTSRPassStandard.release();
        }
        if (mTSRPassProfessional != null) {
            mTSRPassProfessional.release();
        }
        mStopDecode = true;
        TSRSdk.getInstance().release();
    }

    private void initView(boolean isFullScreenRender, String srAlgorithm, String cmpAlgorithm) {
        // Init playControllerButton
        Button playControlButton = findViewById(R.id.playControlButton);
        playControlButton.setText(R.string.pause_video);
        playControlButton.setOnClickListener(view -> {
            if (mIsPause) {
                mIsPause = false;
                playControlButton.setText(R.string.pause_video);
            } else {
                mIsPause = true;
                playControlButton.setText(R.string.play_video);
            }
        });

        TextView sr = findViewById(R.id.sr);
        TextView cmp = findViewById(R.id.cmp_algorithm);

        // Whether to compare bilinear
        if ("不对比".equals(cmpAlgorithm) || "直接渲染".equals(srAlgorithm)) {
            sr.setVisibility(View.INVISIBLE);
            cmp.setVisibility(View.INVISIBLE);
        } else {
            sr.setText(srAlgorithm);
            cmp.setText(cmpAlgorithm);
        }

        GLSurfaceView glSurfaceView = new GLSurfaceView(mContext);
        glSurfaceView.setEGLContextClientVersion(3);
        glSurfaceView.setRenderer(TsrActivity.this);

        if (!isFullScreenRender) {
            glSurfaceView.setLayoutParams(new LayoutParams((int) (mFrameWidth * mSrRatio),
                    (int) (mFrameHeight * mSrRatio)));
        }

        FrameLayout frameLayout = findViewById(R.id.video_view);
        frameLayout.addView(glSurfaceView);
        frameLayout.setOnTouchListener(TapHelper.getInstance());
    }

    // Time interval for double swipe to exit
    private static final long TIME_EXIT = 2000;
    // Double swipe to exit
    private long mBackPressed;
    @Override
    public void onBackPressed(){
        if(mBackPressed + TIME_EXIT > System.currentTimeMillis()){
            super.onBackPressed();
        }else{
            Toast.makeText(this,R.string.slide_again, Toast.LENGTH_SHORT).show();
            mBackPressed = System.currentTimeMillis();
        }
    }
}