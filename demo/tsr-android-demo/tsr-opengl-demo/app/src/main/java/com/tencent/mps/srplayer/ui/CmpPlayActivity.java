package com.tencent.mps.srplayer.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.tencent.mps.srplayer.R;
import com.tencent.mps.srplayer.helper.TapHelper;
import com.tencent.mps.srplayer.opengl.Texture;
import com.tencent.mps.srplayer.pass.CompareTexDrawer;
import com.tencent.mps.srplayer.pass.OffScreenRenderPass;
import com.tencent.mps.srplayer.pass.TieProGlProcessor;
import com.tencent.mps.srplayer.pass.VideoFrameDrawer;
import com.tencent.mps.srplayer.record.MediaRecorder;
import com.tencent.mps.srplayer.utils.DialogUtils;
import com.tencent.mps.srplayer.utils.FileUtils;
import com.tencent.mps.srplayer.utils.FpsUtil;
import com.tencent.mps.srplayer.utils.ProgressDialogUtils;
import com.tencent.mps.tie.api.v2.ErrorCode;
import com.tencent.mps.tie.api.v2.TiePro;
import com.tencent.mps.tie.api.v2.TieStd;
import com.tencent.mps.tie.api.v2.TsrStd;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executors;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

// 实时对比两个算法的播放效果
public class CmpPlayActivity extends AppCompatActivity implements GLSurfaceView.Renderer, OnFrameAvailableListener {

    private static final String TAG = "CmpPlayActivity";

    static {
        Algorithm.initializeDescriptions();
    }

    private final Context mContext = this;
    // Flag indicating if the video is currently paused
    private boolean mIsPause;
    // Flag indicating if there is a new frame
    private volatile boolean updateTexture;
    // SurfaceTexture bound to MediaPlayer
    private SurfaceTexture mSurfaceTexture;
    /// //////////////////
    private TieStd mTieStd;
    private TiePro mTiePro;
    private TsrStd mTsrStd;
    private TieStd mTieStdCmp;
    private TiePro mTieProCmp;
    private TsrStd mTsrStdCmp;
    private TieProGlProcessor mTieProProcessor;
    private TieProGlProcessor mTieProCmpProcessor;
    private boolean mPrepareReady = false;
    private boolean mCmpPrepareReady = false;
    private boolean mIsFinishing = false;
    /// //////////////////

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
    private volatile Algorithm mAlgorithm;
    // The frame processing algorithm being compared
    private volatile Algorithm mAlgorithmCmp;
    // The Algorithm to switch
    private volatile Algorithm mSwitchAlgorithm;
    // Is turn off SR
    private volatile boolean mIsTurnOffSR;
    private volatile boolean mIsTurnOffSRChange = false;// 标记 mIsTurnOffSR 是否发生了改变
    // InputTexture
    private Texture mInputTextureOES;// 解码出来的帧纹理（OES 格式）
    private int mInputTexture2D = -1;// 解码出来的帧纹理（OES 格式）转换为 2D 格式的纹理的 ID
    private int mProcessedTexture2D = -1;// 处理后的帧纹理的ID（2D 格式）
    private boolean mHasValidFrame = false;// 标记是否有可渲染的帧。在收到首帧之后就一直为 true 值。
    // MediaRecorder
    private MediaRecorder mMediaRecorder;
    // Video rotation
    private volatile int mRotation;
    // Is record video?
    private boolean mIsRecordVideo;
    private GLSurfaceView mGLSurfaceView;
    private int mPlayFrameCount = 0;
    private volatile long mVideoFrameCount = 0;
    private MediaExtractor mExtractor;
    private volatile MediaCodec mMediaCodec;
    private String mFileName;
    private float mFrameRate;
    private String mExportCodecType;
    private int mExportBitrateMbps;
    private int mOutputWidth;
    private int mOutputHeight;
    private boolean mIsFullScreenRender = false;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    // Double swipe to exit
    private long mBackPressed;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportActionBar().hide();
        setContentView(R.layout.activity_atomic_play);

        mFileName = getIntent().getStringExtra("file_name");
        mExportCodecType = getIntent().getStringExtra("export_codec");
        mExportBitrateMbps = getIntent().getIntExtra("export_bitrate", 10);
        mIsRecordVideo = getIntent().getBooleanExtra("export_video", false);

