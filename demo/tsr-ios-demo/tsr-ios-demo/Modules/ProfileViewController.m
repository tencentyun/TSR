#import "ProfileViewController.h"
#import <AVFoundation/AVFoundation.h>  // 音视频处理框架
#import <MetalKit/MetalKit.h>           // Metal 渲染框架
#import <tsr_client/TSRPass.h>          // 腾讯超分辨率 SDK
#import <tsr_client/TIEPass.h>          // 腾讯图像增强 SDK
#import <QuartzCore/QuartzCore.h>       // CACurrentMediaTime

@interface ProfileViewController () <MTKViewDelegate>

// ==================== 视频播放相关 ====================
@property (nonatomic, strong) AVPlayer *player;                    // 视频播放器
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput; // 视频帧输出（用于获取原始像素数据）
@property (nonatomic, strong) CADisplayLink *displayLink;          // 与屏幕刷新同步的定时器
@property (nonatomic, assign) float videoFPS;                      // 视频的原始帧率

// ==================== 超分/增强处理器 ====================
@property (nonatomic, strong) TSRPass *tsrStandard;      // 超分-标准版
@property (nonatomic, strong) TSRPass *tsrStandardExt;   // 超分-标准增强版
@property (nonatomic, strong) TSRPass *tsrPro;           // 超分-专业版
@property (nonatomic, strong) TSRPass *tsrProExt;        // 超分-专业增强版
@property (nonatomic, strong) TIEPass *tieStandard;      // 图像增强-标准版
@property (nonatomic, strong) TIEPass *tiePro;           // 图像增强-专业版

// ==================== Metal 渲染相关 ====================
@property (nonatomic, strong) id<MTLDevice> device;                // GPU 设备
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;    // GPU 命令队列
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState; // 渲染管线状态
@property (nonatomic, strong) id<MTLTexture> inTexture;   // 输入纹理（原始视频帧）
@property (nonatomic, strong) id<MTLTexture> srTexture;   // 超分输出纹理
@property (nonatomic, strong) id<MTLTexture> ieTexture;   // 增强输出纹理
@property (nonatomic, strong) MTKView *mtkView;           // Metal 渲染视图

// ==================== 配置参数 ====================
@property (nonatomic, copy) NSString *algorithm;          // 当前选择的算法名称
@property (nonatomic, assign) CGSize videoSize;           // 原始视频尺寸
@property (nonatomic, assign) float srRatio;              // 超分倍率
@property (nonatomic, assign) int outputWidth;            // 输出宽度
@property (nonatomic, assign) int outputHeight;           // 输出高度
@property (nonatomic, copy) NSString *videoName;          // 播放文件名（含扩展名）

// ==================== 性能统计 ====================
@property (nonatomic, assign) int frameCount;             // 已处理帧数
@property (nonatomic, assign) float avgCost;              // 累计处理耗时（毫秒）
@property (nonatomic, assign) BOOL srCreateDone;          // 超分Pass是否创建完成
@property (nonatomic, assign) CFTimeInterval fpsLastUpdateTime; // 上次更新 FPS 的时间戳
@property (nonatomic, assign) int fpsCounter;             // 从上次统计以来的帧数
@property (nonatomic, assign) BOOL resourcesReleased;     // 资源是否已释放
@property (nonatomic, assign) BOOL algorithmEnabled;      // 算法是否启用（用于对比效果）

// ==================== UI 组件 ====================
@property (nonatomic, strong) UITextView *statusTextView; // 状态信息文本框

@end

@implementation ProfileViewController

#pragma mark - 生命周期

/// 视图即将显示时调用
- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    // 禁用自动锁屏，防止长时间播放时屏幕变暗
    [UIApplication sharedApplication].idleTimerDisabled = YES;
}

/// 视图即将消失时调用
- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    // 恢复自动锁屏
    [UIApplication sharedApplication].idleTimerDisabled = NO;
}

/// 视图消失后调用（导航返回时释放资源）
- (void)viewDidDisappear:(BOOL)animated {
    [super viewDidDisappear:animated];
    if (self.isMovingFromParentViewController || self.isBeingDismissed) {
        [self cleanupResources];
    }
}

