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

- (void)verifyTSRLicense {
    [TSRSdk.getInstance deInit];
    [TSRSdk.getInstance initWithAppId:APPID authId:AUTH_ID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
}

- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
    NSLog(@"Online verification callback");
    if (status == TSRSdkLicenseStatusAvailable) {
        [self createTSRPassAndTIEPass];
        [_player play];
    } else {
        NSLog(@"sdk license status is %ld", (long)status);
    }
}

-(void)createTSRPassAndTIEPass {
    NSLog(@"inputWidth = %d, inputHeight = %d", (int)_videoSize.width, (int)_videoSize.height);

    MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width * _srRatio height:_videoSize.height * _srRatio mipmapped:NO];
    srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];
    
    _tsr_pass_standard = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
    
    _tsr_pass_professional_fast = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessionalFast device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];

    _tsr_pass_professional_high_quality = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessionalHighQuality device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
    
    MTLTextureDescriptor *ieTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
    ieTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    
    _ie_texture = [_device newTextureWithDescriptor:ieTextureDescriptor];
    _tie_pass_fast = [[TIEPass alloc] initWithDevice:_device inputWidth:_videoSize.width inputHeight:_videoSize.height algorithmType:TIEAlgorithmTypeProfessionalFast];
    _tie_pass_high_quality = [[TIEPass alloc] initWithDevice:_device inputWidth:_videoSize.width inputHeight:_videoSize.height algorithmType:TIEAlgorithmTypeProfessionalHighQuality];
    
    _srCreateDone = true;
}

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm {
    self = [super init];
    if (self) {
        _srRatio = srRatio;
        _algorithm = algorithm;

        // Initialize the Metal device and command queue
        id<MTLDevice>device = MTLCreateSystemDefaultDevice();
        _device = device;
        _commandQueue = [device newCommandQueue];
        
        _videoSize = [self loadVideo:videoURL];
        MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
        _in_texture = [device newTextureWithDescriptor:textureDescriptor];

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
        
        // configure MTKView
        _mtkView = [[MTKView alloc] initWithFrame:rect device:device];
        _mtkView.delegate = self;
        _mtkView.framebufferOnly = NO;
        [self.view addSubview:_mtkView];
        
        [self verifyTSRLicense];

        [self setUI];

        [self setupRenderPipeline];
    }
    return self;
}

- (void)viewDidLoad {
    [super viewDidLoad];
}

