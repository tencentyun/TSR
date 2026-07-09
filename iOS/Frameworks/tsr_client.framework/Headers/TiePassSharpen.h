//
//  TiePassSharpen.h
//  tsr-client
//
//  原子能力：锐化。
//  输入：BGRA CVPixelBuffer。输出：BGRA CVPixelBuffer（同尺寸）。
//
//  锐化强度 sharpness [0, 1]，默认 0.4：
//    0.1~0.2  微弱锐化，肉眼几乎无感（适合原片已足够清晰的场景）
//    0.3~0.5  自然锐化，推荐通用值（当前默认 0.4）
//    0.6~0.8  明显锐化，细线处可能 halo（适合被 TAA 模糊后的恢复）
//    0.9~1.0  过度锐化，ringing 明显（不推荐）
//

#ifndef TiePassSharpen_h
#define TiePassSharpen_h

#import "TieShaderPass.h"

NS_ASSUME_NONNULL_BEGIN

@interface TiePassSharpen : NSObject <TieShaderPass>
@property (nonatomic, assign) float sharpness;   ///< 锐化强度 [0, 1]，默认 0.4
@end

NS_ASSUME_NONNULL_END

#endif /* TiePassSharpen_h */
