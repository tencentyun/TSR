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

NS_ASSUME_NONNULL_BEGIN

@interface VideoRendererMetal : NSObject <MTKViewDelegate>

- (instancetype)initWithMetalKitView:(MTKView *)view;

/// Submit a NV12/BGRA CVPixelBuffer for rendering (thread-safe)
- (void)renderPixelBuffer:(CVPixelBufferRef)pixelBuffer;

/// The renderer's Metal command queue
@property (nonatomic, strong, readonly) id<MTLCommandQueue> commandQueue;

/// The MTKView this renderer is bound to
@property (nonatomic, weak, readonly) MTKView *metalView;

@end

NS_ASSUME_NONNULL_END
