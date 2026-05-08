//
//  TIEPassV2ViewController.m
//  tsr-ios-demo
//
//  Demo page running TIEPassV2 (Y-channel CoreML enhancement) logic.
//  Ported from TSRSDK-V2-Demo/ViewController.m
//

#import "TIEPassV2ViewController.h"
#import "VideoRendererMetal.h"
#import <AVFoundation/AVFoundation.h>
#import <MetalKit/MetalKit.h>
#import <mach/mach.h>
#import <tsr_client/TIEPassV2.h>

#pragma mark - ViewController

@interface TIEPassV2ViewController ()

// ── Player ──────────────────────────────────────────────────────────────
@property (nonatomic, strong) AVPlayer                  *player;
@property (nonatomic, strong) AVPlayerItem              *playerItem;
@property (nonatomic, strong) AVPlayerItemVideoOutput   *videoOutput;

// ── Rendering ───────────────────────────────────────────────────────────
@property (nonatomic, strong) MTKView                   *metalView;
@property (nonatomic, strong) CADisplayLink             *displayLink;
@property (nonatomic, strong) VideoRendererMetal        *renderer;

// ── TIEPassV2 ─────────────────────────────────────────────────────────
@property (nonatomic, strong) TIEPassV2                 *tiePass;
@property (nonatomic, assign) BOOL                       licenseVerified;

// ── UI ──────────────────────────────────────────────────────────────────
@property (nonatomic, strong) UILabel                   *hudLabel;
@property (nonatomic, strong) UILabel                   *authLabel;
@property (nonatomic, strong) UIButton                  *toggleButton;
@property (nonatomic, strong) UIButton                  *shaderButton;

// ── Stats ───────────────────────────────────────────────────────────────
@property (nonatomic, assign) NSInteger      frameCount;
@property (nonatomic, assign) CFTimeInterval lastFPSTime;
@property (nonatomic, assign) double         currentFPS;
@property (nonatomic, assign) double         lastInferenceTimeMs;

// CPU delta
@property (nonatomic, assign) uint64_t       cpuTimeBaseline;
@property (nonatomic, assign) uint64_t       cpuTimeLast;
@property (nonatomic, assign) double         cpuDeltaMs;

// State
@property (nonatomic, assign) BOOL           enhanceEnabled;
@property (nonatomic, assign) BOOL           shaderEnabled;

@end

@implementation TIEPassV2ViewController

#pragma mark - Lifecycle

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    self.enhanceEnabled = YES;
    self.shaderEnabled  = NO;
    self.licenseVerified = NO;

    [self _setupMetalView];
    [self _setupHUD];
    [self _setupPlayer];
    [self _initTIEPassV2];
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [self _startDisplayLink];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [self.displayLink invalidate];
    self.displayLink = nil;
}

#pragma mark - Setup

- (void)_setupMetalView {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    MTKView *metalView = [[MTKView alloc] initWithFrame:self.view.bounds device:device];
    metalView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    metalView.contentMode = UIViewContentModeScaleAspectFill;
    [self.view insertSubview:metalView atIndex:0];
    self.metalView = metalView;

    self.renderer = [[VideoRendererMetal alloc] initWithMetalKitView:metalView];
}

