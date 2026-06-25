package com.tencent.mps.srplayer.common.decoder;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;

/**
 * 基于 MediaCodec + MediaExtractor 的视频解码器，使用 Surface 输出模式：
 * 把解码出的帧直接渲染到外部传入的 {@link Surface}（通常由 SurfaceTexture 包装的 OES 纹理）。
 *
 * <p>与 {@link Decode2Yuv} 对照：</p>
 * <ul>
 *     <li>{@link Decode2Yuv} 使用 ByteBuffer 输出模式，YUV 在 CPU 内存里；</li>
 *     <li>{@link Decode2Surface} 使用 Surface 输出模式，帧数据全程在 GPU 上（OES 纹理）。</li>
 * </ul>
 *
 * <p>典型使用流程：</p>
 * <ol>
 *     <li>外部先创建 {@link android.graphics.SurfaceTexture}（绑定到 OES 纹理）+ {@link Surface}；</li>
 *     <li>调用 {@link #init(String, Surface)} 初始化解码器；</li>
 *     <li>循环调用 {@link #dequeueOutputFrameToSurface()}，返回 true 表示已把一帧推到 Surface
 *         （此后调用方需要在 GL 线程调用 {@code SurfaceTexture.updateTexImage()} 把帧绑到 OES 纹理）；</li>
 *     <li>不再使用时调用 {@link #release()}。</li>
 * </ol>
 *
 * <p>注意：本类不持有 Surface 的所有权，只是作为 MediaCodec 的输出目标使用；
 * Surface / SurfaceTexture / OES 纹理的生命周期由调用方管理。</p>
 */
public class Decode2Surface {
    private static final String TAG = "Decode2Surface";
    private static final long TIMEOUT_US = 10000L; // 10ms 超时

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private int mVideoTrackIndex = -1;
    private boolean mInputEos = false;

    // 视频参数
    private int mWidth;
    private int mHeight;
    private long mDurationUs; // 视频总时长（微秒）

    // 当前帧 PTS（最近一次成功 dequeue 的帧）
    private long mCurrentPresentationTimeUs;

    /**
     * 初始化解码器并把输出绑到给定 Surface。
     *
     * @param filePath 视频文件路径
     * @param surface  解码输出目标（通常是包装 OES 纹理的 SurfaceTexture 对应 Surface）
     * @throws IOException           文件无法打开或解码器创建失败
     * @throws IllegalStateException 找不到视频轨道
     */
    public void init(String filePath, Surface surface) throws IOException {
        if (surface == null) {
            throw new IllegalArgumentException("surface == null");
        }
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(filePath);
        initInternal(extractor, filePath, surface);
    }

    /**
     * 初始化解码器（本地相册 URI 版本）。仅依赖 ContentResolver 读取权限，
     * 不需要额外的 READ_EXTERNAL_STORAGE。
     */
    public void init(Context context, Uri uri, Surface surface) throws IOException {
        if (surface == null) {
            throw new IllegalArgumentException("surface == null");
        }
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(context, uri, null);
        initInternal(extractor, uri.toString(), surface);
    }

    private void initInternal(MediaExtractor extractor, String sourceDesc, Surface surface) throws IOException {
        mExtractor = extractor;

        // 查找视频轨道
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            MediaFormat format = mExtractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                mVideoTrackIndex = i;
                break;
            }
        }
        if (mVideoTrackIndex == -1) {
            throw new IllegalStateException("No video track found in " + sourceDesc);
        }

        mExtractor.selectTrack(mVideoTrackIndex);
        MediaFormat format = mExtractor.getTrackFormat(mVideoTrackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);
        mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        mDurationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;

        Log.i(TAG, "init() video: " + mWidth + "x" + mHeight + " mime=" + mime + " duration=" + mDurationUs + "us");

        // 配置 MediaCodec：使用 Surface 输出模式。
        // 注意：走 Surface 后 KEY_COLOR_FORMAT 会被忽略，由硬解器决定，所以这里不再设置。
        mDecoder = MediaCodec.createDecoderByType(mime);
        mDecoder.configure(format, surface, null, 0);
        mDecoder.start();

        Log.i(TAG, "init() decoder started (Surface output)");
    }

    /**
     * 喂入输入数据，并尝试出一帧到 Surface。
     *
     * <p>返回 true 表示已经通过 {@code releaseOutputBuffer(index, true)} 把一帧渲染到了 Surface，
     * 调用方接下来需要：</p>
     * <ol>
     *     <li>等待 {@code SurfaceTexture.OnFrameAvailableListener} 回调（或直接进入 GL 线程）；</li>
     *     <li>在 GL 线程调用 {@code SurfaceTexture.updateTexImage()} 把帧绑到 OES 纹理。</li>
     * </ol>
     *
     * <p>返回 false 表示当前没有可用帧，调用方应稍后重试。</p>
     */
    public boolean dequeueOutputFrameToSurface() {
        if (mDecoder == null) {
            return false;
        }

        // 喂入输入数据
        feedInput();

        // 获取输出帧
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputIndex = mDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 输出格式变化（Surface 模式下一般只关心 W/H）
            MediaFormat outputFormat = mDecoder.getOutputFormat();
            mWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH);
            mHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT);
            Log.i(TAG, "dequeue() output format changed: " + mWidth + "x" + mHeight);
            // 再尝试一次
            outputIndex = mDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
        }

        if (outputIndex >= 0) {
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                // EOS：丢弃这帧（不渲染），执行循环播放
                mDecoder.releaseOutputBuffer(outputIndex, false);
                loopPlayback();
                return false;
            }

            mCurrentPresentationTimeUs = bufferInfo.presentationTimeUs;
            // render=true：把帧推到 Surface（解码器内部 → Surface 队列 → SurfaceTexture）
            mDecoder.releaseOutputBuffer(outputIndex, true);
            return true;
        }

        return false;
    }

    /**
     * 释放所有资源。
     */
    public void release() {
        if (mDecoder != null) {
            try {
                mDecoder.stop();
            } catch (Exception e) {
                Log.w(TAG, "release() decoder stop error", e);
            }
            mDecoder.release();
            mDecoder = null;
        }
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
        Log.i(TAG, "release()");
    }

    // ---- Getter ----

    /** 最近一次成功 dequeue 的帧的 PTS（微秒） */
    public long getCurrentPresentationTimeUs() {
        return mCurrentPresentationTimeUs;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public long getDurationUs() {
        return mDurationUs;
    }

    // ---- 私有方法 ----

    private void feedInput() {
        if (mInputEos) {
            return;
        }
        int inputIndex = mDecoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex >= 0) {
            java.nio.ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputIndex);
            int sampleSize = mExtractor.readSampleData(inputBuffer, 0);
            if (sampleSize < 0) {
                mDecoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                mInputEos = true;
            } else {
                long presentationTimeUs = mExtractor.getSampleTime();
                mDecoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                mExtractor.advance();
            }
        }
    }

    /** 循环播放：重置 extractor 与 decoder 状态 */
    private void loopPlayback() {
        Log.i(TAG, "loopPlayback() restarting from beginning");
        mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        mDecoder.flush();
        mInputEos = false;
    }
}
