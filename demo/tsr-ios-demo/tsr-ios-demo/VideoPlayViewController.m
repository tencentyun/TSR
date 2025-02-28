//
//  ViewController.m
//  demo
//
//

// ViewController.mm
#import "VideoPlayViewController.h"
#import "Logger.h"

#import <OpenGLES/ES3/glext.h>
#import <CoreVideo/CoreVideo.h>

@implementation VideoPlayViewController

- (void)verifyTSRLicense {
    [TSRSdk.getInstance deInit];
    [TSRSdk.getInstance initWithAppId:APPID authId:AUTH_ID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
}

- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
    NSLog(@"Online verification callback");
    if (status == TSRSdkLicenseStatusAvailable) {
        if (!_srCreateDone) {
            [self createTSRPassAndTIEPass:_glContext];
        }
        [_player play];
    } else {
        NSLog(@"sdk license status is %ld", (long)status);
    }
}

-(void)createTSRPassAndTIEPass: (EAGLContext*)context {
    NSLog(@"inputWidth = %d, inputHeight = %d", (int)_videoSize.width, (int)_videoSize.height);
    [EAGLContext setCurrentContext:_glContext];
    TSRInitStatusCode initStatus;
    
    _tsr_pass_standard = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", initStatus);
    
    _tsr_pass_standard_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", initStatus);
    
    _tsr_pass_professional = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", initStatus);

    _tsr_pass_professional_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessionalColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

    TIEInitStatusCode tieInitStatus;
    _tie_pass_standard = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
    NSLog(@"initStatus: %d", tieInitStatus);
    
    _tie_pass_professional = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
    NSLog(@"initStatus: %d", tieInitStatus);
    
    _srCreateDone = true;
}

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm {
    self = [super init];
    if (self) {
        _srRatio = srRatio;
        _algorithm = algorithm;
        
        _videoSize = [self loadVideo:videoURL];

        [self.infoLabel setText:_algorithm];
        
        CGRect rect;
        if (srRatio > 0) {
            rect = CGRectMake(0, 0, _videoSize.width * srRatio / 3, _videoSize.height * srRatio / 3);
        } else {
            // The SR setting is "Auto"
            int screenHeight = self.view.bounds.size.height * 3;
            int screenWidth = self.view.bounds.size.width * 3;
            int videoWidth = _videoSize.width;
            int videoHeight = _videoSize.height;
            int viewWidth, viewHeight;
            double videoRatio = 1.0 * videoWidth / videoHeight;
            double screenRatio = 1.0 * screenWidth / screenHeight;
            if (screenRatio > videoRatio) {
                _srRatio = videoRatio;
                int width = (int) (_srRatio * screenHeight);
                viewWidth = width;
                viewHeight = screenHeight;
            } else {
                _srRatio = 1 / videoRatio;
                int height = (int) (screenWidth * _srRatio);
                viewWidth = screenWidth;
                viewHeight = height;
            }
            _outputWidth = viewWidth;
            _outputHeight = viewHeight;
            rect = CGRectMake(0, 0, viewWidth / 3, viewHeight / 3);
        }
        
        // 创建 OpenGL ES 上下文
        _glContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
        CVOpenGLESTextureCacheCreate(kCFAllocatorDefault, NULL, _glContext, NULL, &_textureCache);
        self.renderer = [[VideoRenderer alloc] initWithContext:_glContext inputWidth:_videoSize.width inputHeight:_videoSize.height];
        [self.renderer setupGL];
        // 设置MTKView
        _glkView = [[GLKView alloc] initWithFrame:rect context:_glContext];
        _glkView.delegate = self;
        _glkView.enableSetNeedsDisplay = NO;
        [self.view addSubview:_glkView];
        
        [self verifyTSRLicense];
        
        // 设置UI
        [self setUI];
        
        // 添加定时器以保证连续渲染
        _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateDisplay)];
        [_displayLink addToRunLoop:[NSRunLoop currentRunLoop] forMode:NSRunLoopCommonModes];
    
    }
    return self;
}

- (void)updateDisplay {
    [_glkView display];
}

- (void)viewDidLoad {
    [super viewDidLoad];
}

- (void)viewServiceDidTerminateWithError:(NSError *)error {
    NSLog(@"Remote view service terminated with error: %@", error);
}

