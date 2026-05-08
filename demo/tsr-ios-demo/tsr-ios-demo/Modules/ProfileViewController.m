#import "ProfileViewController.h"
#import <AVFoundation/AVFoundation.h>
#import <MetalKit/MetalKit.h>
#import <tsr_client/TSRPass.h>
#import <tsr_client/TIEPass.h>
#import <QuartzCore/QuartzCore.h>

@interface ProfileViewController () <MTKViewDelegate>

// ==================== 视频播放相关 ====================
@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput;
@property (nonatomic, strong) CADisplayLink *displayLink;
@property (nonatomic, assign) float videoFPS;

// ==================== 超分/增强处理器 ====================
@property (nonatomic, strong) TSRPass *tsrStandard;
@property (nonatomic, strong) TSRPass *tsrStandardExt;
@property (nonatomic, strong) TSRPass *tsrPro;
@property (nonatomic, strong) TSRPass *tsrProExt;
@property (nonatomic, strong) TIEPass *tieStandard;
@property (nonatomic, strong) TIEPass *tiePro;

// ==================== Metal 渲染相关 ====================
@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> inTexture;
@property (nonatomic, strong) id<MTLTexture> srTexture;
@property (nonatomic, strong) id<MTLTexture> ieTexture;
@property (nonatomic, strong) MTKView *mtkView;
@property (nonatomic, assign) CVMetalTextureCacheRef textureCache;

// ==================== 配置参数 ====================
@property (nonatomic, copy) NSString *algorithm;
@property (nonatomic, assign) CGSize videoSize;
@property (nonatomic, assign) float srRatio;
@property (nonatomic, assign) int outputWidth;
@property (nonatomic, assign) int outputHeight;
@property (nonatomic, copy) NSString *videoName;

// ==================== 性能统计 ====================
@property (nonatomic, assign) int frameCount;
@property (nonatomic, assign) float totalCostMs;
@property (nonatomic, assign) BOOL srCreateDone;
@property (nonatomic, assign) CFTimeInterval fpsLastUpdateTime;
@property (nonatomic, assign) int fpsCounter;
@property (nonatomic, assign) BOOL resourcesReleased;
@property (nonatomic, assign) BOOL algorithmEnabled;

// ==================== UI 组件 ====================
@property (nonatomic, strong) UITextView *statusTextView;

@end

@implementation ProfileViewController

#pragma mark - 生命周期

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [UIApplication sharedApplication].idleTimerDisabled = YES;
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [UIApplication sharedApplication].idleTimerDisabled = NO;
}

- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    if (self.isMovingFromParentViewController || self.isBeingDismissed) {
        [self cleanupResources];
    }
}

- (void)viewWillTransitionToSize:(CGSize)size withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
    [coordinator animateAlongsideTransition:^(id<UIViewControllerTransitionCoordinatorContext> context) {
        CGSize screenPx = CGSizeMake(size.width * 3, size.height * 3);
        CGRect rect = [self calcMTKViewRectForScreenPx:screenPx];
        self->_mtkView.frame = rect;
        self->_statusTextView.frame = CGRectMake(0, size.height - 80, size.width, 80);
    } completion:nil];
}

#pragma mark - 初始化

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString *)algorithm {
    if (self = [super init]) {
        _srRatio    = srRatio;
        _algorithm  = algorithm;
        _videoName  = videoURL.lastPathComponent ?: @"";
        _videoSize  = [self loadVideo:videoURL];

        // 计算输出尺寸
        if (srRatio > 0) {
            _outputWidth  = _videoSize.width  * srRatio;
            _outputHeight = _videoSize.height * srRatio;
        } else {
            CGSize screenPx = CGSizeMake(self.view.bounds.size.width * 3,
                                         self.view.bounds.size.height * 3);
            [self calcOutputSizeForScreenPx:screenPx];
        }

        CGRect rect = CGRectMake(0, 0, _outputWidth / 3.0, _outputHeight / 3.0);
        [self setupMetalWithRect:rect];

        // 【步骤4】创建显示链接，设置刷新率
        _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateDisplay)];
        if (@available(iOS 10.0, *)) {
            // preferredFramesPerSecond 只支持屏幕刷新率的整除因子。在 60Hz 屏幕上，可用的帧率值为 60、30、20、15 等。当设置为 25 时，系统会自动降级到 20fps，导致丢帧。所以这里不能设置为 _videoFPS。
            _displayLink.preferredFramesPerSecond = 30;
        }
        [_displayLink addToRunLoop:NSRunLoop.currentRunLoop forMode:NSDefaultRunLoopMode];

        [self setupUI];

        // 性能测试：30 分钟后自动退出
        [NSTimer scheduledTimerWithTimeInterval:60 * 30
                                         target:self
                                       selector:@selector(terminateApp)
                                       userInfo:nil
                                        repeats:NO];

        _fpsLastUpdateTime = CACurrentMediaTime();
        _algorithmEnabled  = NO;

        [self createPasses];
        [_player play];
    }
    return self;
}

