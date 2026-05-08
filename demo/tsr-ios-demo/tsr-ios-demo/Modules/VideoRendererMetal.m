//
//  VideoRendererMetal.m
//  tsr-ios-demo
//
//  Metal CVPixelBuffer renderer (NV12 / BGRA).
//  Business-agnostic: renders any CVPixelBuffer to an MTKView.
//

#import "VideoRendererMetal.h"
#import <Metal/Metal.h>
#import <MetalKit/MetalKit.h>
#import <CoreVideo/CoreVideo.h>

typedef struct {
    float position[4];
    float texCoord[2];
} VMRVertex;

static const VMRVertex kVMRQuadVertices[] = {
    { {-1.0f, -1.0f, 0, 1}, {0.0f, 1.0f} },
    { { 1.0f, -1.0f, 0, 1}, {1.0f, 1.0f} },
    { {-1.0f,  1.0f, 0, 1}, {0.0f, 0.0f} },
    { { 1.0f,  1.0f, 0, 1}, {1.0f, 0.0f} },
};

@interface VideoRendererMetal ()
@property (nonatomic, strong) id<MTLDevice>              device;
@property (nonatomic, strong, readwrite) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> yuvPipelineState;
@property (nonatomic, strong) id<MTLRenderPipelineState> bgraPipelineState;
@property (nonatomic, strong) id<MTLBuffer>              vertexBuffer;
@property (nonatomic, assign) CVMetalTextureCacheRef     textureCache;
@property (nonatomic, assign) CVPixelBufferRef           pendingBuffer;
@property (nonatomic, strong) dispatch_semaphore_t       bufferSemaphore;
@property (nonatomic, weak, readwrite) MTKView           *metalView;
@end

@implementation VideoRendererMetal

- (instancetype)initWithMetalKitView:(MTKView *)view {
    self = [super init];
    if (!self) return nil;

    _device = view.device ?: MTLCreateSystemDefaultDevice();
    view.device = _device;
    view.framebufferOnly = YES;
    view.colorPixelFormat = MTLPixelFormatBGRA8Unorm;
    view.delegate = self;
    view.preferredFramesPerSecond = 30;
    view.enableSetNeedsDisplay = NO;

    _metalView = view;
    _commandQueue = [_device newCommandQueue];
    _bufferSemaphore = dispatch_semaphore_create(1);

    CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, _device, nil, &_textureCache);

    [self _buildYUVPipeline:view];
    [self _buildBGRAPipeline:view];
    [self _buildVertexBuffer];

    return self;
}

#pragma mark - Pipeline Setup

- (void)_buildYUVPipeline:(MTKView *)view {
    id<MTLLibrary> library = [_device newDefaultLibrary];
    id<MTLFunction> vertFn = [library newFunctionWithName:@"vmr_yuv_vertex"];
    id<MTLFunction> fragFn = [library newFunctionWithName:@"vmr_yuv_fragment"];

    MTLVertexDescriptor *vtxDesc = [[MTLVertexDescriptor alloc] init];
    vtxDesc.attributes[0].format      = MTLVertexFormatFloat4;
    vtxDesc.attributes[0].offset      = 0;
    vtxDesc.attributes[0].bufferIndex = 0;
    vtxDesc.attributes[1].format      = MTLVertexFormatFloat2;
    vtxDesc.attributes[1].offset      = sizeof(float) * 4;
    vtxDesc.attributes[1].bufferIndex = 0;
    vtxDesc.layouts[0].stride         = sizeof(VMRVertex);

    MTLRenderPipelineDescriptor *pipeDesc = [[MTLRenderPipelineDescriptor alloc] init];
    pipeDesc.vertexFunction                  = vertFn;
    pipeDesc.fragmentFunction                = fragFn;
    pipeDesc.vertexDescriptor                = vtxDesc;
    pipeDesc.colorAttachments[0].pixelFormat = view.colorPixelFormat;

    NSError *error = nil;
    _yuvPipelineState = [_device newRenderPipelineStateWithDescriptor:pipeDesc error:&error];
    if (error) NSLog(@"[VideoRendererMetal] YUV Pipeline error: %@", error);
}

- (void)_buildBGRAPipeline:(MTKView *)view {
    id<MTLLibrary> library = [_device newDefaultLibrary];
    id<MTLFunction> vertFn = [library newFunctionWithName:@"vmr_bgra_vertex"];
    id<MTLFunction> fragFn = [library newFunctionWithName:@"vmr_bgra_fragment"];

    MTLVertexDescriptor *vtxDesc = [[MTLVertexDescriptor alloc] init];
    vtxDesc.attributes[0].format      = MTLVertexFormatFloat4;
    vtxDesc.attributes[0].offset      = 0;
    vtxDesc.attributes[0].bufferIndex = 0;
    vtxDesc.attributes[1].format      = MTLVertexFormatFloat2;
    vtxDesc.attributes[1].offset      = sizeof(float) * 4;
    vtxDesc.attributes[1].bufferIndex = 0;
    vtxDesc.layouts[0].stride         = sizeof(VMRVertex);

    MTLRenderPipelineDescriptor *pipeDesc = [[MTLRenderPipelineDescriptor alloc] init];
    pipeDesc.vertexFunction                  = vertFn;
    pipeDesc.fragmentFunction                = fragFn;
    pipeDesc.vertexDescriptor                = vtxDesc;
    pipeDesc.colorAttachments[0].pixelFormat = view.colorPixelFormat;

    NSError *error = nil;
    _bgraPipelineState = [_device newRenderPipelineStateWithDescriptor:pipeDesc error:&error];
    if (error) NSLog(@"[VideoRendererMetal] BGRA Pipeline error: %@", error);
}

