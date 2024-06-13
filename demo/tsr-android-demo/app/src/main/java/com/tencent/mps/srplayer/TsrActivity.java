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
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.DisplayMetrics;
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
import com.tencent.mps.srplayer.record.MediaRecorder;
import com.tencent.mps.srplayer.utils.DialogUtils;
import com.tencent.mps.srplayer.utils.FileUtils;
import com.tencent.mps.srplayer.utils.ProgressDialogUtils;
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
    // Super-resolution pass
    private TSRPass mTSRPassStandard;
    private TSRPass mTSRPassStandardWithParams;
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
    // MediaRecorder
    private MediaRecorder mMediaRecorder;
    // Video rotation
    private volatile int mRotation;
    // Is record video?
    private boolean mIsRecordVideo;
    private volatile int mPlayFrameCount = 0;
    private volatile long mVideoFrameCount = 0;
    private long costCount = 0;
    private final Context mContext = this;
    private MediaExtractor mExtractor;
    private volatile MediaCodec mMediaCodec;
    private volatile boolean mStopDecode;
    private String mFileName;
    private String mCodecType;
    private float mFrameRate;
    private int mBitrateMbps;
    private int mOutputWidth;
    private int mOutputHeight;
    private boolean mIsFullScreenRender;
    private final Object mDumpLock = new Object();

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

                        if (mIsRecordVideo) {
                            synchronized (mDumpLock) {
                                mDumpLock.wait();
                            }
                        }
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

        mIsFullScreenRender = false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mFileName = sharedPreferences.getString("video_title", "");
        String srRatio = sharedPreferences.getString("sr_ratio", "1.0");
        if ("全屏自适应".equals(srRatio)) {
            mIsFullScreenRender = true;
        } else {
            mSrRatio = Float.parseFloat(srRatio);
        }

        mAlgorithm = sharedPreferences.getString("video_algorithm", "");
        mCompareAlgorithm = sharedPreferences.getString("compare_algorithm", "");
        mCodecType = sharedPreferences.getString("codec_type", "H264");
        mBitrateMbps = Integer.parseInt(sharedPreferences.getString("bitrate_mbps", "20"));
        mIsRecordVideo = getIntent().getBooleanExtra("export_video", false);
        if ("专业版增强".equals(mAlgorithm)) {
            // Not use SR
            mSrRatio = 1.0f;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        mExtractor = new MediaExtractor();
        try {
            String uriString = getIntent().getStringExtra("videoUri");
            if (uriString != null) {
                Uri videoUri = Uri.parse(uriString);
                if (videoUri != null) {
                    mFileName = getIntent().getStringExtra("fileName");
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
        TSRSdk.getInstance().init(mAppId, status -> {
            if (status == TSRSdkLicenseStatus.AVAILABLE) {
                Log.i(TAG, "Online verify sdk license success: " + status);
                mTSRPassStandard = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);
                mTSRPassStandardWithParams = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);
                mTSRPassProfessional = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL);
                mTIEPass = new TIEPass();
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
            //获取视频时长，单位：毫秒(ms)
            String duration_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration_s != null) {
                long duration = Long.parseLong(duration_s);

                //获取视频帧数
                int frameRate = 0;
                String count_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                if (count_s != null) {
                    mVideoFrameCount = Long.parseLong(count_s);
                    //计算帧率
                    float dt = (float) duration / mVideoFrameCount; // 平均每帧的时间间隔
                    mFrameRate = Math.round(1000 / dt * 100) * 0.01f; // 帧率

                    Log.i(TAG, "video frame rate = " + mFrameRate + ", frame count = " + mVideoFrameCount);
                }

                ProgressDialogUtils.showProgressDialog(this, "Exporting...");
            }
        }
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

        if (mIsRecordVideo) {
            configureMediaRecorder(mFileName, mOutputWidth,
                    mOutputHeight, mFrameRate, mBitrateMbps, mCodecType);
        }

        startDecode();
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        Log.i(TAG, "onSurfaceChanged: " + width + "x" + height + ", rotation = " + mRotation);
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

        /*-------------------------------- Step 1: init the TSRPass. -------------------------------------------*/
        if (mIsFullScreenRender) {
            mSrRatio = calculateSrRatio(width, height, mFrameWidth, mFrameHeight);
        }
        mTSRPassStandard.init(mFrameWidth, mFrameHeight, mSrRatio);
        mTSRPassStandardWithParams.init(mFrameWidth, mFrameHeight, mSrRatio);
        // Optional. Sets the brightness, saturation and contrast level of the TSRPass. The default value is set to (50, 50, 50).
        // Here we set these parameters to slightly enhance the image.
        mTSRPassStandardWithParams.setParameters(51, 52, 55);
        mTSRPassProfessional.init(mFrameWidth, mFrameHeight, mSrRatio);
        mTIEPass.init(mFrameWidth, mFrameHeight);
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (mTexOESToTex2DPass == null || mCompareTexDrawer == null || mTSRPassStandard == null ||
                mTSRPassStandardWithParams == null || mTSRPassProfessional == null ||
                mTIEPass == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }

        long startTime = System.currentTimeMillis();

        boolean newFrame = false;
        if (updateTexture) {
            mSurfaceTexture.updateTexImage();
            updateTexture = false;
            newFrame = true;
            mPlayFrameCount++;

            if (mIsRecordVideo) {
                synchronized (mDumpLock) {
                    mDumpLock.notifyAll();
                }
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
            case "普通播放":
                processTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D);
                break;
            case "超分播放(标准版)":
                if (mTSRPassStandard == null) {
                    Log.w(TAG, "mTSRPassStandard is null!");
                    break;
                }
                processTextureId = mTSRPassStandard.render(tex2dId);
                break;
            case "超分播放(标准版+增强参数)":
                if (mTSRPassStandardWithParams == null) {
                    Log.w(TAG, "mTSRPassStandardWithParams is null!");
                    break;
                }
                processTextureId = mTSRPassStandardWithParams.render(tex2dId);
                break;
            case "超分播放(专业版)":
                if (mTSRPassProfessional == null) {
                    Log.w(TAG, "mTSRPassProfessional is null!");
                    break;
                }
                processTextureId = mTSRPassProfessional.render(tex2dId);
                break;
            case "增强播放(专业版)":
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
                case "不对比":
                    mVideoFrameDrawer.draw(processTextureId);
                    if (newFrame) {
                        long cost = System.currentTimeMillis() - startTime;
                        costCount += cost;
                        Log.v(TAG, "cost = " + cost + ", avg cost = " + costCount / mPlayFrameCount);
                    }
                    break;
                case "普通播放":
                    if (mBilinearRenderPass == null) {
                        Log.w(TAG, "mBilinearRenderPass is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
                case "超分播放(标准版)":
                    if (mTSRPassStandard == null) {
                        Log.w(TAG, "mTSRPassStandard is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mTSRPassStandard.render(tex2dId);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
                case "超分播放(标准版+增强参数)":
                    if (mTSRPassStandardWithParams == null) {
                        Log.w(TAG, "mTSRPassStandardWithParams is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mTSRPassStandardWithParams.render(tex2dId);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
                case "超分播放(专业版)":
                    if (mTSRPassProfessional == null) {
                        Log.w(TAG, "mTSRPassProfessional is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mTSRPassProfessional.render(tex2dId);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
                case "增强播放(专业版)":
                    if (mTIEPass == null) {
                        Log.w(TAG, "mTIEPass is null!");
                        mVideoFrameDrawer.draw(processTextureId);
                        break;
                    }
                    cmpTextureId = mTIEPass.render(tex2dId);
                    mCompareTexDrawer.draw(processTextureId, cmpTextureId);
                    break;
            }
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
        if (mMediaRecorder != null) {
            mMediaRecorder = null;
        }
        mStopDecode = true;
        TSRSdk.getInstance().release();
    }

    private void configureMediaRecorder(String fileName, int frameWidth, int frameHeight, float frameRate, int bitrateMbps, String codecType) {
        String filePath = mContext.getExternalFilesDir("dump_video/") + "/" +
                fileName.split("\\.")[0]+ "_" + mSrRatio + "x_" + mAlgorithm + "_" + codecType + "_" + bitrateMbps + "M_" +
                System.currentTimeMillis() + ".mp4";
        mMediaRecorder = new MediaRecorder(mContext, filePath,
                frameWidth, frameHeight, mRotation,
                frameRate, bitrateMbps, codecType, EGL14.eglGetCurrentContext());
        mMediaRecorder.setOnRecordFinishListener(path -> {
            ProgressDialogUtils.hideProgressDialog();
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

    private void initView(boolean isRecordVideo, String srAlgorithm, String cmpAlgorithm) {
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
        }

        TextView sr = findViewById(R.id.sr);
        TextView cmp = findViewById(R.id.cmp_algorithm);

        // Whether to compare bilinear
        if ("不对比".equals(cmpAlgorithm)) {
            sr.setVisibility(View.INVISIBLE);
            cmp.setVisibility(View.INVISIBLE);
        } else {
            sr.setText(srAlgorithm);
            cmp.setText(cmpAlgorithm);
        }

        GLSurfaceView glSurfaceView = new GLSurfaceView(mContext);
        glSurfaceView.setEGLContextClientVersion(3);
        glSurfaceView.setRenderer(TsrActivity.this);

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
        glSurfaceView.setLayoutParams(new LayoutParams((int) mOutputWidth,
                (int) mOutputHeight));

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

    private float calculateSrRatio(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        Log.i(TAG, "viewWidth = " + viewWidth + ", viewHeight = " + viewHeight + ", videoWidth = " + videoWidth + ", videoHeight = " + videoHeight);
        double videoRatio = 1.0 * videoWidth / videoHeight;
        double viewRatio = 1.0 * viewWidth / viewHeight;
        if (viewRatio > videoRatio) {
            return Math.max((float) viewHeight / videoHeight, 1.0f);
        } else {
            return Math.max((float) viewWidth / videoWidth, 1.0f);
        }
    }

    private void dumpFrame(int textureId) {
        if ("超分播放(标准版)".equals(mAlgorithm) || "普通播放".equals(mAlgorithm) || "超分播放(标准版+增强参数)".equals(mAlgorithm)) {
            mMediaRecorder.encodeFrame(textureId, System.nanoTime());
            if (mPlayFrameCount == mVideoFrameCount) {
                mMediaRecorder.stop();
            }
        } else {
            if (mPlayFrameCount > 1) {
                mMediaRecorder.encodeFrame(textureId, System.nanoTime());
                if (mPlayFrameCount == mVideoFrameCount) {
                    int lastId = -1;
                    if ("超分播放(专业版)".equals(mAlgorithm)) {
                        lastId = mTSRPassProfessional.render(textureId);
                    } else {
                        lastId = mTIEPass.render(textureId);
                    }

                    try {
                        Thread.sleep((long) (1000 / mFrameRate));
                    } catch (InterruptedException e) {
                        Log.i(TAG, "sleep exception: " + e.getMessage());
                    }
                    mMediaRecorder.encodeFrame(lastId, System.nanoTime());
                    mMediaRecorder.stop();
                }
            }
        }
    }

}