/// 处理横竖屏切换
- (void)viewWillTransitionToSize:(CGSize)size withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];
    
    [coordinator animateAlongsideTransition:^(id<UIViewControllerTransitionCoordinatorContext> context) {
        // 使用新的屏幕尺寸进行计算
        CGFloat screenWidth = size.width * 3;
        CGFloat screenHeight = size.height * 3;
        CGFloat videoWidth = self->_videoSize.width;
        CGFloat videoHeight = self->_videoSize.height;
        
        CGRect rect;
        if (self->_srRatio > 0) {
            // 用户指定了固定的超分比例，直接使用
            int outputWidth = videoWidth * self->_srRatio;
            int outputHeight = videoHeight * self->_srRatio;
            
            // 计算适配屏幕的显示尺寸（保持宽高比）
            CGFloat outputRatio = (CGFloat)outputWidth / outputHeight;
            CGFloat screenRatio = screenWidth / screenHeight;
            
            CGFloat displayWidth, displayHeight;
            if (screenRatio > outputRatio) {
                // 屏幕更宽，以高度为准
                displayHeight = screenHeight;
                displayWidth = displayHeight * outputRatio;
            } else {
                // 屏幕更高，以宽度为准
                displayWidth = screenWidth;
                displayHeight = displayWidth / outputRatio;
            }
            
            rect = CGRectMake(0, 0, displayWidth / 3, displayHeight / 3);
            
            // 更新输出尺寸
            self->_outputWidth = outputWidth;
            self->_outputHeight = outputHeight;
        } else {
            // Auto 模式：根据屏幕尺寸自动计算超分比例
            CGFloat videoRatio = videoWidth / videoHeight;
            CGFloat screenRatio = screenWidth / screenHeight;
            
            CGFloat viewWidth, viewHeight;
            if (screenRatio > videoRatio) {
                // 屏幕更宽，以高度为准
                viewHeight = screenHeight;
                viewWidth = viewHeight * videoRatio;
            } else {
                // 屏幕更高，以宽度为准
                viewWidth = screenWidth;
                viewHeight = viewWidth / videoRatio;
            }
            
            self->_outputWidth = viewWidth;
            self->_outputHeight = viewHeight;
            rect = CGRectMake(0, 0, viewWidth / 3, viewHeight / 3);
        }
        
        // 更新 MTKView 的 frame
        self->_mtkView.frame = rect;
        
        // 更新状态文本框的位置
        self->_statusTextView.frame = CGRectMake(0, size.height - 80, size.width, 80);
        
    } completion:nil];
}

#pragma mark - 初始化

/// 使用视频URL、超分倍率和算法名称初始化
/// @param videoURL 视频文件的本地或网络URL
/// @param srRatio 超分倍率（如2.0表示放大2倍），传0表示自适应屏幕
/// @param algorithm 算法名称字符串
- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString *)algorithm {
    if (self = [super init]) {
        _srRatio = srRatio;
        _algorithm = algorithm;
        _videoName = videoURL.lastPathComponent ?: @"";
        
        // 【步骤1】加载视频并获取原始尺寸、帧率
        _videoSize = [self loadVideo:videoURL];
        
        // 【步骤2】计算输出尺寸
        if (srRatio > 0) {
            _outputWidth = _videoSize.width * srRatio;
            _outputHeight = _videoSize.height * srRatio;
        } else {
            CGFloat screenW = self.view.bounds.size.width * 3;
            CGFloat screenH = self.view.bounds.size.height * 3;
            CGFloat videoRatio = _videoSize.width / _videoSize.height;
            if (screenW / screenH > videoRatio) {
                _srRatio = videoRatio;
                _outputWidth = _srRatio * screenH;
                _outputHeight = screenH;
            } else {
                _srRatio = 1.0 / videoRatio;
                _outputWidth = screenW;
                _outputHeight = screenW * _srRatio;
            }
        }
        
        // 【步骤3】设置 Metal 渲染环境
        CGRect rect = CGRectMake(0, 0, _outputWidth / 3, _outputHeight / 3);
        [self setupMetalWithRect:rect];
        
        // 【步骤4】创建显示链接，与屏幕刷新同步（按视频帧率刷新）
        _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateDisplay)];
        //NSInteger targetFPS = MAX(1, (NSInteger)lroundf(_videoFPS));
        if (@available(iOS 10.0, *)) {
            // preferredFramesPerSecond 只支持屏幕刷新率的整除因子。在 60Hz 屏幕上，可用的帧率值为 60、30、20、15 等。当设置为 25 时，系统会自动降级到 20fps，导致丢帧。所以这里不能设置为 _videoFPS。
            _displayLink.preferredFramesPerSecond = 30;
        }
        [_displayLink addToRunLoop:NSRunLoop.currentRunLoop forMode:NSRunLoopCommonModes];
        
        // 【步骤5】设置 UI 界面
        [self setupUI];
        
        // 【步骤6】性能测试，x分钟后自动退出应用
        [NSTimer scheduledTimerWithTimeInterval:60*30
                                         target:self
                                       selector:@selector(terminateApp) 
                                       userInfo:nil 
                                        repeats:NO];
        
        // 初始化 FPS 统计
        _fpsLastUpdateTime = CACurrentMediaTime();
        _fpsCounter = 0;
        _frameCount = 0;
        _avgCost = 0;
        _resourcesReleased = NO;
        _algorithmEnabled = NO;  // 默认 不启用算法
        
        // 直接按算法创建 Pass，并开始播放（假定外部已完成鉴权）
        [self createPasses];
        [_player play];
    }
    return self;
}