- (void)_setupHUD {
    // Auth status label
    UILabel *authLabel = [[UILabel alloc] init];
    authLabel.textColor = [UIColor colorWithRed:1.0 green:0.8 blue:0.2 alpha:1.0];
    authLabel.font = [UIFont monospacedSystemFontOfSize:12 weight:UIFontWeightMedium];
    authLabel.text = @"⏳ Authenticating...";
    authLabel.backgroundColor = [UIColor colorWithWhite:0 alpha:0.55];
    [self.view addSubview:authLabel];
    self.authLabel = authLabel;

    // HUD text
    UILabel *label = [[UILabel alloc] init];
    label.textColor = [UIColor colorWithRed:0.2 green:1.0 blue:0.4 alpha:1.0];
    label.font = [UIFont monospacedSystemFontOfSize:13 weight:UIFontWeightMedium];
    label.text = @"Loading...";
    label.backgroundColor = [UIColor colorWithWhite:0 alpha:0.55];
    [self.view addSubview:label];
    self.hudLabel = label;

    // Enhance toggle button
    UIButton *btn = [UIButton buttonWithType:UIButtonTypeSystem];
    btn.backgroundColor = [UIColor colorWithRed:0.0 green:0.6 blue:1.0 alpha:0.9];
    btn.layer.cornerRadius = 8;
    btn.clipsToBounds = YES;
    [btn setTitle:@"✦ Enhance ON" forState:UIControlStateNormal];
    [btn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    btn.titleLabel.font = [UIFont systemFontOfSize:13 weight:UIFontWeightSemibold];
    [btn addTarget:self action:@selector(_toggleEnhance:) forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:btn];
    self.toggleButton = btn;

    // Shader toggle button
    UIButton *shaderBtn = [UIButton buttonWithType:UIButtonTypeSystem];
    shaderBtn.backgroundColor = [UIColor colorWithRed:0.4 green:0.4 blue:0.4 alpha:0.9];
    shaderBtn.layer.cornerRadius = 8;
    shaderBtn.clipsToBounds = YES;
    [shaderBtn setTitle:@"◇ Color OFF" forState:UIControlStateNormal];
    [shaderBtn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    shaderBtn.titleLabel.font = [UIFont systemFontOfSize:13 weight:UIFontWeightSemibold];
    [shaderBtn addTarget:self action:@selector(_toggleShader:) forControlEvents:UIControlEventTouchUpInside];
    [self.view addSubview:shaderBtn];
    self.shaderButton = shaderBtn;
}

- (void)viewSafeAreaInsetsDidChange {
    [super viewSafeAreaInsetsDidChange];
    [self _layoutHUD];
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];
    [self _layoutHUD];
}

- (void)_layoutHUD {
    CGFloat topInset  = self.view.safeAreaInsets.top;
    CGFloat w         = self.view.bounds.size.width;
    CGFloat rightEdge = w - 12;
    CGFloat btnW = 136, btnH = 36;

    self.authLabel.frame = CGRectMake(12, topInset + 4, w - btnW - 24, 20);
    self.hudLabel.frame  = CGRectMake(12, topInset + 26, w - btnW - 24, 20);

    self.toggleButton.frame = CGRectMake(rightEdge - btnW, topInset + 8,            btnW, btnH);
    self.shaderButton.frame = CGRectMake(rightEdge - btnW, topInset + 8 + btnH + 8, btnW, btnH);
}

- (void)_setupPlayer {
    NSURL *videoURL = [[NSBundle mainBundle] URLForResource:@"sample" withExtension:@"mp4"];

    NSDictionary *outputSettings = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
    };
    self.videoOutput = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:outputSettings];

    self.playerItem = [AVPlayerItem playerItemWithURL:videoURL];
    [self.playerItem addOutput:self.videoOutput];

    self.player = [AVPlayer playerWithPlayerItem:self.playerItem];
    self.player.actionAtItemEnd = AVPlayerActionAtItemEndNone;

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(_playerItemDidEnd:)
                                                 name:AVPlayerItemDidPlayToEndTimeNotification
                                               object:self.playerItem];
    [self.player play];
}

- (void)_initTIEPassV2 {
    // TSRSdk is already initialized and license verified by MainViewController.
    // Directly create TIEPassV2 — no need to re-init the SDK.
    self.cpuTimeBaseline = [self _currentCPUTimeUs];
    self.cpuTimeLast     = self.cpuTimeBaseline;
    self.licenseVerified = YES;
    [self _createTIEPassV2];
}

/// Create TIEPassV2 instance after license verification succeeds.
- (void)_createTIEPassV2 {
    int32_t videoWidth  = 720;
    int32_t videoHeight = 1280;
    AVAsset *asset = self.playerItem.asset;
    NSArray<AVAssetTrack *> *videoTracks = [asset tracksWithMediaType:AVMediaTypeVideo];
    if (videoTracks.count > 0) {
        AVAssetTrack *track = videoTracks.firstObject;
        CGSize naturalSize = track.naturalSize;
        CGAffineTransform transform = track.preferredTransform;
        CGSize transformedSize = CGSizeApplyAffineTransform(naturalSize, transform);
        videoWidth  = (int32_t)fabs(transformedSize.width);
        videoHeight = (int32_t)fabs(transformedSize.height);
        NSLog(@"[TIEPassV2VC] Video resolution: %dx%d (natural: %.0fx%.0f)",
              videoWidth, videoHeight, naturalSize.width, naturalSize.height);
    } else {
        NSLog(@"[TIEPassV2VC] No video track found, using default resolution: %dx%d", videoWidth, videoHeight);
    }

    TIEInitStatusCode initStatus;
    self.tiePass = [[TIEPassV2 alloc] initWithInputWidth:videoWidth
                                             inputHeight:videoHeight
                                          initStatusCode:&initStatus];
    self.tiePass.colorEnhanceEnabled = self.shaderEnabled;

    if (initStatus != TIEInitStatusCodeSuccess) {
        NSLog(@"[TIEPassV2VC] TIEPassV2 init failed with status: %d", (int)initStatus);
        dispatch_async(dispatch_get_main_queue(), ^{
            self.authLabel.text = [NSString stringWithFormat:@"⚠️ Init failed: %d", (int)initStatus];
            self.authLabel.textColor = [UIColor colorWithRed:1.0 green:0.3 blue:0.3 alpha:1.0];
        });
    } else {
        NSLog(@"[TIEPassV2VC] TIEPassV2 init succeeded.");
        dispatch_async(dispatch_get_main_queue(), ^{
            self.authLabel.text = @"🔒 License: Verified ✓";
            self.authLabel.textColor = [UIColor colorWithRed:0.2 green:1.0 blue:0.4 alpha:1.0];
        });
    }
}

#pragma mark - DisplayLink

