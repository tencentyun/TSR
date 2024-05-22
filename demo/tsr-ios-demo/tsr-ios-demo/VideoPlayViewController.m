//
//  ViewController.m
//  demo
//
//

// ViewController.mm
#import "VideoPlayViewController.h"
#import "Logger.h"
#import <tsr_client/TsrSdk.h>

@implementation VideoPlayViewController

- (void)verifyTSRLicenseAndCreateSRPass {
    [TSRSdk.getInstance reset];
    [TSRSdk.getInstance initWithAppId:APPID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
}

- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
    NSLog(@"Online verification callback");
    if (status == TSRSdkLicenseStatusAvailable) {
        [self createTSRPass];
    } else {
        NSLog(@"sdk license status is %ld", (long)status);
    }
}

-(void)createTSRPass {
    _isTsrOn = true;
    
    int outputWidth = _videoSize.width * _srRatio;
    int outputHeight = _videoSize.height * _srRatio;
    NSLog(@"inputWidth = %d, inputHeight = %d, srRatio = %f, outputWidth = %d, outputHeight = %d",
          (int)_videoSize.width, (int)_videoSize.height, _srRatio, outputWidth, outputHeight);

    MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:outputWidth height:outputHeight mipmapped:NO];
    textureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    _sr_texture = [_device newTextureWithDescriptor:textureDescriptor];
    
    // 初始化tsrpass
    _tsr_pass = [[TSRPass alloc] initWithDevice:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
    // Sets the parameters of the TSRPass.
    // These three parameters are empirical values and are only for reference. You can change their values according to your own needs.
    [_tsr_pass setParametersWithBrightness:52 saturation:52 contrast:58];
}

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio {
    self = [super init];
    if (self) {
        [self addEdgePanGesture];
        
        _srRatio = srRatio;
        
        // 初始化Metal设备和命令队列
        id<MTLDevice>device = MTLCreateSystemDefaultDevice();
        _device = device;
        _commandQueue = [device newCommandQueue];
        
        _videoSize = [self loadVideo:videoURL];
        MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
        _in_texture = [device newTextureWithDescriptor:textureDescriptor];
        
        if (srRatio > 0) {
            _isUseTsr = true;
            _isTsrOn = true;
            [self.infoLabel setText:@"TSR: ON"];
            [self verifyTSRLicenseAndCreateSRPass];
        } else {
            _isUseTsr = false;
            _isTsrOn = false;
            [self.infoLabel setText:@"TSR: OFF"];
        }
        
        // 设置MTKView
        MTKView* mtkView = [[MTKView alloc] initWithFrame:self.view.bounds device:device];
        mtkView.delegate = self;
        mtkView.framebufferOnly = NO;
        [self.view addSubview:mtkView];
        
        // 设置UI
        [self setUI];
        
        // 创建渲染管线
        [self setupRenderPipeline:mtkView device:device];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
}

- (void)setupRenderPipeline:(MTKView*)mtkView device:(id<MTLDevice>)device {
    NSError *error;
    
    id<MTLLibrary> library = [device newDefaultLibrary];
    id<MTLFunction> vertexFunction = [library newFunctionWithName:@"vertexShader"];
    id<MTLFunction> fragmentFunction = [library newFunctionWithName:@"fragmentShader"];
    
    MTLRenderPipelineDescriptor *pipelineDescriptor = [[MTLRenderPipelineDescriptor alloc] init];
    pipelineDescriptor.vertexFunction = vertexFunction;
    pipelineDescriptor.fragmentFunction = fragmentFunction;
    pipelineDescriptor.colorAttachments[0].pixelFormat = mtkView.colorPixelFormat;
    
    _pipelineState = [device newRenderPipelineStateWithDescriptor:pipelineDescriptor error:&error];
    
    if (!_pipelineState) {
        NSLog(@"Failed to create pipeline state: %@", error);
    }
}

#pragma mark - MTKViewDelegate

- (void)mtkView:(MTKView *)view drawableSizeWillChange:(CGSize)size {
}

- (void)drawInMTKView:(MTKView *)view {
    id<CAMetalDrawable> drawable = [view currentDrawable];
    if (!drawable) {
        return;
    }
    
    // 获取当前视频帧
    CMTime currentTime = _player.currentItem.currentTime;
    CVPixelBufferRef pixelBuffer = [_videoOutput copyPixelBufferForItemTime:currentTime itemTimeForDisplay:nil];
    if (!pixelBuffer) {
        NSLog(@"pixel buffer is null");
        return;
    }
    
    [self updateInputTextureWithPixelBuffer:pixelBuffer];
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    
    // 创建命令缓冲区
    id<MTLCommandBuffer> commandBuffer = [_commandQueue commandBuffer];
    
    if (_isUseTsr) {
        if (_isTsrOn) {
            _sr_texture = [_tsr_pass render:_in_texture commandBuffer:commandBuffer];
        } else {
            // 使用双线性插值算法
            // 创建一个渲染命令编码器
            MTLRenderPassDescriptor *renderPassDescriptor = [MTLRenderPassDescriptor renderPassDescriptor];
            renderPassDescriptor.colorAttachments[0].texture = _sr_texture;
            renderPassDescriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
            renderPassDescriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
            renderPassDescriptor.colorAttachments[0].clearColor = MTLClearColorMake(0.0, 0.0, 0.0, 1.0);
            id<MTLRenderCommandEncoder> encoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPassDescriptor];
            // 设置渲染管线状态
            [encoder setRenderPipelineState:_pipelineState];
            // 设置纹理
            [encoder setFragmentTexture:_in_texture atIndex:0];
            // 编码渲染命令
            [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            // 结束编码
            [encoder endEncoding];
        }
    }
    
    // 创建渲染编码器
    MTLRenderPassDescriptor *renderPassDescriptor = [MTLRenderPassDescriptor renderPassDescriptor];
    renderPassDescriptor.colorAttachments[0].texture = drawable.texture;
    renderPassDescriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
    renderPassDescriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
    renderPassDescriptor.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
    
    id<MTLRenderCommandEncoder> renderEncoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPassDescriptor];
    
    // 设置渲染管道状态
    [renderEncoder setRenderPipelineState:_pipelineState];
    
    // 设置纹理
    if (_isUseTsr) {
        [renderEncoder setFragmentTexture:_sr_texture atIndex:0];
    } else {
        [renderEncoder setFragmentTexture:_in_texture atIndex:0];
    }
    
    // 绘制
    [renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    
    // 结束编码
    [renderEncoder endEncoding];
    
    // 提交命令
    [commandBuffer presentDrawable:drawable];
    [commandBuffer commit];
    
    // 释放资源
    CVPixelBufferRelease(pixelBuffer);
}

- (void)updateInputTextureWithPixelBuffer:(CVPixelBufferRef)pixelBuffer {
    size_t width = CVPixelBufferGetWidth(pixelBuffer);
    size_t height = CVPixelBufferGetHeight(pixelBuffer);
    
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    
    [_in_texture replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:baseAddress bytesPerRow:bytesPerRow];
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
}

- (CGSize)loadVideo:(NSURL*)videoURL {
    AVAsset *asset = [AVAsset assetWithURL:videoURL];
    NSArray *tracks = [asset tracksWithMediaType:AVMediaTypeVideo];
    CGSize videoSize = CGSizeZero;

    if (tracks.count > 0) {
        AVAssetTrack *videoTrack = tracks.firstObject;
        videoSize = videoTrack.naturalSize;
    }
    
    AVPlayerItem *playerItem = [AVPlayerItem playerItemWithAsset:asset];
    
    NSDictionary *outputSettings = @{(id)kCVPixelBufferPixelFormatTypeKey : @(kCVPixelFormatType_32BGRA)};
    _videoOutput = [[AVPlayerItemVideoOutput alloc] initWithOutputSettings:outputSettings];
    [playerItem addOutput:_videoOutput];
    
    _player = [AVPlayer playerWithPlayerItem:playerItem];
    [_player play];
    
    //添加播放完成通知
    [[NSNotificationCenter defaultCenter]addObserver:self selector:@selector(runLoopTheMovie:) name:AVPlayerItemDidPlayToEndTimeNotification object:_player.currentItem];
    
    return videoSize;
}

- (void)setUI {
    // 创建播放/暂停按钮
    self.playPauseButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.playPauseButton setTitle:@"Play/Pause" forState:UIControlStateNormal];
    [self.playPauseButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.playPauseButton addTarget:self action:@selector(togglePlayPause:) forControlEvents:UIControlEventTouchUpInside];
    self.playPauseButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    // 创建文本切换按钮
    self.srSwitchButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.srSwitchButton setTitle:@"TSR: ON/OFF" forState:UIControlStateNormal];
    [self.srSwitchButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.srSwitchButton addTarget:self action:@selector(switchTsr:) forControlEvents:UIControlEventTouchUpInside];
    self.srSwitchButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 获取屏幕的宽度和高度
    CGFloat screenWidth = [UIScreen mainScreen].bounds.size.width;
    CGFloat screenHeight = [UIScreen mainScreen].bounds.size.height;

    // 计算按钮的位置和大小
    CGFloat buttonWidth = 100;
    CGFloat buttonHeight = 50;
    CGFloat buttonY = screenHeight - buttonHeight - 20; // 20是按钮距离底部的间距

    // 设置播放/暂停按钮的位置和大小
    CGFloat playPauseButtonX = (screenWidth - buttonWidth * 2) / 3; // 计算播放/暂停按钮的 X 坐标
    self.playPauseButton.frame = CGRectMake(playPauseButtonX, buttonY, buttonWidth, buttonHeight);

    // 设置文本切换按钮的位置和大小
    CGFloat srSwitchButtonX = playPauseButtonX * 2 + buttonWidth; // 计算文本切换按钮的 X 坐标
    self.srSwitchButton.frame = CGRectMake(srSwitchButtonX, buttonY, buttonWidth, buttonHeight);

    // 将按钮添加到视图中
    [self.view addSubview:self.playPauseButton];
    [self.view addSubview:self.srSwitchButton];
    
    // 创建提示信息的文本框
    self.infoLabel = [[UILabel alloc] init];
    self.infoLabel.text = @"TSR: ON";
    self.infoLabel.textColor = [UIColor redColor];
    self.infoLabel.backgroundColor = [UIColor clearColor];
    [self.infoLabel sizeToFit];
    // 设置文本框的位置
    CGFloat labelX = 20; // 距离屏幕左边的间距
    CGFloat labelY = 40; // 距离屏幕顶部的间距
    CGFloat labelWidth = 200; // 设置文本框的宽度
    CGFloat labelHeight = self.infoLabel.frame.size.height;
    self.infoLabel.frame = CGRectMake(labelX, labelY, labelWidth, labelHeight);
    [self.view addSubview:self.infoLabel];
}