- (void)setupRenderPipeline {
    NSError *error;
    
    id<MTLLibrary> library = [_device newDefaultLibrary];
    id<MTLFunction> vertexFunction = [library newFunctionWithName:@"vertexShader"];
    id<MTLFunction> fragmentFunction = [library newFunctionWithName:@"fragmentShader"];
    
    MTLRenderPipelineDescriptor *pipelineDescriptor = [[MTLRenderPipelineDescriptor alloc] init];
    pipelineDescriptor.vertexFunction = vertexFunction;
    pipelineDescriptor.fragmentFunction = fragmentFunction;
    pipelineDescriptor.colorAttachments[0].pixelFormat = _mtkView.colorPixelFormat;
    
    _pipelineState = [_device newRenderPipelineStateWithDescriptor:pipelineDescriptor error:&error];
    
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
    
    // Get the current video frame
    CMTime currentTime = _player.currentItem.currentTime;
    CVPixelBufferRef pixelBuffer = [_videoOutput copyPixelBufferForItemTime:currentTime itemTimeForDisplay:nil];
    if (!pixelBuffer) {
        NSLog(@"pixel buffer is null");
        return;
    }
    
    if (!_srCreateDone) {
        NSLog(@"sr is not ready");
        return;
    }
    
    [self updateTextureWithPixelBuffer:pixelBuffer texture:_in_texture];
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    
    // Create a command buffer
    id<MTLCommandBuffer> commandBuffer = [_commandQueue commandBuffer];

    if ([_algorithm isEqualToString:@"Enhanced(PRO-Fast)"]) {
        _ie_texture = [_tie_pass_fast render:_in_texture commandBuffer:commandBuffer];
    } else if ([_algorithm isEqualToString:@"Enhanced(PRO-High Quality)"]) {
        _ie_texture = [_tie_pass_high_quality render:_in_texture commandBuffer:commandBuffer];
    } else if ([_algorithm isEqualToString:@"Super-Resolution(STD)"]) {
        _sr_texture = [_tsr_pass_standard render:_in_texture commandBuffer:commandBuffer];
    } else if ([_algorithm isEqualToString:@"Super-Resolution(PRO-Fast)"]){
        _sr_texture = [_tsr_pass_professional_fast render:_in_texture commandBuffer:commandBuffer];
    } else if ([_algorithm isEqualToString:@"Super-Resolution(PRO-High Quality)"]){
        _sr_texture = [_tsr_pass_professional_high_quality render:_in_texture commandBuffer:commandBuffer];
    }
    
    // Create a render encoder
    MTLRenderPassDescriptor *renderPassDescriptor = [MTLRenderPassDescriptor renderPassDescriptor];
    renderPassDescriptor.colorAttachments[0].texture = drawable.texture;
    renderPassDescriptor.colorAttachments[0].loadAction = MTLLoadActionClear;
    renderPassDescriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
    renderPassDescriptor.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
    
    id<MTLRenderCommandEncoder> renderEncoder = [commandBuffer renderCommandEncoderWithDescriptor:renderPassDescriptor];
    
    // Set the render pipeline state
    [renderEncoder setRenderPipelineState:_pipelineState];
    
    // set texture
    if ([_algorithm isEqualToString:@"Enhanced(PRO-Fast)"] || [_algorithm isEqualToString:@"Enhanced(PRO-High Quality)"]) {
        [renderEncoder setFragmentTexture:_ie_texture atIndex:0];
    } else if ([_algorithm isEqualToString:@"Normal"]) {
        [renderEncoder setFragmentTexture:_in_texture atIndex:0];
    } else {
        [renderEncoder setFragmentTexture:_sr_texture atIndex:0];
    }
    
    // render
    [renderEncoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];

    [renderEncoder endEncoding];

    [commandBuffer presentDrawable:drawable];
    [commandBuffer commit];
    
    // Release resources
    CVPixelBufferRelease(pixelBuffer);
}

