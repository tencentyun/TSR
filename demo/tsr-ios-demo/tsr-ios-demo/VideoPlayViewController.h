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
@property (nonatomic, strong) UIButton *proSRButton;
@property (nonatomic, strong) UIButton *proIEButton;
@property (nonatomic, strong) UIButton *playDirectlyButton;
@property (nonatomic, strong) UIButton *standardSRButton;
@property (nonatomic, strong) UILabel *infoLabel;
@property (nonatomic, strong) MTKView *mtkView;
@property (nonatomic, strong) UIView *whiteView;

@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> in_texture;
@property (nonatomic, strong) id<MTLTexture> sr_texture;
@property (nonatomic, strong) id<MTLTexture> source_texture;

@property (nonatomic, strong) TSRPass* tsr_pass_standard;
@property (nonatomic, strong) TSRPass* tsr_pass_professional;
@property (nonatomic, strong) TIEPass* tie_pass;

@property (nonatomic, strong) NSString* algorithm;
@property (nonatomic, assign) CGSize videoSize;
@property (nonatomic, assign) float srRatio;
@property (nonatomic, assign) BOOL isVideoLandscape;
@property (nonatomic, assign) int outputWidth;
@property (nonatomic, assign) int outputHeight;

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm;

@end

