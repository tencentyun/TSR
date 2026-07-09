//
//  PlayViewController.m
//  tsr-ios-demo
//
//  CoreML 增强/超分演示页：AVPlayer 解码 NV12 → TieEnhancer 推理 → Metal 上屏。
//
//  集成要点（客户参考）：
//    1. 创建 TieEnhancer 实例，构造 TieEnhancerConfig（指定任务类型 + 视频编码尺寸）；
//    2. 在后台线程调用 [enhancer setup:config]（加载模型/预热会阻塞，避免卡主线程）；
//    3. setup 返回 TieInitResult — code==OK 表示就绪，outputWidth/Height 为有效输出尺寸；
//    4. 每帧调用 [enhancer process:nv12Buffer] → 返回 NV12 CVPixelBuffer；
//    5. process 返回值是 SDK 内部复用 buffer，必须在下次 process / close 前消费（本 Demo 在渲染后立即释放）；
//    6. 用毕调用 [enhancer close] 释放资源。
//
//  播放器设置要点：
//    - AVPlayerItemVideoOutput 指定 NV12（kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange）；
//    - 用编码尺寸（track.naturalSize）初始化 TieEnhancerConfig，而非显示尺寸；
//    - 竖拍视频 preferredTransform 含 90° 旋转时，用编码尺寸初始化引擎、显示尺寸做 letterbox。
//
//  布局策略：竖屏宽度铺满 / 横屏高度铺满，控件半透明覆盖。
//

#import "PlayViewController.h"
#import "VideoRendererMetal.h"
#import "VideoColorSpace.h"
#import <AVFoundation/AVFoundation.h>
#import <MetalKit/MetalKit.h>
#import <tsr_client/TieEnhancer.h>

// ---- 布局常量 ----
static const CGFloat kInfoHeight      = 28;   // 信息显示区高度（单行）
static const CGFloat kBtnHeight       = 36;   // 操作按钮统一高度
static const CGFloat kPlayPauseSize   = 40;   // 播放暂停按钮（正方形，仅符号）
static const CGFloat kToggleBtnWidth  = 58;   // 开关按钮宽度（容纳中文文案）
static const CGFloat kControlSpacing  = 12;   // 按钮间距
static const CGFloat kControlPadding  = 12;   // 操作区边距
static const CGFloat kCornerRadius    = 8;

@interface PlayViewController ()

// 视频来源
@property (nonatomic, copy)   NSString     *videoDisplayName;
@property (nonatomic, strong) NSURL        *videoURL;
@property (nonatomic, assign) TieEnhancerType engineType;

// 播放
@property (nonatomic, strong) AVPlayer                *player;
@property (nonatomic, strong) AVPlayerItem            *playerItem;
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput;
@property (nonatomic, assign) BOOL                     isPlaying;

// 渲染
@property (nonatomic, strong) MTKView            *metalView;
@property (nonatomic, strong) CADisplayLink      *displayLink;
@property (nonatomic, strong) VideoRendererMetal *renderer;

// SDK 能力
@property (nonatomic, strong) id<MTLDevice>   device;
@property (nonatomic, strong) TieEnhancer       *engine;
@property (nonatomic, assign) BOOL             engineReady;

// 统计
@property (nonatomic, assign) NSInteger      frameCount;
@property (nonatomic, assign) CFTimeInterval lastFPSTime;
@property (nonatomic, assign) double         currentFPS;
@property (nonatomic, assign) double         lastInferenceTimeMs;

// 开关
@property (nonatomic, assign) BOOL enhanceEnabled;
@property (nonatomic, assign) BOOL autoEnhance;          // --auto-enhance 命令行参数
// 暂停时缓存最后一帧，仅状态切换时重新渲染一次（避免持续推理耗电）
@property (nonatomic, assign) CVPixelBufferRef cachedRawBuffer;
@property (nonatomic, assign) BOOL needsRenderWhilePaused;

// UI —— 信息区
@property (nonatomic, strong) UIView  *infoPanel;
@property (nonatomic, strong) UILabel *infoLabel1;

// UI —— 操作区
@property (nonatomic, strong) UIView   *controlPanel;
@property (nonatomic, strong) UIButton *playPauseBtn;
@property (nonatomic, strong) UIButton *enhanceBtn;

