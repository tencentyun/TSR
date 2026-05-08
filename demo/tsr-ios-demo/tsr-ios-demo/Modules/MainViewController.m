//
//  MainViewController.m
//  tsr-ios-demo
//

#import "MainViewController.h"
#import "SettingsViewController.h"
#import "ProfileViewController.h"
#import "TIEPassV2ViewController.h"
#import "Logger.h"
#import <tsr_client/TSRSdk.h>

@interface MainViewController () <TSRSdkLicenseVerifyResultCallback>
@property (nonatomic, strong) UIButton *settingsButton;
@property (nonatomic, strong) UIButton *profileButton;
@property (nonatomic, strong) UIButton *tiePassV2Button;
@property (nonatomic, assign) BOOL licenseReady;
@end

@implementation MainViewController

- (void)viewDidLoad {
    [super viewDidLoad];
    
    self.view.backgroundColor = UIColor.whiteColor;
    self.title = @"TsrDemo";
    self.licenseReady = NO;
    
    CGFloat centerX = (self.view.bounds.size.width - 200) / 2;
    
    self.settingsButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.settingsButton.frame = CGRectMake(centerX, 200, 200, 50);
    [self.settingsButton setTitle:@"效果对比" forState:UIControlStateNormal];
    [self.settingsButton setTitleColor:UIColor.whiteColor forState:UIControlStateNormal];
    self.settingsButton.backgroundColor = [UIColor colorWithRed:26/255.0 green:221/255.0 blue:202/255.0 alpha:1.0];
    self.settingsButton.layer.cornerRadius = 8;
    [self.settingsButton addTarget:self action:@selector(openSettings) forControlEvents:UIControlEventTouchUpInside];
    self.settingsButton.enabled = NO;
    self.settingsButton.alpha = 0.5;
    [self.view addSubview:self.settingsButton];
    
    self.profileButton = [UIButton buttonWithType:UIButtonTypeSystem];
    self.profileButton.frame = CGRectMake(centerX, 280, 200, 50);
    [self.profileButton setTitle:@"性能测试" forState:UIControlStateNormal];
    [self.profileButton setTitleColor:UIColor.whiteColor forState:UIControlStateNormal];
    self.profileButton.backgroundColor = [UIColor colorWithRed:52/255.0 green:152/255.0 blue:219/255.0 alpha:1.0];
    self.profileButton.layer.cornerRadius = 8;
    [self.profileButton addTarget:self action:@selector(openProfile) forControlEvents:UIControlEventTouchUpInside];
    self.profileButton.enabled = NO;
    self.profileButton.alpha = 0.5;
    [self.view addSubview:self.profileButton];

    self.tiePassV2Button = [UIButton buttonWithType:UIButtonTypeSystem];
    self.tiePassV2Button.frame = CGRectMake(centerX, 360, 200, 50);
    [self.tiePassV2Button setTitle:@"效果对比（新）" forState:UIControlStateNormal];
    [self.tiePassV2Button setTitleColor:UIColor.whiteColor forState:UIControlStateNormal];
    self.tiePassV2Button.backgroundColor = [UIColor colorWithRed:142/255.0 green:68/255.0 blue:173/255.0 alpha:1.0];
    self.tiePassV2Button.layer.cornerRadius = 8;
    [self.tiePassV2Button addTarget:self action:@selector(openTIEPassV2) forControlEvents:UIControlEventTouchUpInside];
    self.tiePassV2Button.enabled = NO;
    self.tiePassV2Button.alpha = 0.5;
    [self.view addSubview:self.tiePassV2Button];

    [self verifyLicense];
}

- (void)verifyLicense {
    NSInteger appId = [[[NSBundle mainBundle] objectForInfoDictionaryKey:@"APP_ID"] integerValue];
    NSInteger authId = [[[NSBundle mainBundle] objectForInfoDictionaryKey:@"AUTH_ID"] integerValue];
    if (appId == 0 || authId == 0) {
        NSLog(@"[License] APP_ID/AUTH_ID 未正确配置，请检查 Config.xcconfig 文件");
        return;
    }
    
    [TSRSdk.getInstance deInit];
    [TSRSdk.getInstance initWithAppId:appId
                               authId:authId
          sdkLicenseVerifyResultCallback:self
                                tsrLogger:[Logger new]];
}

#pragma mark - TSRSdkLicenseVerifyResultCallback

- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
    dispatch_async(dispatch_get_main_queue(), ^{
        if (status == TSRSdkLicenseStatusAvailable) {
            self.licenseReady = YES;
            self.settingsButton.enabled = YES;
            self.profileButton.enabled = YES;
            self.tiePassV2Button.enabled = YES;
            self.settingsButton.alpha = 1.0;
            self.profileButton.alpha = 1.0;
            self.tiePassV2Button.alpha = 1.0;
        } else {
            self.licenseReady = NO;
            self.settingsButton.enabled = NO;
            self.profileButton.enabled = NO;
            self.tiePassV2Button.enabled = NO;
            self.settingsButton.alpha = 0.5;
            self.profileButton.alpha = 0.5;
            self.tiePassV2Button.alpha = 0.5;
            NSLog(@"License status: %ld", (long)status);
        }
    });
}

#pragma mark - 按钮事件

- (void)openSettings {
    if (!self.licenseReady) { return; }
    [self.navigationController pushViewController:[SettingsViewController new] animated:YES];
}

- (void)openProfile {
    if (!self.licenseReady) { return; }
    NSURL *videoURL = [[NSBundle mainBundle] URLForResource:@"720x1280" withExtension:@"mp4"];
    ProfileViewController *profileVC = [[ProfileViewController alloc] initWithVideoURL:videoURL srRatio:-1 algorithm:@"增强播放(专业版)"];
    [self.navigationController pushViewController:profileVC animated:YES];
}

- (void)openTIEPassV2 {
    if (!self.licenseReady) { return; }
    TIEPassV2ViewController *vc = [[TIEPassV2ViewController alloc] init];
    [self.navigationController pushViewController:vc animated:YES];
}

@end