- (void)_startDisplayLink {
    self.displayLink = [CADisplayLink displayLinkWithTarget:self
                                                   selector:@selector(_displayLinkFired:)];
    self.displayLink.preferredFrameRateRange = CAFrameRateRangeMake(30, 30, 30);
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop]
                           forMode:NSRunLoopCommonModes];
    self.lastFPSTime = CACurrentMediaTime();
    self.frameCount  = 0;
}

- (void)_displayLinkFired:(CADisplayLink *)link {
    CMTime outputTime = [self.videoOutput itemTimeForHostTime:link.timestamp];
    if (![self.videoOutput hasNewPixelBufferForItemTime:outputTime]) return;

    CVPixelBufferRef rawBuffer = [self.videoOutput copyPixelBufferForItemTime:outputTime
                                                           itemTimeForDisplay:nil];
    if (!rawBuffer) return;

    // Update FPS stats
    self.frameCount++;
    CFTimeInterval now = CACurrentMediaTime();
    if (now - self.lastFPSTime >= 1.0) {
        self.currentFPS  = self.frameCount / (now - self.lastFPSTime);
        self.frameCount  = 0;
        self.lastFPSTime = now;
        [self _updateCPUDelta];
        [self _updateHUD];
    }

    // Enhancement using TIEPassV2
    CVPixelBufferRef displayBuffer = rawBuffer;
    BOOL needsRelease = NO;

    if (self.enhanceEnabled && self.tiePass) {
        CFTimeInterval t0 = CACurrentMediaTime();
        CVPixelBufferRef enhanced = [self.tiePass renderWithPixelBuffer:rawBuffer];
        self.lastInferenceTimeMs = (CACurrentMediaTime() - t0) * 1000.0;

        if (enhanced && enhanced != rawBuffer) {
            displayBuffer = enhanced;
            needsRelease = YES;
        }
    }

    // Render to Metal view
    [self.renderer renderPixelBuffer:displayBuffer];

    if (needsRelease) {
        CVPixelBufferRelease(displayBuffer);
    }
    CVPixelBufferRelease(rawBuffer);
}

#pragma mark - HUD Update

- (void)_updateHUD {
    NSString *enhanceStr = self.enhanceEnabled ? @"ON " : @"OFF";
    NSString *licStr = self.licenseVerified ? @"✓" : @"✗";
    NSString *cpuStr = [NSString stringWithFormat:@"ΔCPU %.1fms/s", self.cpuDeltaMs];

    self.hudLabel.text = [NSString stringWithFormat:
                          @"FPS: %.1f  Infer: %.1fms  [%@] %@  %@",
                          self.currentFPS, self.lastInferenceTimeMs, licStr, enhanceStr, cpuStr];
}

#pragma mark - CPU Time

- (uint64_t)_currentCPUTimeUs {
    struct task_thread_times_info info;
    mach_msg_type_number_t count = TASK_THREAD_TIMES_INFO_COUNT;
    kern_return_t kr = task_info(mach_task_self(),
                                 TASK_THREAD_TIMES_INFO,
                                 (task_info_t)&info,
                                 &count);
    if (kr != KERN_SUCCESS) return 0;
    return (uint64_t)info.user_time.seconds * 1000000ULL
         + (uint64_t)info.user_time.microseconds;
}

- (void)_updateCPUDelta {
    uint64_t now = [self _currentCPUTimeUs];
    self.cpuDeltaMs  = (double)(now - self.cpuTimeLast) / 1000.0;
    self.cpuTimeLast = now;
}

#pragma mark - Actions

- (void)_toggleEnhance:(UIButton *)btn {
    self.enhanceEnabled = !self.enhanceEnabled;

    if (self.enhanceEnabled) {
        [btn setTitle:@"✦ Enhance ON" forState:UIControlStateNormal];
        btn.backgroundColor = [UIColor colorWithRed:0.0 green:0.6 blue:1.0 alpha:0.9];
    } else {
        [btn setTitle:@"○ Enhance OFF" forState:UIControlStateNormal];
        btn.backgroundColor = [UIColor colorWithRed:0.4 green:0.4 blue:0.4 alpha:0.9];
    }
    self.cpuTimeLast = [self _currentCPUTimeUs];
}

- (void)_toggleShader:(UIButton *)btn {
    self.shaderEnabled = !self.shaderEnabled;
    self.tiePass.colorEnhanceEnabled = self.shaderEnabled;

    if (self.shaderEnabled) {
        [btn setTitle:@"◆ Color ON" forState:UIControlStateNormal];
        btn.backgroundColor = [UIColor colorWithRed:0.8 green:0.4 blue:0.0 alpha:0.9];
    } else {
        [btn setTitle:@"◇ Color OFF" forState:UIControlStateNormal];
        btn.backgroundColor = [UIColor colorWithRed:0.4 green:0.4 blue:0.4 alpha:0.9];
    }
    self.cpuTimeLast = [self _currentCPUTimeUs];
}

#pragma mark - Notifications

- (void)_playerItemDidEnd:(NSNotification *)note {
    [self.player seekToTime:kCMTimeZero];
    [self.player play];
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [self.displayLink invalidate];
    [self.tiePass deInit];
}

@end