// 视频尺寸（用于 letterbox 计算）
@property (nonatomic, assign) CGSize videoSize;
// 编码尺寸（用于引擎初始化，CVPixelBuffer 实际尺寸，可能与显示尺寸宽高互换）
@property (nonatomic, assign) int32_t encodedWidth;
@property (nonatomic, assign) int32_t encodedHeight;
// 是否需旋转渲染（竖拍视频 preferredTransform 含 90° rotation）
@property (nonatomic, assign) BOOL needsRotation;

@end

@implementation PlayViewController

#pragma mark - Init

- (instancetype)initWithVideoURL:(NSURL *)videoURL
                     displayName:(NSString *)displayName
                      engineType:(TieEnhancerType)engineType {
    self = [super initWithNibName:nil bundle:nil];
    if (self) {
        _videoURL = videoURL;
        _videoDisplayName = [displayName copy];
        _engineType = engineType;
    }
    return self;
}

#pragma mark - 生命周期

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor blackColor];
    self.title = @"";

    self.enhanceEnabled = NO;
    self.autoEnhance    = [NSProcessInfo.processInfo.arguments containsObject:@"--auto-enhance"];
    self.engineReady    = NO;
    self.isPlaying      = YES;

    [self setupMetalView];
    [self setupInfoPanel];
    [self setupControlPanel];
    [self setupPlayer];
    [self setupSDK];

    // 监听内存告警
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(didReceiveMemoryWarning)
                                                 name:UIApplicationDidReceiveMemoryWarningNotification
                                               object:nil];
}

- (void)viewWillAppear:(BOOL)animated {
    [super viewWillAppear:animated];
    [UIApplication sharedApplication].idleTimerDisabled = YES;
    [self startDisplayLink];
}

- (void)viewWillDisappear:(BOOL)animated {
    [super viewWillDisappear:animated];
    [UIApplication sharedApplication].idleTimerDisabled = NO;
    [self.displayLink invalidate];
    self.displayLink = nil;
}

- (BOOL)prefersHomeIndicatorAutoHidden {
    return YES;
}

#pragma mark - Setup —— Metal 视图

- (void)setupMetalView {
    self.device = MTLCreateSystemDefaultDevice();
    MTKView *metalView = [[MTKView alloc] initWithFrame:self.view.bounds device:self.device];
    metalView.backgroundColor = [UIColor blackColor];
    [self.view insertSubview:metalView atIndex:0];
    self.metalView = metalView;
    self.renderer = [[VideoRendererMetal alloc] initWithMetalKitView:metalView];
}

#pragma mark - Setup —— 信息显示区（最底部）

- (void)setupInfoPanel {
    self.infoPanel = [[UIView alloc] init];
    self.infoPanel.backgroundColor = [UIColor colorWithWhite:0 alpha:0.55];
    [self.view addSubview:self.infoPanel];

    self.infoLabel1 = [[UILabel alloc] init];
    self.infoLabel1.font = [UIFont monospacedSystemFontOfSize:11 weight:UIFontWeightRegular];
    self.infoLabel1.textColor = [UIColor colorWithRed:0.2 green:1.0 blue:0.4 alpha:1.0];
    self.infoLabel1.numberOfLines = 1;
    self.infoLabel1.adjustsFontSizeToFitWidth = YES;
    self.infoLabel1.minimumScaleFactor = 0.7;
    [self.infoPanel addSubview:self.infoLabel1];

    self.infoLabel1.text = [NSString stringWithFormat:@"%@ | ⏳ %@ SDK 初始化中...",
                            self.videoDisplayName ?: @"-", [self engineDisplayName]];
}

#pragma mark - Setup —— 操作区

- (void)setupControlPanel {
    self.controlPanel = [[UIView alloc] init];
    self.controlPanel.backgroundColor = [UIColor clearColor];
    [self.view addSubview:self.controlPanel];

    // 播放/暂停
    self.playPauseBtn = [self makeControlButton:@"❚❚"
                                          color:[UIColor colorWithRed:0.2 green:0.2 blue:0.2 alpha:0.85]
                                         action:@selector(togglePlayPause)];
    [self.controlPanel addSubview:self.playPauseBtn];

    // 增强/超分开关
    NSString *toggleName = [self engineDisplayName];   // "增强" or "超分"
    self.enhanceBtn = [self makeControlButton:toggleName
                                        color:[UIColor colorWithRed:0.0 green:0.6 blue:1.0 alpha:0.85]
                                       action:@selector(toggleEnhance)];
    [self.controlPanel addSubview:self.enhanceBtn];

    [self updateEnhanceButtonAppearance];
}

