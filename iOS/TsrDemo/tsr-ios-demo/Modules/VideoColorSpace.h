//
//  VideoColorSpace.h
//  tsr-ios-demo
//
//  视频颜色空间检测：从 AVAssetTrack 的 CMFormatDescription 读取 YCbCrMatrix 和 FullRange，
//  映射为 YUV→RGB 转换所需的色彩空间枚举。
//
//  对标 Android：ColorSpaceUtil.java。
//

#import <AVFoundation/AVFoundation.h>

NS_ASSUME_NONNULL_BEGIN

/// YUV→RGB 色彩空间（矩阵 + 范围）
typedef NS_ENUM(NSInteger, VMRYuvColorSpace) {
    VMRYuvColorSpaceBT601Limited,   ///< BT.601 Limited（SD 视频默认）
    VMRYuvColorSpaceBT601Full,      ///< BT.601 Full Range
    VMRYuvColorSpaceBT709Limited,   ///< BT.709 Limited（HD 视频默认）
    VMRYuvColorSpaceBT709Full,      ///< BT.709 Full Range
};

@interface VideoColorSpace : NSObject

/// 从 AVAssetTrack 检测颜色空间。
/// 读取优先级：
///   1. CMFormatDescription 显式声明的 YCbCrMatrix + FullRangeVideo；
///   2. 读不到时默认 BT.709 Limited（H.264/H.265 SDR 规范默认色彩空间）；
///   3. BT.2020 暂回退 BT.709 Limited（避免 HDR 流水线缺失）。
+ (VMRYuvColorSpace)detectFromTrack:(AVAssetTrack *)track;

/// 调试用：枚举 → 可读字符串
+ (NSString *)debugString:(VMRYuvColorSpace)cs;

@end

NS_ASSUME_NONNULL_END