- (void)_buildVertexBuffer {
    _vertexBuffer = [_device newBufferWithBytes:kVMRQuadVertices
                                         length:sizeof(kVMRQuadVertices)
                                        options:MTLResourceStorageModeShared];
}

#pragma mark - Public

- (void)renderPixelBuffer:(CVPixelBufferRef)pixelBuffer {
    if (!pixelBuffer) return;
    dispatch_semaphore_wait(_bufferSemaphore, DISPATCH_TIME_FOREVER);
    CVPixelBufferRetain(pixelBuffer);
    CVPixelBufferRef old = _pendingBuffer;
    _pendingBuffer = pixelBuffer;
    dispatch_semaphore_signal(_bufferSemaphore);
    if (old) CVPixelBufferRelease(old);
}

#pragma mark - MTKViewDelegate

- (void)mtkView:(MTKView *)view drawableSizeWillChange:(CGSize)size {}

- (void)drawInMTKView:(MTKView *)view {
    dispatch_semaphore_wait(_bufferSemaphore, DISPATCH_TIME_FOREVER);
    CVPixelBufferRef buffer = _pendingBuffer;
    if (buffer) CVPixelBufferRetain(buffer);
    dispatch_semaphore_signal(_bufferSemaphore);

    if (!buffer) return;

    OSType pixelFormat = CVPixelBufferGetPixelFormatType(buffer);
    BOOL isBGRA = (pixelFormat == kCVPixelFormatType_32BGRA);

    size_t width  = CVPixelBufferGetWidth(buffer);
    size_t height = CVPixelBufferGetHeight(buffer);

    id<CAMetalDrawable> drawable = view.currentDrawable;
    MTLRenderPassDescriptor *rpd = view.currentRenderPassDescriptor;
    if (!drawable || !rpd) {
        CVPixelBufferRelease(buffer);
        return;
    }

    id<MTLCommandBuffer> cmdBuf = [_commandQueue commandBuffer];

    if (isBGRA) {
        CVMetalTextureRef bgraTexRef = NULL;
        CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, _textureCache, buffer, nil,
            MTLPixelFormatBGRA8Unorm, width, height, 0, &bgraTexRef);

        if (!bgraTexRef) {
            CVPixelBufferRelease(buffer);
            return;
        }

        id<MTLTexture> bgraTex = CVMetalTextureGetTexture(bgraTexRef);
        id<MTLRenderCommandEncoder> enc = [cmdBuf renderCommandEncoderWithDescriptor:rpd];
        [enc setRenderPipelineState:_bgraPipelineState];
        [enc setVertexBuffer:_vertexBuffer offset:0 atIndex:0];
        [enc setFragmentTexture:bgraTex atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [enc endEncoding];
        [cmdBuf presentDrawable:drawable];
        [cmdBuf commit];
        CFRelease(bgraTexRef);
    } else {
        // NV12 YUV → RGB
        CVMetalTextureRef yTexRef  = NULL;
        CVMetalTextureRef uvTexRef = NULL;

        CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, _textureCache, buffer, nil,
            MTLPixelFormatR8Unorm, width, height, 0, &yTexRef);

        CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, _textureCache, buffer, nil,
            MTLPixelFormatRG8Unorm, width / 2, height / 2, 1, &uvTexRef);

        if (!yTexRef || !uvTexRef) {
            if (yTexRef)  CFRelease(yTexRef);
            if (uvTexRef) CFRelease(uvTexRef);
            CVPixelBufferRelease(buffer);
            return;
        }

        id<MTLTexture> yTex  = CVMetalTextureGetTexture(yTexRef);
        id<MTLTexture> uvTex = CVMetalTextureGetTexture(uvTexRef);

        id<MTLRenderCommandEncoder> enc = [cmdBuf renderCommandEncoderWithDescriptor:rpd];
        [enc setRenderPipelineState:_yuvPipelineState];
        [enc setVertexBuffer:_vertexBuffer offset:0 atIndex:0];
        [enc setFragmentTexture:yTex  atIndex:0];
        [enc setFragmentTexture:uvTex atIndex:1];
        [enc drawPrimitives:MTLPrimitiveTypeTriangleStrip vertexStart:0 vertexCount:4];
        [enc endEncoding];
        [cmdBuf presentDrawable:drawable];
        [cmdBuf commit];

        CFRelease(yTexRef);
        CFRelease(uvTexRef);
    }
    CVPixelBufferRelease(buffer);
}

- (void)dealloc {
    if (_textureCache) {
        CVMetalTextureCacheFlush(_textureCache, 0);
        CFRelease(_textureCache);
    }
    if (_pendingBuffer) CVPixelBufferRelease(_pendingBuffer);
}

@end