        mAlgorithm = (Algorithm) getIntent().getSerializableExtra("algorithm");
        mAlgorithmCmp = (Algorithm) getIntent().getSerializableExtra("compare_algorithm");
        createPass();

        String srRatio = getIntent().getStringExtra("sr_ratio");
        if (TextUtils.isEmpty(srRatio)) {
            // Not use SR
            mSrRatio = 1.0f;
        } else if ("全屏自适应".equals(srRatio) || "Full Screen Adaptive".equals(srRatio)) {
            mIsFullScreenRender = true;
        } else {
            mSrRatio = Float.parseFloat(srRatio);
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        mExtractor = new MediaExtractor();
        try {
            String uriString = getIntent().getStringExtra("video_uri");
            if (uriString != null) {
                Uri videoUri = Uri.parse(uriString);
                if (videoUri != null) {
                    mExtractor.setDataSource(mContext, videoUri, null);
                    retriever.setDataSource(mContext, videoUri);
                }
            } else {
                AssetFileDescriptor afd = mContext.getAssets().openFd(mFileName);
                mExtractor.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "open source failed: " + e.getMessage());
            return;
        }

        prepareDecoder();

        mRotation = Integer.parseInt(
                Objects.requireNonNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));

        initView(mIsRecordVideo, mAlgorithm, mAlgorithmCmp);