- (UIButton *)makeControlButton:(NSString *)title
                          color:(UIColor *)color
                         action:(SEL)action {
    UIButton *btn = [UIButton buttonWithType:UIButtonTypeCustom];
    [btn setTitle:title forState:UIControlStateNormal];
    [btn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    btn.titleLabel.font = [UIFont systemFontOfSize:12 weight:UIFontWeightSemibold];
    btn.titleLabel.adjustsFontSizeToFitWidth = YES;
    btn.titleLabel.minimumScaleFactor = 0.7;
    btn.backgroundColor = color;
    btn.layer.cornerRadius = kCornerRadius;
    btn.clipsToBounds = YES;
    [btn addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    return btn;
}

- (void)updateEnhanceButtonAppearance {
    NSString *name = [self engineDisplayName];
    if (self.enhanceEnabled) {
        self.enhanceBtn.backgroundColor = [UIColor colorWithRed:0.0 green:0.6 blue:1.0 alpha:0.85];
        [self.enhanceBtn setTitle:[NSString stringWithFormat:@"%@ ON", name] forState:UIControlStateNormal];
    } else {
        self.enhanceBtn.backgroundColor = [UIColor colorWithRed:0.4 green:0.4 blue:0.4 alpha:0.85];
        [self.enhanceBtn setTitle:[NSString stringWithFormat:@"%@ OFF", name] forState:UIControlStateNormal];
    }
}


#pragma mark - 布局

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];

    CGFloat viewW = self.view.bounds.size.width;
    CGFloat viewH = self.view.bounds.size.height;
    CGFloat safeTop    = self.view.safeAreaInsets.top;
    CGFloat safeBottom = self.view.safeAreaInsets.bottom;
    CGFloat safeLeft   = self.view.safeAreaInsets.left;
    CGFloat safeRight  = self.view.safeAreaInsets.right;

    BOOL isLandscape = viewW > viewH;

    // --- 1. 信息区：固定在最底部（单行）---
    self.infoPanel.frame = CGRectMake(0, viewH - safeBottom - kInfoHeight,
                                      viewW, kInfoHeight + safeBottom);
    self.infoLabel1.frame = CGRectMake(8, 4, viewW - 16, kInfoHeight - 4);

    // --- 2. 操作区：竖屏底部，横屏右侧 ---
    [self layoutControlPanelIsLandscape:isLandscape
                                  viewW:viewW viewH:viewH
                               safeTop:safeTop safeBottom:safeBottom
                              safeLeft:safeLeft safeRight:safeRight];

    // --- 3. Metal 视图：等比例 letterbox ---
    [self layoutMetalViewIsLandscape:isLandscape
                               viewW:viewW viewH:viewH
                            safeTop:safeTop safeBottom:safeBottom
                           safeLeft:safeLeft safeRight:safeRight];
}

/// 操作区布局
- (void)layoutControlPanelIsLandscape:(BOOL)isLandscape
                                viewW:(CGFloat)viewW viewH:(CGFloat)viewH
                             safeTop:(CGFloat)safeTop safeBottom:(CGFloat)safeBottom
                            safeLeft:(CGFloat)safeLeft safeRight:(CGFloat)safeRight {
    CGFloat spacing = kControlSpacing;

    if (isLandscape) {
        // 横屏：垂直排列在右侧，所有按钮同宽
        CGFloat panelW = kToggleBtnWidth + kControlPadding * 2;
        CGFloat totalH = kBtnHeight * 2 + spacing;
        CGFloat panelY = safeTop + (viewH - safeTop - safeBottom - totalH) / 2;
        self.controlPanel.frame = CGRectMake(viewW - safeRight - panelW - 4,
                                             panelY, panelW, totalH);

        self.playPauseBtn.frame = CGRectMake(kControlPadding, 0, kToggleBtnWidth, kBtnHeight);
        self.enhanceBtn.frame   = CGRectMake(kControlPadding, kBtnHeight + spacing, kToggleBtnWidth, kBtnHeight);
    } else {
        // 竖屏：水平排列在底部（信息区上方），播放按钮为正方形，开关按钮更宽
        CGFloat totalW = kPlayPauseSize + kToggleBtnWidth + spacing;
        CGFloat panelX = (viewW - totalW) / 2;
        CGFloat panelY = viewH - safeBottom - kInfoHeight - kBtnHeight - kControlPadding - 4;
        self.controlPanel.frame = CGRectMake(panelX - kControlPadding,
                                             panelY - kControlPadding,
                                             totalW + kControlPadding * 2,
                                             kBtnHeight + kControlPadding * 2);

        self.playPauseBtn.frame = CGRectMake(kControlPadding, kControlPadding, kPlayPauseSize, kBtnHeight);
        self.enhanceBtn.frame   = CGRectMake(kControlPadding + kPlayPauseSize + spacing, kControlPadding, kToggleBtnWidth, kBtnHeight);
    }
}

