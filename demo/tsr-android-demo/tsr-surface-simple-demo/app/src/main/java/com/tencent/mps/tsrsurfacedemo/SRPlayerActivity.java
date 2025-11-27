package com.tencent.mps.tsrsurfacedemo;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.opengl.EGL14;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import com.tencent.mps.srplayer.R;
import com.tencent.mps.tie.api.TSRPass.TSRAlgorithmType;
import com.tencent.mps.tie.api.TSRSurface;
import com.tencent.mps.tie.api.config.AutoFallbackConfig;
import com.tencent.mps.tie.api.config.TSRSurfaceConfig;
import java.io.IOException;
import java.util.Objects;

public class SRPlayerActivity extends Activity
        implements SurfaceHolder.Callback {

    private static final String TAG = "SRPlayerActivity";
    private MediaPlayer mMediaPlayer;
    private TSRSurface mSrSurface;
    private SurfaceView mSurfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // -----------------  1. 设置界面（只有一个SurfaceView）
        setContentView(com.tencent.mps.srplayer.R.layout.activity_srplayer);
        mSurfaceView = findViewById(R.id.video_view);
        mSurfaceView.getHolder().addCallback(this);

        // ----------------- 2. 初始化TSRSdk
        TsrSdkHelper.getInstance().init(this);

        try {
            // ----------------- 3. 初始化超分Surface（核心组件）
            mSrSurface = new TSRSurface(EGL14.eglGetCurrentContext(),
                    new TSRSurfaceConfig.Builder()
                            .setAlgorithmType(TSRAlgorithmType.PROFESSIONAL_COLOR_RETOUCHING_EXT)
                            .setAutoFallbackConfig(new AutoFallbackConfig(5, 33, ((inputWidth, inputHeight) -> {
                                Log.w(TAG, inputWidth + "x" + inputHeight + " fallback to standard");
                            })))
                            .setMaxInputResolution(3840, 3840)
                            .build());
        } catch (Exception e) {
            Log.e(TAG, Objects.requireNonNull(e.getMessage()));
        }

        // 4. 初始化播放器
        mMediaPlayer = new MediaPlayer();
        setupMediaPlayer();
    }

    private void setupMediaPlayer() {
        try {
            // 设置视频源（这里用本地视频示例）
            AssetFileDescriptor afd = getAssets().openFd("原视频_720P.mp4");
            mMediaPlayer.setDataSource(afd.getFileDescriptor(),
                    afd.getStartOffset(), afd.getLength());
            afd.close();

            // 设置循环播放
            mMediaPlayer.setLooping(true);

            // 准备播放器（异步）
            mMediaPlayer.prepareAsync();
            mMediaPlayer.setOnPreparedListener(mp -> {
                // 媒体准备完成后，如果Surface已经创建，立即设置分辨率
                if (mSrSurface != null && mSurfaceView.getHolder().getSurface() != null) {
                    int videoWidth = mMediaPlayer.getVideoWidth();
                    int videoHeight = mMediaPlayer.getVideoHeight();
                    int viewWidth = mSurfaceView.getWidth();
                    int viewHeight = mSurfaceView.getHeight();

                    if (videoWidth > 0 && videoHeight > 0 && viewWidth > 0 && viewHeight > 0) {
                        int[] outputResolution = calculateOutputResolution(viewWidth, viewHeight, videoWidth, videoHeight);

                        // -----------------  7. 设置分辨率
                        mSrSurface.setResolution(videoWidth, videoHeight, outputResolution[0], outputResolution[1]);

                        // 更新SurfaceView布局
                        runOnUiThread(() -> {
                            ViewGroup.LayoutParams params = mSurfaceView.getLayoutParams();
                            params.width = outputResolution[0];
                            params.height = outputResolution[1];
                            mSurfaceView.setLayoutParams(params);
                            mSurfaceView.requestLayout();
                        });
                    }
                }

                // 播放器准备好后开始播放
                mMediaPlayer.start();
            });

        } catch (IOException e) {
            Log.e(TAG, "setupMediaPlayer failed: ", e);
        }
    }

    // Surface创建时的回调
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");

        try {
            // ----------------- 5. 连接超分Surface的输出到显示界面
            if (mSrSurface != null) {
                mSrSurface.setOutputSurface(holder.getSurface());
            }

            // ----------------- 6. 连接播放器到超分Surface的输入
            if (mMediaPlayer != null) {
                mMediaPlayer.setSurface(mSrSurface.getInputSurface());
            }
        } catch (Exception e) {
            Log.e(TAG, Objects.requireNonNull(e.getMessage()));
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        try {
            // 断开连接
            if (mSrSurface != null) {
                mSrSurface.setOutputSurface(null);
            }

            if (mMediaPlayer != null) {
                mMediaPlayer.setSurface(null);
            }
        } catch (Exception e) {
            Log.e(TAG, Objects.requireNonNull(e.getMessage()));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放资源
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        if (mSrSurface != null) {
            mSrSurface.release();
            mSrSurface = null;
        }
    }

    private int[] calculateOutputResolution(int viewWidth, int viewHeight, int videoWidth, int videoHeight) {
        int[] result = new int[2];
        double videoRatio = 1.0 * videoWidth / videoHeight;
        double viewRatio = 1.0 * viewWidth / viewHeight;
        if (viewRatio > videoRatio) {
            int width = (int) (videoRatio * viewHeight);
            result[0] = width;
            result[1] = viewHeight;
        } else {
            int height = (int) (viewWidth / videoRatio);
            result[0] = viewWidth;
            result[1] = height;
        }
        result[0] = result[0] / 2 * 2;
        result[1] = result[1] / 2 * 2;
        Log.i(TAG, "outputWidth = " + result[0] + ", outputHeight = " + result[1]);
        return result;
    }
}