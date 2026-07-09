//
//  TieYuvToRgbPass.h
//  tsr-client
//
//  原子能力：NV12 → BGRA 色彩空间转换。
//  输入：NV12 CVPixelBuffer。输出：BGRA CVPixelBuffer（同尺寸）。
//  通过 colorSpace 属性控制 YUV→RGB 矩阵（默认 BT.709 Limited）。
//

#ifndef TieYuvToRgbPass_h
#define TieYuvToRgbPass_h

#import "TieShaderPass.h"

NS_ASSUME_NONNULL_BEGIN

/// YUV→RGB 色彩空间。
typedef NS_ENUM(NSInteger, TieYuvColorSpace) {
    TieYuvColorSpaceBT601Limited,   ///< BT.601 Limited（SD 视频）
    TieYuvColorSpaceBT601Full,      ///< BT.601 Full Range
    TieYuvColorSpaceBT709Limited,   ///< BT.709 Limited（HD 视频默认）
    TieYuvColorSpaceBT709Full,      ///< BT.709 Full Range
};

@interface TieYuvToRgbPass : NSObject <TieShaderPass>

/// YUV→RGB 转换色彩空间（默认 BT.709 Limited）。下一帧生效。
@property (nonatomic, assign) TieYuvColorSpace colorSpace;

@end

NS_ASSUME_NONNULL_END

#endif /* TieYuvToRgbPass_h */
