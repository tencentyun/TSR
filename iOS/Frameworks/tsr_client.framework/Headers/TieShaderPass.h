//
//  TieShaderPass.h
//  tsr-client
//
//  Shader 原子能力域的统一协议。每个原子 pass 单一职责、无状态拼接，
//  由调用方按需自行串联。所有 pass 必须在同一 Metal 线程调用。
//

#ifndef TieShaderPass_h
#define TieShaderPass_h

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <CoreVideo/CoreVideo.h>

NS_ASSUME_NONNULL_BEGIN

/// 原子 shader pass 统一接口。
/// 输入/输出的具体像素格式由各 pass 自行约定（见各类头注释）。
/// 返回值是 pass 内部复用的 buffer，下一次 process / close 前消费完毕，调用方不要 close。
@protocol TieShaderPass <NSObject>

- (instancetype)initWithDevice:(id<MTLDevice>)device;

/// 处理一帧。失败返回 NULL。
- (nullable CVPixelBufferRef)process:(CVPixelBufferRef)input;

- (void)close;

@end

NS_ASSUME_NONNULL_END

#endif /* TieShaderPass_h */
