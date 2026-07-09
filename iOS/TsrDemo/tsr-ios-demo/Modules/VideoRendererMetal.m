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

/// Metal shader 侧 YuvColorParams 对应的 CPU 结构体（内存布局一致）
typedef struct {
    simd_float3x3 matrix;   ///< YUV→RGB 3×3 列优先矩阵
    simd_float3   offset;   ///< Y/U/V 各通道偏移
} VMRYuvColorParams;

/// 根据色彩空间枚举生成 shader 用的矩阵 + 偏移参数
static VMRYuvColorParams VMRMakeYuvColorParams(VMRYuvColorSpace cs) {
    // 矩阵系数（行优先；shader 使用列优先，simd_float3x3 传入时自动转）
    //
    // 公式：RGB = matrix * (YUV + offset)
    //   Limited: Y 列含 255/219 缩放，offset.y = -16/255
    //   Full:    Y 列无缩放，offset.y = 0
    //
    // BT.601 系数（色度部分两者相同，仅 Y 列缩放不同）:
    //   R = Y' + 1.402   * Cr'
    //   G = Y' - 0.34414 * Cb' - 0.71414 * Cr'
    //   B = Y' + 1.772   * Cb'
    // BT.709:
    //   R = Y' + 1.5748  * Cr'
    //   G = Y' - 0.18732 * Cb' - 0.46812 * Cr'
    //   B = Y' + 1.8556  * Cb'

    static const float kScaleY  = 255.0f / 219.0f;  // ≈ 1.164 — Limited Y 范围补偿
    static const float kScaleUV = 255.0f / 224.0f;  // ≈ 1.138 — Limited UV 范围补偿（Cb/Cr ∈ [16,240]）

    BOOL isFull = (cs == VMRYuvColorSpaceBT601Full ||
                   cs == VMRYuvColorSpaceBT709Full);
    BOOL is709  = (cs == VMRYuvColorSpaceBT709Limited ||
                   cs == VMRYuvColorSpaceBT709Full);

    float sY  = isFull ? 1.0f : kScaleY;   // Y 列缩放
    float sUV = isFull ? 1.0f : kScaleUV;  // Cb/Cr 列缩放（对齐 Android）

    float cbR = 0.0f,       crR = sUV * (is709 ? 1.5748f  : 1.402f);
    float cbG = sUV * (is709 ? -0.18732f : -0.34414f);
    float crG = sUV * (is709 ? -0.46812f : -0.71414f);
    float cbB = sUV * (is709 ? 1.8556f  : 1.772f),  crB = 0.0f;

    float m[9] = {
        sY,   sY,    sY,    // R/G/B Y 分量
        cbR,  cbG,   cbB,   // R/G/B Cb 分量
        crR,  crG,   crB,   // R/G/B Cr 分量
    };

    simd_float3x3 mat = simd_matrix(
        simd_make_float3(m[0], m[1], m[2]),
        simd_make_float3(m[3], m[4], m[5]),
        simd_make_float3(m[6], m[7], m[8]));

    // Limited: Y offset = -16/255; Full: 0
    // Cb/Cr 始终 -0.5 (= -128/255，中心化)
    simd_float3 offset = simd_make_float3(
        isFull ? 0.0f : -16.0f / 255.0f,
        -0.5f,
        -0.5f);

    VMRYuvColorParams p;
    p.matrix = mat;
    p.offset = offset;
    return p;
}

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

/// 90° CW 旋转后的顶点
static const VMRVertex kVMRQuadVertices90CW[] = {
    { {-1.0f, -1.0f, 0, 1}, {0.0f, 0.0f} },
    { { 1.0f, -1.0f, 0, 1}, {0.0f, 1.0f} },
    { {-1.0f,  1.0f, 0, 1}, {1.0f, 0.0f} },
    { { 1.0f,  1.0f, 0, 1}, {1.0f, 1.0f} },
};

/// 90° CCW 旋转后的顶点
static const VMRVertex kVMRQuadVertices90CCW[] = {
    { {-1.0f, -1.0f, 0, 1}, {1.0f, 1.0f} },
    { { 1.0f, -1.0f, 0, 1}, {1.0f, 0.0f} },
    { {-1.0f,  1.0f, 0, 1}, {0.0f, 1.0f} },
    { { 1.0f,  1.0f, 0, 1}, {0.0f, 0.0f} },
};

@interface VideoRendererMetal ()
@property (nonatomic, strong) id<MTLDevice>              device;
@property (nonatomic, strong, readwrite) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> yuvPipelineState;
@property (nonatomic, strong) id<MTLRenderPipelineState> bgraPipelineState;
@property (nonatomic, strong) id<MTLBuffer>              vertexBuffer;
@property (nonatomic, strong) id<MTLBuffer>              vertexBuffer90CW;
@property (nonatomic, strong) id<MTLBuffer>              vertexBuffer90CCW;
@property (nonatomic, assign) CVMetalTextureCacheRef     textureCache;
@property (nonatomic, assign) CVPixelBufferRef           pendingBuffer;
@property (nonatomic, strong) dispatch_semaphore_t       bufferSemaphore;
@property (nonatomic, weak, readwrite) MTKView           *metalView;
@property (nonatomic, assign) VMRYuvColorParams          yuvParams;   ///< 缓存的 YUV 转换参数
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
    _yuvColorSpace = VMRYuvColorSpaceBT709Limited; // H.264/H.265 SDR 默认
    _yuvParams = VMRMakeYuvColorParams(_yuvColorSpace);

    CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, _device, nil, &_textureCache);

    [self _buildYUVPipeline:view];
    [self _buildBGRAPipeline:view];
    [self _buildVertexBuffer];

    return self;
}

- (void)setYuvColorSpace:(VMRYuvColorSpace)cs {
    if (_yuvColorSpace == cs) return;
    _yuvColorSpace = cs;
    _yuvParams = VMRMakeYuvColorParams(cs);
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
    _vertexBuffer90CW = [_device newBufferWithBytes:kVMRQuadVertices90CW
                                              length:sizeof(kVMRQuadVertices90CW)
                                             options:MTLResourceStorageModeShared];
    _vertexBuffer90CCW = [_device newBufferWithBytes:kVMRQuadVertices90CCW
                                               length:sizeof(kVMRQuadVertices90CCW)
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

    id<MTLBuffer> vbuf = _vertexBuffer;
    if (_rotation == VMRRotationCW)  vbuf = _vertexBuffer90CW;
    if (_rotation == VMRRotationCCW) vbuf = _vertexBuffer90CCW;

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
        [enc setVertexBuffer:vbuf offset:0 atIndex:0];
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
        [enc setVertexBuffer:vbuf offset:0 atIndex:0];
        [enc setFragmentTexture:yTex  atIndex:0];
        [enc setFragmentTexture:uvTex atIndex:1];
        [enc setFragmentBytes:&_yuvParams length:sizeof(_yuvParams) atIndex:0];  // YUV 矩阵参数
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
