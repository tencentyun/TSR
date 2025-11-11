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
#import <tsr_client/CommonConfig.h>
#import <Photos/Photos.h>

@implementation VideoPlayViewController {
    dispatch_queue_t _serialQueue;
}

- (void)verifyTSRLicense {
    [TSRSdk.getInstance deInit];
    [TSRSdk.getInstance initWithAppId:APPID authId:AUTH_ID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
}

- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
    NSLog(@"Online verification callback");
    if (status == TSRSdkLicenseStatusAvailable) {
        if (!_srCreateDone) {
            if (!_isUseMetal) {
                [self createTSRPassAndTIEPass:_glContext];
            } else {
                [self createTSRPassAndTIEPassMetal];
            }
        }
        if (_isRecording) {
            dispatch_async(_serialQueue, ^{
                [self setupVideoWriter];
                _lastFrameTime = kCMTimeZero;
                [_assetWriter startWriting];
                [_assetWriter startSessionAtSourceTime:kCMTimeZero];
            });
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
    
    _tsr_pass_standard = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandard glContext: context inputWidth:10 inputHeight:10 srRatio:_srRatio initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", initStatus);
    initStatus = [_tsr_pass_standard reInit:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
    
    _tsr_pass_standard_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", initStatus);
    

    AutoFallbackConfig *autoFallbackConfig = [AutoFallbackConfig configWithConsecutiveTimeoutFrames:5
                                                                timeoutDurationMs:33
                                                               listener:^(NSInteger w, NSInteger h) {
        NSLog(@"TSR AutoFallback: %dx%d", w, h);
    }];
    NSDictionary *tsrConfig = @{
        [RenderPassConfig algorithmType]: @(TSRAlgorithmTypeProfessional),
        [RenderPassConfig inputWidth]: @(_videoSize.width),
        [RenderPassConfig inputHeight]: @(_videoSize.height),
        [RenderPassConfig srRatio]: @(self.srRatio),
        //[RenderPassConfig autoFallbackConfig]: autoFallbackConfig
    };
    _tsr_pass_professional = [[TSRPass alloc] initWithRenderPassConfig:tsrConfig glContext:context initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", initStatus);

    _tsr_pass_professional_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessionalColorRetouchingExt glContext: context inputWidth:10 inputHeight:10 srRatio:_srRatio initStatusCode:&initStatus];
    // [_tsr_pass_professional_ext enableProSRAutoFallback:10 timeoutDurationMs:33 fallbackListener:nil];
    initStatus = [_tsr_pass_professional_ext reInit:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];

    TIEInitStatusCode tieInitStatus;
//    _tie_pass_standard = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeStandard glContext: context inputWidth:10 inputHeight:10 initStatusCode:&tieInitStatus];
//    NSLog(@"initStatus: %d", tieInitStatus);
    // initStatus = [_tie_pass_standard reInit:_videoSize.width inputHeight:_videoSize.height];
    _tie_pass_standard = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
    NSLog(@"initStatus: %d", tieInitStatus);
    AutoFallbackConfig *autoFallbackConfigTIE = [AutoFallbackConfig configWithConsecutiveTimeoutFrames:10
                                                                                     timeoutDurationMs:33
                                                               listener:^(NSInteger w, NSInteger h) {
        NSLog(@"TIE AutoFallback: %dx%d", w, h);
    }];
    NSDictionary *tieConfig = @{
        [RenderPassConfig algorithmType]: @(TIEAlgorithmTypeProfessional),
        [RenderPassConfig inputWidth]: @(_videoSize.width),
        [RenderPassConfig inputHeight]: @(_videoSize.height),
        [RenderPassConfig autoFallbackConfig]: autoFallbackConfigTIE
    };
    _tie_pass_professional = [[TIEPass alloc] initWithRenderPassConfig:tieConfig glContext:context initStatusCode:&initStatus];
    NSLog(@"initStatus: %d", tieInitStatus);
    
    _srCreateDone = true;
}

-(void)createTSRPassAndTIEPassMetal {
    NSLog(@"inputWidth = %d, inputHeight = %d", (int)_videoSize.width, (int)_videoSize.height);
//    TSRInitStatusCode initStatus;
//    TIEInitStatusCode tieInitStatus;
//    if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
//        MTLTextureDescriptor *ieTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
//        ieTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
//        _ie_texture = [_device newTextureWithDescriptor:ieTextureDescriptor];
//
//        _tie_pass_standard = [[TIEPass alloc] initWithTIEAlgorithmType:TIEAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
//    } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
//        MTLTextureDescriptor *ieTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
//        ieTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
//        _ie_texture = [_device newTextureWithDescriptor:ieTextureDescriptor];
//
//        _tie_pass_professional = [[TIEPass alloc] initWithTIEAlgorithmType:TIEAlgorithmTypeProfessional device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
//
////        [_tie_pass_professional enableProIEAutoFallback:10 timeoutDurationMs:33 fallbackListener:^(int w, int h) {
////            NSLog(@"TSR AutoFallback: %dx%d", w, h);
////        }];
//    } else if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
//        MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width * _srRatio height:_videoSize.height * _srRatio mipmapped:NO];
//        srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
//        _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];
//
//        _tsr_pass_standard = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
//
//    } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
//        MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width * _srRatio height:_videoSize.height * _srRatio mipmapped:NO];
//        srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
//        _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];
//
//        _tsr_pass_standard_ext = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt device: _device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
//
//    } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
//        MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width * _srRatio height:_videoSize.height * _srRatio mipmapped:NO];
//        srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
//        _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];
//
//        _tsr_pass_professional = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessional device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
//    } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
//        MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width * _srRatio height:_videoSize.height * _srRatio mipmapped:NO];
//        srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
//        _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];
//
//        _tsr_pass_professional_ext = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessionalHighQuality device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
//    }

    NSLog(@"inputWidth = %d, inputHeight = %d", (int)_videoSize.width, (int)_videoSize.height);

        MTLTextureDescriptor *srTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width * _srRatio height:_videoSize.height * _srRatio mipmapped:NO];
        srTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
        _sr_texture = [_device newTextureWithDescriptor:srTextureDescriptor];

        TSRInitStatusCode initStatus;

        _tsr_pass_standard = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

        _tsr_pass_standard_ext = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt device: _device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

        _tsr_pass_professional = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessional device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

        _tsr_pass_professional_ext = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessionalHighQuality device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
        [_tsr_pass_professional_ext enableProSRAutoFallback:10 timeoutDurationMs:33 fallbackListener:^(int w, int h) {
            NSLog(@"TSR AutoFallback: %dx%d", w, h);
        }];

        MTLTextureDescriptor *ieTextureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
        ieTextureDescriptor.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;

        _ie_texture = [_device newTextureWithDescriptor:ieTextureDescriptor];
        TIEInitStatusCode tieInitStatus;
        _tie_pass_standard = [[TIEPass alloc] initWithTIEAlgorithmType:TIEAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];

        _tie_pass_professional = [[TIEPass alloc] initWithTIEAlgorithmType:TIEAlgorithmTypeProfessional device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
        [_tie_pass_professional enableProIEAutoFallback:10 timeoutDurationMs:33 fallbackListener:^(int w, int h) {
            NSLog(@"TSR AutoFallback: %dx%d", w, h);
        }];

    _srCreateDone = true;
}

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm isRecording:(BOOL)isRecording {
    self = [super init];
    if (self) {
        _srRatio = srRatio;
        _algorithm = algorithm;
        _isRecording = isRecording;

        if (_isRecording) {
            _frameCount = 0;
            _fps = 30;
            _serialQueue = dispatch_queue_create("com.tencent.mps.tsr-ios-demo1", DISPATCH_QUEUE_SERIAL);
            
            NSString *originalFilename = [[videoURL lastPathComponent] stringByDeletingPathExtension];
            
            NSCharacterSet *illegalChars = [NSCharacterSet characterSetWithCharactersInString:@"/\\:?*<>|\"() "];
            NSString *safeAlgorithm = [[algorithm componentsSeparatedByCharactersInSet:illegalChars] componentsJoinedByString:@"_"];
            
            NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
            formatter.dateFormat = @"yyyyMMdd_HHmmss";
            NSString *timestamp = [formatter stringFromDate:[NSDate date]];
            
            // 4. 组合新文件名（格式：原文件名_算法_时间戳.mp4）
            NSString *outputFilename = [NSString stringWithFormat:@"%@_%@_%@.mp4",
                                        originalFilename,
                                        safeAlgorithm,
                                        timestamp];
            _outputURL = [NSURL fileURLWithPath:[NSTemporaryDirectory() stringByAppendingPathComponent:outputFilename]];
        }
        
        _videoSize = [self loadVideo:videoURL];
        
        _isUseMetal = USE_METAL;

        _frameCount = 0;
        _avgCost = 0;
        
        [self.infoLabel setText:_algorithm];
        
        CGRect rect;
        if (srRatio > 0) {
            _outputWidth = _videoSize.width * srRatio;
            _outputHeight = _videoSize.height * srRatio;
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
        
        if (_isUseMetal) {
            id<MTLDevice>device = MTLCreateSystemDefaultDevice();
            _device = device;
            _commandQueue = [device newCommandQueue];
            
            MTLTextureDescriptor *textureDescriptor = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:_videoSize.width height:_videoSize.height mipmapped:NO];
            _in_texture = [device newTextureWithDescriptor:textureDescriptor];
                
            NSError *error;
            
            // 设置MTKView
            _mtkView = [[MTKView alloc] initWithFrame:rect device:device];
            _mtkView.delegate = self;
            _mtkView.framebufferOnly = NO;
            _mtkView.enableSetNeedsDisplay = YES;
            
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

            [self.view addSubview:_mtkView];
        } else {
            _glContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
            CVOpenGLESTextureCacheCreate(kCFAllocatorDefault, NULL, _glContext, NULL, &_textureCache);
            self.renderer = [[VideoRenderer alloc] initWithContext:_glContext inputWidth:_videoSize.width inputHeight:_videoSize.height outputWidth:_outputWidth outputHeight:_outputHeight];
            [self.renderer setupGL];
            // 设置MTKView
            _glkView = [[GLKView alloc] initWithFrame:rect context:_glContext];
            _glkView.delegate = self;
            _glkView.enableSetNeedsDisplay = NO;
            [self.view addSubview:_glkView];
        }
        
        // 添加定时器以保证连续渲染
        _displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(updateDisplay)];
        [_displayLink addToRunLoop:[NSRunLoop currentRunLoop] forMode:NSRunLoopCommonModes];

        [self verifyTSRLicense];
        
        // 设置UI
        [self setUI];
    }
    return self;
}

- (void)updateDisplay {
    if (_isUseMetal) {
        [_mtkView setNeedsDisplay];
    } else {
        [_glkView display];
    }
}

- (void)viewDidLoad {
    [super viewDidLoad];
}

- (void)viewServiceDidTerminateWithError:(NSError *)error {
    NSLog(@"Remote view service terminated with error: %@", error);
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
    
    if (!_srCreateDone) {
        NSLog(@"sr is not ready");
        return;
    }
    
    [self updateTextureWithPixelBuffer:pixelBuffer texture:_in_texture];
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
    
    // 创建命令缓冲区
    id<MTLCommandBuffer> commandBuffer = [_commandQueue commandBuffer];
    
    if (!_isRecording) {
        NSDate *startDate = [NSDate date];
        if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
            _ie_texture = [_tie_pass_standard render:_in_texture commandBuffer:commandBuffer];
        } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
            _ie_texture = [_tie_pass_professional render:_in_texture commandBuffer:commandBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
            _sr_texture = [_tsr_pass_standard render:_in_texture commandBuffer:commandBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
            _sr_texture = [_tsr_pass_standard_ext render:_in_texture commandBuffer:commandBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
            _sr_texture = [_tsr_pass_professional render:_in_texture commandBuffer:commandBuffer];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
            _sr_texture = [_tsr_pass_professional_ext render:_in_texture commandBuffer:commandBuffer];
        }
        NSTimeInterval duration = -[startDate timeIntervalSinceNow];
        NSLog(@"耗时: %.4f ms", duration * 1000);
    } else {
        NSDate *startDate = [NSDate date];
        CVPixelBufferRef pb = pixelBuffer;
        if ([_algorithm isEqualToString:@"增强播放(标准版)"]) {
            pb = [_tie_pass_standard renderWithPixelBuffer:pixelBuffer];
            [self updateTextureWithPixelBuffer:pb texture:_ie_texture];
        } else if ([_algorithm isEqualToString:@"增强播放(专业版)"]) {
            pb = [_tie_pass_professional renderWithPixelBuffer:pixelBuffer];
            [self updateTextureWithPixelBuffer:pb texture:_ie_texture];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版)"]) {
            pb = [_tsr_pass_standard renderWithPixelBuffer:pixelBuffer];
            [self updateTextureWithPixelBuffer:pb texture:_sr_texture];
        } else if ([_algorithm isEqualToString:@"超分播放(标准版-增强)"]) {
            pb = [_tsr_pass_standard_ext renderWithPixelBuffer:pixelBuffer];
            [self updateTextureWithPixelBuffer:pb texture:_sr_texture];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版)"]) {
            pb = [_tsr_pass_professional renderWithPixelBuffer:pixelBuffer];
            [self updateTextureWithPixelBuffer:pb texture:_sr_texture];
        } else if ([_algorithm isEqualToString:@"超分播放(专业版-增强)"]) {
            pb = [_tsr_pass_professional_ext renderWithPixelBuffer:pixelBuffer];
            [self updateTextureWithPixelBuffer:pb texture:_sr_texture];
        }

        NSTimeInterval duration = -[startDate timeIntervalSinceNow];

        CMTime presentationTime = CMTimeMake(_frameCount, _fps);
        NSLog(@"presentationTime = %ld", presentationTime.value);
        if (_lastFrameTime.value != 0 && presentationTime.value <= self.lastFrameTime.value) {
            return;
        }
        self.lastFrameTime = presentationTime;
        if (self.videoInput.readyForMoreMediaData) {
            if (![self.pixelBufferAdaptor appendPixelBuffer:pb
                                   withPresentationTime:presentationTime]) {
                NSLog(@"写入失败: %@", self.assetWriter.error);
            }
        }


        _avgCost += duration * 1000;
        _frameCount++;
        NSLog(@"耗时: %.4f ms, 平均耗时: %.4fms", duration * 1000, _avgCost / _frameCount);
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
    if ([_algorithm isEqualToString:@"增强播放(标准版)"] || [_algorithm isEqualToString:@"增强播放(专业版)"]) {
        [renderEncoder setFragmentTexture:_ie_texture atIndex:0];
    } else if ([_algorithm isEqualToString:@"普通播放"]) {
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

- (void)updateTextureWithPixelBuffer:(CVPixelBufferRef)pixelBuffer texture: (id<MTLTexture>)texture{
    size_t width = CVPixelBufferGetWidth(pixelBuffer);
    size_t height = CVPixelBufferGetHeight(pixelBuffer);
    
    CVPixelBufferLockBaseAddress(pixelBuffer, 0);
    void *baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer);
    size_t bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer);
    
    [texture replaceRegion:MTLRegionMake2D(0, 0, width, height) mipmapLevel:0 withBytes:baseAddress bytesPerRow:bytesPerRow];
    
    CVPixelBufferUnlockBaseAddress(pixelBuffer, 0);
}

#pragma mark - OPENGL ES Render
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
    
    if (!_srCreateDone) {
        NSLog(@"sr is not ready");
        return;
    }

    bool useRenderMethod = !_isRecording;
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
            NSDate *startDate = [NSDate date];
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
            NSTimeInterval duration = -[startDate timeIntervalSinceNow];
            NSLog(@"耗时: %.4f ms", duration * 1000);

            // 实际渲染到屏幕
            [self.renderer render:outputTexture];
            
            CVOpenGLESTextureCacheFlush(_textureCache, 0);
            CFRelease(cvTexture);


        }
    } else {
        NSDate *startDate = [NSDate date];
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
        NSTimeInterval duration = -[startDate timeIntervalSinceNow];

        CMTime presentationTime = CMTimeMake(_frameCount, _fps);
        NSLog(@"presentationTime = %ld", presentationTime.value);
        if (_lastFrameTime.value != 0 && presentationTime.value <= self.lastFrameTime.value) {
            return;
        }
        self.lastFrameTime = presentationTime;
        if (self.videoInput.readyForMoreMediaData) {
            if (![self.pixelBufferAdaptor appendPixelBuffer:enhancedBuffer
                                   withPresentationTime:presentationTime]) {
                NSLog(@"写入失败: %@", self.assetWriter.error);
            }
        }

        _avgCost += duration * 1000;
        _frameCount++;
        NSLog(@"耗时: %.4f ms, 平均耗时: %.4fms", duration * 1000, _avgCost / _frameCount);

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

- (void)setupVideoWriter {
    // 确保删除已存在的文件
    if ([[NSFileManager defaultManager] fileExistsAtPath:_outputURL.path]) {
        NSError *removeError;
        [[NSFileManager defaultManager] removeItemAtURL:_outputURL error:&removeError];
        if (removeError) {
            NSLog(@"删除旧文件失败: %@", removeError);
            return;
        }
    }
    
    NSError *error = nil;
    _assetWriter = [[AVAssetWriter alloc] initWithURL:_outputURL
                                            fileType:AVFileTypeMPEG4
                                               error:&error];
    
    if (error) {
        NSLog(@"初始化 AVAssetWriter 失败: %@", error);
        return;
    }
    
    // 视频输出设置（需根据实际输出尺寸调整）
    NSDictionary *videoSettings = @{
        AVVideoCodecKey: AVVideoCodecTypeH264,
        AVVideoWidthKey: @(_outputWidth),
        AVVideoHeightKey: @(_outputHeight),
        AVVideoCompressionPropertiesKey: @{
            AVVideoAverageBitRateKey: @(10 * 1024 * 1024),
            AVVideoExpectedSourceFrameRateKey: @(_fps)
        }
    };
    
    _videoInput = [AVAssetWriterInput assetWriterInputWithMediaType:AVMediaTypeVideo
                                                     outputSettings:videoSettings];
    _videoInput.expectsMediaDataInRealTime = YES;
    
    // 像素缓冲区属性
    NSDictionary *pixelBufferAttributes = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_32BGRA),
        (id)kCVPixelBufferWidthKey: @(_outputWidth),
        (id)kCVPixelBufferHeightKey: @(_outputHeight)
    };
    
    _pixelBufferAdaptor = [AVAssetWriterInputPixelBufferAdaptor assetWriterInputPixelBufferAdaptorWithAssetWriterInput:_videoInput
                                                                                          sourcePixelBufferAttributes:pixelBufferAttributes];
    
    if ([_assetWriter canAddInput:_videoInput]) {
        [_assetWriter addInput:_videoInput];
    }
}

