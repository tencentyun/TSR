//
//  MainViewController.m
//  tsr-ios-demo
//
//  首页：演示 TieSdk 鉴权 + 跳转各增强能力演示页。
//
//  集成流程（客户参考）：
//    1. 实现 TieSdkLicenseCallback，创建 TieSdkConfig 填入 AppId/AuthId；
//    2. 调用 [TieSdk.sharedInstance initWithConfig:] 发起在线鉴权（异步）；
//    3. 回调中收到 TieLicenseStatusAvailable → 鉴权通过，可创建 TieEnhancer；
//    4. 使用完毕后调用 [TieSdk.sharedInstance close]。
//
//  本页提供两种视频来源：
//    - 内置视频：扫描 Bundle 根目录 *.mp4，PickerView 切换。
//    - 本地视频：PHPickerViewController 从相册选择，拷贝到 Caches 目录持久化。
//

#import "MainViewController.h"
#import "PlayViewController.h"
#import "Logger.h"
#import <tsr_client/TieSdk.h>
#import <PhotosUI/PhotosUI.h>

static const CGFloat kSectionPadding  = 16;
static const CGFloat kPickerHeight    = 120;
static const CGFloat kButtonHeight    = 44;
static const CGFloat kCornerRadius    = 8;

@interface MainViewController () <
    TieSdkLicenseCallback,
    UIPickerViewDataSource,
    UIPickerViewDelegate,
    PHPickerViewControllerDelegate
>

// 鉴权
@property (nonatomic, assign) BOOL licenseReady;
@property (nonatomic, assign) BOOL autoDemoIE;
@property (nonatomic, assign) BOOL autoDemoSR;

// 视频来源
@property (nonatomic, copy)   NSArray<NSString *> *videoAssets;          // 内置 mp4 文件名列表
@property (nonatomic, copy)   NSString            *selectedVideoFileName; // 当前选中的内置视频
@property (nonatomic, strong) NSURL               *localVideoURL;         // 本地视频 URL（非 nil 时优先生效）
@property (nonatomic, copy)   NSString            *localVideoDisplayName; // 本地视频可读名称

// UI
@property (nonatomic, strong) UIScrollView *scrollView;
@property (nonatomic, strong) UIView       *contentView;

@property (nonatomic, strong) UILabel      *videoSectionLabel;
@property (nonatomic, strong) UIPickerView *videoPicker;
@property (nonatomic, strong) UIButton     *pickLocalButton;
@property (nonatomic, strong) UILabel      *currentVideoLabel;

@property (nonatomic, strong) UILabel      *entrySectionLabel;
@property (nonatomic, strong) UIButton     *ieDemoButton;
@property (nonatomic, strong) UIButton     *srDemoButton;

@end

@implementation MainViewController

#pragma mark - 生命周期

- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = [UIColor systemBackgroundColor];
    self.title = @"TieDemo";
    self.licenseReady = NO;

    self.autoDemoIE   = [NSProcessInfo.processInfo.arguments containsObject:@"--auto-demo"];
    self.autoDemoSR = [NSProcessInfo.processInfo.arguments containsObject:@"--auto-demo-sr"];

    // --test-cached：测试用，使用 Caches 目录下已有的本地视频（上一次相册选择的文件）
    if ([NSProcessInfo.processInfo.arguments containsObject:@"--test-cached"]) {
        NSString *cachesDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
        NSString *cachedPath = [cachesDir stringByAppendingPathComponent:@"local_IMG_0013.mov"];
        if ([[NSFileManager defaultManager] fileExistsAtPath:cachedPath]) {
            self.localVideoURL = [NSURL fileURLWithPath:cachedPath];
            self.localVideoDisplayName = @"IMG_0013.mov (测试)";
            NSLog(@"[Home] --test-cached: %@ (%.2f MB)", cachedPath,
                  [[[NSFileManager defaultManager] attributesOfItemAtPath:cachedPath error:nil] fileSize] / (1024.0 * 1024.0));
        }
    }

    [self scanVideoAssets];

    // --video <name>：指定内置视频文件名（如 720x1280.mp4），配合 auto-demo 使用
    // 放在 scanVideoAssets 之后以覆盖其 firstObject 默认选择
    NSArray<NSString *> *args = NSProcessInfo.processInfo.arguments;
    NSInteger videoIdx = [args indexOfObject:@"--video"];
    if (videoIdx != NSNotFound && videoIdx + 1 < (NSInteger)args.count) {
        self.selectedVideoFileName = args[videoIdx + 1];
        NSLog(@"[Home] --video 指定: %@", self.selectedVideoFileName);
    }

    [self setupUI];
    [self verifyLicense];
}

