//
//  VideoRendererMetal.h
//  tsr-ios-demo
//
//  Metal CVPixelBuffer renderer (NV12 / BGRA).
//  Business-agnostic: renders any CVPixelBuffer to an MTKView.
//

#import <Foundation/Foundation.h>
#import <MetalKit/MetalKit.h>
#import <CoreVideo/CoreVideo.h>
#import "VideoColorSpace.h"

NS_ASSUME_NONNULL_BEGIN

/// 旋转方向
typedef NS_ENUM(NSInteger, VMRRotation) {
    VMRRotationNone = 0,   ///< 不旋转
    VMRRotationCW   = 1,   ///< 90° 顺时针
    VMRRotationCCW  = 2,   ///< 90° 逆时针
};

@interface VideoRendererMetal : NSObject <MTKViewDelegate>

- (instancetype)initWithMetalKitView:(MTKView *)view;

/// Submit a NV12/BGRA CVPixelBuffer for rendering (thread-safe)
- (void)renderPixelBuffer:(CVPixelBufferRef)pixelBuffer;

/// 为竖拍视频设置旋转方向（根据 preferredTransform.b 的符号）
@property (nonatomic, assign) VMRRotation rotation;

/// YUV→RGB 色彩空间（默认 BT.709 Limited）。设置后下一帧生效。
@property (nonatomic, assign) VMRYuvColorSpace yuvColorSpace;

/// The renderer's Metal command queue
@property (nonatomic, strong, readonly) id<MTLCommandQueue> commandQueue;

/// The MTKView this renderer is bound to
@property (nonatomic, weak, readonly) MTKView *metalView;

@end

NS_ASSUME_NONNULL_END
