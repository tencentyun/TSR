package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Surface;
import android.view.View;
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
import com.tencent.mps.tie.api.TSRLogger;
import com.tencent.mps.tie.api.TSRPass;
import com.tencent.mps.tie.api.TSRSdk;
import com.tencent.mps.tie.api.TSRSdk.TSRSdkLicenseStatus;

import java.io.IOException;
import java.util.Objects;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class MainActivity extends AppCompatActivity implements GLSurfaceView.Renderer, OnFrameAvailableListener {
    private static final String TAG = "MainActivity";

    /**--------------------------------- THE PARAMS FOR SDK VERIFICATION----------------------------------------*/
    // Modify mAppId to your APPID which can be found in Tencent Cloud account.
    private final long mAppId;
    /**---------------------------------------------------------------------------------------------------------*/

    // Time interval for double swipe to exit
    private static final long TIME_EXIT = 2000;
    // Double swipe to exit
    private long mBackPressed;
    // Flag indicating if the video is currently paused
    private boolean mIsPause;
    // Flag indicating if there is a new frame
    private volatile boolean updateTexture;
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
    // Drawer for rendering frames on screen
    private VideoFrameDrawer mVideoFrameDrawer;

    // Width of the original video frame
    private int mFrameWidth;
    // Height of the original video frame
    private int mFrameHeight;
    // Flag indicating if bilinear interpolation comparison is needed
    private boolean mIsCompareLerp;
    // Super-resolution ratio
    private float mSrRatio;
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
    // Export video bitrate
    private int mBitrateMbps;
    // Export video codec type;
    private String mCodecType;
    // Video frame rate
    private int mFrameRate;
    // Video rotation
    private int mRotation;
    // Is turn on SR?
    private boolean mIsSROn;
    private final Context mContext = this;
    // TsrLogger
    private final TSRLogger mTSRLogger = (logLevel, tag, msg) -> {
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
            Log.e(TAG, Objects.requireNonNull(e.getMessage()));
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

        if (mIsSROn) {
            /*-------------------------------- Step 1: create the TSRPass. -------------------------------------------*/
            mTSRPass.init(mTexOESToTex2DPass.getOutputTexture().getWidth(),
                    mTexOESToTex2DPass.getOutputTexture().getHeight(), mSrRatio);
        }

        if (mIsRecordVideo) {
            mFilePath = mContext.getExternalFilesDir("dump_video/") + "/" +
                    mFileName.split("\\.")[0]+ "_sr" + mSrRatio  + "x_" +
                    System.currentTimeMillis() + ".mp4";
            mMediaRecorder = new MediaRecorder(mContext, mFilePath,
                    (int) (mFrameWidth * mSrRatio), (int) (mFrameHeight * mSrRatio), mRotation,
                    mFrameRate, mBitrateMbps, mCodecType, EGL14.eglGetCurrentContext());
            mMediaRecorder.setOnRecordFinishListener(path -> {
                ProgressDialogUtils.hideProgressDialog();
                FileUtils.saveVideoToAlbum(mContext, mFilePath);
                runOnUiThread(() -> DialogUtils.showSimpleConfirmDialog(mContext, mContext.getResources().getString(R.string.dump_done),
                        (dialogInterface, i) -> finish()));
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
        if (mTexOESToTex2DPass == null || mBilinearRenderPass == null
                || mCompareTexDrawer == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }

        boolean newFrame = false;
        synchronized (this) {
            if (updateTexture) {
                mSurfaceTexture.updateTexImage();
                updateTexture = false;
                newFrame = true;
            }
        }

        float[] transformMatrix = new float[16];
        mSurfaceTexture.getTransformMatrix(transformMatrix);

        /* Step 2: (Optional) If the type of your input texture is TextureOES, you must Convert TextureOES to Texture2D.*/
        int tex2dId = mTexOESToTex2DPass.render(mInputTexture.getTextureId(), mInputTexture.getType(), transformMatrix);

        if (mIsSROn) {
            /* Step 3: Pass the input texture's id to TSRPass#render(int), and get the output texture's id. The output
        texture is the result of super-resolution.*/
            int srTextureId = mTSRPass.render(tex2dId, transformMatrix);

            /* Step 4: Use the TSRPass's output texture to do your own render. */
            if (mMediaRecorder != null) {
                if (newFrame) {
                    mMediaRecorder.encodeFrame(srTextureId, mSurfaceTexture.getTimestamp());
                }
            } else {
                if (mIsCompareLerp) {
                    int bilinearTextureId = mBilinearRenderPass.render(tex2dId, GLES30.GL_TEXTURE_2D, transformMatrix);
                    mCompareTexDrawer.draw(srTextureId, bilinearTextureId);
                } else {
                    mVideoFrameDrawer.draw(srTextureId);
                }
            }
        } else {
            mVideoFrameDrawer.draw(tex2dId);
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
        mFileName = sharedPreferences.getString(mContext.getString(R.string.video_title), "");
        String srRatio = sharedPreferences.getString(mContext.getString(R.string.sr_ratio), "2.0");
        if ("OFF".equals(srRatio)) {
            mIsSROn = false;
            mSrRatio = 1.0f;
        } else {
            mIsSROn = true;
            mSrRatio = Float.parseFloat(srRatio);
        }
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
            Button playControlButton = findViewById(R.id.playControlButton);
            playControlButton.setText(R.string.pause_video);
            playControlButton.setOnClickListener(view -> {
                if (mIsPause) {
                    mIsPause = false;
                    mMediaPlayer.start();
                    playControlButton.setText(R.string.pause_video);
                } else {
                    mIsPause = true;
                    mMediaPlayer.pause();
                    playControlButton.setText(R.string.play_video);
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
                afd.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "open source failed: " + e.getMessage());
            return;
        }

        if (mIsRecordVideo) {
            //获取视频时长，单位：毫秒(ms)
            String duration_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration_s != null) {
                long duration = Long.parseLong(duration_s);

                //获取视频帧数
                String count_s = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
                if (count_s != null) {
                    long count = Long.parseLong(count_s);

                    //计算帧率
                    long dt = duration / count; // 平均每帧的时间间隔
                    mFrameRate = (int) (1000 / dt); // 帧率

                    Log.i(TAG, "video frame rate = " + mFrameRate);
                }
            }
        }

        mRotation = Integer.parseInt(Objects.requireNonNull(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)));
        Log.i(TAG, "rotation = " + mRotation);

        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setLooping(false);
        mMediaPlayer.prepareAsync();
        mMediaPlayer.setOnPreparedListener(mediaPlayer -> {
            mFrameWidth = mMediaPlayer.getVideoWidth();
            mFrameHeight = mMediaPlayer.getVideoHeight();
            Log.i(TAG, "width = " + mFrameWidth + ", height = " + mFrameHeight);

            if (mFrameWidth > mFrameHeight) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
            verifyLicense();
        });

        // 绑定播放结束事件
        mMediaPlayer.setOnCompletionListener(mediaPlayer -> {
            new Handler().postDelayed(() -> {
                // 退出操作
                if (mMediaRecorder != null) {
                    mMediaRecorder.stop();
                }
            }, 2000);  // delay 2000ms
        });
    }

    private void verifyLicense() {
        TSRSdk.getInstance().init(mAppId, status -> {
            if (status == TSRSdkLicenseStatus.AVAILABLE) {
                Log.i(TAG, "Online verify sdk license success: " + status);
                createTsrPassAndAddView();
            } else {
                Log.i(TAG, "Online verify sdk license failed: " + status);
                runOnUiThread(() -> {
                    Toast.makeText(mContext, "Online verify sdk license failed: " + status, Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }, mTSRLogger);
    }

    private void createTsrPassAndAddView() {
        if (mIsSROn) {
            // Create TsrPassAndroid
            mTSRPass = new TSRPass();
        }

        GLSurfaceView glSurfaceView = new GLSurfaceView(mContext);
        glSurfaceView.setEGLContextClientVersion(3);
        glSurfaceView.setRenderer(MainActivity.this);

        if (!mIsFullScreenRender) {
            glSurfaceView.setLayoutParams(new LayoutParams((int) (mFrameWidth * mSrRatio),
                    (int) (mFrameHeight * mSrRatio)));
        }

        runOnUiThread(() -> {
            FrameLayout frameLayout = findViewById(R.id.video_view);
            frameLayout.addView(glSurfaceView);
            frameLayout.setOnTouchListener(TapHelper.getInstance());

            mMediaPlayer.start();
        });
    }
}