#pragma mark - 内置视频扫描

/// 扫描 Bundle 根目录下所有 .mp4 文件作为内置视频（Videos 是 Xcode group，文件被打平到 Bundle 根层级）。
- (void)scanVideoAssets {
    NSArray<NSString *> *allMP4s = [[NSBundle mainBundle] pathsForResourcesOfType:@"mp4" inDirectory:nil];
    NSMutableArray<NSString *> *mp4s = [NSMutableArray array];
    for (NSString *path in allMP4s) {
        NSString *name = path.lastPathComponent;
        if (name) [mp4s addObject:name];
    }
    [mp4s sortUsingSelector:@selector(localizedStandardCompare:)];
    self.videoAssets = mp4s;
    NSLog(@"[Home] 找到 %lu 个内置视频: %@", (unsigned long)mp4s.count,
          [mp4s componentsJoinedByString:@", "]);

    if (mp4s.count > 0) {
        self.selectedVideoFileName = mp4s.firstObject;
    }
}

#pragma mark - UI 构建

- (void)setupUI {
    // ScrollView 防止小屏设备内容被键盘遮挡
    self.scrollView = [[UIScrollView alloc] initWithFrame:self.view.bounds];
    self.scrollView.autoresizingMask = UIViewAutoresizingFlexibleWidth | UIViewAutoresizingFlexibleHeight;
    [self.view addSubview:self.scrollView];

    self.contentView = [[UIView alloc] init];
    [self.scrollView addSubview:self.contentView];

    // 视频选择区域标题
    self.videoSectionLabel = [self makeSectionLabel:@"📹 选择视频"];

    // 内置视频 Picker
    self.videoPicker = [[UIPickerView alloc] init];
    self.videoPicker.dataSource = self;
    self.videoPicker.delegate   = self;

    // 本地视频按钮
    self.pickLocalButton = [UIButton buttonWithType:UIButtonTypeSystem];
    [self.pickLocalButton setTitle:@"选择本地视频" forState:UIControlStateNormal];
    self.pickLocalButton.titleLabel.font = [UIFont systemFontOfSize:15 weight:UIFontWeightMedium];
    self.pickLocalButton.backgroundColor = [UIColor systemGray5Color];
    self.pickLocalButton.layer.cornerRadius = kCornerRadius;
    [self.pickLocalButton addTarget:self action:@selector(pickLocalVideo) forControlEvents:UIControlEventTouchUpInside];

    // 当前选择提示
    self.currentVideoLabel = [[UILabel alloc] init];
    self.currentVideoLabel.font = [UIFont systemFontOfSize:12 weight:UIFontWeightRegular];
    self.currentVideoLabel.textColor = [UIColor secondaryLabelColor];
    self.currentVideoLabel.numberOfLines = 0;
    [self refreshCurrentVideoLabel];

    // 入口区域标题
    self.entrySectionLabel = [self makeSectionLabel:@"🎬 演示入口"];

    // 画质增强按钮
    self.ieDemoButton = [self makeDemoButton:@"增强"
                                       color:[UIColor colorWithRed:0.0 green:0.6 blue:1.0 alpha:1.0]
                                      action:@selector(openDemoIE)];
    // 超分辨率按钮
    self.srDemoButton = [self makeDemoButton:@"超分"
                                       color:[UIColor colorWithRed:142/255.0 green:68/255.0 blue:173/255.0 alpha:1.0]
                                      action:@selector(openDemoSR)];

    // 添加到 contentView
    [self.contentView addSubview:self.videoSectionLabel];
    [self.contentView addSubview:self.videoPicker];
    [self.contentView addSubview:self.pickLocalButton];
    [self.contentView addSubview:self.currentVideoLabel];
    [self.contentView addSubview:self.entrySectionLabel];
    [self.contentView addSubview:self.ieDemoButton];
    [self.contentView addSubview:self.srDemoButton];
}

- (UIButton *)makeDemoButton:(NSString *)title color:(UIColor *)color action:(SEL)action {
    UIButton *btn = [UIButton buttonWithType:UIButtonTypeSystem];
    [btn setTitle:title forState:UIControlStateNormal];
    [btn setTitleColor:[UIColor whiteColor] forState:UIControlStateNormal];
    btn.titleLabel.font = [UIFont systemFontOfSize:15 weight:UIFontWeightSemibold];
    btn.backgroundColor = color;
    btn.layer.cornerRadius = kCornerRadius;
    [btn addTarget:self action:action forControlEvents:UIControlEventTouchUpInside];
    btn.enabled = NO;
    btn.alpha = 0.5;
    return btn;
}