- (void)addEdgePanGesture {
    // 添加滑动手势识别器
    UIScreenEdgePanGestureRecognizer *rightEdgePanGestureRecognizer = [[UIScreenEdgePanGestureRecognizer alloc] initWithTarget:self action:@selector(handleEdgePanGesture:)];
    rightEdgePanGestureRecognizer.edges = UIRectEdgeRight; // 从屏幕右边缘开始滑动
    [self.view addGestureRecognizer:rightEdgePanGestureRecognizer];
    
    // 添加滑动手势识别器
    UIScreenEdgePanGestureRecognizer *leftEdgePanGestureRecognizer = [[UIScreenEdgePanGestureRecognizer alloc] initWithTarget:self action:@selector(handleEdgePanGesture:)];
    leftEdgePanGestureRecognizer.edges = UIRectEdgeLeft; // 从屏幕左边缘开始滑动
    [self.view addGestureRecognizer:leftEdgePanGestureRecognizer];
}

- (void)handleEdgePanGesture:(UIScreenEdgePanGestureRecognizer *)gestureRecognizer {
    if (gestureRecognizer.state == UIGestureRecognizerStateEnded) {
        // 关闭当前视图控制器并返回到上一层视图控制器
        [self dismissViewControllerAnimated:YES completion:nil];
    }
}

- (void)togglePlayPause:(UIButton *)button {
    if (self.player.rate == 0) {
        // 如果 AVPlayer 当前是暂停状态，开始播放
        [self.player play];
    } else {
        // 如果 AVPlayer 当前是播放状态，暂停播放
        [self.player pause];
    }
}

- (void)switchTsr:(UITapGestureRecognizer *)recognizer {
    if (!_isTsrOn) {
        [self.infoLabel setText:@"TSR: ON"];
    } else {
        [self.infoLabel setText:@"TSR: OFF"];
    }
    _isTsrOn = !_isTsrOn;
}

#pragma mark - 接收播放完成的通知
- (void)runLoopTheMovie:(NSNotification *)notification {
    AVPlayerItem *playerItem = notification.object;
    [playerItem seekToTime:kCMTimeZero];
    [_player play];
}

@end
