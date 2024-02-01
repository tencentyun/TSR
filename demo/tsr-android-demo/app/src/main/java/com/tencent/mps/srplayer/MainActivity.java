package com.tencent.mps.srplayer;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.SurfaceTexture;
import android.graphics.SurfaceTexture.OnFrameAvailableListener;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnPreparedListener;
import android.net.Uri;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.FrameLayout.LayoutParams;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.preference.PreferenceManager;
import com.tencent.mps.srplayer.helper.TapHelper;
import com.tencent.mps.srplayer.opengl.Texture;
import com.tencent.mps.srplayer.opengl.Texture.Type;
import com.tencent.mps.srplayer.pass.CompareTexDrawer;
import com.tencent.mps.srplayer.pass.BilinearRenderPass;
import com.tencent.mps.srplayer.pass.TexOESToTex2DPass;
import com.tencent.mps.srplayer.pass.VideoFrameDrawer;
import com.tencent.mps.tsr.api.TsrLogger;
import com.tencent.mps.tsr.api.TsrPass;
import com.tencent.mps.tsr.api.TsrSdk;
import com.tencent.mps.tsr.api.TsrSdk.SdkLicenseStatus;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private TsrPass mTsrPass;
    // Conversion of textureOES to texture2D
    private TexOESToTex2DPass mTexOESToTex2DPass;
    // Bilinear rendering pass
    private BilinearRenderPass mBilinearRenderPass;
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
    // Flag indicating if full-screen rendering is enabled
    private boolean mIsFullScreenRender;
    // MediaPlayer
    private MediaPlayer mMediaPlayer;
    // TsrLogger
    private final TsrLogger mTsrLogger = new TsrLogger() {
        @Override
        public void v(String tag, String msg) {
            Log.v(tag, msg);
        }

        @Override
        public void d(String tag, String msg) {
            Log.d(tag, msg);
        }

        @Override
        public void i(String tag, String msg) {
            Log.i(tag, msg);
        }

        @Override
        public void e(String tag, String msg) {
            Log.e(tag, msg);
        }

        @Override
        public void w(String tag, String msg) {
            Log.w(tag, msg);
        }
    };

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
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
            mVideoFrameDrawer.createOnGLThread(getApplicationContext());
            mCompareTexDrawer.createOnGLThread(getApplicationContext());
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Create input texture，bind it to SurfaceTexture to get frame from mediaPlayer.
        Texture inputTexture = new Texture(false, Type.TEXTURE_OSE, 0, mFrameWidth, mFrameHeight);
        mSurfaceTexture = new SurfaceTexture(inputTexture.getTextureId());
        mSurfaceTexture.setOnFrameAvailableListener(this);
        Surface surface = new Surface(mSurfaceTexture);
        if (mMediaPlayer != null) {
            mMediaPlayer.setSurface(surface);
        } else {
            Log.w(TAG, "Set surface to MediaPlayer failed because MediaPlayer is null.");
        }
        surface.release();

        // Create the pass that convert TextureOES to Texture2D.
        mTexOESToTex2DPass = new TexOESToTex2DPass(inputTexture);
        // Create BilinearPass
        mBilinearRenderPass = new BilinearRenderPass(mTexOESToTex2DPass.getOutputTexture(), mSrRatio);

        /*-------------------------------- Step 1: create the TSRPass. -------------------------------------------*/
        mTsrPass.init(mTexOESToTex2DPass.getOutputTexture().getWidth(),
                mTexOESToTex2DPass.getOutputTexture().getHeight(), mSrRatio);
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
        if (mCompareTexDrawer != null) {
            mCompareTexDrawer.onSurfaceChanged(width, height);
        }
        if (mVideoFrameDrawer != null) {
            mVideoFrameDrawer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
        if (mTexOESToTex2DPass == null || mTsrPass == null || mBilinearRenderPass == null
                || mCompareTexDrawer == null) {
            Log.w(TAG, "pass or drawer is null!");
            return;
        }

        synchronized (this) {
            if (updateTexture) {
                mSurfaceTexture.updateTexImage();
                updateTexture = false;
            }
        }
        /* Step 2: (Optional) If the type of your input texture is TextureOES, you must Convert TextureOES to Texture2D.*/
        int tex2dId = mTexOESToTex2DPass.render();

        /* Step 3: Pass the input texture's id to TSRPass#render(int), and get the output texture's id. The output
        texture is the result of super-resolution.*/
        int srTextureId = mTsrPass.render(tex2dId);

        /* Step 4: Use the TSRPass's output texture to do your own render. */
        if (mIsCompareLerp) {
            int bilinearTextureId = mBilinearRenderPass.render();
            mCompareTexDrawer.draw(srTextureId, bilinearTextureId);
        } else {
            mVideoFrameDrawer.draw(srTextureId);
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
        if (mTsrPass != null) {
            mTsrPass.release();
        }
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
        }
        TsrSdk.getInstance().release();
    }

    private void initViewAndMediaPlayer() {
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

        Context context = getApplicationContext();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String fileName = sharedPreferences.getString(context.getString(R.string.video_title), "");
        mSrRatio = Float.parseFloat(sharedPreferences.getString(context.getString(R.string.sr_ratio), "2.0"));
        mIsFullScreenRender = sharedPreferences.getBoolean(context.getString(R.string.full_screen_config), false);
        mIsCompareLerp = sharedPreferences.getBoolean(context.getString(R.string.compare_lerp), true);

        // Whether to compare bilinear
        TextView sr = findViewById(R.id.sr);
        TextView lerp = findViewById(R.id.lerp);
        if (!mIsCompareLerp) {
            sr.setVisibility(View.INVISIBLE);
            lerp.setVisibility(View.INVISIBLE);
        }

        // Configure MediaPlayer
        mMediaPlayer = new MediaPlayer();
        try {
            String uriString = getIntent().getStringExtra("videoUri");
            if (uriString != null) {
                Uri videoUri = Uri.parse(uriString);
                if (videoUri != null) {
                    mMediaPlayer.setDataSource(context, videoUri);
                }
            } else {
                AssetFileDescriptor afd = context.getAssets().openFd(fileName);
                mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            }
        } catch (IOException e) {
            Log.e(TAG, "open source failed: " + e.getMessage());
            return;
        }
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setLooping(true);
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

                Context context = getApplicationContext();

                offlineVerifyLicense(context);
            }
        });
    }

    private void offlineVerifyLicense(Context context) {
        if (mLicensePath == null) {
            copyAssetsFileToSDCard(context);
        }

        SdkLicenseStatus status = TsrSdk.getInstance().init(mAppId, mLicensePath, mTsrLogger);
        if (status == SdkLicenseStatus.AVAILABLE) {
            Log.i(TAG, "Verify sdk license success: " + status.toString());
            createTsrPassAndAddView(context);
        } else {
            Log.i(TAG, "Verify sdk license failed: " + status.toString());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(context, "Verify sdk license failed: " + status.toString(), Toast.LENGTH_SHORT).show();
                    finish();
                }
            });
        }
    }

    private void createTsrPassAndAddView(Context context) {
        // Create TsrPass
        mTsrPass = new TsrPass();

        mGLSurfaceView = new GLSurfaceView(context);
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

    private void copyAssetsFileToSDCard(Context context) {
        AssetManager assetManager = context.getAssets();
        String licenseName = null;
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (files != null) {
            for (String file : files) {
                if (file.endsWith(".crt")) {
                    licenseName = file;
                    break;
                }
            }
        }

        if (licenseName == null) {
            Log.i(TAG, "license not found.");
            return;
        }

        InputStream in = null;
        OutputStream out = null;

        try {
            in = assetManager.open(licenseName);
            mLicensePath = context.getExternalFilesDir("license/").getAbsolutePath() + "/" + licenseName;
            File outFile = new File(mLicensePath);
            out = new FileOutputStream(outFile);
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}