/// Metal 视图 letterbox：竖屏宽度铺满，横屏高度铺满。
- (void)layoutMetalViewIsLandscape:(BOOL)isLandscape
                             viewW:(CGFloat)viewW viewH:(CGFloat)viewH
                          safeTop:(CGFloat)safeTop safeBottom:(CGFloat)safeBottom
                         safeLeft:(CGFloat)safeLeft safeRight:(CGFloat)safeRight {
    if (self.videoSize.width <= 0 || self.videoSize.height <= 0) {
        self.metalView.frame = self.view.bounds;
        return;
    }

    CGFloat videoAspect = self.videoSize.width / self.videoSize.height;
    CGFloat displayW, displayH;

    if (isLandscape) {
        // 横屏：高度铺满整个屏幕，宽度按比例缩放
        displayH = viewH;
        displayW = viewH * videoAspect;
        if (displayW > viewW) {
            // 视频过宽时宽度收窄到屏幕宽度
            displayW = viewW;
            displayH = viewW / videoAspect;
        }
        // 居左 + 垂直居中
        CGFloat metalX = 0;
        CGFloat metalY = (viewH - displayH) / 2;
        self.metalView.frame = CGRectMake(metalX, metalY, displayW, displayH);
    } else {
        // 竖屏：宽度铺满整个屏幕，高度按比例缩放
        displayW = viewW;
        displayH = viewW / videoAspect;
        if (displayH > viewH) {
            // 视频过高时高度收窄到屏幕高度
            displayH = viewH;
            displayW = viewH * videoAspect;
        }
        // 居左 + 置顶
        CGFloat metalX = 0;
        CGFloat metalY = 0;
        self.metalView.frame = CGRectMake(metalX, metalY, displayW, displayH);
    }
}

#pragma mark - Setup —— 播放器