#pragma mark - 渲染逻辑
- (void)glkView:(GLKView *)view drawInRect:(CGRect)rect {
    // 确保上下文设置成功
    if (![EAGLContext setCurrentContext:_glContext]) {
        NSLog(@"Failed to set EAGLContext");
        return;
    }
    
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    
    // 检查之前的GL错误
    GLenum preError = glGetError();
    if (preError != GL_NO_ERROR) {
        NSLog(@"Pre-existing GL error before binding FBO: %d", preError);
    }
    
    GLint oldFBO;
    // 绑定默认帧缓冲前验证参数
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &oldFBO);
    glBindFramebuffer(GL_FRAMEBUFFER, oldFBO);
    
    // 检查帧缓冲完整性
    GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        NSLog(@"Framebuffer incomplete: 0x%04X", status);
    }

    // 获取视频帧
    CMTime currentTime = _player.currentItem.currentTime;
    CVPixelBufferRef pixelBuffer = [_videoOutput copyPixelBufferForItemTime:currentTime itemTimeForDisplay:nil];
    if (!pixelBuffer) return;
    
    bool useRenderMethod = true;
    if (useRenderMethod) {
        // 创建OpenGL纹理
        CVOpenGLESTextureRef cvTexture = NULL;
        CVReturn err = CVOpenGLESTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            _textureCache,
            pixelBuffer,
            NULL,
            GL_TEXTURE_2D,
            GL_RGBA,
            (GLsizei)CVPixelBufferGetWidth(pixelBuffer),
            (GLsizei)CVPixelBufferGetHeight(pixelBuffer),
            GL_BGRA,
            GL_UNSIGNED_BYTE,
            0,
            &cvTexture
        );
        
        if (err == noErr && cvTexture) {
            GLuint texture = CVOpenGLESTextureGetName(cvTexture);
            int outputTexture = texture;
            
            // 根据算法选择渲染路径
            if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
                outputTexture = [_tie_pass_standard render:texture];
            } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
                outputTexture = [_tie_pass_professional render:texture];
            } else if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
                outputTexture = [_tsr_pass_standard render:texture];
            } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
                outputTexture = [_tsr_pass_standard_ext render:texture];
            } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
                outputTexture = [_tsr_pass_professional render:texture];
            } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
                outputTexture = [_tsr_pass_professional_ext render:texture];
            }
            
            // 实际渲染到屏幕
            [self.renderer render:outputTexture];
            
            CVOpenGLESTextureCacheFlush(_textureCache, 0);
            CFRelease(cvTexture);
        }
    } else {
        // 根据算法选择渲染路径
        CVPixelBufferRef enhancedBuffer = pixelBuffer;
        if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
            enhancedBuffer = [_tie_pass_standard renderWithPixelBuffer:pixelBuffer];
        } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
            enhancedBuffer = [_tie_pass_professional renderWithPixelBuffer:pixelBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
            enhancedBuffer = [_tsr_pass_standard renderWithPixelBuffer:pixelBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
            enhancedBuffer = [_tsr_pass_standard_ext renderWithPixelBuffer:pixelBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
            enhancedBuffer = [_tsr_pass_professional renderWithPixelBuffer:pixelBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
            enhancedBuffer = [_tsr_pass_professional_ext renderWithPixelBuffer:pixelBuffer];
        }

        CVOpenGLESTextureRef cvTexture = NULL;
        CVReturn err = CVOpenGLESTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            _textureCache,
            enhancedBuffer,
            NULL,
            GL_TEXTURE_2D,
            GL_RGBA,
            (GLsizei)CVPixelBufferGetWidth(enhancedBuffer),
            (GLsizei)CVPixelBufferGetHeight(enhancedBuffer),
            GL_BGRA,
            GL_UNSIGNED_BYTE,
            0,
            &cvTexture
        );
        int width = (GLsizei)CVPixelBufferGetWidth(enhancedBuffer);
        int height = (GLsizei)CVPixelBufferGetHeight(enhancedBuffer);
        
        if (err == noErr && cvTexture) {
            GLuint texture = CVOpenGLESTextureGetName(cvTexture);
            [self.renderer render:texture];
            
            CVOpenGLESTextureCacheFlush(_textureCache, 0);
            CFRelease(cvTexture);
        }
    }
    
    if (pixelBuffer) {
        CVPixelBufferRelease(pixelBuffer);
    }

    // 错误检查
    GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        NSLog(@"GL error at end of frame: %d", error);
    }
    
    // 清理OpenGL状态
    glBindTexture(GL_TEXTURE_2D, 0);
    glFlush();

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
    
    //添加播放完成通知
    [[NSNotificationCenter defaultCenter]addObserver:self selector:@selector(runLoopTheMovie:) name:AVPlayerItemDidPlayToEndTimeNotification object:_player.currentItem];
    
    return videoSize;
}