- (UILabel *)makeSectionLabel:(NSString *)text {
    UILabel *l = [[UILabel alloc] init];
    l.text = text;
    l.font = [UIFont systemFontOfSize:14 weight:UIFontWeightBold];
    l.textColor = [UIColor labelColor];
    return l;
}

- (void)refreshCurrentVideoLabel {
    if (self.localVideoURL) {
        self.currentVideoLabel.text = [NSString stringWithFormat:@"当前: 本地 · %@",
                                       self.localVideoDisplayName ?: @"本地视频"];
    } else if (self.selectedVideoFileName.length > 0) {
        self.currentVideoLabel.text = [NSString stringWithFormat:@"当前: 内置 · %@",
                                       self.selectedVideoFileName];
    } else {
        self.currentVideoLabel.text = @"当前: 无可用视频";
    }
}

- (void)viewDidLayoutSubviews {
    [super viewDidLayoutSubviews];

    CGFloat w = self.view.bounds.size.width;
    CGFloat contentW = w - kSectionPadding * 2;
    CGFloat y = kSectionPadding;

    // 视频选择区域标题
    self.videoSectionLabel.frame = CGRectMake(kSectionPadding, y, contentW, 20);
    y += 24;

    // Picker
    self.videoPicker.frame = CGRectMake(kSectionPadding, y, contentW, kPickerHeight);
    y += kPickerHeight + 8;

    // 本地视频按钮
    self.pickLocalButton.frame = CGRectMake(kSectionPadding, y, contentW, kButtonHeight);
    y += kButtonHeight + 8;

    // 当前选择
    CGSize labelSize = [self.currentVideoLabel sizeThatFits:CGSizeMake(contentW, CGFLOAT_MAX)];
    self.currentVideoLabel.frame = CGRectMake(kSectionPadding, y, contentW, labelSize.height);
    y += labelSize.height + 24;

    // 入口区域标题
    self.entrySectionLabel.frame = CGRectMake(kSectionPadding, y, contentW, 20);
    y += 28;

    // 演示按钮
    CGFloat btnW = 90;
    CGFloat btnGap = 8;
    CGFloat row1X = (w - btnW * 2 - btnGap) / 2;
    self.ieDemoButton.frame = CGRectMake(row1X, y, btnW, kButtonHeight);
    self.srDemoButton.frame = CGRectMake(row1X + btnW + btnGap, y, btnW, kButtonHeight);
    y += kButtonHeight + kSectionPadding;

    // ScrollView contentSize
    self.contentView.frame = CGRectMake(0, 0, w, y);
    self.scrollView.contentSize = CGSizeMake(w, y);
}

#pragma mark - UIPickerView

- (NSInteger)numberOfComponentsInPickerView:(UIPickerView *)pickerView {
    return 1;
}

- (NSInteger)pickerView:(UIPickerView *)pickerView numberOfRowsInComponent:(NSInteger)component {
    return self.videoAssets.count;
}

- (NSString *)pickerView:(UIPickerView *)pickerView titleForRow:(NSInteger)row forComponent:(NSInteger)component {
    return self.videoAssets[row];
}

- (void)pickerView:(UIPickerView *)pickerView didSelectRow:(NSInteger)row inComponent:(NSInteger)component {
    self.selectedVideoFileName = self.videoAssets[row];
    // 选择内置视频时清除本地视频来源
    [self clearLocalVideo];
    [self refreshCurrentVideoLabel];
}

#pragma mark - 本地视频选择（相册）

- (void)pickLocalVideo {
    PHPickerConfiguration *config = [[PHPickerConfiguration alloc] init];
    config.filter = [PHPickerFilter videosFilter];
    config.selectionLimit = 1;
    PHPickerViewController *picker = [[PHPickerViewController alloc] initWithConfiguration:config];
    picker.delegate = self;
    [self presentViewController:picker animated:YES completion:nil];
}

