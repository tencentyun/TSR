//
//  ViewController.h
//  demo
//
//

// VideoPlayViewController.h
#import <UIKit/UIKit.h>
#import <AVFoundation/AVFoundation.h>
#import <MetalKit/MetalKit.h>
#import <tsr_client/TSRPass.h>
#import <tsr_client/TSRSdk.h>
#import <tsr_client/TIEPass.h>

#define APPID -1
#define AUTH_ID 0

@interface VideoPlayViewController : UIViewController<MTKViewDelegate, TSRSdkLicenseVerifyResultCallback>

@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput;

@property (nonatomic, strong) UIButton *playPauseButton;
@property (nonatomic, strong) UIButton *proSRHighQualityButton;
@property (nonatomic, strong) UIButton *proSRFastButton;
@property (nonatomic, strong) UIButton *proIEFastButton;
@property (nonatomic, strong) UIButton *proIEHighQualityButton;
@property (nonatomic, strong) UIButton *playDirectlyButton;
@property (nonatomic, strong) UIButton *standardSRButton;
@property (nonatomic, strong) UILabel *infoLabel;
@property (nonatomic, strong) MTKView *mtkView;
@property (nonatomic, strong) UIView *whiteView;
@property (nonatomic, strong) UIImageView *imageView;

@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> in_texture;
@property (nonatomic, strong) id<MTLTexture> sr_texture;
@property (nonatomic, strong) id<MTLTexture> ie_texture;

@property (nonatomic, strong) TSRPass* tsr_pass_standard;
@property (nonatomic, strong) TSRPass* tsr_pass_professional_fast;
@property (nonatomic, strong) TSRPass* tsr_pass_professional_high_quality;
@property (nonatomic, strong) TIEPass* tie_pass_fast;
@property (nonatomic, strong) TIEPass* tie_pass_high_quality;

@property (nonatomic, strong) NSString* algorithm;
@property (nonatomic, assign) CGSize videoSize;
@property (nonatomic, assign) float srRatio;
@property (nonatomic, assign) BOOL isVideoLandscape;
@property (nonatomic, assign) BOOL isFullScreen;
@property (nonatomic, assign) int outputWidth;
@property (nonatomic, assign) int outputHeight;
@property (nonatomic, assign) bool srCreateDone;

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm;

@end

