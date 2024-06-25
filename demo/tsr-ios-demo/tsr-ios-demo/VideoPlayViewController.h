//
//  ViewController.h
//  demo
//
//

// ViewController.h
#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>
#import <MetalKit/MetalKit.h>
#import <tsr_client/TSRPass.h>
#import <tsr_client/TSRSdk.h>

#define APPID -1
#define LICENSE_NAME @"" // if your license name is auth_IOS.crt, please set it @"auth_IOS.crt"

@interface VideoPlayViewController : UIViewController<MTKViewDelegate, TSRSdkLicenseVerifyResultCallback>

@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput;

@property (nonatomic, strong) UIButton *playPauseButton;
@property (nonatomic, strong) UIButton *srSwitchButton;
@property (nonatomic, strong) UILabel *infoLabel;

@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> in_texture;
@property (nonatomic, strong) id<MTLTexture> sr_texture;

@property (nonatomic, strong) TSRPass* tsr_pass;

@property (nonatomic, assign) CGSize videoSize;
@property (nonatomic, assign) float srRatio;
@property (nonatomic, assign) BOOL isVideoLandscape;
@property (nonatomic, assign) BOOL isTsrOn;
@property (nonatomic, assign) BOOL isUseTsr;

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio;

@end

