package com.tencent.mps.srplayer.common.video;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Size;

import androidx.annotation.RequiresApi;

import java.io.IOException;

/**
 * 视频轻量探测：只用 {@link MediaExtractor} 读取视频轨道的尺寸与色彩信息，
 * 不创建 {@link android.media.MediaCodec}、不需要 Surface。
 *
 * <p>用途：在创建 OES 纹理 / GL 资源 / TieSurface <b>之前</b>先拿到视频元信息。否则会陷入
 * "解码器 configure 需要 Surface → Surface 需要 OES 纹理 → 纹理/GL 资源需要尺寸"的循环依赖，
 * 被迫先建临时纹理再重建；颜色信息则因 Surface 路径下解码器不暴露 MediaFormat 而彻底丢失。</p>
 *
 * <p>{@link ColorInfo} 包含 width/height/colorRange/colorStandard。后两个值仅在 API 24+ 可读；
 * 读不到时为 -1，由 {@link ColorSpaceUtil} 决定是否按分辨率启发式推断。</p>
 */
public final class VideoProbe {
    private VideoProbe() {}

    /** 探测结果：视频尺寸 + 颜色字段。colorRange/colorStandard 读不到时为 -1。 */
    public static final class ColorInfo {
        public final int width;
        public final int height;
        /** {@link MediaFormat#KEY_COLOR_RANGE} 值；读不到时为 -1 */
        public final int colorRange;
        /** {@link MediaFormat#KEY_COLOR_STANDARD} 值；读不到时为 -1 */
        public final int colorStandard;

        public ColorInfo(int width, int height, int colorRange, int colorStandard) {
            this.width = width;
            this.height = height;
            this.colorRange = colorRange;
            this.colorStandard = colorStandard;
        }
    }

    /** 探测本地文件路径视频信息。 */
    public static ColorInfo probe(String filePath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(filePath);
            return probeInternal(extractor, filePath);
        } finally {
            extractor.release();
        }
    }

    /** 探测相册 URI 视频信息（仅依赖 ContentResolver 读取权限）。 */
    public static ColorInfo probe(Context context, Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            return probeInternal(extractor, uri.toString());
        } finally {
            extractor.release();
        }
    }

    private static ColorInfo probeInternal(MediaExtractor extractor, String sourceDesc) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                int w = format.getInteger(MediaFormat.KEY_WIDTH);
                int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                int range = readOptionalInt(format, MediaFormat.KEY_COLOR_RANGE);
                int standard = readOptionalInt(format, MediaFormat.KEY_COLOR_STANDARD);
                return new ColorInfo(w, h, range, standard);
            }
        }
        throw new IllegalStateException("No video track found in " + sourceDesc);
    }

    /** 读取可选整型字段；不存在时返回 -1。 */
    private static int readOptionalInt(MediaFormat format, String key) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
                && format.containsKey(key)) {
            try {
                return format.getInteger(key);
            } catch (Exception e) {
                // 部分 codec 写入了非法值，吞掉返回 -1
            }
        }
        return -1;
    }
}