- (void)setUI {
    for (UIView *subview in self.view.subviews) {
        [subview removeFromSuperview];
    }

    _whiteView = [[UIView alloc] init];
    _whiteView.backgroundColor = [UIColor whiteColor];
    _whiteView.frame = self.view.bounds;
    [self.view addSubview:_whiteView];
    [self addEdgePanGesture];
    
    [self.view addSubview:_glkView];
    
    // 创建播放/暂停按钮
    self.playPauseButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.playPauseButton setTitle:@"播放/暂停" forState:UIControlStateNormal];
    [self.playPauseButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.playPauseButton addTarget:self action:@selector(togglePlayPause:) forControlEvents:UIControlEventTouchUpInside];
    self.playPauseButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 Pro SR 按钮
    self.proSRButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proSRButton setTitle:@"超分播放(专业版)" forState:UIControlStateNormal];
    [self.proSRButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proSRButton addTarget:self action:@selector(proSRButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proSRButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    
    self.proSRExtButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proSRExtButton setTitle:@"超分播放(专业版-增强)" forState:UIControlStateNormal];
    [self.proSRExtButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proSRExtButton addTarget:self action:@selector(proSRExtButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proSRExtButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 Pro IE 按钮
    self.proIEButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proIEButton setTitle:@"增强播放(专业版)" forState:UIControlStateNormal];
    [self.proIEButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proIEButton addTarget:self action:@selector(proIEButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proIEButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 playDirectly 按钮
    self.playDirectlyButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.playDirectlyButton setTitle:@"普通播放" forState:UIControlStateNormal];
    [self.playDirectlyButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.playDirectlyButton addTarget:self action:@selector(playDirectlyButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.playDirectlyButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // 创建 Standard SR 按钮
    self.standardSRButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.standardSRButton setTitle:@"超分播放(标准版)" forState:UIControlStateNormal];
    [self.standardSRButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.standardSRButton addTarget:self action:@selector(standardSRButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.standardSRButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    
    self.standardSRExtButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.standardSRExtButton setTitle:@"超分播放(标准版-增强)" forState:UIControlStateNormal];
    [self.standardSRExtButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.standardSRExtButton addTarget:self action:@selector(standardSRExtButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.standardSRExtButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    
    // 创建 Standard IE 按钮
    self.standardIEButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.standardIEButton setTitle:@"增强播放(标准版)" forState:UIControlStateNormal];
    [self.standardIEButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.standardIEButton addTarget:self action:@selector(standardIEButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.standardIEButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    
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

    if ([_algorithm isEqualToString:@"增强播放(专业版)"] || [_algorithm isEqualToString:@"普通播放"]
        || [_algorithm isEqualToString:@"增强播放(标准版)"]) {
        CGFloat ieFastButtonY = buttonY - buttonHeight - 20; // 在 srSwitchButton 上方 40 个点
        self.proIEButton.frame = CGRectMake(algorithmSwitchButtonX, ieFastButtonY, buttonWidth, buttonHeight);

        CGFloat standardIEButtonY = ieFastButtonY - buttonHeight - 20; // 在 srSwitchButton 上方 40 个点
        self.standardIEButton.frame = CGRectMake(algorithmSwitchButtonX, standardIEButtonY, buttonWidth, buttonHeight);
        
        CGFloat playDirectlyButtonY = standardIEButtonY - buttonHeight - 20; // 在 srSwitchButton 上方 40 个点
        self.playDirectlyButton.frame = CGRectMake(algorithmSwitchButtonX, playDirectlyButtonY, buttonWidth, buttonHeight);

        [self.view addSubview:self.proIEButton];
        [self.view addSubview:self.standardIEButton];
        [self.view addSubview:self.playDirectlyButton];
    } else {
        self.proSRExtButton.frame = CGRectMake(algorithmSwitchButtonX, buttonY, buttonWidth, buttonHeight);
        
        CGFloat proSRButtonY = buttonY - buttonHeight - 20;
        self.proSRButton.frame = CGRectMake(algorithmSwitchButtonX, proSRButtonY, buttonWidth, buttonHeight);
        
        CGFloat stdSRExtButtonY = proSRButtonY - buttonHeight - 20;
        self.standardSRExtButton.frame = CGRectMake(algorithmSwitchButtonX, stdSRExtButtonY, buttonWidth, buttonHeight);

        // 设置 Standard SR 按钮的位置和大小
        CGFloat standardSRButtonY = stdSRExtButtonY - buttonHeight - 20; // 在 srSwitchButton 上方 20 个点
        self.standardSRButton.frame = CGRectMake(algorithmSwitchButtonX, standardSRButtonY, buttonWidth, buttonHeight);

        // 设置 playDirectly 按钮的位置和大小
        CGFloat playDirectlyButtonY = standardSRButtonY - buttonHeight - 20; // 在 srSwitchButton 上方 40 个点
        self.playDirectlyButton.frame = CGRectMake(algorithmSwitchButtonX, playDirectlyButtonY, buttonWidth, buttonHeight);

        [self.view addSubview:self.proSRExtButton];
        [self.view addSubview:self.proSRButton];
        [self.view addSubview:self.standardSRExtButton];
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
        [self dismissViewControllerAnimated:YES completion:^{
            // 在这里执行您需要在视图控制器关闭后进行的操作
            [self.tsr_pass_standard deInit];
            [self.tsr_pass_professional deInit];
            [self.tsr_pass_professional_ext deInit];
            [self.tie_pass_standard deInit];
            [self.tie_pass_professional deInit];
            [TSRSdk.getInstance deInit];
            
            // 停止显示链接
            if (self->_displayLink) {
                [self->_displayLink invalidate];
                self->_displayLink = nil;
            }
        }];
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
    _algorithm = @"普通播放";
    self.infoLabel.text = _algorithm;
}

// 添加 Standard SR 按钮的事件处理方法
- (void)standardSRButtonTapped:(UIButton *)sender {
    _algorithm = @"超分播放(标准版)";
    self.infoLabel.text = _algorithm;
}

- (void)standardSRExtButtonTapped:(UIButton *)sender {
    _algorithm = @"超分播放(标准版-增强)";
    self.infoLabel.text = _algorithm;
}

bool enable = false;
// 添加 Pro SR 按钮的事件处理方法
- (void)proSRButtonTapped:(UIButton *)sender {
    _algorithm = @"超分播放(专业版)";
    self.infoLabel.text = _algorithm;
}

- (void)proSRExtButtonTapped:(UIButton *)sender {
    _algorithm = @"超分播放(专业版-增强)";
    self.infoLabel.text = _algorithm;
}

// 添加 Standard IE 按钮的事件处理方法
- (void)standardIEButtonTapped:(UIButton *)sender {
    _algorithm = @"增强播放(标准版)";
    self.infoLabel.text = _algorithm;
}

- (void)proIEButtonTapped:(UIButton *)sender {
    _algorithm = @"增强播放(专业版)";
    self.infoLabel.text = _algorithm;
}

#pragma mark - 接收播放完成的通知
- (void)runLoopTheMovie:(NSNotification *)notification {
    AVPlayerItem *playerItem = notification.object;
    [playerItem seekToTime:kCMTimeZero];
    [_player play];
}

- (void)viewWillTransitionToSize:(CGSize)size withTransitionCoordinator:(id<UIViewControllerTransitionCoordinator>)coordinator {
    [super viewWillTransitionToSize:size withTransitionCoordinator:coordinator];

    [coordinator animateAlongsideTransition:^(id<UIViewControllerTransitionCoordinatorContext> context) {
        [self setUI];
        
        self.whiteView.frame = CGRectMake(0, 0, size.width, size.height);
        
        CGRect rect;
        if (self->_srRatio > 0) {
            rect = CGRectMake(0, 0, self->_videoSize.width * self->_srRatio / 3, self->_videoSize.height * self->_srRatio / 3);
        } else {
            // The SR setting is "Auto"
            int screenHeight = self.view.bounds.size.height * 3;
            int screenWidth = self.view.bounds.size.width * 3;
            int videoWidth = self->_videoSize.width;
            int videoHeight = self->_videoSize.height;
            int viewWidth, viewHeight;
            double videoRatio = 1.0 * videoWidth / videoHeight;
            double screenRatio = 1.0 * screenWidth / screenHeight;
            if (screenRatio > videoRatio) {
                self->_srRatio = videoRatio;
                int width = (int) (self->_srRatio * screenHeight);
                viewWidth = width;
                viewHeight = screenHeight;
            } else {
                self->_srRatio = 1 / videoRatio;
                int height = (int) (screenWidth * self->_srRatio);
                viewWidth = screenWidth;
                viewHeight = height;
            }
            self->_outputWidth = viewWidth;
            self->_outputHeight = viewHeight;
            rect = CGRectMake(0, 0, viewWidth / 3, viewHeight / 3);
        }
        self->_glkView.frame = rect;
    } completion:nil];
}

@end