        if (mIsRecordVideo) {
            // Get video duration, unit: milliseconds (ms)
            String duration_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration_s != null) {
                long duration = Long.parseLong(duration_s);

                // Get video frame count
                int frameRate = 0;
                String count_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                if (count_s != null) {
                    mVideoFrameCount = Long.parseLong(count_s);
                    // Calculate frame rate
                    float dt = (float) duration / mVideoFrameCount; // Average time interval per frame
                    mFrameRate = Math.round(1000 / dt * 100) * 0.01f; // Frame rate

                    Log.i(TAG, "video frame rate = " + mFrameRate + ", frame count = " + mVideoFrameCount);
                }

                ProgressDialogUtils.showProgressDialog(this, "Exporting...");
            }
        }

        // 打印所有有用的调试信息
        Log.i(TAG, "onCreate() - " + "FileName: " + mFileName + ", " + "ExportCodec: " + mExportCodecType + ", "
                + "ExportBitrate: " + mExportBitrateMbps + "Mbps, " + "IsRecordVideo: " + mIsRecordVideo + ", "
                + "Algorithm: " + mAlgorithm + ", " + "CompareAlgorithm: " + mAlgorithmCmp + ", " + "SRRatio: "
                + mSrRatio + ", " + "IsFullScreenRender: " + mIsFullScreenRender + ", " + "VideoRotation: " + mRotation
                + "°, " + "FrameRate: " + mFrameRate + ", " + "FrameCount: " + mVideoFrameCount);
    }

    private void createPass() {
        switch (mAlgorithm) {
            case TIE_STD:
                mTieStd = new TieStd();
                break;
            case TIE_PRO:
                mTiePro = new TiePro();
                mTieProProcessor = new TieProGlProcessor(mTiePro);
                break;
            case TSR_STD:
                mTsrStd = new TsrStd();
                break;
        }
        switch (mAlgorithmCmp) {
            case TIE_STD:
                mTieStdCmp = new TieStd();
                break;
            case TIE_PRO:
                mTieProCmp = new TiePro();
                mTieProCmpProcessor = new TieProGlProcessor(mTieProCmp);
                break;
            case TSR_STD:
                mTsrStdCmp = new TsrStd();
                break;
        }
    }

    private void prepareDecoder() {
        // Initialize the decoder
        try {
            Log.i(TAG, "prepareDecoder");

            if (mMediaCodec != null) {
                mMediaCodec.stop();
                mMediaCodec.release();
            }

            for (int i = 0; i < mExtractor.getTrackCount(); i++) {
                MediaFormat format = mExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    mExtractor.selectTrack(i);
                    mFrameWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    mFrameHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        mRotation = format.getInteger(MediaFormat.KEY_ROTATION);
                        if (mRotation == 90 || mRotation == 270) {
                            int tmp = mFrameWidth;
                            mFrameWidth = mFrameHeight;
                            mFrameHeight = tmp;
                        }
                    }

                    mInputTextureOES = new Texture(false, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, mFrameWidth,
                            mFrameHeight);
                    mSurfaceTexture = new SurfaceTexture(mInputTextureOES.getTextureId());
                    mSurfaceTexture.setOnFrameAvailableListener(this);
                    Surface surface = new Surface(mSurfaceTexture);

                    mMediaCodec = MediaCodec.createDecoderByType(mime);
                    mMediaCodec.configure(format, surface, null, 0);
                    break;
                }
            }
            mMediaCodec.start();
        } catch (Exception e) {
            Log.e(TAG, "mediacodec exception:" + e.getMessage());
        }
    }

    private void initView(boolean isRecordVideo, Algorithm srAlgorithm, Algorithm cmpAlgorithm) {
        if (!isRecordVideo) {
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

            Button switchSRButton = findViewById(R.id.switchSRButton);
            if (mAlgorithmCmp == Algorithm.NO_CMP) {
                switchSRButton.setOnClickListener(view -> {
                    if (mIsTurnOffSR) {
                        mAlgorithm = mSwitchAlgorithm;
                        mIsTurnOffSR = false;
                        mIsTurnOffSRChange = true;
                        switchSRButton.setText(R.string.turn_off_sr);
                    } else {
                        mSwitchAlgorithm = mAlgorithm;
                        mAlgorithm = Algorithm.NONE;
                        mIsTurnOffSR = true;
                        mIsTurnOffSRChange = true;
                        switchSRButton.setText(R.string.turn_on_sr);
                    }
                });
            } else {
                switchSRButton.setVisibility(View.GONE);
            }
        }

        TextView sr = findViewById(R.id.sr);
        TextView cmp = findViewById(R.id.cmp_algorithm);

        // Whether to compare bilinear
        if (cmpAlgorithm == Algorithm.NO_CMP) {
            sr.setVisibility(View.INVISIBLE);
            cmp.setVisibility(View.INVISIBLE);
        } else {
            sr.setText(srAlgorithm.toString());
            cmp.setText(cmpAlgorithm.toString());
        }

        mGLSurfaceView = new GLSurfaceView(mContext);
        mGLSurfaceView.setEGLContextClientVersion(2);
        mGLSurfaceView.setRenderer(CmpPlayActivity.this);

        if (mIsFullScreenRender) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            int viewHeight = displayMetrics.heightPixels;
            int viewWidth = displayMetrics.widthPixels;
            int videoWidth = mFrameWidth;
            int videoHeight = mFrameHeight;
            double videoRatio = 1.0 * videoWidth / videoHeight;
            double viewRatio = 1.0 * viewWidth / viewHeight;
            if (viewRatio > videoRatio) {
                int width = (int) (videoRatio * viewHeight);
                mOutputWidth = width;
                mOutputHeight = viewHeight;
            } else {
                int height = (int) (viewWidth / videoRatio);
                mOutputWidth = viewWidth;
                mOutputHeight = height;
            }
        } else {
            mOutputWidth = (int) (mFrameWidth * mSrRatio);
            mOutputHeight = (int) (mFrameHeight * mSrRatio);
        }
        Log.i(TAG, "outputWidth = " + mOutputWidth + ", outputHeight = " + mOutputHeight);
        mGLSurfaceView.setLayoutParams(new LayoutParams(mOutputWidth, mOutputHeight));

        FrameLayout frameLayout = findViewById(R.id.video_view);
        frameLayout.addView(mGLSurfaceView);
        frameLayout.setOnTouchListener(TapHelper.getInstance());
    }

    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
        mVideoFrameDrawer = new VideoFrameDrawer();
        mCompareTexDrawer = new CompareTexDrawer();
        try {
            mVideoFrameDrawer.createOnGLThread(mContext);
            mCompareTexDrawer.createOnGLThread(mContext);
        } catch (IOException e) {
            Log.e(TAG, Objects.requireNonNull(e.getMessage()));
        }

        if (mIsRecordVideo) {
            configureMediaRecorder(mFileName, mOutputWidth, mOutputHeight, mFrameRate, mExportBitrateMbps,
                    mExportCodecType);
        }
    }

    private void configureMediaRecorder(String fileName, int frameWidth, int frameHeight, float frameRate,
            int bitrateMbps, String codecType) {
        String filePath = mContext.getExternalFilesDir("dump_video/") + "/" + fileName.split("\\.")[0] + "_"
                + mAlgorithm.description + "_" + mSrRatio + "腾讯终端增强.mp4";
        mMediaRecorder = new MediaRecorder(mContext, filePath, frameWidth, frameHeight, mRotation, frameRate,
                bitrateMbps, codecType, EGL14.eglGetCurrentContext());
        mMediaRecorder.setOnRecordFinishListener(path -> {
            runOnUiThread(ProgressDialogUtils::hideProgressDialog);
            FileUtils.saveVideoToAlbum(mContext, filePath);
            runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                    mContext.getResources().getString(R.string.dump_done), (dialogInterface, i) -> finish()));
        });
        try {
            mMediaRecorder.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        Log.i(TAG, "onSurfaceChanged: " + width + "x" + height + ", rotation = " + mRotation);

        if (mIsFullScreenRender) {
            mSrRatio = calculateSrRatio(width, height, mFrameWidth, mFrameHeight);
        }

        if (mCompareTexDrawer != null) {
            mCompareTexDrawer.onSurfaceChanged(width, height);
        }
        if (mVideoFrameDrawer != null) {
            mVideoFrameDrawer.onSurfaceChanged(width, height);
        }
        // Create the pass that convert TextureOES to Texture2D.
        mTexOESToTex2DPass = new OffScreenRenderPass();
        mTexOESToTex2DPass.init(GLES30.GL_TEXTURE_2D, mInputTextureOES.getWidth(), mInputTextureOES.getHeight(),
                "shaders/videoTexOES.frag");

        mBilinearRenderPass = new OffScreenRenderPass();
        mBilinearRenderPass.init(GLES30.GL_TEXTURE_2D, width, height, "shaders/videoTex2D.frag");

        initPass();
    }

    private static float calculateSrRatio(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        Log.i(TAG, "viewWidth = " + viewWidth + ", viewHeight = " + viewHeight + ", videoWidth = " + videoWidth
                + ", videoHeight = " + videoHeight);
        double videoRatio = 1.0 * videoWidth / videoHeight;
        double viewRatio = 1.0 * viewWidth / viewHeight;
        if (viewRatio > videoRatio) {
            return Math.max((float) viewHeight / videoHeight, 1.0f);
        } else {
            return Math.max((float) viewWidth / videoWidth, 1.0f);
        }
    }

    // 初始化算法对象
    private void initPass() {
        switch (mAlgorithm) {
            case NO_CMP:
            case NONE:
                mPrepareReady = true;
                tryStartDecode();
                break;

            case TIE_STD:
                mPrepareReady = mTieStd.init(mFrameWidth, mFrameHeight) == ErrorCode.SUCCESS;
                if (mPrepareReady) {
                    tryStartDecode();
                } else {
                    toastAndFinish("TIE_STD init failed");
                }
                break;

            case TIE_PRO:
                Executors.newSingleThreadExecutor().execute(() -> {
                    ErrorCode res = mTiePro.init(mFrameWidth, mFrameHeight);
                    if (res == ErrorCode.SUCCESS) {
                        // TiePro NPU 初始化成功后，在 GL 线程初始化 GL 资源
                        mGLSurfaceView.queueEvent(() -> {
                            mTieProProcessor.init(mFrameWidth, mFrameHeight);
                            mPrepareReady = true;
                            tryStartDecode();
                        });
                    } else {
                        Log.e(TAG, "mTiePro init failed: " + res);
                        toastAndFinish("TIE_PRO_NPU init failed");
                    }
                });
                break;

            case TSR_STD:
                mPrepareReady = mTsrStd.init(mFrameWidth, mFrameHeight, mOutputWidth, mOutputHeight)
                        == ErrorCode.SUCCESS;
                if (mPrepareReady) {
                    tryStartDecode();
                } else {
                    toastAndFinish("TSR_STD init failed");
                }
                break;

            default:
                Log.w(TAG, "Unhandled algorithm type: " + mAlgorithm);
                break;
        }

        switch (mAlgorithmCmp) {
            case NO_CMP:
            case NONE:
                mCmpPrepareReady = true;
                tryStartDecode();
                break;

            case TIE_STD:
                mCmpPrepareReady = mTieStdCmp.init(mFrameWidth, mFrameHeight) == ErrorCode.SUCCESS;
                if (mCmpPrepareReady) {
                    tryStartDecode();
                } else {
                    toastAndFinish("TIE_STD Cmp init failed");
                }
                break;

            case TIE_PRO:
                Executors.newSingleThreadExecutor().execute(() -> {
                    ErrorCode resCmp = mTieProCmp.init(mFrameWidth, mFrameHeight);
                    if (resCmp == ErrorCode.SUCCESS) {
                        // TieProCmp NPU 初始化成功后，在 GL 线程初始化 GL 资源
                        mGLSurfaceView.queueEvent(() -> {
                            mTieProCmpProcessor.init(mFrameWidth, mFrameHeight);
                            mCmpPrepareReady = true;
                            tryStartDecode();
                        });
                    } else {
                        Log.e(TAG, "mTieProCmp init failed: " + resCmp);
                        toastAndFinish("TIE_PRO_NPU Cmp init failed");
                    }
                });
                break;

            case TSR_STD:
                mCmpPrepareReady = mTsrStdCmp.init(mFrameWidth, mFrameHeight, mOutputWidth, mOutputHeight)
                        == ErrorCode.SUCCESS;
                if (mCmpPrepareReady) {
                    tryStartDecode();
                } else {
                    toastAndFinish("TSR_STD Cmp init failed");
                }
                break;

            default:
                Log.w(TAG, "Unhandled comparison algorithm type: " + mAlgorithmCmp);
                break;
        }
    }

    private void tryStartDecode() {
        Log.i(TAG,
                "tryStartDecode. mAlgorithm=" + mAlgorithm + ", mCompareAlgorithm=" + mAlgorithmCmp + ", mPrepareReady="
                        + mPrepareReady + ", mCmpPrepareReady=" + mCmpPrepareReady);
        if (mPrepareReady && mCmpPrepareReady) {
            startDecode();
        }
        // 由于 initPass() 方法中已经通过 mPrepareReady 和 mCmpPrepareReady 标志确保了所有算法对象都已成功初始化，并且只有在两个标志都为 true 时才会调用 startDecode() 开始解码和渲染流程，因此在 processByAlgorithm() 和 renderFrame() 方法中不再需要重复检查 isInitialized()。
    }

    private void toastAndFinish(String msg) {
        // 标记状态，以便后续流程可以判断状态。因为finish()是抛到主线程异步执行的。
        mIsFinishing = true;
        runOnUiThread(() -> {
            Log.w(TAG, "toastAndFinish() " + msg);
            Toast.makeText(CmpPlayActivity.this, msg, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private void startDecode() {
        // Initialize the decoder
        Log.i(TAG, "start decode");
        final long TIMEOUT_US = 10000;
        final long[] startTime = {-1};
        final long[] pauseTime = {0};
        final boolean[] pausing = {false};

        mHandlerThread = new HandlerThread("DecodeThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (mIsPause) {
                    if (!pausing[0]) {
                        // Record the pause time point for updating the start time
                        pauseTime[0] = System.currentTimeMillis();
                    }
                    pausing[0] = true;
                    sendEmptyMessage(0);
                    return;
                }
                if (startTime[0] == -1) {
                    startTime[0] = System.currentTimeMillis();
                }
                if (pausing[0]) {
                    pausing[0] = false;
                    startTime[0] += System.currentTimeMillis() - pauseTime[0];
                }

                int inputBufferIndex = mMediaCodec.dequeueInputBuffer(TIMEOUT_US);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(inputBufferIndex);
                    int sampleSize = mExtractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        mMediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        mMediaCodec.queueInputBuffer(inputBufferIndex, 0, sampleSize, mExtractor.getSampleTime(), 0);
                        mExtractor.advance();
                    }
                }
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufferIndex = mMediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    // All frames have been decoded
                    mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

                    mHandler.removeCallbacksAndMessages(null);

                    prepareDecoder();

                    mIsPause = false;
                    pausing[0] = false;
                    startTime[0] = -1;
                    pauseTime[0] = 0;

                    sendEmptyMessage(0);
                    return;
                }
                if (outputBufferIndex >= 0) {
                    // Get the estimated display time of this frame (milliseconds)
                    long presentationTimeMs = bufferInfo.presentationTimeUs / 1000;
                    // Calculate the elapsed time since the start of decoding
                    long elapsedTime = System.currentTimeMillis() - startTime[0];
                    // If the estimated display time is greater than the elapsed time, wait for a while
                    if (presentationTimeMs > elapsedTime) {
                        try {
                            Thread.sleep(presentationTimeMs - elapsedTime);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "mediacodec exception:" + e.getMessage());
                        }
                    }
                    mMediaCodec.releaseOutputBuffer(outputBufferIndex, true);

                    if (!mIsRecordVideo) {
                        sendEmptyMessage(0);
                    }
                } else {
                    sendEmptyMessage(0);
                }
            }
        };

        mHandler.sendEmptyMessage(0);
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (mTexOESToTex2DPass == null || mCompareTexDrawer == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }
        float[] transformMatrix = new float[16];
        boolean newFrame = false; // 标记是否是新帧
        if (updateTexture) {
            // 更新当前帧数据。先标记 updateTexture 为 false，再读取新纹理。注意如果不调用 updateTexImage() 读取新纹理，则不会再收到 onFrameAvailable() 回调。
            updateTexture = false;
            mSurfaceTexture.updateTexImage();
            mSurfaceTexture.getTransformMatrix(transformMatrix);
            mHasValidFrame = true;
            newFrame = true;

            mPlayFrameCount++;
            if (mIsRecordVideo) {
                mHandler.sendEmptyMessage(0);
            }
        }

        // 没有有效帧时直接返回（首帧之前）
        if (!mHasValidFrame) {
            return;
        } else if (newFrame || mIsTurnOffSRChange) {
            mIsTurnOffSRChange = false;

            /* Step 2: (Optional) If the type of your input texture is TextureOES, you must Convert TextureOES to Texture2D.*/
            mInputTexture2D = mTexOESToTex2DPass.render(mInputTextureOES.getTextureId(), mInputTextureOES.getType(),
                    transformMatrix);
            mProcessedTexture2D = mInputTexture2D;

            /* Step 3: Pass the input texture's id to TSRPass or TIEPass, and get the output texture's id. The output texture is the result of super-resolution or image enhance.*/
            processByAlgorithm();
        }

        /* Step 4: Use the TSRPass's output texture to do your own render. */
        if (mMediaRecorder != null) {
            if (newFrame) {
                // Dump the processed frames to .mp4
                dumpFrame(mProcessedTexture2D);
            }
        } else {
            // Render the processed frames to screen
            renderFrame();
        }

        FpsUtil.FpsData fpsData = FpsUtil.tryGetFPS();
        if (fpsData != null) {
            runOnUiThread(() -> {
                Log.v(TAG, "fps = " + fpsData.getFps() + ", avg fps = " + fpsData.getAvgFps());
            });
        }
    }

    private void processByAlgorithm() {
        if (mIsFinishing) {
            return;
        }
        switch (mAlgorithm) {
            case NONE:
                mProcessedTexture2D = mBilinearRenderPass.render(mInputTexture2D, GLES30.GL_TEXTURE_2D);
                break;
            case TIE_STD:
                mProcessedTexture2D = mTieStd.process(mInputTexture2D);
                break;
            case TIE_PRO:
                mProcessedTexture2D = mTieProProcessor.process(mInputTexture2D);
                break;
            case TSR_STD:
                mProcessedTexture2D = mTsrStd.process(mInputTexture2D);
                break;

        }
    }

    private void dumpFrame(int textureId) {
        mMediaRecorder.encodeFrame(textureId, System.nanoTime());
        if (mPlayFrameCount == mVideoFrameCount) {
            mMediaRecorder.stop();
        }
        runOnUiThread(() -> ProgressDialogUtils.updateText(
                "Exporting..." + (int) ((float) mPlayFrameCount / mVideoFrameCount * 100) + "%"));
    }

    private void renderFrame() {
        if (mIsFinishing) {
            return;
        }
        int cmpTextureId;
        switch (mAlgorithmCmp) {
            case NO_CMP:
                mVideoFrameDrawer.draw(mProcessedTexture2D);
                break;
            case NONE:
                cmpTextureId = mBilinearRenderPass.render(mInputTexture2D, GLES30.GL_TEXTURE_2D);
                mCompareTexDrawer.draw(mProcessedTexture2D, cmpTextureId);
                break;
            case TIE_STD:
                cmpTextureId = mTieStdCmp.process(mInputTexture2D);
                mCompareTexDrawer.draw(mProcessedTexture2D, cmpTextureId);
                break;
            case TIE_PRO:
                cmpTextureId = mTieProCmpProcessor.process(mInputTexture2D);
                mCompareTexDrawer.draw(mProcessedTexture2D, cmpTextureId);
                break;
            case TSR_STD:
                cmpTextureId = mTsrStdCmp.process(mInputTexture2D);
                mCompareTexDrawer.draw(mProcessedTexture2D, cmpTextureId);
                break;

        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        updateTexture = true;
        //Log.v(TAG, "onFrameAvailable");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 将所有涉及 OpenGL 资源的释放放到 GL 线程执行
        if (mGLSurfaceView != null) {
            mGLSurfaceView.queueEvent(() -> {
                // 释放算法对象
                if (mTieStd != null) {
                    mTieStd.release();
                    mTieStd = null;
                }
                if (mTiePro != null) {
                    mTiePro.release();
                    mTiePro = null;
                }
                if (mTsrStd != null) {
                    mTsrStd.release();
                    mTsrStd = null;
                }
                if (mTieStdCmp != null) {
                    mTieStdCmp.release();
                    mTieStdCmp = null;
                }
                if (mTieProCmp != null) {
                    mTieProCmp.release();
                    mTieProCmp = null;
                }
                if (mTsrStdCmp != null) {
                    mTsrStdCmp.release();
                    mTsrStdCmp = null;
                }
                // 释放 TieProGlProcessor
                if (mTieProProcessor != null) {
                    mTieProProcessor.release();
                    mTieProProcessor = null;
                }
                if (mTieProCmpProcessor != null) {
                    mTieProCmpProcessor.release();
                    mTieProCmpProcessor = null;
                }
                // 释放渲染 pass
                if (mBilinearRenderPass != null) {
                    mBilinearRenderPass.release();
                    mBilinearRenderPass = null;
                }
                if (mTexOESToTex2DPass != null) {
                    mTexOESToTex2DPass.release();
                    mTexOESToTex2DPass = null;
                }
                // 释放纹理
                if (mInputTextureOES != null) {
                    mInputTextureOES.release();
                    mInputTextureOES = null;
                }
                // 释放绘制器
                if (mCompareTexDrawer != null) {
                    mCompareTexDrawer.release();
                    mCompareTexDrawer = null;
                }
                if (mVideoFrameDrawer != null) {
                    mVideoFrameDrawer.release();
                    mVideoFrameDrawer = null;
                }
            });
            mGLSurfaceView = null;
        }

        if (mMediaRecorder != null) {
            mMediaRecorder = null;
        }

        // 停止 HandlerThread 的消息循环
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
            try {
                mHandlerThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, Objects.requireNonNull(e.getMessage()));
            }
            mHandlerThread = null;
        }
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }
        if (mMediaCodec != null) {
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
        if (mSurfaceTexture != null) {
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        FpsUtil.reset();
    }

    @Override
    public void onBackPressed() {
        if (mBackPressed + 2000 > System.currentTimeMillis()) {
            super.onBackPressed();
        } else {
            Toast.makeText(this, R.string.slide_again, Toast.LENGTH_SHORT).show();
            mBackPressed = System.currentTimeMillis();
        }
    }

    // 定义算法枚举
    public enum Algorithm {
        NO_CMP,
        NONE,
        TIE_STD,
        TIE_PRO,
        TSR_STD;

        private String description;

        public static void initializeDescriptions() {
            Algorithm.NO_CMP.setDescription("NoCmp");
            Algorithm.NONE.setDescription("None");
            Algorithm.TIE_STD.setDescription("TieStd");
            Algorithm.TIE_PRO.setDescription("TiePro");
            Algorithm.TSR_STD.setDescription("TsrStd");
        }

        public void setDescription(String description) {
            this.description = description;
        }

        @NonNull
        @Override
        public String toString() {
            return description;
        }
    }
}