#pragma mark - Metal 设置

/// 设置 Metal 渲染环境
/// @param rect MTKView 的显示区域
- (void)setupMetalWithRect:(CGRect)rect {
    _device = MTLCreateSystemDefaultDevice();
    _commandQueue = [_device newCommandQueue];
    
    MTLTextureDescriptor *desc = [MTLTextureDescriptor 
        texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm 
                                     width:_videoSize.width 
                                    height:_videoSize.height 
                                 mipmapped:NO];
    _inTexture = [_device newTextureWithDescriptor:desc];
    
    _mtkView = [[MTKView alloc] initWithFrame:rect device:_device];
    _mtkView.delegate = self;
    _mtkView.framebufferOnly = NO;
    _mtkView.enableSetNeedsDisplay = YES;
    
    id<MTLLibrary> lib = [_device newDefaultLibrary];
    MTLRenderPipelineDescriptor *pipeDesc = [MTLRenderPipelineDescriptor new];
    pipeDesc.vertexFunction = [lib newFunctionWithName:@"vertexShader"];
    pipeDesc.fragmentFunction = [lib newFunctionWithName:@"fragmentShader"];
    pipeDesc.colorAttachments[0].pixelFormat = _mtkView.colorPixelFormat;
    
    _pipelineState = [_device newRenderPipelineStateWithDescriptor:pipeDesc error:nil];
    [self.view addSubview:_mtkView];
}

#pragma mark - UI 设置

- (void)setupUI {
    UIView *whiteView = [[UIView alloc] initWithFrame:self.view.bounds];
    whiteView.backgroundColor = UIColor.whiteColor;
    [self.view insertSubview:whiteView atIndex:0];
    
    CGFloat screenH = UIScreen.mainScreen.bounds.size.height;
    _statusTextView = [[UITextView alloc] initWithFrame:CGRectMake(0, screenH - 80, 
                                                                   UIScreen.mainScreen.bounds.size.width, 80)];
    _statusTextView.backgroundColor = [UIColor colorWithWhite:0 alpha:0.7];
    _statusTextView.textColor = UIColor.greenColor;
    _statusTextView.font = [UIFont systemFontOfSize:12];
    _statusTextView.editable = NO;
    _statusTextView.userInteractionEnabled = YES;  // 启用交互
    [self.view addSubview:_statusTextView];
    
    // 添加点击手势，用于切换算法开关
    UITapGestureRecognizer *tapGesture = [[UITapGestureRecognizer alloc] 
                                          initWithTarget:self 
                                          action:@selector(toggleAlgorithm)];
    [_statusTextView addGestureRecognizer:tapGesture];
}

#pragma mark - 视频加载