#pragma mark - 私有：尺寸计算

/// 根据屏幕像素尺寸计算 MTKView 的 frame（保持视频宽高比）
- (CGRect)calcMTKViewRectForScreenPx:(CGSize)screenPx {
    if (_srRatio > 0) {
        int outW = _videoSize.width  * _srRatio;
        int outH = _videoSize.height * _srRatio;
        CGFloat ratio = (CGFloat)outW / outH;
        CGFloat displayW, displayH;
        if (screenPx.width / screenPx.height > ratio) {
            displayH = screenPx.height; displayW = displayH * ratio;
        } else {
            displayW = screenPx.width;  displayH = displayW / ratio;
        }
        _outputWidth  = outW;
        _outputHeight = outH;
        return CGRectMake(0, 0, displayW / 3.0, displayH / 3.0);
    } else {
        [self calcOutputSizeForScreenPx:screenPx];
        return CGRectMake(0, 0, _outputWidth / 3.0, _outputHeight / 3.0);
    }
}

/// Auto 模式：根据屏幕像素尺寸自动计算超分比例和输出尺寸
- (void)calcOutputSizeForScreenPx:(CGSize)screenPx {
    CGFloat videoRatio  = _videoSize.width / _videoSize.height;
    CGFloat screenRatio = screenPx.width   / screenPx.height;
    if (screenRatio > videoRatio) {
        _srRatio      = videoRatio;
        _outputWidth  = _srRatio * screenPx.height;
        _outputHeight = screenPx.height;
    } else {
        _srRatio      = 1.0 / videoRatio;
        _outputWidth  = screenPx.width;
        _outputHeight = screenPx.width * _srRatio;
    }
}

#pragma mark - Metal 设置

- (void)setupMetalWithRect:(CGRect)rect {
    _device       = MTLCreateSystemDefaultDevice();
    _commandQueue = [_device newCommandQueue];

    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:_videoSize.width
                                    height:_videoSize.height
                                 mipmapped:NO];
    _inTexture = [_device newTextureWithDescriptor:desc];

    _mtkView = [[MTKView alloc] initWithFrame:rect device:_device];
    _mtkView.delegate             = self;
    _mtkView.framebufferOnly      = NO;
    _mtkView.enableSetNeedsDisplay = YES;

    id<MTLLibrary> lib = [_device newDefaultLibrary];
    MTLRenderPipelineDescriptor *pipeDesc = [MTLRenderPipelineDescriptor new];
    pipeDesc.vertexFunction                    = [lib newFunctionWithName:@"vertexShader"];
    pipeDesc.fragmentFunction                  = [lib newFunctionWithName:@"fragmentShader"];
    pipeDesc.colorAttachments[0].pixelFormat   = _mtkView.colorPixelFormat;
    _pipelineState = [_device newRenderPipelineStateWithDescriptor:pipeDesc error:nil];

    [self.view addSubview:_mtkView];

    if (CVMetalTextureCacheCreate(kCFAllocatorDefault, nil, _device, nil, &_textureCache) != kCVReturnSuccess) {
        NSLog(@"Failed to create CVMetalTextureCache");
    }
}

#pragma mark - UI 设置

- (void)setupUI {
    UIView *bg = [[UIView alloc] initWithFrame:self.view.bounds];
    bg.backgroundColor = UIColor.whiteColor;
    [self.view insertSubview:bg atIndex:0];

    CGRect screen = UIScreen.mainScreen.bounds;
    _statusTextView = [[UITextView alloc] initWithFrame:CGRectMake(0, screen.size.height - 80, screen.size.width, 80)];
    _statusTextView.backgroundColor      = [UIColor colorWithWhite:0 alpha:0.7];
    _statusTextView.textColor            = UIColor.greenColor;
    _statusTextView.font                 = [UIFont systemFontOfSize:12];
    _statusTextView.editable             = NO;
    [self.view addSubview:_statusTextView];

    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(toggleAlgorithm)];
    [_statusTextView addGestureRecognizer:tap];
}

#pragma mark - 视频加载

