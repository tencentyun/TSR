package com.tencent.mps.srplayer.ui;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import com.tencent.mps.tie.api.v2.ErrorCode;
import com.tencent.mps.tie.api.v2.TiePro;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

// 解码 mp4 文件，使用 TsrSdk 进行增强处理后，输出 yuv 文件。用于评测视频增强的效果。
public class Exporter {

    private static final String TAG = "Exporter";
    private final TiePro mTiePro = new TiePro();
    private int mWidth, mHeight;
    private boolean mModelReady = false;

    private boolean init(int width, int height) {
        ErrorCode result = mTiePro.init(width, height);
        Log.i(TAG, "init result: " + result);
        return result == ErrorCode.SUCCESS;
    }

    public boolean decodeToYuv(String inputPath, String outputPath) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        FileOutputStream fos = null;

        try {
            // 1. 创建MediaExtractor并设置数据源
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            // 2. 选择视频轨道
            int videoTrackIndex = selectVideoTrack(extractor);
            if (videoTrackIndex < 0) {
                Log.e(TAG, "未找到视频轨道");
                return false;
            }

            // 3. 获取视频格式
            MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
            extractor.selectTrack(videoTrackIndex);
            mWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            mHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            String mime = format.getString(MediaFormat.KEY_MIME);
            Log.i(TAG, "视频信息: " + mWidth + "x" + mHeight + ", MIME: " + mime);

            // 初始化模型（同步调用，当前已在调用者线程）
            mModelReady = init(mWidth, mHeight);

            // 4. 创建解码器
            decoder = MediaCodec.createDecoderByType(mime);
            decoder.configure(format, null, null, 0);
            decoder.start();

            // 5. 准备输出文件
            fos = new FileOutputStream(outputPath);

            // 6. 开始解码循环
            return decodeFrames(extractor, decoder, fos);

        } catch (Exception e) {
            Log.e(TAG, "解码过程错误", e);
            return false;
        } finally {
            // 7. 释放资源
            mTiePro.release();
            if (decoder != null) {
                decoder.stop();
                decoder.release();
            }
            if (extractor != null) {
                extractor.release();
            }
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private boolean decodeFrames(MediaExtractor extractor, MediaCodec decoder, FileOutputStream fos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean sawInputEOS = false;
        boolean sawOutputEOS = false;
        int decodedFrameCount = 0;
        final long TIMEOUT_US = 10 * 1000;

        while (!sawOutputEOS) {
            // 输入数据
            if (!sawInputEOS) {
                int inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferIndex);
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);

                    if (sampleSize < 0) {
                        sawInputEOS = true;
                        decoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        long presentationTimeUs = extractor.getSampleTime();
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0);
                        extractor.advance();
                    }
                }
            }

            // 输出数据
            int outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outputBufferIndex >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    sawOutputEOS = true;
                }

                if (info.size > 0) {
                    ByteBuffer outputBuffer = decoder.getOutputBuffer(outputBufferIndex);
                    // outputBuffer 是 nv12 格式. 不知道有没有可能有 stride 需要处理。
                    boolean ret = writeYuvFrame(outputBuffer, info, fos);
                    decodedFrameCount++;
                    Log.v(TAG, "输出第 " + decodedFrameCount + " 帧 " + ret);
                    if (!ret) {
                        return false;
                    }
                }

                decoder.releaseOutputBuffer(outputBufferIndex, false);
            }
        }

        Log.i(TAG, "输出完成，共 " + decodedFrameCount + " 帧");
        return true;
    }

    // buffer 是 nv12 格式
    private boolean writeYuvFrame(ByteBuffer buffer, MediaCodec.BufferInfo info, FileOutputStream fos) {
        try {
            buffer.position(info.offset);
            buffer.limit(info.offset + info.size);

            // 先对 ByteBuffer 中的 Y 通道数据进行增强处理（原地更新）
            boolean ret = enhance(buffer);
            if (!ret) {
                return false;
            }

            // 增强完成后，将数据拷贝到 byte[] 写入文件
            buffer.position(info.offset);
            byte[] frameData = new byte[info.size];
            buffer.get(frameData);

            fos.write(frameData);
            fos.flush();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "写入YUV数据错误", e);
            return false;
        }
    }

    private boolean enhance(ByteBuffer yuvData) {
        if (!mModelReady) {
            Log.e(TAG, "enhance() fail");
            return false;
        }
        mTiePro.process(yuvData, mWidth, mHeight);
        return true;
    }
}