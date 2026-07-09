//
//  TieEnhancer.h
//  tsr-client
//
//  推理增强器：对单帧 NV12 CVPixelBuffer 做增强或超分。
//  增强(1×)与超分(2×/3×)共用一个类，用 TieEnhancerType 区分；倍率由 SDK 内部决策。
//  输入输出均为 NV12 CVPixelBuffer（420YpCbCr8BiPlanarVideoRange）。
//  不做色彩转换、不上屏——调用方自行完成后续渲染。
//

#ifndef TieEnhancer_h
#define TieEnhancer_h

#import <Foundation/Foundation.h>
#import <CoreVideo/CoreVideo.h>

NS_ASSUME_NONNULL_BEGIN

#pragma mark - 枚举

/// 处理意图。
/// TieEnhancerTypeIE — 同尺寸增强（1×）；
/// TieEnhancerTypeSR — 超分，倍率（2×/3×）由 SDK 按视频尺寸自动决策。
typedef NS_ENUM(NSInteger, TieEnhancerType) {
    TieEnhancerTypeIE = 0,   ///< Y 通道同尺寸增强（1×）
    TieEnhancerTypeSR = 1,   ///< Y 通道超分（2×/3× 自动决策）
};

/// setup 返回码。只增不改语义，调用方通常只需判断 == OK。
typedef NS_ENUM(NSInteger, TieInitCode) {
    TieInitCodeOK                 = 0,    ///< 成功
    TieInitCodeInvalidConfig      = 1,    ///< Config 为 nil 或字段非法
    TieInitCodeUnsupportedSize    = 2,    ///< 视频分辨率不支持
    TieInitCodeModelIOError       = 4,    ///< 模型文件读取失败
    TieInitCodeModelDecryptError  = 6,    ///< 模型解密失败
    TieInitCodeModelLoadError     = 5,    ///< 模型加载/编译失败
    TieInitCodeModelShapeMismatch = 7,    ///< 模型 I/O 校验不通过
    TieInitCodeWarmupFailed       = 8,    ///< 初始化推理失败
    TieInitCodeLicenseUnavailable = 9,    ///< 鉴权未通过
    TieInitCodeUnknown            = 99,   ///< 未知错误
};

#pragma mark - Config

/// TieEnhancer 初始化入参。调用方只声明意图与视频源宽高，倍率/档位/模型由 SDK 决策。
@interface TieEnhancerConfig : NSObject
@property (nonatomic, assign) TieEnhancerType task;          ///< 处理意图
@property (nonatomic, assign) int32_t videoWidth;    ///< 视频源宽（像素）
@property (nonatomic, assign) int32_t videoHeight;   ///< 视频源高（像素）

+ (instancetype)configWithTask:(TieEnhancerType)task
                    videoWidth:(int32_t)videoWidth
                   videoHeight:(int32_t)videoHeight;
@end

#pragma mark - InitResult

/// setup 返回结果。code==OK 表示成功，其余为失败原因。
@interface TieInitResult : NSObject
@property (nonatomic, readonly) TieInitCode code;
@property (nonatomic, readonly, copy) NSString *message;    ///< 人类可读，仅日志/调试
@property (nonatomic, readonly) int32_t inferMs;            ///< 推理耗时估计（ms）
@property (nonatomic, readonly) int32_t outputWidth;        ///< 有效输出宽 = videoW × upscale
@property (nonatomic, readonly) int32_t outputHeight;       ///< 有效输出高 = videoH × upscale
@property (nonatomic, readonly) int32_t outputStride;       ///< 输出 Y 平面行 stride（通常 = outputWidth）
@end

#pragma mark - TieEnhancer

@interface TieEnhancer : NSObject

/// 初始化推理引擎。
- (instancetype)init;

/// 按 Config 初始化：内部完成倍率决策、模型选择、加载与初始化推理。
/// 请在后台线程调用（加载可能阻塞）。失败时 code != OK，调用方应回退原始播放。
- (TieInitResult *)setup:(TieEnhancerConfig *)config;

/// 对一帧 NV12 做推理。输入/输出均为 NV12（420YpCbCr8BiPlanarVideoRange）。
/// 返回 buffer 为 SDK 内部复用，下一次 process / close 前消费完毕。
/// 失败或未初始化时返回输入原帧（调用方据此回退原始播放）。
- (CVPixelBufferRef)process:(CVPixelBufferRef)nv12Input;

/// 释放全部资源。
- (void)close;

@end

NS_ASSUME_NONNULL_END

#endif /* TieEnhancer_h */
