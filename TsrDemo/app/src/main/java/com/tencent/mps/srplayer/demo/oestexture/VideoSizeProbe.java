package com.tencent.mps.srplayer.demo.oestexture;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Size;

import java.io.IOException;

/**
 * 视频尺寸轻量探测：只用 {@link MediaExtractor} 读取视频轨道的 width/height，
 * 不创建 {@link android.media.MediaCodec}、不需要 Surface。
 *
 * <p>用途：在创建 OES 纹理 / GL 资源 <b>之前</b> 先拿到视频尺寸。否则会陷入
 * "解码器 configure 需要 Surface → Surface 需要 OES 纹理 → 纹理/GL 资源需要尺寸" 的循环依赖，
 * 被迫先建临时纹理再重建。先探尺寸即可一次成型。</p>
 */
public final class VideoSizeProbe {
    private VideoSizeProbe() {}

    /** 探测本地文件路径视频尺寸。 */
    public static Size probe(String filePath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(filePath);
            return probeInternal(extractor, filePath);
        } finally {
            extractor.release();
        }
    }

    /** 探测相册 URI 视频尺寸（仅依赖 ContentResolver 读取权限）。 */
    public static Size probe(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            return probeInternal(extractor, uri.toString());
        } finally {
            extractor.release();
        }
    }

    private static Size probeInternal(MediaExtractor extractor, String sourceDesc) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                int w = format.getInteger(MediaFormat.KEY_WIDTH);
                int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                return new Size(w, h);
            }
        }
        throw new IllegalStateException("No video track found in " + sourceDesc);
    }
}