- (void)picker:(PHPickerViewController *)picker didFinishPicking:(NSArray<PHPickerResult *> *)results {
    [picker dismissViewControllerAnimated:YES completion:nil];
    if (results.count == 0) return;

    PHPickerResult *result = results.firstObject;
    NSItemProvider *provider = result.itemProvider;
    if (![provider hasItemConformingToTypeIdentifier:UTTypeMovie.identifier]) return;

    [provider loadFileRepresentationForTypeIdentifier:UTTypeMovie.identifier
                                    completionHandler:^(NSURL * _Nullable url, NSError * _Nullable error) {
        if (error || !url) {
            NSLog(@"[Home] 加载视频失败: %@", error);
            return;
        }
        // 拷贝到 Caches 目录（NSTemporaryDirectory 可被系统随时清理，Caches 更持久）
        NSString *cachesDir = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES).firstObject;
        NSString *tmpPath = [cachesDir stringByAppendingPathComponent:
                             [NSString stringWithFormat:@"local_%@", url.lastPathComponent]];
        NSURL *tmpURL = [NSURL fileURLWithPath:tmpPath];
        [[NSFileManager defaultManager] removeItemAtURL:tmpURL error:nil];
        NSError *copyErr = nil;
        if (![[NSFileManager defaultManager] copyItemAtURL:url toURL:tmpURL error:&copyErr]) {
            NSLog(@"[Home] 拷贝视频到临时目录失败: %@", copyErr);
            return;
        }
        dispatch_async(dispatch_get_main_queue(), ^{
            self.localVideoURL = tmpURL;
            self.localVideoDisplayName = result.itemProvider.suggestedName ?: url.lastPathComponent;
            NSLog(@"[Home] 本地视频已选择: %@", self.localVideoDisplayName);
            [self refreshCurrentVideoLabel];
        });
    }];
}

- (void)clearLocalVideo {
    if (self.localVideoURL) {
        [[NSFileManager defaultManager] removeItemAtURL:self.localVideoURL error:nil];
        self.localVideoURL = nil;
        self.localVideoDisplayName = nil;
    }
}

#pragma mark - 鉴权

/// 启动在线鉴权（异步）。APP_ID / AUTH_ID 从 Info.plist 读取，
/// 配置方式：在 Config.xcconfig 中设置 APP_ID 和 AUTH_ID，Info.plist 通过 ${APP_ID} 引用。
- (void)verifyLicense {
    long appId = [[[NSBundle mainBundle] objectForInfoDictionaryKey:@"APP_ID"] integerValue];
    int authId = (int)[[[NSBundle mainBundle] objectForInfoDictionaryKey:@"AUTH_ID"] integerValue];
    if (appId == 0 || authId == 0) {
        NSLog(@"[License] APP_ID/AUTH_ID 未正确配置，请检查 Config.xcconfig 文件");
        return;
    }

    [TieSdk.sharedInstance close];
    Logger *logger = [Logger new];
    TieSdkConfig *config = [TieSdkConfig configWithAppId:appId
                                                 authId:authId
                                                 logger:logger
                                               callback:self];
    [TieSdk.sharedInstance initWithConfig:config];
}

- (void)onTieLicenseResult:(TieLicenseStatus)status {
    dispatch_async(dispatch_get_main_queue(), ^{
        BOOL ok = (status == TieLicenseStatusAvailable);
        self.licenseReady = ok;
        self.ieDemoButton.enabled = ok;
        self.ieDemoButton.alpha = ok ? 1.0 : 0.5;
        self.srDemoButton.enabled = ok;
        self.srDemoButton.alpha = ok ? 1.0 : 0.5;
        if (!ok) {
            NSLog(@"[License] 鉴权失败, status=%ld", (long)status);
        }
        if (ok && self.autoDemoSR) {
            NSLog(@"[AutoDemo-SR] 鉴权通过，自动跳转超分辨率演示页");
            [self openDemoSR];
        } else if (ok && self.autoDemoIE) {
            NSLog(@"[AutoDemo] 鉴权通过，自动跳转演示页");
            [self openDemoIE];
        }
    });
}

#pragma mark - 进入演示

- (NSURL *)currentVideoURL {
    if (self.localVideoURL) return self.localVideoURL;
    if (self.selectedVideoFileName.length == 0) return nil;
    return [[NSBundle mainBundle] URLForResource:self.selectedVideoFileName.stringByDeletingPathExtension
                                   withExtension:@"mp4"];
}

/// IE（画质增强 1×）入口
- (void)openDemoIE {
    [self openDemoWithType:TieEnhancerTypeIE];
}

/// SR（超分辨率 2×/3×）入口
- (void)openDemoSR {
    [self openDemoWithType:TieEnhancerTypeSR];
}

- (void)openDemoWithType:(TieEnhancerType)engineType {
    if (!self.licenseReady) return;

    NSURL *videoURL = [self currentVideoURL];
    if (!videoURL) {
        NSLog(@"[Home] 无可用的视频来源");
        return;
    }

    // 检查文件是否存在
    if (![videoURL checkResourceIsReachableAndReturnError:nil]) {
        NSLog(@"[Home] 视频文件不可访问: %@", videoURL);
        return;
    }

    NSString *displayName = self.localVideoDisplayName ?: self.selectedVideoFileName;
    PlayViewController *vc = [[PlayViewController alloc] initWithVideoURL:videoURL
                                                                        displayName:displayName
                                                                         engineType:engineType];
    [self.navigationController pushViewController:vc animated:YES];
}

@end