- (void)setupPlayer {
    if (!self.videoURL) {
        NSLog(@"[Demo] 无可用的视频 URL");
        return;
    }

    // 显式检查文件存在性，本地视频可能被系统清理
    if (![self.videoURL checkResourceIsReachableAndReturnError:nil]) {
        NSLog(@"[Demo] 视频文件不可访问: %@", self.videoURL.path);
        self.infoLabel1.text = @"⚠️ 视频文件不可访问";
        self.infoLabel1.textColor = [UIColor colorWithRed:1.0 green:0.3 blue:0.3 alpha:1.0];
        return;
    }

    NSLog(@"[Demo] 播放视频: %@ (%.2f MB)",
          self.videoURL.lastPathComponent,
          [[[NSFileManager defaultManager] attributesOfItemAtPath:self.videoURL.path error:nil] fileSize] / (1024.0 * 1024.0));

    NSDictionary *outputSettings = @{
        (id)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
    };
    self.videoOutput = [[AVPlayerItemVideoOutput alloc] initWithPixelBufferAttributes:outputSettings];
    self.playerItem = [AVPlayerItem playerItemWithURL:self.videoURL];
    [self.playerItem addOutput:self.videoOutput];
    self.player = [AVPlayer playerWithPlayerItem:self.playerItem];
    self.player.actionAtItemEnd = AVPlayerActionAtItemEndNone;

    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(playerItemDidEnd:)
                                                 name:AVPlayerItemDidPlayToEndTimeNotification
                                               object:self.playerItem];

    // 观察播放器状态和错误
    [self.playerItem addObserver:self forKeyPath:@"status" options:NSKeyValueObservingOptionNew context:nil];
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(playerItemFailed:)
                                                 name:AVPlayerItemFailedToPlayToEndTimeNotification
                                               object:self.playerItem];

    // 读取视频尺寸
    AVAsset *asset = self.playerItem.asset;
    AVAssetTrack *track = [asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
    if (track) {
        self.encodedWidth  = (int32_t)track.naturalSize.width;
        self.encodedHeight = (int32_t)track.naturalSize.height;
        // 检测是否含 90°/270° 旋转（竖拍视频）
        CGFloat rotB = track.preferredTransform.b;
        self.needsRotation = (fabs(rotB) == 1.0);
        // b > 0 用 CW，b < 0 用 CCW（与 preferredTransform 方向相反）
        VMRRotation rot = VMRRotationNone;
        if (rotB > 0.5)      rot = VMRRotationCCW;  // b>0 需要逆时针纠正
        else if (rotB < -0.5) rot = VMRRotationCW;
        // 旋转时用显示尺寸做 letterbox
        if (self.needsRotation) {
            CGSize d = CGSizeApplyAffineTransform(track.naturalSize, track.preferredTransform);
            self.videoSize = CGSizeMake(fabs(d.width), fabs(d.height));
        } else {
            self.videoSize = CGSizeMake(track.naturalSize.width, track.naturalSize.height);
        }
        self.renderer.rotation = rot;
        VMRYuvColorSpace cs = [VideoColorSpace detectFromTrack:track];
        self.renderer.yuvColorSpace = cs;
        NSLog(@"[Demo] 视频: 编码 %dx%d  显示 %.0fx%.0f  旋转=%d b=%.0f  color=%@",
              self.encodedWidth, self.encodedHeight,
              self.needsRotation ? self.videoSize.width : self.videoSize.width,
              self.needsRotation ? self.videoSize.height : self.videoSize.height,
              self.needsRotation, rotB,
              [VideoColorSpace debugString:cs]);
    } else {
        // 异步读取
        __weak typeof(self) weakSelf = self;
        [asset loadValuesAsynchronouslyForKeys:@[@"tracks"] completionHandler:^{
            dispatch_async(dispatch_get_main_queue(), ^{
                AVAssetTrack *t = [asset tracksWithMediaType:AVMediaTypeVideo].firstObject;
                if (t) {
                    weakSelf.encodedWidth  = (int32_t)t.naturalSize.width;
                    weakSelf.encodedHeight = (int32_t)t.naturalSize.height;
                    CGFloat rotB = t.preferredTransform.b;
                    weakSelf.needsRotation = (fabs(rotB) == 1.0);
                    VMRRotation rot = VMRRotationNone;
                    if (rotB > 0.5)      rot = VMRRotationCCW;
                    else if (rotB < -0.5) rot = VMRRotationCW;
                    if (weakSelf.needsRotation) {
                        CGSize d = CGSizeApplyAffineTransform(t.naturalSize, t.preferredTransform);
                        weakSelf.videoSize = CGSizeMake(fabs(d.width), fabs(d.height));
                    } else {
                        weakSelf.videoSize = CGSizeMake(t.naturalSize.width, t.naturalSize.height);
                    }
                    weakSelf.renderer.rotation = rot;
                    weakSelf.renderer.yuvColorSpace = [VideoColorSpace detectFromTrack:t];
                    [weakSelf.view setNeedsLayout];
                }
            });
        }];
    }

    [self.player play];
    self.isPlaying = YES;
    [self updatePlayPauseButton];
}

#pragma mark - Setup —— SDK（TieEnhancer 初始化）