- (CGSize)loadVideo:(NSURL *)url {
    AVAsset *asset      = [AVAsset assetWithURL:url];
    AVAssetTrack *track = [asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
    if (track) _videoFPS = track.nominalFrameRate;

    AVPlayerItem *item = [AVPlayerItem playerItemWithAsset:asset];
    _videoOutput = [[AVPlayerItemVideoOutput alloc] initWithOutputSettings:@{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA)
    }];
    [item addOutput:_videoOutput];
    _player = [AVPlayer playerWithPlayerItem:item];

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(videoDidEnd:)
                                                 name:AVPlayerItemDidPlayToEndTimeNotification
                                               object:item];
    return track ? track.naturalSize : CGSizeZero;
}

#pragma mark - 超分/增强 Pass 创建

- (void)createPasses {
    // 清理已有引用
    _tsrStandard = _tsrStandardExt = _tsrPro = _tsrProExt = nil;
    _tieStandard = _tiePro = nil;
    _srTexture   = _ieTexture = nil;

    if ([_algorithm isEqualToString:@"普通播放"]) {
        _srCreateDone = YES;
        return;
    }

    if ([_algorithm hasPrefix:@"超分播放"]) {
        _srTexture = [self newTextureWithWidth:_videoSize.width * _srRatio
                                       height:_videoSize.height * _srRatio];

        NSDictionary *tsrMap = @{
            @"超分播放(标准版)"    : @(TSRAlgorithmTypeStandard),
            @"超分播放(标准版-增强)": @(TSRAlgorithmTypeStandardColorRetouchingExt),
            @"超分播放(专业版)"    : @(TSRAlgorithmTypeProfessional),
            @"超分播放(专业版-增强)": @(TSRAlgorithmTypeProfessionalHighQuality),
        };
        NSNumber *typeNum = tsrMap[_algorithm];
        if (typeNum) {
            TSRInitStatusCode status;
            TSRPass *pass = [[TSRPass alloc] initWithTSRAlgorithmType:typeNum.integerValue
                                                               device:_device
                                                           inputWidth:_videoSize.width
                                                          inputHeight:_videoSize.height
                                                              srRatio:_srRatio
                                                       initStatusCode:&status];
            if ([_algorithm isEqualToString:@"超分播放(标准版)"])         _tsrStandard    = pass;
            else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) _tsrStandardExt = pass;
            else if ([_algorithm isEqualToString:@"超分播放(专业版)"])     _tsrPro         = pass;
            else                                                           _tsrProExt      = pass;
        }
    } else if ([_algorithm hasPrefix:@"增强播放"]) {
        _ieTexture = [self newTextureWithWidth:_videoSize.width height:_videoSize.height];

        TIEInitStatusCode status;
        TIEAlgorithmType type = [_algorithm isEqualToString:@"增强播放(标准版)"]
            ? TIEAlgorithmTypeStandard : TIEAlgorithmTypeProfessional;
        TIEPass *pass = [[TIEPass alloc] initWithTIEAlgorithmType:type
                                                           device:_device
                                                       inputWidth:_videoSize.width
                                                      inputHeight:_videoSize.height
                                                   initStatusCode:&status];
        if ([_algorithm isEqualToString:@"增强播放(标准版)"]) _tieStandard = pass;
        else                                                   _tiePro      = pass;
    }

    _srCreateDone = YES;
}

/// 创建可读写的 BGRA8 纹理
- (id<MTLTexture>)newTextureWithWidth:(NSUInteger)width height:(NSUInteger)height {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                     width:width height:height mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    return [_device newTextureWithDescriptor:desc];
}

#pragma mark - 显示更新

- (void)updateDisplay {
    [_mtkView setNeedsDisplay];
}

#pragma mark - MTKViewDelegate

- (void)mtkView:(MTKView *)view drawableSizeWillChange:(CGSize)size {}

- (void)updateStatus {
    CFTimeInterval now     = CACurrentMediaTime();
    CFTimeInterval elapsed = now - _fpsLastUpdateTime;
    if (elapsed < 3.0) return;

    NSInteger fpsInt = lroundf(_fpsCounter / elapsed);
    NSInteger avgInt = lroundf(_frameCount > 0 ? _totalCostMs / _frameCount : 0);
    NSString *status = [NSString stringWithFormat:@"%@ %@ | %@\n处理帧率: %ld | 耗时: %ld ms",
                        _algorithmEnabled ? @"[开启]" : @"[关闭]",
                        _algorithm, _videoName ?: @"-",
                        (long)fpsInt, (long)avgInt];
    dispatch_async(dispatch_get_main_queue(), ^{ self.statusTextView.text = status; });

    _fpsLastUpdateTime = now;
    _fpsCounter = _totalCostMs = _frameCount = 0;
}

