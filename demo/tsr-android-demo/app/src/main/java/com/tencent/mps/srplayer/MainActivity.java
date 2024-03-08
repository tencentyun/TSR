package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
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
import com.tencent.mps.tsr.api.TSRLogger;
import com.tencent.mps.tsr.api.TSRPass;
import com.tencent.mps.tsr.api.TSRSdk;
import com.tencent.mps.tsr.api.TSRSdk.TSRSdkLicenseStatus;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer, OnFrameAvailableListener {
    private static final String TAG = "MainActivity";

    /**--------------------------------- THE PARAMS FOR SDK VERIFICATION----------------------------------------*/
    // Modify mAppId to your APPID which can be found in Tencent Cloud account.
    private final long mAppId = -1;

    // Modify mLicensePath to your sdk license's path in your test phone.
    // If you want to run demo as soon as possible, you can just put your sdk license to assets. Demo is going to
    // copy .crt file from assets to sdcard as the mLicensePath is null, and will load it to init TsrSdk.
    private String mLicensePath = null;
    /**---------------------------------------------------------------------------------------------------------*/

    // Time interval for double swipe to exit
    private static final long TIME_EXIT = 2000;
    // Double swipe to exit
    private long mBackPressed;
    // View for playing videos
    private GLSurfaceView mGLSurfaceView;
    // Button for controlling play and pause of video
    private Button mPlayControlButton;
    // Flag indicating if the video is currently paused
    private boolean mIsPause;
    // Drawer for rendering frames on screen
    private VideoFrameDrawer mVideoFrameDrawer;
    // Flag indicating if there is a new frame
    private boolean updateTexture;
    // SurfaceTexture bound to MediaPlayer
    private SurfaceTexture mSurfaceTexture;
    // Super-resolution pass
    private TSRPass mTSRPass;
    // Conversion of textureOES to texture2D
    private OffScreenRenderPass mTexOESToTex2DPass;
    // Bilinear rendering pass
    private OffScreenRenderPass mBilinearRenderPass;
    // Comparing two textures
    private CompareTexDrawer mCompareTexDrawer;
    // Width of the original video frame
    private int mFrameWidth;
    // Height of the original video frame
    private int mFrameHeight;
    // Flag indicating if bilinear interpolation comparison is needed
    private boolean mIsCompareLerp;
    // Super-resolution ratio
    private float mSrRatio;
    // Export video bitrate
    private int mBitrateMbps;
    // Export video codec type;
    private String mCodecType;
    // Flag indicating if full-screen rendering is enabled
    private boolean mIsFullScreenRender;
    // MediaPlayer
    private MediaPlayer mMediaPlayer;
    // Input video file name
    private String mFileName;
    // Video file path
    private String mFilePath;
    // InputTexture
    private Texture mInputTexture;
    // MediaRecorder
    private MediaRecorder mMediaRecorder;
    // Is record video?
    private boolean mIsRecordVideo;
    // Video frame rate
    private int mFrameRate;
    // Video rotation
    private int mRotation;
    private Context mContext = this;
    // TsrLogger
    private final TSRLogger mTSRLogger = new TSRLogger() {

        @Override
        public void logWithLevel(int logLevel, String tag, String msg) {
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
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mIsRecordVideo = getIntent().getBooleanExtra("op_type", false);
        if (mIsRecordVideo) {
            ProgressDialogUtils.showProgressDialog(this, "Exporting...");
        }
        // Init
        initViewAndMediaPlayer();
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
            e.printStackTrace();
        }

        // Create input texture，bind it to SurfaceTexture to get frame from mediaPlayer.
        mInputTexture = new Texture(false, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0, mFrameWidth, mFrameHeight);
        mSurfaceTexture = new SurfaceTexture(mInputTexture.getTextureId());
        mSurfaceTexture.setOnFrameAvailableListener(this);
        Surface surface = new Surface(mSurfaceTexture);
        if (mMediaPlayer != null) {
            mMediaPlayer.setSurface(surface);
        } else {
            Log.w(TAG, "Set surface to MediaPlayer failed because MediaPlayer is null.");
        }
        surface.release();

        // Create the pass that convert TextureOES to Texture2D.
        mTexOESToTex2DPass = new OffScreenRenderPass(GLES30.GL_TEXTURE_2D,
                mInputTexture.getWidth(), mInputTexture.getHeight(), "shaders/videoTexOES.frag");
        // Create BilinearPass
        mBilinearRenderPass = new OffScreenRenderPass(GLES30.GL_TEXTURE_2D, (int) (mFrameWidth * mSrRatio),
                (int) (mFrameHeight * mSrRatio), "shaders/videoTex2D.frag");

        /*-------------------------------- Step 1: create the TSRPass. -------------------------------------------*/
        mTSRPass.init(mTexOESToTex2DPass.getOutputTexture().getWidth(),
                mTexOESToTex2DPass.getOutputTexture().getHeight(), mSrRatio);

        if (mIsRecordVideo) {
            mFilePath = mContext.getExternalFilesDir("dump_video/") + "/" +
                    mFileName.split("\\.")[0]+ "_sr" + mSrRatio  + "x_" +
                    System.currentTimeMillis() + ".mp4";
            mMediaRecorder = new MediaRecorder(mContext, mFilePath,
                    (int) (mFrameWidth * mSrRatio), (int) (mFrameHeight * mSrRatio), mRotation,
                    mFrameRate, mBitrateMbps, mCodecType, EGL14.eglGetCurrentContext());
            mMediaRecorder.setOnRecordFinishListener(new MediaRecorder.OnRecordFinishListener() {
                @Override
                public void onRecordFinish(String path) {
                    ProgressDialogUtils.hideProgressDialog();
                    FileUtils.saveVideoToAlbum(mContext, mFilePath);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            DialogUtils.showSimpleConfirmDialog(mContext, mContext.getResources().getString(R.string.dump_done),
                                    new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialogInterface, int i) {
                                    finish();
                                }
                            });
                        }
                    });
                }
            });
            try {
                mMediaRecorder.start();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
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
        if (mTexOESToTex2DPass == null || mTSRPass == null || mBilinearRenderPass == null
                || mCompareTexDrawer == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }

        mSurfaceTexture.updateTexImage();

        /* Step 2: (Optional) If the type of your input texture is TextureOES, you must Convert TextureOES to Texture2D.*/
        int tex2dId = mTexOESToTex2DPass.render(mInputTexture.getTextureId(), mInputTexture.getType());

        /* Step 3: Pass the input texture's id to TSRPass#render(int), and get the output texture's id. The output
        texture is the result of super-resolution.*/
        int srTextureId = mTSRPass.render(tex2dId);

        /* Step 4: Use the TSRPass's output texture to do your own render. */
        if (mMediaRecorder != null) {
            mMediaRecorder.encodeFrame(srTextureId, mSurfaceTexture.getTimestamp());
        } else {
            if (mIsCompareLerp) {
                int bilinearTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D);
                mCompareTexDrawer.draw(srTextureId, bilinearTextureId);
            } else {
                mVideoFrameDrawer.draw(srTextureId);
            }
        }
    }

    @Override
    synchronized public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        updateTexture = true;
    }

    @Override
    public void onBackPressed(){
        if(mBackPressed + TIME_EXIT > System.currentTimeMillis()){
            super.onBackPressed();
        }else{
            Toast.makeText(this,R.string.slide_again, Toast.LENGTH_SHORT).show();
            mBackPressed = System.currentTimeMillis();
        }
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
        if (mTSRPass != null) {
            mTSRPass.release();
        }
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
        }
        if (mMediaRecorder != null) {
            mMediaRecorder = null;
        }
        TSRSdk.getInstance().release();
    }

    private void initViewAndMediaPlayer() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mFileName = "girl-544x960.mp4";
        mSrRatio = Float.parseFloat(sharedPreferences.getString(mContext.getString(R.string.sr_ratio), "2.0"));
        mIsFullScreenRender = sharedPreferences.getBoolean(mContext.getString(R.string.full_screen_config), false);
        mIsCompareLerp = sharedPreferences.getBoolean(mContext.getString(R.string.compare_lerp), false);
        mCodecType = sharedPreferences.getString(mContext.getString(R.string.codec_type), "H264");
        mBitrateMbps = Integer.parseInt(sharedPreferences.getString(mContext.getString(R.string.bitrate_mbps), "20"));

        if (!mIsRecordVideo) {
            // Whether to compare bilinear
            if (!mIsCompareLerp) {
                findViewById(R.id.sr).setVisibility(View.INVISIBLE);
                findViewById(R.id.lerp).setVisibility(View.INVISIBLE);
            }

            // Init playControllerButton
            mPlayControlButton = findViewById(R.id.playControlButton);
            mPlayControlButton.setText(R.string.pause_video);
            mPlayControlButton.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (mIsPause) {
                        mIsPause = false;
                        mMediaPlayer.start();
                        mPlayControlButton.setText(R.string.pause_video);
                    } else {
                        mIsPause = true;
                        mMediaPlayer.pause();
                        mPlayControlButton.setText(R.string.play_video);
                    }
                }
            });
        }

        // Configure MediaPlayer
        mMediaPlayer = new MediaPlayer();
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            String uriString = getIntent().getStringExtra("videoUri");
            if (uriString != null) {
                Uri videoUri = Uri.parse(uriString);
                if (videoUri != null) {
                    mFileName = getIntent().getStringExtra("fileName");
                    mMediaPlayer.setDataSource(mContext, videoUri);
                    retriever.setDataSource(mContext, videoUri);
                }
            } else {
                AssetFileDescriptor afd = mContext.getAssets().openFd(mFileName);
                mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                retriever.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            }
        } catch (IOException e) {
            Log.e(TAG, "open source failed: " + e.getMessage());
            return;
        }
        //获取视频时长，单位：毫秒(ms)
        String duration_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        long duration = Long.valueOf(duration_s);

        //获取视频帧数
        String count_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
        long count = Long.valueOf(count_s);

        //计算帧率
        long dt = duration / count; // 平均每帧的时间间隔
        mFrameRate = (int) (1000 / dt); // 帧率

        Log.i(TAG, "video frame rate = " + mFrameRate);

        mRotation = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
        Log.i(TAG, "rotation = " + mRotation);

        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setLooping(false);
        mMediaPlayer.prepareAsync();
        mMediaPlayer.setOnPreparedListener(new OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                mFrameWidth = mMediaPlayer.getVideoWidth();
                mFrameHeight = mMediaPlayer.getVideoHeight();
                Log.i(TAG, "width = " + mFrameWidth + ", height = " + mFrameHeight);

                if (mFrameWidth > mFrameHeight) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                }
                offlineVerifyLicense();
            }
        });

        // 绑定播放结束事件
        mMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                if (mMediaRecorder != null) {
                    mMediaRecorder.stop();
                }
            }
        });
    }

    private void offlineVerifyLicense() {
        if (mLicensePath == null) {
            mLicensePath = FileUtils.copyAssetsFileToSDCard(mContext);
        }

        TSRSdkLicenseStatus status = TSRSdk.getInstance().init(mAppId, mLicensePath, mTSRLogger);
        if (status == TSRSdkLicenseStatus.AVAILABLE) {
            Log.i(TAG, "Verify sdk license success: " + status.toString());
            createTsrPassAndAddView();
        } else {
            Log.i(TAG, "Verify sdk license failed: " + status.toString());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(mContext, "Verify sdk license failed: " + status.toString(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }
    }

    private void createTsrPassAndAddView() {
        // Create TsrPassAndroid
        mTSRPass = new TSRPass();

        mGLSurfaceView = new GLSurfaceView(mContext);
        mGLSurfaceView.setEGLContextClientVersion(3);
        mGLSurfaceView.setRenderer(MainActivity.this);

        if (!mIsFullScreenRender) {
            mGLSurfaceView.setLayoutParams(new LayoutParams((int) (mFrameWidth * mSrRatio),
                    (int) (mFrameHeight * mSrRatio)));
        }

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                FrameLayout frameLayout = findViewById(R.id.video_view);
                frameLayout.addView(mGLSurfaceView);
                frameLayout.setOnTouchListener(TapHelper.getInstance());

                mMediaPlayer.start();
            }
        });
    }
}