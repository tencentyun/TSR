//
//  TiePassDenoise.h
//  tsr-client
//
//  原子能力：双边滤波降噪（空间 + 颜色域联合加权，边缘保持）。
//  输入：BGRA CVPixelBuffer。输出：BGRA CVPixelBuffer（同尺寸）。
//
//  参数说明：
//    sigmaColor [0.02, 0.3]，默认 0.1 —— 颜色敏感度，越小纹理保留越多、降噪越弱：
//      0.02~0.05  仅抹平极细微噪点，适合低压缩高画质原片
//      0.08~0.12  可见降噪，纹理基本保留（推荐默认）
//      0.15~0.20  明显降噪，纹理开始软化（适合高压缩低码率）
//      0.25~0.30  油画感，细节丢失明显（仅适合极端噪点场景）
//    sigmaSpace [0.5, 4.0]，默认 2.0 —— 空间域半径，越小只抹近邻噪点，越大抹大范围噪点。
//

#ifndef TiePassDenoise_h
#define TiePassDenoise_h

#import "TieShaderPass.h"

NS_ASSUME_NONNULL_BEGIN

@interface TiePassDenoise : NSObject <TieShaderPass>
@property (nonatomic, assign) float sigmaColor;   ///< 颜色域 sigma，典型 0.05~0.2，默认 0.1
@property (nonatomic, assign) float sigmaSpace;   ///< 空间域 sigma，典型 1.0~3.0，默认 2.0
@end

NS_ASSUME_NONNULL_END

#endif /* TiePassDenoise_h */
