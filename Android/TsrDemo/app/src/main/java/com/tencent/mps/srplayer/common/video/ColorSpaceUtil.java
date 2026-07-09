package com.tencent.mps.srplayer.common.video;

import android.media.MediaFormat;

import com.tencent.mps.srplayer.common.gl.Nv12Shaders;
import com.tencent.mps.tie.api.TieTextureEnhancer.ColorStandard;

/**
 * 视频颜色空间检测：把 {@link android.media.MediaCodec}/{@link android.media.MediaExtractor}
 * 读到的 {@link MediaFormat#KEY_COLOR_RANGE}/{@link MediaFormat#KEY_COLOR_STANDARD}（API 24+）
 * 映射到 Demo / SDK 实际使用的色彩标准枚举。
 *
 * <p>读取优先级：</p>
 * <ol>
 *   <li>codec 显式声明的字段（最准确）；</li>
 *   <li>读不到时默认 BT.709（H.264/H.265 SDR 的规范默认色彩空间）；</li>
 *   <li>Range 读不到时默认 Limited（最常见兜底档）。</li>
 * </ol>
 *
 * <p>BT.2020 暂统一回退到 BT.709，避免 HDR 流水线缺失放大偏差。</p>
 */
public final class ColorSpaceUtil {
    private ColorSpaceUtil() {}

    /**
     * 根据 codec 字段推断 SDK 的 {@link ColorStandard}。
     *
     * @param colorRange    {@link MediaFormat#KEY_COLOR_RANGE} 值；-1 表示读不到
     * @param colorStandard {@link MediaFormat#KEY_COLOR_STANDARD} 值；-1 表示读不到
     * @param videoWidth    视频宽度（未使用，保留参数兼容性）
     * @return 永不为 null；读不到色域字段时默认 BT.709 LIMITED
     */
    public static ColorStandard detectForSdk(int colorRange, int colorStandard, int videoWidth) {
        boolean is709 = isBt709(colorStandard);
        boolean full = (colorRange == MediaFormat.COLOR_RANGE_FULL);
        if (is709) {
            return full ? ColorStandard.BT709_FULL : ColorStandard.BT709_LIMITED;
        } else {
            return full ? ColorStandard.BT601_FULL : ColorStandard.BT601_LIMITED;
        }
    }

    /**
     * 把 SDK 的 {@link ColorStandard} 映射到 Demo ByteBuffer 路径专用的
     * {@link Nv12Shaders.YuvColorSpace}（NV12 直送 shader）。
     */
    public static Nv12Shaders.YuvColorSpace toNv12(ColorStandard cs) {
        switch (cs) {
            case BT601_FULL:    return Nv12Shaders.YuvColorSpace.BT601_FULL;
            case BT709_LIMITED: return Nv12Shaders.YuvColorSpace.BT709_LIMITED;
            case BT709_FULL:    return Nv12Shaders.YuvColorSpace.BT709_FULL;
            case BT601_LIMITED:
            default:            return Nv12Shaders.YuvColorSpace.BT601_LIMITED;
        }
    }

    private static boolean isBt709(int colorStandard) {
        if (colorStandard == MediaFormat.COLOR_STANDARD_BT709
                || colorStandard == MediaFormat.COLOR_STANDARD_BT2020 /* 暂按 709 兜底 */) {
            return true;
        } else if (colorStandard == MediaFormat.COLOR_STANDARD_BT601_PAL
                || colorStandard == MediaFormat.COLOR_STANDARD_BT601_NTSC) {
            return false;
        }
        // 没读到合法值（codec 未写 VUI）：默认 BT.709（H.264/H.265 SDR 规范默认色彩空间）
        return true;
    }
}
