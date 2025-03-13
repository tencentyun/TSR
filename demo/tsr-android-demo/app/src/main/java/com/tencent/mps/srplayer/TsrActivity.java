package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
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
import com.tencent.mps.srplayer.helper.TapHelper;
import com.tencent.mps.srplayer.opengl.Texture;
import com.tencent.mps.srplayer.pass.CompareTexDrawer;
import com.tencent.mps.srplayer.pass.OffScreenRenderPass;
import com.tencent.mps.srplayer.pass.VideoFrameDrawer;
import com.tencent.mps.srplayer.record.MediaRecorder;
import com.tencent.mps.srplayer.utils.DialogUtils;
import com.tencent.mps.srplayer.utils.FileUtils;
import com.tencent.mps.srplayer.utils.FpsUtil;
import com.tencent.mps.srplayer.utils.ProgressDialogUtils;
import com.tencent.mps.tie.api.TIEPass;
import com.tencent.mps.tie.api.TIEPass.TIEInitStatusCode;
import com.tencent.mps.tie.api.TSRPass;
import com.tencent.mps.tie.api.TSRPass.TSRAlgorithmType;
import com.tencent.mps.tie.api.TSRPass.TSRInitStatusCode;
import com.tencent.mps.tie.api.TSRSdk;
import com.tencent.mps.tie.api.TSRSdk.TSRSdkLicenseStatus;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class TsrActivity extends AppCompatActivity implements GLSurfaceView.Renderer, OnFrameAvailableListener {

    private static final String TAG = "TsrActivity";
    // Time interval for double swipe to exit
    private static final long TIME_EXIT = 2000;
    /**
     * --------------------------------- THE PARAMS FOR SDK VERIFICATION----------------------------------------
     */
    // Modify mAppId to your APPID which can be found in Tencent Cloud account.
    private final long mAppId = -1;
    // Modify mAuthId to your authentication ID, which can be obtained from the Tencent Cloud MPS Team.
    private final int mAuthId = 0;
    /**
     * ---------------------------------------------------------------------------------------------------------
     */

    private final Context mContext = this;
    int totalCost = 0;
    int i = 0;
    ExecutorService mSingleThreadExecutor = Executors.newSingleThreadExecutor();
    // Flag indicating if the video is currently paused
    private boolean mIsPause;
    // Flag indicating if there is a new frame
    private volatile boolean updateTexture;
    // SurfaceTexture bound to MediaPlayer
    private SurfaceTexture mSurfaceTexture;
    // Super-resolution pass
    private TSRPass mTSRPass;
    // Image enhance pass
    private TIEPass mTIEPass;
    // Super-resolution pass for compare
    private TSRPass mTSRPassCmp;
    // Image enhance pass for compare
    private TIEPass mTIEPassCmp;
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
    private volatile Algorithm mCompareAlgorithm;
    // The Algorithm to switch
    private volatile Algorithm mSwitchAlgorithm;
    // Is turn off SR
    private volatile boolean mIsTurnOffSR;
    // InputTexture
    private Texture mInputTexture;
    // MediaRecorder
    private MediaRecorder mMediaRecorder;
    // Video rotation
    private volatile int mRotation;
    // Is record video?
    private boolean mIsRecordVideo;
    private GLSurfaceView mGLSurfaceView;
    private volatile int mPlayFrameCount = 0;
    private volatile long mVideoFrameCount = 0;
    private MediaExtractor mExtractor;
    private volatile MediaCodec mMediaCodec;
    private String mFileName;
    private float mFrameRate;
    private String mExportCodecType;
    private int mExportBitrateMbps;
    private int mOutputWidth;
    private int mOutputHeight;
    private boolean mIsFullScreenRender;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    // Double swipe to exit
    private long mBackPressed;

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

                    mInputTexture = new Texture(false, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, mFrameWidth, mFrameHeight);
                    mSurfaceTexture = new SurfaceTexture(mInputTexture.getTextureId());
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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Algorithm.initializeDescriptions(mContext);

        mIsFullScreenRender = false;

        mFileName = getIntent().getStringExtra("file_name");
        mExportCodecType = getIntent().getStringExtra("export_codec");
        mExportBitrateMbps = getIntent().getIntExtra("export_bitrate", 10);
        mIsRecordVideo = getIntent().getBooleanExtra("export_video", false);
        String algorithm = getIntent().getStringExtra("algorithm");
        switch (Objects.requireNonNull(algorithm)) {
            case "None":
            case "无":
                mAlgorithm = Algorithm.NORMAL;
                break;
            case "Standard SR":
            case "标准版超分":
                mAlgorithm = Algorithm.SR_STD;
                break;
            case "Standard SR+Enhancement":
            case "标准版超分+增强":
                mAlgorithm = Algorithm.SR_STD_EH;
                break;
            case "Professional SR":
            case "专业版超分":
                mAlgorithm = Algorithm.SR_PRO;
                break;
            case "Professional Enhancement":
            case "专业版增强":
                mAlgorithm = Algorithm.IE_PRO;
                break;
            case "Standard Enhancement":
            case "标准版增强":
                mAlgorithm = Algorithm.IE_STD;
                break;
            case "Professional SR+Enhancement":
            case "专业版超分+增强":
                mAlgorithm = Algorithm.SR_PRO_EH;
                break;
        }
        String compareAlgorithm = getIntent().getStringExtra("compare_algorithm");
        switch (Objects.requireNonNull(compareAlgorithm)) {
            case "No Comparison":
            case "不对比":
                mCompareAlgorithm = Algorithm.NONE;
                break;
            case "None":
            case "无":
                mCompareAlgorithm = Algorithm.NORMAL;
                break;
            case "Standard SR":
            case "标准版超分":
                mCompareAlgorithm = Algorithm.SR_STD;
                break;
            case "Standard SR+Enhancement":
            case "标准版超分+增强":
                mCompareAlgorithm = Algorithm.SR_STD_EH;
                break;
            case "Professional SR":
            case "专业版超分":
                mCompareAlgorithm = Algorithm.SR_PRO;
                break;
            case "Professional Enhancement":
            case "专业版增强":
                mCompareAlgorithm = Algorithm.IE_PRO;
                break;
            case "Standard Enhancement":
            case "标准版增强":
                mCompareAlgorithm = Algorithm.IE_STD;
                break;
            case "Professional SR+Enhancement":
            case "专业版超分+增强":
                mCompareAlgorithm = Algorithm.SR_PRO_EH;
                break;
        }

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

        mRotation = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));
        Log.i(TAG, "rotation = " + mRotation);

        // Init TSRSdk
        TSRSdk.getInstance().init(getApplicationContext(), mAppId, mAuthId, status -> {
            if (status == TSRSdkLicenseStatus.AVAILABLE) {
                Log.i(TAG, "Online verify sdk license success: " + status);
                // Initialize the Pass for super-resolution/enhanced rendering
                if (mAlgorithm == Algorithm.SR_STD) {
                    mTSRPass = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);
                } else if (mAlgorithm == Algorithm.SR_STD_EH) {
                    mTSRPass = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD_COLOR_RETOUCHING_EXT);
                } else if (mAlgorithm == Algorithm.SR_PRO) {
                    mTSRPass = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL);
                } else if (mAlgorithm == Algorithm.SR_PRO_EH) {
                    mTSRPass = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL_COLOR_RETOUCHING_EXT);
                } else if (mAlgorithm == Algorithm.IE_STD) {
                    mTIEPass = new TIEPass(TIEPass.TIEAlgorithmType.STANDARD);
                } else if (mAlgorithm == Algorithm.IE_PRO) {
                    mTIEPass = new TIEPass(TIEPass.TIEAlgorithmType.PROFESSIONAL);
                }

                if (mCompareAlgorithm == Algorithm.SR_STD) {
                    mTSRPassCmp = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);
                } else if (mCompareAlgorithm == Algorithm.SR_STD_EH) {
                    mTSRPassCmp = new TSRPass(TSRAlgorithmType.STANDARD_COLOR_RETOUCHING_EXT);
                } else if (mCompareAlgorithm == Algorithm.SR_PRO) {
                    mTSRPassCmp = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL);
                } else if (mCompareAlgorithm == Algorithm.SR_PRO_EH) {
                    mTSRPassCmp = new TSRPass(TSRAlgorithmType.PROFESSIONAL_COLOR_RETOUCHING_EXT);
                } else if (mCompareAlgorithm == Algorithm.IE_STD) {
                    mTIEPassCmp = new TIEPass(TIEPass.TIEAlgorithmType.STANDARD);
                } else if (mCompareAlgorithm == Algorithm.IE_PRO) {
                    mTIEPassCmp = new TIEPass(TIEPass.TIEAlgorithmType.PROFESSIONAL);
                }
                runOnUiThread(() -> initView(mIsRecordVideo, mAlgorithm, mCompareAlgorithm));
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
            configureMediaRecorder(mFileName, mOutputWidth,
                    mOutputHeight, mFrameRate, mExportBitrateMbps, mExportCodecType);
        }

        startDecode();
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        Log.i(TAG, "onSurfaceChanged: " + width + "x" + height + ", rotation = " + mRotation);

        /*-------------------------------- Step 1: init the TSRPass. -------------------------------------------*/
        if (mIsFullScreenRender) {
            mSrRatio = calculateSrRatio(width, height, mFrameWidth, mFrameHeight);
        }

        if (isSrAlgorithm(mAlgorithm)) {
            long ss = System.currentTimeMillis();
            TSRPass.TSRInitStatusCode error = mTSRPass.init(7, 7, mSrRatio);
            long start = System.currentTimeMillis();
            Log.i(TAG, "init cost = " + (start - ss));
            error = mTSRPass.reInit(mFrameWidth, mFrameHeight, mSrRatio);
            long end = System.currentTimeMillis();
            Log.i(TAG, "reinit cost = " + (end - start));
            String errorMsg = "";
            switch (error) {
                case SUCCESS:
                    Log.i(TAG, "TSRPass init success.");
                    break;
                case OPENGL_ES_VERSION_LOWER_THAN_3_1:
                case SDK_LICENSE_STATUS_NOT_AVAILABLE:
                case ALGORITHM_TYPE_INVALID:
                case ML_MODEL_INIT_FAILED:
                case INPUT_RESOLUTION_INVALID:
                case INSTANCE_NOT_EXIST:
                case ML_MODEL_NOT_CONFIGURE:
                    errorMsg = "TSRPass initialization failed. " + error.getDescription();
                    String finalErrorMsg = errorMsg;
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format(finalErrorMsg),
                            (dialogInterface, i) -> {
                            }));
                    break;
                default:
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format("Unknown error"),
                            (dialogInterface, i) -> {
                            }));
            }
        } else if (mAlgorithm == Algorithm.IE_PRO || mAlgorithm == Algorithm.IE_STD) {
            long ss = System.currentTimeMillis();
            TIEPass.TIEInitStatusCode error = mTIEPass.init(200, 200);
            long start = System.currentTimeMillis();
            Log.i(TAG, "init cost = " + (start - ss));
            error = mTIEPass.reInit(mFrameWidth, mFrameHeight);
            long end = System.currentTimeMillis();
            Log.i(TAG, "reinit cost = " + (end - start));

            String errorMsg = "";
            switch (error) {
                case SUCCESS:
                    Log.i(TAG, "TIEPass init success.");
                    break;
                case OPENGL_ES_VERSION_LOWER_THAN_3_1:
                case SDK_LICENSE_STATUS_NOT_AVAILABLE:
                case ALGORITHM_TYPE_INVALID:
                case ML_MODEL_INIT_FAILED:
                case INPUT_RESOLUTION_INVALID:
                case INSTANCE_NOT_EXIST:
                case ML_MODEL_NOT_CONFIGURE:
                    errorMsg = "TIEPass initialization failed. " + error.getDescription();
                    String finalErrorMsg = errorMsg;
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format(finalErrorMsg),
                            (dialogInterface, i) -> {
                            }));
                    break;
                default:
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format("Unknown error"),
                            (dialogInterface, i) -> {
                            }));
            }
        }

        if (isSrAlgorithm(mCompareAlgorithm)) {
            TSRPass.TSRInitStatusCode error = mTSRPassCmp.init(mFrameWidth, mFrameHeight, mSrRatio);
            String errorMsg = "";
            switch (error) {
                case SUCCESS:
                    Log.i(TAG, "TSRPass init success.");
                    break;
                case OPENGL_ES_VERSION_LOWER_THAN_3_1:
                case SDK_LICENSE_STATUS_NOT_AVAILABLE:
                case ALGORITHM_TYPE_INVALID:
                case ML_MODEL_INIT_FAILED:
                case INPUT_RESOLUTION_INVALID:
                case INSTANCE_NOT_EXIST:
                case ML_MODEL_NOT_CONFIGURE:
                    errorMsg = "TSRPass initialization failed. " + error.getDescription();
                    String finalErrorMsg = errorMsg;
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format(finalErrorMsg),
                            (dialogInterface, i) -> {
                            }));
                    break;
                default:
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format("Unknown error"),
                            (dialogInterface, i) -> {
                            }));
            }

        } else if (mCompareAlgorithm == Algorithm.IE_PRO || mCompareAlgorithm == Algorithm.IE_STD) {
            TIEPass.TIEInitStatusCode error = mTIEPassCmp.init(mFrameWidth, mFrameHeight);
            String errorMsg = "";
            switch (error) {
                case SUCCESS:
                    Log.i(TAG, "TIEPass init success.");
                    break;
                case OPENGL_ES_VERSION_LOWER_THAN_3_1:
                case SDK_LICENSE_STATUS_NOT_AVAILABLE:
                case ALGORITHM_TYPE_INVALID:
                case ML_MODEL_INIT_FAILED:
                case INPUT_RESOLUTION_INVALID:
                case INSTANCE_NOT_EXIST:
                case ML_MODEL_NOT_CONFIGURE:
                    errorMsg = "TIEPass initialization failed. " + error.getDescription();
                    String finalErrorMsg = errorMsg;
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format(finalErrorMsg),
                            (dialogInterface, i) -> {
                            }));
                    break;
                default:
                    runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                            String.format("Unknown error"),
                            (dialogInterface, i) -> {
                            }));
            }
        }

        if (mCompareTexDrawer != null) {
            mCompareTexDrawer.onSurfaceChanged(width, height);
        }
        if (mVideoFrameDrawer != null) {
            mVideoFrameDrawer.onSurfaceChanged(width, height);
        }
        // Create the pass that convert TextureOES to Texture2D.
        mTexOESToTex2DPass = new OffScreenRenderPass();
        mTexOESToTex2DPass.init(GLES30.GL_TEXTURE_2D,
                mInputTexture.getWidth(), mInputTexture.getHeight(), "shaders/videoTexOES.frag");

        mBilinearRenderPass = new OffScreenRenderPass();
        mBilinearRenderPass.init(GLES30.GL_TEXTURE_2D, width,
                height, "shaders/videoTex2D.frag");
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (mTexOESToTex2DPass == null || mCompareTexDrawer == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }

        boolean newFrame = false;

        if (updateTexture) {
            updateTexture = false;
            mSurfaceTexture.updateTexImage();

            newFrame = true;
            mPlayFrameCount++;
            if (mIsRecordVideo) {
                mHandler.sendEmptyMessage(0);
            }
        }

        float[] transformMatrix = new float[16];
        mSurfaceTexture.getTransformMatrix(transformMatrix);

        /* Step 2: (Optional) If the type of your input texture is TextureOES, you must Convert TextureOES to Texture2D.*/
        int tex2dId = mTexOESToTex2DPass.render(mInputTexture.getTextureId(), mInputTexture.getType(), transformMatrix);

        int processTextureId = -1;
        int cmpTextureId;
        /* Step 3: Pass the input texture's id to TSRPass or TIEPass, and get the output texture's id. The output
        texture is the result of super-resolution or image enhance.*/

        switch (mAlgorithm) {
            case NORMAL:
                processTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D);
                break;
            case SR_STD:
            case SR_STD_EH:
            case SR_PRO:
            case SR_PRO_EH:
                if (mTSRPass == null) {
                    Log.w(TAG, "mTSRPass is null!");
                    break;
                }
                long startTime = System.currentTimeMillis();
                processTextureId = mTSRPass.render(tex2dId);
                long cost = System.currentTimeMillis() - startTime;
                totalCost += cost;
                i++;
                if (i % 60 == 0) {
                    if (cost <= 33) {
                        Log.v(TAG, "cost = " + cost + ", avgCost = " + totalCost / i);
                    } else {
                        Log.e(TAG, "cost = " + cost + ", avgCost = " + totalCost / i);
                    }
                }
                break;
            case IE_PRO:
            case IE_STD:
                if (mTIEPass == null) {
                    Log.w(TAG, "mTIEPass is null!");
                    break;
                }
                processTextureId = mTIEPass.render(tex2dId);
                break;
        }


        /* Step 4: Use the TSRPass's output texture to do your own render. */
        if (mMediaRecorder != null) {
            if (newFrame) {
                // Dump the processed frames to .mp4
                dumpFrame(processTextureId);
            }
        } else {
            // Render the processed frames to screen
            switch (mCompareAlgorithm) {
                case NONE:
                    mVideoFrameDrawer.draw(processTextureId);
                    break;
                case NORMAL:
                    if (mBilinearRenderPass == null) {
                        Log.w(TAG, "mBilinearRenderPass is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
                case SR_STD:
                case SR_STD_EH:
                case SR_PRO:
                case SR_PRO_EH:
                    if (mTSRPassCmp == null) {
                        Log.w(TAG, "mTSRPassCmp is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mTSRPassCmp.render(tex2dId);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
                case IE_PRO:
                case IE_STD:
                    if (mTIEPassCmp == null) {
                        Log.w(TAG, "mTIEPass is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mTIEPassCmp.render(tex2dId);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
            }
        }

        FpsUtil.FpsData fpsData = FpsUtil.tryGetFPS();
        if (fpsData != null) {
            runOnUiThread(() -> {
                Log.v(TAG, "fps = " + fpsData.getFps() + ", avg fps = " + fpsData.getAvgFps());
                // show fps
                TextView fpsView = findViewById(R.id.fps);
                String fps = "FPS: " + (int) fpsData.getFps();
                fpsView.setText(fps);

                // show avg fps
                TextView avgFpsView = findViewById(R.id.avgFps);
                String avgFps = "AVG_FPS: " + (int) fpsData.getAvgFps();
                avgFpsView.setText(avgFps);
            });
        }
    }

    @Override
    public void onFrameAvailable(SurfaceTexture surfaceTexture) {
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
        if (mGLSurfaceView != null) {
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
        if (mInputTexture != null) {
            mInputTexture.release();
            mInputTexture = null;
        }
        if (mCompareTexDrawer != null) {
            mCompareTexDrawer.release();
            mCompareTexDrawer = null;
        }
        if (mVideoFrameDrawer != null) {
            mVideoFrameDrawer.release();
            mVideoFrameDrawer = null;
        }
        FpsUtil.reset();
        if (mTIEPass != null) {
            mTIEPass.deInit();
            mTIEPass = null;
        }
        if (mTIEPassCmp != null) {
            mTIEPassCmp.deInit();
            mTIEPassCmp = null;
        }
        if (mTSRPass != null) {
            mTSRPass.deInit();
            mTSRPass = null;
        }
        if (mTSRPassCmp != null) {
            mTSRPassCmp.deInit();
            mTSRPassCmp = null;
        }
        TSRSdk.getInstance().deInit();
    }

    private void configureMediaRecorder(String fileName, int frameWidth, int frameHeight, float frameRate,
            int bitrateMbps, String codecType) {
        String filePath = mContext.getExternalFilesDir("dump_video/") + "/" +
                fileName.split("\\.")[0] + "_" + mSrRatio + "x_" + mAlgorithm + "_" + codecType + "_" + bitrateMbps
                + "M_" +
                System.currentTimeMillis() + ".mp4";
        mMediaRecorder = new MediaRecorder(mContext, filePath,
                frameWidth, frameHeight, mRotation,
                frameRate, bitrateMbps, codecType, EGL14.eglGetCurrentContext());
        mMediaRecorder.setOnRecordFinishListener(path -> {
            runOnUiThread(ProgressDialogUtils::hideProgressDialog);
            FileUtils.saveVideoToAlbum(mContext, filePath);
            runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext,
                    mContext.getResources().getString(R.string.dump_done),
                    (dialogInterface, i) -> finish()));
        });
        try {
            mMediaRecorder.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
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
            if (mCompareAlgorithm == Algorithm.NONE) {
                switchSRButton.setOnClickListener(view -> {
                    if (mIsTurnOffSR) {
                        mAlgorithm = mSwitchAlgorithm;
                        mIsTurnOffSR = false;
                        switchSRButton.setText(R.string.turn_off_sr);
                    } else {
                        mSwitchAlgorithm = mAlgorithm;
                        mAlgorithm = Algorithm.NORMAL;
                        mIsTurnOffSR = true;
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
        if (cmpAlgorithm == Algorithm.NONE) {
            sr.setVisibility(View.INVISIBLE);
            cmp.setVisibility(View.INVISIBLE);
        } else {
            sr.setText(srAlgorithm.toString());
            cmp.setText(cmpAlgorithm.toString());
        }

        mGLSurfaceView = new GLSurfaceView(mContext);
        mGLSurfaceView.setEGLContextClientVersion(2);
        mGLSurfaceView.setRenderer(TsrActivity.this);

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
        mGLSurfaceView.setLayoutParams(new LayoutParams((int) mOutputWidth,
                (int) mOutputHeight));

        FrameLayout frameLayout = findViewById(R.id.video_view);
        frameLayout.addView(mGLSurfaceView);
        frameLayout.setOnTouchListener(TapHelper.getInstance());
    }

    @Override
    public void onBackPressed() {
        if (mBackPressed + TIME_EXIT > System.currentTimeMillis()) {
            super.onBackPressed();
        } else {
            Toast.makeText(this, R.string.slide_again, Toast.LENGTH_SHORT).show();
            mBackPressed = System.currentTimeMillis();
        }
    }

    private float calculateSrRatio(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
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

    private void dumpFrame(int textureId) {
        mMediaRecorder.encodeFrame(textureId, System.nanoTime());
        if (mPlayFrameCount == mVideoFrameCount) {
            mMediaRecorder.stop();
        }
        runOnUiThread(() -> ProgressDialogUtils.updateText(
                "Exporting..." + (int) ((float) mPlayFrameCount / mVideoFrameCount * 100) + "%"));
    }

    private boolean isSrAlgorithm(Algorithm algorithm) {
        return algorithm == Algorithm.SR_STD || algorithm == Algorithm.SR_STD_EH ||
                algorithm == Algorithm.SR_PRO || algorithm == Algorithm.SR_PRO_EH;
    }

    public enum Algorithm {
        NONE, // no render
        NORMAL, // render directly
        IE_STD, // Enhanced(STD)
        SR_STD, // Super-Resolution(STD)
        SR_STD_EH, // Super-Resolution(STD-Enhanced Params)
        IE_PRO,// Enhanced(PRO)
        SR_PRO, // Super-Resolution(PRO)
        SR_PRO_EH;

        private String description;

        public static void initializeDescriptions(Context context) {
            Resources res = context.getResources();
            String[] descriptions = res.getStringArray(R.array.compare_algorithm);

            if (descriptions.length >= Algorithm.values().length) {
                Algorithm.NONE.setDescription(descriptions[0]);
                Algorithm.NORMAL.setDescription(descriptions[1]);
                Algorithm.IE_STD.setDescription(descriptions[2]);
                Algorithm.SR_STD.setDescription(descriptions[3]);
                Algorithm.SR_STD_EH.setDescription(descriptions[4]);
                Algorithm.IE_PRO.setDescription(descriptions[5]);
                Algorithm.SR_PRO.setDescription(descriptions[6]);
                Algorithm.SR_PRO_EH.setDescription(descriptions[7]);

            } else {
                throw new IllegalStateException(
                        "The array length in arrays.xml does not match the number of enum constants.");
            }
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