- (CGSize)loadVideo:(NSURL *)url {
    AVAsset *asset = [AVAsset assetWithURL:url];
    AVAssetTrack *track = [asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
    CGSize size = track ? track.naturalSize : CGSizeZero;
    if (track) {
        _videoFPS = track.nominalFrameRate;
    }
    
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
    return size;
}

#pragma mark - 超分/增强 Pass 创建

/// 按算法名称创建对应 Pass；只创建必要的 Pass 和目标纹理
- (void)createPasses {
    TSRInitStatusCode tsrStatus = TSRInitStatusCodeSuccess;
    TIEInitStatusCode tieStatus = TIEInitStatusCodeSuccess;
    
    // 清理已有引用
    _tsrStandard = nil;
    _tsrStandardExt = nil;
    _tsrPro = nil;
    _tsrProExt = nil;
    _tieStandard = nil;
    _tiePro = nil;
    _srTexture = nil;
    _ieTexture = nil;
    
    // 普通播放，无需创建处理 Pass
    if ([_algorithm isEqualToString:@"普通播放"]) {
        _srCreateDone = YES;
        return;
    }
    
    // 需要超分的算法：创建超分纹理 + 指定 Pass
    if ([_algorithm hasPrefix:@"超分播放"]) {
        MTLTextureDescriptor *srDesc = [MTLTextureDescriptor 
            texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm 
                                         width:_videoSize.width * _srRatio 
                                        height:_videoSize.height * _srRatio 
                                     mipmapped:NO];
        srDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
        _srTexture = [_device newTextureWithDescriptor:srDesc];
        
        if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
            _tsrStandard = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandard
                                                             device:_device
                                                         inputWidth:_videoSize.width
                                                        inputHeight:_videoSize.height
                                                            srRatio:_srRatio
                                                     initStatusCode:&tsrStatus];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
            _tsrStandardExt = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt
                                                                 device:_device
                                                             inputWidth:_videoSize.width
                                                            inputHeight:_videoSize.height
                                                                srRatio:_srRatio
                                                         initStatusCode:&tsrStatus];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
            _tsrPro = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessional
                                                         device:_device
                                                     inputWidth:_videoSize.width
                                                    inputHeight:_videoSize.height
                                                        srRatio:_srRatio
                                                 initStatusCode:&tsrStatus];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
            _tsrProExt = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessionalHighQuality
                                                            device:_device
                                                        inputWidth:_videoSize.width
                                                       inputHeight:_videoSize.height
                                                           srRatio:_srRatio
                                                    initStatusCode:&tsrStatus];
        }
    }
    // 需要增强的算法：创建增强纹理 + 指定 Pass
    else if ([_algorithm hasPrefix:@"增强播放"]) {
        MTLTextureDescriptor *ieDesc = [MTLTextureDescriptor 
            texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm 
                                         width:_videoSize.width 
                                        height:_videoSize.height 
                                     mipmapped:NO];
        ieDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
        _ieTexture = [_device newTextureWithDescriptor:ieDesc];
        
        if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
            _tieStandard = [[TIEPass alloc] initWithTIEAlgorithmType:TIEAlgorithmTypeStandard
                                                             device:_device
                                                         inputWidth:_videoSize.width
                                                        inputHeight:_videoSize.height
                                                     initStatusCode:&tieStatus];
        } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
            _tiePro = [[TIEPass alloc] initWithTIEAlgorithmType:TIEAlgorithmTypeProfessional
                                                         device:_device
                                                     inputWidth:_videoSize.width
                                                    inputHeight:_videoSize.height
                                                 initStatusCode:&tieStatus];
        }
    }
    
    _srCreateDone = YES;
}

#pragma mark - 显示更新

- (void)updateDisplay {
    [_mtkView setNeedsDisplay];
}

#pragma mark - MTKViewDelegate

- (void)mtkView:(MTKView *)view drawableSizeWillChange:(CGSize)size {}

