//
//  TiePassSR.h
//  tsr-client
//
//  原子能力：shader 超分。
//  输入：BGRA CVPixelBuffer。输出：BGRA CVPixelBuffer（尺寸 = 输入 × scale）。
//  scale=1 即纯边缘增强；scale>1 即 shader 超分（如 1.5 / 2.0）。
//

#ifndef TiePassSR_h
#define TiePassSR_h

#import "TieShaderPass.h"

NS_ASSUME_NONNULL_BEGIN

@interface TiePassSR : NSObject <TieShaderPass>

/// 输出放大倍率，默认 1.0。
/// scale == 1.0：同尺寸边缘增强（输出尺寸 = 输入尺寸，仅做锐化型边缘处理）。
/// scale  > 1.0：Shader 超分（输出尺寸 = 输入尺寸 × scale）。
/// 典型值：1.5 / 2.0；不支持 < 1.0 的缩小。
@property (nonatomic, assign) float scale;

@end

NS_ASSUME_NONNULL_END

#endif /* TiePassSR_h */