- (CGSize)loadVideo:(NSURL*)videoURL {
    AVAsset *asset = [AVAsset assetWithURL:videoURL];
    NSArray *tracks = [asset tracksWithMediaType:AVMediaTypeVideo];
    CGSize videoSize = CGSizeZero;

    if (tracks.count > 0) {
        AVAssetTrack *videoTrack = tracks.firstObject;
        _fps = videoTrack.nominalFrameRate;
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
    
    if (!_isUseMetal) {
        [self.view addSubview:_glkView];
    } else {
        [self.view addSubview:_mtkView];
    }
    
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
            
            if (!self.isUseMetal) {
                if (self.renderer) {
                    [self.renderer cleanupGL];
                    self.renderer = nil;
                }
                if (self.textureCache) {
                    CFRelease(self.textureCache);
                    self.textureCache = nil;
                }
                [EAGLContext setCurrentContext:nil];
                _glContext = nil;
            }

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
    if (!_isRecording) {
//    if (true) {
        AVPlayerItem *playerItem = notification.object;
        [playerItem seekToTime:kCMTimeZero];
        [_player play];
    } else {
        _isRecording = false;
        [self.player pause];
        dispatch_async(_serialQueue, ^{
            [self.videoInput markAsFinished];
            [self.assetWriter finishWritingWithCompletionHandler:^{
                if (self->_assetWriter.status == AVAssetWriterStatusCompleted) {
                    NSLog(@"Export completed");
                    [PHPhotoLibrary requestAuthorizationForAccessLevel:PHAccessLevelAddOnly handler:^(PHAuthorizationStatus status) {
                        if (status == PHAuthorizationStatusAuthorized) {
                            [[PHPhotoLibrary sharedPhotoLibrary] performChanges:^{
                                PHAssetCreationRequest *request = [PHAssetCreationRequest creationRequestForAsset];
                                [request addResourceWithType:PHAssetResourceTypeVideo
                                                      fileURL:self.outputURL
                                                     options:nil];
                            } completionHandler:^(BOOL success, NSError * _Nullable error) {
                                dispatch_async(dispatch_get_main_queue(), ^{
                                    if (success) {
                                        [self showAlertWithTitle:@"保存成功" message:@"视频已保存到相册"];
                                    } else {
                                        [self showAlertWithTitle:@"保存失败" message:error.localizedDescription];
                                    }
                                });
                            }];
                        } else {
                            dispatch_async(dispatch_get_main_queue(), ^{
                                [self showAlertWithTitle:@"无权限" message:@"请前往设置开启相册权限"];
                            });
                        }
                    }];
                } else {
                    NSLog(@"Export failed: %ld", self->_assetWriter.status);
                }
            }];
        });
        
    }
}

- (void)showAlertWithTitle:(NSString *)title message:(NSString *)message {
    UIAlertController *alert = [UIAlertController alertControllerWithTitle:title
                                                                   message:message
                                                            preferredStyle:UIAlertControllerStyleAlert];
    [alert addAction:[UIAlertAction actionWithTitle:@"确定" style:UIAlertActionStyleDefault handler:^(UIAlertAction* _Nonnull action) {
        [self dismissViewControllerAnimated:YES completion:^{
            // 在这里执行您需要在视图控制器关闭后进行的操作
            [self.tsr_pass_standard deInit];
            [self.tsr_pass_standard_ext deInit];
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
    }]];
    [self presentViewController:alert animated:YES completion:nil];
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
        if (!_isUseMetal) {
            self->_glkView.frame = rect;
        } else {
            self->_mtkView.frame = rect;
        }
    } completion:nil];
}

@end