- (void)drawInMTKView:(MTKView *)view {
    id<CAMetalDrawable> drawable = view.currentDrawable;
    if (!drawable || !_srCreateDone) return;
    
    CMTime currentTime = _player.currentItem.currentTime;
    if (![_videoOutput hasNewPixelBufferForItemTime:currentTime]) {
        return; // 没有新帧，不渲染
    }
    
    CVPixelBufferRef pixelBuffer = [_videoOutput 
        copyPixelBufferForItemTime:currentTime 
              itemTimeForDisplay:nil];
    if (!pixelBuffer) return;
    
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    [_inTexture replaceRegion:MTLRegionMake2D(0, 0, 
                                              CVPixelBufferGetWidth(pixelBuffer),
                                              CVPixelBufferGetHeight(pixelBuffer))
                  mipmapLevel:0
                    withBytes:CVPixelBufferGetBaseAddress(pixelBuffer)
                  bytesPerRow:CVPixelBufferGetBytesPerRow(pixelBuffer)];
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    CVPixelBufferRelease(pixelBuffer);
    
    id<MTLCommandBuffer> cmdBuffer = [_commandQueue commandBuffer];
    
    NSDate *start = [NSDate date];
    id<MTLTexture> outputTexture = _inTexture;
    
    // 根据算法开关状态决定是否应用处理
    if (_algorithmEnabled) {
        if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
            outputTexture = _ieTexture = [_tieStandard render:_inTexture commandBuffer:cmdBuffer];
        } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
            outputTexture = _ieTexture = [_tiePro render:_inTexture commandBuffer:cmdBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
            outputTexture = _srTexture = [_tsrStandard render:_inTexture commandBuffer:cmdBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
            outputTexture = _srTexture = [_tsrStandardExt render:_inTexture commandBuffer:cmdBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
            outputTexture = _srTexture = [_tsrPro render:_inTexture commandBuffer:cmdBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
            outputTexture = _srTexture = [_tsrProExt render:_inTexture commandBuffer:cmdBuffer];
        }
    }
    
    _avgCost += -[start timeIntervalSinceNow] * 1000;
    _frameCount++;
    _fpsCounter++;
    
    CFTimeInterval now = CACurrentMediaTime();
    if (_fpsLastUpdateTime == 0) {
        _fpsLastUpdateTime = now;
    }
    CFTimeInterval elapsed = now - _fpsLastUpdateTime;
    if (elapsed >= 3.0) { // 每 3 秒刷新一次
        float fps = _fpsCounter / elapsed;
        float avg = _frameCount > 0 ? _avgCost / _frameCount : 0;
        NSInteger fpsInt = lroundf(fps);
        NSInteger avgInt = lroundf(avg);
        NSString *statusPrefix = _algorithmEnabled ? @"[开启]" : @"[关闭]";
        NSString *status = [NSString stringWithFormat:@"%@ %@ | %@ \n帧率: %ld | 帧耗时: %ld ms",
                            statusPrefix, _algorithm, _videoName ?: @"-", (long)fpsInt, (long)avgInt];
        dispatch_async(dispatch_get_main_queue(), ^{
            self.statusTextView.text = status;
        });
        _fpsLastUpdateTime = now;
        _fpsCounter = 0;
    }
    
    MTLRenderPassDescriptor *passDesc = [MTLRenderPassDescriptor renderPassDescriptor];
    passDesc.colorAttachments[0].texture = drawable.texture;
    passDesc.colorAttachments[0].loadAction = MTLLoadActionClear;
    passDesc.colorAttachments[0].storeAction = MTLStoreActionStore;
    passDesc.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
    
    id<MTLRenderCommandEncoder> encoder = [cmdBuffer renderCommandEncoderWithDescriptor:passDesc];
    [encoder setRenderPipelineState:_pipelineState];
    [encoder setFragmentTexture:outputTexture atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding];
    
    [cmdBuffer presentDrawable:drawable];
    [cmdBuffer commit];
}

#pragma mark - 资源清理

- (void)cleanupResources {
    if (self.resourcesReleased) return;
    self.resourcesReleased = YES;
    
    [self.player pause];
    
    [self.tsrStandard deInit];
    [self.tsrStandardExt deInit];
    [self.tsrPro deInit];
    [self.tsrProExt deInit];
    [self.tieStandard deInit];
    [self.tiePro deInit];
    
    [self.displayLink invalidate];
    [[NSNotificationCenter defaultCenter] removeObserver:self];
}

#pragma mark - 视频播放控制

- (void)videoDidEnd:(NSNotification *)notification {
    [notification.object seekToTime:kCMTimeZero];
    [_player play];
}

#pragma mark - 算法开关

/// 切换算法启用/禁用状态
- (void)toggleAlgorithm {
    _algorithmEnabled = !_algorithmEnabled;
    
    // 重置统计数据，以便准确对比性能
    _frameCount = 0;
    _avgCost = 0;
    _fpsCounter = 0;
    _fpsLastUpdateTime = CACurrentMediaTime();
}

#pragma mark - 其他

- (void)terminateApp {
    exit(0);
}

@end
