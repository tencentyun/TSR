//
//  ViewController.m
//  demo
//
//

// ViewController.mm
#import "VideoPlayViewController.h"
#import "Logger.h"
#import <tsr_client/TSRSdk.h>
#import <tsr_client/TIEPass.h>

@implementation VideoPlayViewController

- (void)verifyTSRLicenseAndCreateSRPass {
    [TSRSdk.getInstance reset];
    [TSRSdk.getInstance initWithAppId:APPID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
}

- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
    NSLog(@"Online verification callback");
    if (status == TSRSdkLicenseStatusAvailable) {
        [self createTSRPassAndTIEPass];
    } else {
        NSLog(@"sdk license status is %ld", (long)status);
    }
}

-(void)createTSRPassAndTIEPass {
    NSLog(@"inputWidth = %d, inputHeight = %d", (int)_videoSize.width, (int)_videoSize.height);

    MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
    srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];
    
    // 初始化tsrpass
    _tsr_pass_standard = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
    // Optional. Sets the brightness, saturation and contrast level of the TSRPass. The default value is set to (50, 50, 50).
    // Here we set (52, 52, 58) to slightly enhance the image.
    [_tsr_pass_standard setParametersWithBrightness:51 saturation:52 contrast:55];
    _tsr_pass_professional = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessional device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];

    MTLTextureDescriptor *ieTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
    ieTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    _source_texture = [_device newTextureWithDescriptor:ieTextureDescriptor];

    _tie_pass = [[TIEPass alloc] initWithDevice:_device inputWidth:_videoSize.width inputHeight:_videoSize.height];
}

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm {
    self = [super init];
    if (self) {
        [self addEdgePanGesture];
        
        _srRatio = srRatio;
        _algorithm = algorithm;

        // 初始化Metal设备和命令队列
        id<MTLDevice>device = MTLCreateSystemDefaultDevice();
        _device = device;
        _commandQueue = [device newCommandQueue];
        
        _videoSize = [self loadVideo:videoURL];
        MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
        _in_texture = [device newTextureWithDescriptor:textureDescriptor];

        [self.infoLabel setText:_algorithm];
        [self verifyTSRLicenseAndCreateSRPass];
        
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
    
    if ([_algorithm isEqualToString:@"Pro Image Enhance"]) {
        _source_texture = [_tie_pass render:_in_texture commandBuffer:commandBuffer];
    } else if ([_algorithm isEqualToString:@"Standard SR"]) {
        _sr_texture = [_tsr_pass_standard render:_in_texture commandBuffer:commandBuffer];
    } else if ([_algorithm isEqualToString:@"Pro SR"]){
        _sr_texture = [_tsr_pass_professional render:_in_texture commandBuffer:commandBuffer];
    } else {
        // 使用双线性插值算法
        // 创建一个渲染命令编码器
        MTLRenderPassDescriptor *renderPassDescriptor = [MTLRenderPassDescriptor renderPassDescriptor];
        renderPassDescriptor.colorAttachments[0].texture = _source_texture;
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
    if ([_algorithm isEqualToString:@"Pro Image Enhance"]) {
        [renderEncoder setFragmentTexture:_source_texture atIndex:0];
    } else if ([_algorithm isEqualToString:@"Play directly"]) {
        [renderEncoder setFragmentTexture:_in_texture atIndex:0];
    } else {
        [renderEncoder setFragmentTexture:_sr_texture atIndex:0];
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

    // 创建 Pro SR 按钮
    self.proSRButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proSRButton setTitle:@"Pro SR" forState:UIControlStateNormal];
    [self.proSRButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proSRButton addTarget:self action:@selector(proSRButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proSRButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 Pro IE 按钮
    self.proIEButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proIEButton setTitle:@"Pro IE" forState:UIControlStateNormal];
    [self.proIEButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proIEButton addTarget:self action:@selector(proIEButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proIEButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 playDirectly 按钮
    self.playDirectlyButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.playDirectlyButton setTitle:@"Play directly" forState:UIControlStateNormal];
    [self.playDirectlyButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.playDirectlyButton addTarget:self action:@selector(playDirectlyButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.playDirectlyButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 Standard SR 按钮
    self.standardSRButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.standardSRButton setTitle:@"Standard SR" forState:UIControlStateNormal];
    [self.standardSRButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.standardSRButton addTarget:self action:@selector(standardSRButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.standardSRButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 获取屏幕的宽度和高度
    CGFloat screenWidth = [UIScreen mainScreen].bounds.size.width;
    CGFloat screenHeight = [UIScreen mainScreen].bounds.size.height;

    // 计算按钮的位置和大小
    CGFloat buttonWidth = 150;
    CGFloat buttonHeight = 50;
    CGFloat buttonY = screenHeight - buttonHeight - 20; // 20是按钮距离底部的间距

    // 设置播放/暂停按钮的位置和大小
    CGFloat playPauseButtonX = (screenWidth - buttonWidth * 2) / 3; // 计算播放/暂停按钮的 X 坐标
    self.playPauseButton.frame = CGRectMake(playPauseButtonX, buttonY, buttonWidth, buttonHeight);

    // 设置文本切换按钮的位置和大小
    CGFloat algorithmSwitchButtonX = playPauseButtonX * 2 + buttonWidth; // 计算文本切换按钮的 X 坐标

    if ([_algorithm isEqualToString:@"Pro Image Enhance"] || [_algorithm isEqualToString:@"Play directly"]) {
        self.proIEButton.frame = CGRectMake(algorithmSwitchButtonX, buttonY, buttonWidth, buttonHeight);

        CGFloat playDirectlyButtonY = buttonY - buttonHeight - 20; // 在 srSwitchButton 上方 40 个点
        self.playDirectlyButton.frame = CGRectMake(algorithmSwitchButtonX, playDirectlyButtonY, buttonWidth, buttonHeight);

        [self.view addSubview:self.proIEButton];
        [self.view addSubview:self.playDirectlyButton];
    } else {
        self.proSRButton.frame = CGRectMake(algorithmSwitchButtonX, buttonY, buttonWidth, buttonHeight);

        // 设置 Standard SR 按钮的位置和大小
        CGFloat standardSRButtonY = buttonY - buttonHeight - 20; // 在 srSwitchButton 上方 20 个点
        self.standardSRButton.frame = CGRectMake(algorithmSwitchButtonX, standardSRButtonY, buttonWidth, buttonHeight);

        // 设置 playDirectly 按钮的位置和大小
        CGFloat playDirectlyButtonY = standardSRButtonY - buttonHeight - 20; // 在 srSwitchButton 上方 40 个点
        self.playDirectlyButton.frame = CGRectMake(algorithmSwitchButtonX, playDirectlyButtonY, buttonWidth, buttonHeight);

        [self.view addSubview:self.proSRButton];
        [self.view addSubview:self.playDirectlyButton];
        [self.view addSubview:self.standardSRButton];
    }

    // 将按钮添加到视图中
    [self.view addSubview:self.playPauseButton];

    
    // 创建提示信息的文本框
    self.infoLabel = [[UILabel alloc] init];
    self.infoLabel.text = _algorithm;
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

// 添加 playDirectly 按钮的事件处理方法
- (void)playDirectlyButtonTapped:(UIButton *)sender {
    _algorithm = @"Play directly";
    self.infoLabel.text = _algorithm;
}

// 添加 Standard SR 按钮的事件处理方法
- (void)standardSRButtonTapped:(UIButton *)sender {
    _algorithm = @"Standard SR";
    self.infoLabel.text = _algorithm;
}

// 添加 Pro SR 按钮的事件处理方法
- (void)proSRButtonTapped:(UIButton *)sender {
    _algorithm = @"Pro SR";
    self.infoLabel.text = _algorithm;
}

// 添加 Pro IE 按钮的事件处理方法
- (void)proIEButtonTapped:(UIButton *)sender {
    _algorithm = @"Pro Image Enhance";
    self.infoLabel.text = _algorithm;
}

#pragma mark - 接收播放完成的通知
- (void)runLoopTheMovie:(NSNotification *)notification {
    AVPlayerItem *playerItem = notification.object;
    [playerItem seekToTime:kCMTimeZero];
    [_player play];
}

@end