- (void)updateTextureWithPixelBuffer:(CVPixelBufferRef)pixelBuffer texture: (id<MTLTexture>)texture{
    size_t width = CVPixelBufferGetWidth(pixelBuffer);
    size_t height = CVPixelBufferGetHeight(pixelBuffer);
    
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    
    [texture replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:baseAddress bytesPerRow:bytesPerRow];
    
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
    
    // Add playback completion notification
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
    
    [self.view addSubview:_mtkView];
    
    // Create play/pause button
    self.playPauseButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.playPauseButton setTitle:@"play/pause" forState:UIControlStateNormal];
    [self.playPauseButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.playPauseButton addTarget:self action:@selector(togglePlayPause:) forControlEvents:UIControlEventTouchUpInside];
    self.playPauseButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // Create Pro SR button
    self.proSRFastButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proSRFastButton setTitle:@"Super-Resolution(PRO-Fast)" forState:UIControlStateNormal];
    [self.proSRFastButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proSRFastButton addTarget:self action:@selector(proSRFastButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proSRFastButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    
    self.proSRHighQualityButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proSRHighQualityButton setTitle:@"Super-Resolution(PRO-High Quality)" forState:UIControlStateNormal];
    [self.proSRHighQualityButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proSRHighQualityButton addTarget:self action:@selector(proSRHighQualityButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proSRHighQualityButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // Create Pro IE button
    self.proIEFastButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proIEFastButton setTitle:@"Enhanced(PRO-Fast)" forState:UIControlStateNormal];
    [self.proIEFastButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proIEFastButton addTarget:self action:@selector(proIEFastButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proIEFastButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];
    
    self.proIEHighQualityButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.proIEHighQualityButton setTitle:@"Enhanced(PRO-High Quality)" forState:UIControlStateNormal];
    [self.proIEHighQualityButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.proIEHighQualityButton addTarget:self action:@selector(proIEHighQualityButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.proIEHighQualityButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // Create playDirectly button
    self.playDirectlyButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.playDirectlyButton setTitle:@"Normal" forState:UIControlStateNormal];
    [self.playDirectlyButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.playDirectlyButton addTarget:self action:@selector(playDirectlyButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.playDirectlyButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // Create Standard SR button
    self.standardSRButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.standardSRButton setTitle:@"Super-Resolution(STD)" forState:UIControlStateNormal];
    [self.standardSRButton setTitleColor:[UIColor redColor] forState:UIControlStateNormal];
    [self.standardSRButton addTarget:self action:@selector(standardSRButtonTapped:) forControlEvents:UIControlEventTouchUpInside];
    self.standardSRButton.backgroundColor = [UIColor colorWithRed:0.0 green:0.0 blue:0.0 alpha:0.5];

    // Get the screen width and height
    CGFloat screenWidth = [UIScreen mainScreen].bounds.size.width;
    CGFloat screenHeight = [UIScreen mainScreen].bounds.size.height;

    // Calculate the position and size of the button
    CGFloat buttonWidth = 150;
    CGFloat buttonHeight = 50;
    CGFloat buttonY = screenHeight - buttonHeight - 20;

    // Set the position and size of the play/pause button
    CGFloat playPauseButtonX = (screenWidth - buttonWidth * 2) / 3;
    self.playPauseButton.frame = CGRectMake(playPauseButtonX, buttonY, buttonWidth, buttonHeight);

    // Set the position and size of the text toggle button
    CGFloat algorithmSwitchButtonX = playPauseButtonX * 2 + buttonWidth;

    if ([_algorithm isEqualToString:@"Enhanced(PRO-High Quality)"] || [_algorithm isEqualToString:@"Enhanced(PRO-Fast)"] || [_algorithm isEqualToString:@"Normal"]) {
        self.proIEHighQualityButton.frame = CGRectMake(algorithmSwitchButtonX, buttonY, buttonWidth, buttonHeight);
        
        CGFloat ieFastButtonY = buttonY - buttonHeight - 20;
        self.proIEFastButton.frame = CGRectMake(algorithmSwitchButtonX, ieFastButtonY, buttonWidth, buttonHeight);

        CGFloat playDirectlyButtonY = ieFastButtonY - buttonHeight - 20;
        self.playDirectlyButton.frame = CGRectMake(algorithmSwitchButtonX, playDirectlyButtonY, buttonWidth, buttonHeight);

        [self.view addSubview:self.proIEHighQualityButton];
        [self.view addSubview:self.proIEFastButton];
        [self.view addSubview:self.playDirectlyButton];
    } else {
        self.proSRHighQualityButton.frame = CGRectMake(algorithmSwitchButtonX, buttonY, buttonWidth, buttonHeight);
        
        CGFloat proSRFastButtonY = buttonY - buttonHeight - 20;
        self.proSRFastButton.frame = CGRectMake(algorithmSwitchButtonX, proSRFastButtonY, buttonWidth, buttonHeight);

        // Set the position and size of the Standard SR button
        CGFloat standardSRButtonY = proSRFastButtonY - buttonHeight - 20;
        self.standardSRButton.frame = CGRectMake(algorithmSwitchButtonX, standardSRButtonY, buttonWidth, buttonHeight);

        // Set the position and size of the playDirectly button
        CGFloat playDirectlyButtonY = standardSRButtonY - buttonHeight - 20;
        self.playDirectlyButton.frame = CGRectMake(algorithmSwitchButtonX, playDirectlyButtonY, buttonWidth, buttonHeight);

        [self.view addSubview:self.proSRHighQualityButton];
        [self.view addSubview:self.proSRFastButton];
        [self.view addSubview:self.playDirectlyButton];
        [self.view addSubview:self.standardSRButton];
    }

    // Add the button to the view
    [self.view addSubview:self.playPauseButton];
    
    // Create a text box for the prompt message
    self.infoLabel = [[UILabel alloc] init];
    self.infoLabel.text = _algorithm;
    self.infoLabel.textColor = [UIColor redColor];
    self.infoLabel.backgroundColor = [UIColor clearColor];
    [self.infoLabel sizeToFit];
    // Set the position of the text box
    CGFloat labelX = 20;
    CGFloat labelY = 40;
    CGFloat labelWidth = 200;
    CGFloat labelHeight = self.infoLabel.frame.size.height;
    self.infoLabel.frame = CGRectMake(labelX, labelY, labelWidth, labelHeight);
    [self.view addSubview:self.infoLabel];
}

- (void)addEdgePanGesture {
    // Add edge pan gesture recognizer
    UIScreenEdgePanGestureRecognizer *rightEdgePanGestureRecognizer = [[UIScreenEdgePanGestureRecognizer alloc] initWithTarget:self action:@selector(handleEdgePanGesture:)];
    rightEdgePanGestureRecognizer.edges = UIRectEdgeRight; // Swipe from the right edge of the screen
    [self.view addGestureRecognizer:rightEdgePanGestureRecognizer];

    // Add edge pan gesture recognizer
    UIScreenEdgePanGestureRecognizer *leftEdgePanGestureRecognizer = [[UIScreenEdgePanGestureRecognizer alloc] initWithTarget:self action:@selector(handleEdgePanGesture:)];
    leftEdgePanGestureRecognizer.edges = UIRectEdgeLeft; // Swipe from the left edge of the screen
    [self.view addGestureRecognizer:leftEdgePanGestureRecognizer];
}

- (void)handleEdgePanGesture:(UIScreenEdgePanGestureRecognizer *)gestureRecognizer {
    if (gestureRecognizer.state == UIGestureRecognizerStateEnded) {
        // Close the current view controller and return to the previous view controller
        [self dismissViewControllerAnimated:YES completion:^{
            // Perform any operations needed after the view controller is closed
            [self.tsr_pass_standard deInit];
            [self.tsr_pass_professional_fast deInit];
            [self.tsr_pass_professional_high_quality deInit];
            [self.tie_pass_fast deInit];
            [self.tie_pass_high_quality deInit];
            [TSRSdk.getInstance deInit];
        }];
    }
}

- (void)togglePlayPause:(UIButton *)button {
    if (self.player.rate == 0) {
        // If AVPlayer is currently paused, start playing
        [self.player play];
    } else {
        // If AVPlayer is currently playing, pause playback
        [self.player pause];
    }
}

// Add event handler for playDirectly button
- (void)playDirectlyButtonTapped:(UIButton *)sender {
    _algorithm = @"Normal Play";
    self.infoLabel.text = _algorithm;
}

// Add event handler for Standard SR button
- (void)standardSRButtonTapped:(UIButton *)sender {
    _algorithm = @"Super-Resolution Play (Standard)";
    self.infoLabel.text = _algorithm;
}

// Add event handler for Pro SR button
- (void)proSRFastButtonTapped:(UIButton *)sender {
    _algorithm = @"Super-Resolution Play (Professional - Low Power)";
    self.infoLabel.text = _algorithm;
}

- (void)proSRHighQualityButtonTapped:(UIButton *)sender {
    _algorithm = @"Super-Resolution Play (Professional - High Power)";
    self.infoLabel.text = _algorithm;
}

// Add event handler for Pro IE button
- (void)proIEFastButtonTapped:(UIButton *)sender {
    _algorithm = @"Enhanced Play (Professional - Low Power)";
    self.infoLabel.text = _algorithm;
}

- (void)proIEHighQualityButtonTapped:(UIButton *)sender {
    _algorithm = @"Enhanced Play (Professional - High Power)";
    self.infoLabel.text = _algorithm;
}

#pragma mark - Receive playback completion notification
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
        self->_mtkView.frame = rect;
    } completion:nil];
}

@end