/// 创建 TieEnhancer 并在后台线程 setup。
/// 要点：
///   - 使用编码尺寸（naturalSize）而非显示尺寸，与 CVPixelBuffer 实际输出一致；
///   - setup 在后台线程执行（模型加载/预热会阻塞数百 ms，避免卡主线程）；
///   - TieInitCodeOK → engineReady=YES，后续 process 调用才有效；
///   - 失败时调用方应回退原始播放。
- (void)setupSDK {
    int32_t videoWidth  = self.encodedWidth;
    int32_t videoHeight = self.encodedHeight;
    if (videoWidth <= 0 || videoHeight <= 0) {
        videoWidth  = (int32_t)self.videoSize.width;
        videoHeight = (int32_t)self.videoSize.height;
    }
    if (videoWidth <= 0 || videoHeight <= 0) {
        // tracks 尚未异步加载完成时的回退值（实际场景建议等待 tracks 就绪后再 init）
        videoWidth  = 720;
        videoHeight = 1280;
    }
    NSLog(@"[Demo] SDK 初始化分辨率 %dx%d", videoWidth, videoHeight);

    self.engine    = [[TieEnhancer alloc] init];

    TieEnhancerConfig *config = [TieEnhancerConfig configWithTask:self.engineType
                                                   videoWidth:videoWidth
                                                  videoHeight:videoHeight];
    const char *typeName = (self.engineType == TieEnhancerTypeIE) ? "IE" : "SR";
    NSLog(@"[Demo] SDK 引擎类型: %s", typeName);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        TieInitResult *result = [self.engine setup:config];
        dispatch_async(dispatch_get_main_queue(), ^{
            if (result.code == TieInitCodeOK) {
                self.engineReady = YES;
                self.infoLabel1.textColor = [UIColor colorWithWhite:0.85 alpha:1.0];
                if (self.autoEnhance) {
                    [self toggleEnhance];
                }
            } else {
                self.infoLabel1.text = [NSString stringWithFormat:@"⚠️ 初始化失败: %ld %@",
                                        (long)result.code, result.message ?: @""];
                self.infoLabel1.textColor = [UIColor colorWithRed:1.0 green:0.3 blue:0.3 alpha:1.0];
            }
            [self updateInfoLabel];
        });
    });
}

#pragma mark - DisplayLink

- (void)startDisplayLink {
    self.displayLink = [CADisplayLink displayLinkWithTarget:self selector:@selector(displayLinkFired:)];
    self.displayLink.preferredFrameRateRange = CAFrameRateRangeMake(30, 30, 30);
    [self.displayLink addToRunLoop:[NSRunLoop mainRunLoop] forMode:NSRunLoopCommonModes];
    self.lastFPSTime = CACurrentMediaTime();
    self.frameCount  = 0;
}

- (void)displayLinkFired:(CADisplayLink *)link {
    if (!self.videoOutput) return;

    // 暂停时仅在增强状态切换后渲染一帧，其余帧跳过以节省功耗
    if (!self.isPlaying) {
        if (!self.needsRenderWhilePaused) return;
        self.needsRenderWhilePaused = NO;
    }

    CVPixelBufferRef rawBuffer = NULL;

    if (self.isPlaying) {
        CMTime outputTime = [self.videoOutput itemTimeForHostTime:link.timestamp];
        // 无新解码帧时直接跳过，避免视频只有 24fps 却渲染 30fps
        if (![self.videoOutput hasNewPixelBufferForItemTime:outputTime]) return;
        rawBuffer = [self.videoOutput copyPixelBufferForItemTime:outputTime itemTimeForDisplay:nil];
    }
    // 暂停时无新帧则用缓存帧
    if (!rawBuffer && self.cachedRawBuffer) {
        rawBuffer = CVPixelBufferRetain(self.cachedRawBuffer);
    }
    if (!rawBuffer) return;

    // 缓存最新原始帧
    if (self.isPlaying && rawBuffer != self.cachedRawBuffer) {
        if (self.cachedRawBuffer) CVPixelBufferRelease(self.cachedRawBuffer);
        self.cachedRawBuffer = CVPixelBufferRetain(rawBuffer);
    }

    if (self.isPlaying) {
        self.frameCount++;
        CFTimeInterval now = CACurrentMediaTime();
        if (now - self.lastFPSTime >= 1.0) {
            self.currentFPS  = self.frameCount / (now - self.lastFPSTime);
            self.frameCount  = 0;
            self.lastFPSTime = now;
            [self updateInfoLabel];
        }
    }

    // 串联：TieEnhancer.process (NV12→NV12)
    CVPixelBufferRef displayBuffer = rawBuffer;
    CVPixelBufferRef engineBuf = NULL;   // SDK 输出 buffer，需在下次 process 前消费

    if (self.enhanceEnabled && self.engineReady) {
        CFTimeInterval t0 = CACurrentMediaTime();
        CVPixelBufferRef nv12 = [self.engine process:rawBuffer];
        self.lastInferenceTimeMs = (CACurrentMediaTime() - t0) * 1000.0;
        if (nv12) {
            engineBuf = nv12;
            displayBuffer = nv12;
        }
    }

    [self.renderer renderPixelBuffer:displayBuffer];

    // SDK 输出 buffer 需在下次 process / close 前消费；此处已渲染完毕，可释放
    if (engineBuf) CVPixelBufferRelease(engineBuf);
    // 释放 AVPlayer 输出
    CVPixelBufferRelease(rawBuffer);
}

