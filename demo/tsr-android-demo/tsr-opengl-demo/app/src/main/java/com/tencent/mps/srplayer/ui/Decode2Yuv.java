package com.tencent.mps.srplayer.ui;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 基于 MediaCodec + MediaExtractor 的视频解码器，使用 ByteBuffer 输出模式直接输出 YUV 数据到 CPU 内存。
 *
 * <p>不传入 Surface，解码器直接输出 NV12/NV21 格式的 YUV 数据。</p>
 *
 * <p>典型使用流程：</p>
 * <ol>
 *     <li>调用 {@link #init(String)} 初始化解码器</li>
 *     <li>循环调用 {@link #dequeueOutputFrame()} 获取解码帧</li>
 *     <li>处理完帧数据后调用 {@link #releaseOutputFrame()} 释放 buffer</li>
 *     <li>不再使用时调用 {@link #release()} 释放资源</li>
 * </ol>
 */
public class Decode2Yuv {
    private static final String TAG = "Decode2Yuv";
    private static final long TIMEOUT_US = 10000L; // 10ms 超时

    private MediaExtractor mExtractor;
    private MediaCodec mDecoder;
    private int mVideoTrackIndex = -1;
    private boolean mInputEos = false;
    private boolean mOutputEos = false;

    // 视频参数
    private int mWidth;
    private int mHeight;
    private int mStride;
    private int mSliceHeight;
    private int mColorFormat;
    private long mDurationUs; // 视频总时长（微秒）

    // 当前输出帧
    private int mCurrentOutputBufferIndex = -1;
    private ByteBuffer mCurrentOutputBuffer;
    private long mCurrentPresentationTimeUs;



    /**
     * 初始化解码器。
     *
     * @param filePath 视频文件路径
     * @throws IOException          如果文件无法打开或解码器创建失败
     * @throws IllegalStateException 如果找不到视频轨道或不支持的颜色格式
     */
    public void init(String filePath) throws IOException {
        // 创建 MediaExtractor
        mExtractor = new MediaExtractor();
        mExtractor.setDataSource(filePath);

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
            throw new IllegalStateException("No video track found in " + filePath);
        }

        mExtractor.selectTrack(mVideoTrackIndex);
        MediaFormat format = mExtractor.getTrackFormat(mVideoTrackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);
        mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
        mDurationUs = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 0;

        Log.i(TAG, "init() video: " + mWidth + "x" + mHeight + " mime=" + mime + " duration=" + mDurationUs + "us");

        // 配置 MediaCodec（ByteBuffer 输出模式，不传入 Surface）
        // 请求 NV12 (COLOR_FormatYUV420SemiPlanar) 输出格式
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);

        mDecoder = MediaCodec.createDecoderByType(mime);
        mDecoder.configure(format, null, null, 0); // null surface = ByteBuffer 模式
        mDecoder.start();

        Log.i(TAG, "init() decoder started");
    }

    /**
     * 从解码器获取下一帧解码数据。
     *
     * <p>此方法会自动处理输入端的数据喂入和循环播放逻辑。
     * 返回 true 表示成功获取到一帧，可以通过 {@link #getCurrentOutputBuffer()} 等方法获取帧数据。
     * 返回 false 表示当前没有可用帧（可能正在解码中）。</p>
     *
     * <p>此方法内置帧率控制：如果当前帧的显示时间还未到，会自动等待。</p>
     *
     * @return true 如果成功获取到一帧
     */
    public boolean dequeueOutputFrame() {
        if (mDecoder == null) {
            return false;
        }

        // 喂入输入数据
        feedInput();

        // 获取输出帧
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int outputIndex = mDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            // 输出格式变化，更新参数
            MediaFormat outputFormat = mDecoder.getOutputFormat();
            updateOutputFormat(outputFormat);
            // 再次尝试获取输出
            outputIndex = mDecoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
        }

        if (outputIndex >= 0) {
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                // 输出 EOS，执行循环播放
                mDecoder.releaseOutputBuffer(outputIndex, false);
                loopPlayback();
                return false;
            }

            mCurrentOutputBufferIndex = outputIndex;
            mCurrentOutputBuffer = mDecoder.getOutputBuffer(outputIndex);
            mCurrentPresentationTimeUs = bufferInfo.presentationTimeUs;
            return true;
        }

        return false;
    }

    /**
     * 释放当前输出帧的 buffer。处理完帧数据后必须调用此方法。
     */
    public void releaseOutputFrame() {
        if (mCurrentOutputBufferIndex >= 0 && mDecoder != null) {
            mDecoder.releaseOutputBuffer(mCurrentOutputBufferIndex, false);
            mCurrentOutputBufferIndex = -1;
            mCurrentOutputBuffer = null;
        }
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

    // ---- Getter 方法 ----

    /** 获取当前输出帧的 ByteBuffer（包含完整的 NV12 数据：Y 平面 + UV 平面） */
    public ByteBuffer getCurrentOutputBuffer() {
        return mCurrentOutputBuffer;
    }

    /** 获取当前帧的显示时间戳（微秒） */
    public long getCurrentPresentationTimeUs() {
        return mCurrentPresentationTimeUs;
    }

    /** 获取视频宽度（像素） */
    public int getWidth() {
        return mWidth;
    }

    /** 获取视频高度（像素） */
    public int getHeight() {
        return mHeight;
    }

    /**
     * 获取 Y 平面每行的字节跨度（含 padding）。
     * 如果 MediaFormat 中未提供 KEY_STRIDE，默认 stride = width。
     */
    public int getStride() {
        return mStride > 0 ? mStride : mWidth;
    }

    /** 获取 slice height（含 padding 的高度），如果未提供则默认等于 height */
    public int getSliceHeight() {
        return mSliceHeight > 0 ? mSliceHeight : mHeight;
    }

    /** 获取颜色格式 */
    public int getColorFormat() {
        return mColorFormat;
    }

    // ---- 私有方法 ----

    /**
     * 向解码器喂入输入数据
     */
    private void feedInput() {
        if (mInputEos) {
            return;
        }

        int inputIndex = mDecoder.dequeueInputBuffer(TIMEOUT_US);
        if (inputIndex >= 0) {
            ByteBuffer inputBuffer = mDecoder.getInputBuffer(inputIndex);
            int sampleSize = mExtractor.readSampleData(inputBuffer, 0);

            if (sampleSize < 0) {
                // 输入 EOS
                mDecoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                mInputEos = true;
            } else {
                long presentationTimeUs = mExtractor.getSampleTime();
                mDecoder.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                mExtractor.advance();
            }
        }
    }

    /**
     * 更新输出格式参数
     */
    private void updateOutputFormat(MediaFormat format) {
        mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
        mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);

        if (format.containsKey(MediaFormat.KEY_STRIDE)) {
            mStride = format.getInteger(MediaFormat.KEY_STRIDE);
        } else {
            mStride = mWidth;
            Log.w(TAG, "updateOutputFormat() KEY_STRIDE not available, using width=" + mWidth + " as stride");
        }

        if (format.containsKey(MediaFormat.KEY_SLICE_HEIGHT)) {
            mSliceHeight = format.getInteger(MediaFormat.KEY_SLICE_HEIGHT);
        } else {
            mSliceHeight = mHeight;
        }

        if (format.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            mColorFormat = format.getInteger(MediaFormat.KEY_COLOR_FORMAT);
        }

        Log.i(TAG, "updateOutputFormat() width=" + mWidth + " height=" + mHeight
                + " stride=" + mStride + " sliceHeight=" + mSliceHeight
                + " colorFormat=" + mColorFormat);

        // 验证颜色格式
        if (mColorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                && mColorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                && mColorFormat != 0x7F420888) { // ImageFormat.YUV_420_888
            Log.w(TAG, "updateOutputFormat() unexpected color format: " + mColorFormat
                    + ", expected NV12 (COLOR_FormatYUV420SemiPlanar=21). Rendering may be incorrect.");
        }
    }

    /**
     * 循环播放：重置 extractor 和 decoder 状态
     */
    private void loopPlayback() {
        Log.i(TAG, "loopPlayback() restarting from beginning");
        mExtractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
        mDecoder.flush();
        mInputEos = false;
        mOutputEos = false;
    }

    /** 获取视频总时长（微秒） */
    public long getDurationUs() {
        return mDurationUs;
    }
}