- (void)drawInMTKView:(MTKView *)view {
    // 解码出新帧才继续
    CMTime currentTime = _player.currentItem.currentTime;
    if (![_videoOutput hasNewPixelBufferForItemTime:currentTime]) return;
    CVPixelBufferRef pixelBuffer = [_videoOutput copyPixelBufferForItemTime:currentTime itemTimeForDisplay:nil];
    if (!pixelBuffer) return;

    // 零拷贝上传纹理
    CVMetalTextureRef cvTex = nil;
    if (CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, _textureCache, pixelBuffer, nil,
            MTLPixelFormatBGRA8Unorm,
            CVPixelBufferGetWidth(pixelBuffer),
            CVPixelBufferGetHeight(pixelBuffer), 0, &cvTex) == kCVReturnSuccess) {
        _inTexture = CVMetalTextureGetTexture(cvTex);
        CFRelease(cvTex);
    } else {
        // 降级：CPU 拷贝
        CVPixelBufferLockBaseAddress(pixelBuffer, 0);
        [_inTexture replaceRegion:MTLRegionMake2D(0, 0,
                                                  CVPixelBufferGetWidth(pixelBuffer),
                                                  CVPixelBufferGetHeight(pixelBuffer))
                      mipmapLevel:0
                        withBytes:CVPixelBufferGetBaseAddress(pixelBuffer)
                      bytesPerRow:CVPixelBufferGetBytesPerRow(pixelBuffer)];
        CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    }
    CVPixelBufferRelease(pixelBuffer);

    // 获取要渲染的 Metal Drawable
    id<CAMetalDrawable> drawable = view.currentDrawable;
    if (!drawable || !_srCreateDone) return;

    // 算法处理
    id<MTLCommandBuffer> cmdBuffer = [_commandQueue commandBuffer];
    CFTimeInterval start = CACurrentMediaTime();
    id<MTLTexture> outputTexture = [self processTexture:_inTexture commandBuffer:cmdBuffer];
    _totalCostMs += (CACurrentMediaTime() - start) * 1000;
    _frameCount++;
    _fpsCounter++;
    [self updateStatus];

    // 渲染到屏幕
    MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
    passDesc.colorAttachments[0].texture     = drawable.texture;
    passDesc.colorAttachments[0].loadAction  = MTLLoadActionClear;
    passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
    passDesc.colorAttachments[0].clearColor  = MTLClearColorMake(0, 0, 0, 1);
    id<MTLRenderCommandEncoder> encoder = [cmdBuffer renderCommandEncoderWithDescriptor:passDesc];
    [encoder setRenderPipelineState:_pipelineState];
    [encoder setFragmentTexture:outputTexture atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding];
    [cmdBuffer presentDrawable:drawable];
    [cmdBuffer commit];
}

/// 根据当前算法对输入纹理进行处理，返回输出纹理
- (id<MTLTexture>)processTexture:(id<MTLTexture>)input commandBuffer:(id<MTLCommandBuffer>)cmdBuffer {
    if (!_algorithmEnabled) return input;

    if (_tsrStandard)    return _srTexture = [_tsrStandard    render:input commandBuffer:cmdBuffer];
    if (_tsrStandardExt) return _srTexture = [_tsrStandardExt render:input commandBuffer:cmdBuffer];
    if (_tsrPro)         return _srTexture = [_tsrPro         render:input commandBuffer:cmdBuffer];
    if (_tsrProExt)      return _srTexture = [_tsrProExt      render:input commandBuffer:cmdBuffer];
    if (_tieStandard)    return _ieTexture = [_tieStandard    render:input commandBuffer:cmdBuffer];
    if (_tiePro)         return _ieTexture = [_tiePro         render:input commandBuffer:cmdBuffer];
    return input;
}

#pragma mark - 资源清理

- (void)cleanupResources {
    if (self.resourcesReleased) return;
    self.resourcesReleased = YES;

    [self.player pause];
    [self.tsrStandard    deInit];
    [self.tsrStandardExt deInit];
    [self.tsrPro         deInit];
    [self.tsrProExt      deInit];
    [self.tieStandard    deInit];
    [self.tiePro         deInit];
    [self.displayLink    invalidate];
    [[NSNotificationCenter defaultCenter] removeObserver:self];

    if (_textureCache) {
        CVMetalTextureCacheFlush(_textureCache, 0);
        CFRelease(_textureCache);
        _textureCache = nil;
    }
}

#pragma mark - 视频播放控制

- (void)videoDidEnd:(NSNotification *)notification {
    [notification.object seekToTime:kCMTimeZero];
    [_player play];
}

#pragma mark - 算法开关

- (void)toggleAlgorithm {
    _algorithmEnabled  = !_algorithmEnabled;
    _frameCount        = 0;
    _totalCostMs       = 0;
    _fpsCounter        = 0;
    _fpsLastUpdateTime = CACurrentMediaTime();
}

#pragma mark - 其他

- (void)terminateApp {
    exit(0);
}

@end