#pragma mark - 信息更新

/// 统一信息更新：按状态动态显示有意义的内容。
/// - 未就绪: "视频名 | ⏳ {增强|超分} SDK 初始化中..."
/// - 就绪 + 增强关: "视频名 | FPS: 29.8  |  {增强|超分} OFF"
/// - 就绪 + 增强开: "视频名 | FPS: 29.8  |  耗时 4.2ms  |  {增强|超分} ON"
- (void)updateInfoLabel {
    if (!self.engineReady) return;
    NSString *name = [self engineDisplayName];
    NSString *toggleStr = self.enhanceEnabled ? [NSString stringWithFormat:@"%@ ON", name]
                                              : [NSString stringWithFormat:@"%@ OFF", name];
    if (self.enhanceEnabled && self.lastInferenceTimeMs > 0) {
        self.infoLabel1.text = [NSString stringWithFormat:@"%@ | FPS: %.1f  |  耗时 %.1fms  |  %@",
                                self.videoDisplayName ?: @"-", self.currentFPS, self.lastInferenceTimeMs, toggleStr];
    } else {
        self.infoLabel1.text = [NSString stringWithFormat:@"%@ | FPS: %.1f  |  %@",
                                self.videoDisplayName ?: @"-", self.currentFPS, toggleStr];
    }
}

#pragma mark - 操作

- (void)togglePlayPause {
    self.isPlaying = !self.isPlaying;
    if (self.isPlaying) {
        [self.player play];
    } else {
        [self.player pause];
    }
    [self updatePlayPauseButton];
}

- (void)updatePlayPauseButton {
    if (self.isPlaying) {
        [self.playPauseBtn setTitle:@"❚❚" forState:UIControlStateNormal];
    } else {
        [self.playPauseBtn setTitle:@"▶" forState:UIControlStateNormal];
    }
}

/// 引擎类型对应的中文名
- (NSString *)engineDisplayName {
    return (self.engineType == TieEnhancerTypeSR) ? @"超分" : @"增强";
}

- (void)toggleEnhance {
    self.enhanceEnabled = !self.enhanceEnabled;
    [self updateEnhanceButtonAppearance];
    [self updateInfoLabel];
    // 暂停时需触发一次渲染以显示新的开关状态效果
    if (!self.isPlaying) {
        self.needsRenderWhilePaused = YES;
    }
}

#pragma mark - 通知 / 清理

- (void)playerItemDidEnd:(NSNotification *)note {
    [self.player seekToTime:kCMTimeZero];
    if (self.isPlaying) {
        [self.player play];
    }
}

- (void)playerItemFailed:(NSNotification *)note {
    NSError *error = note.userInfo[AVPlayerItemFailedToPlayToEndTimeErrorKey];
    NSLog(@"[Demo] AVPlayerItem 播放失败: %@", error);
    self.infoLabel1.text = [NSString stringWithFormat:@"⚠️ 播放失败: %@", error.localizedDescription ?: @"未知"];
    self.infoLabel1.textColor = [UIColor colorWithRed:1.0 green:0.3 blue:0.3 alpha:1.0];
}

- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object change:(NSDictionary *)change context:(void *)context {
    if ([keyPath isEqualToString:@"status"] && object == self.playerItem) {
        AVPlayerItemStatus status = self.playerItem.status;
        if (status == AVPlayerItemStatusFailed) {
            NSLog(@"[Demo] AVPlayerItem status=failed: %@", self.playerItem.error);
        }
    }
}

- (void)didReceiveMemoryWarning {
    NSLog(@"[Demo] ⚠️ 收到内存告警");
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [self.playerItem removeObserver:self forKeyPath:@"status"];
    [self.displayLink invalidate];
    [self.engine close];
    if (_cachedRawBuffer) { CVPixelBufferRelease(_cachedRawBuffer); _cachedRawBuffer = NULL; }
}

@end
