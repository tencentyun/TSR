//
//  ViewController.h
//  demo
//
//

// VideoPlayViewController.h
#import <AVFoundation/AVFoundation.h>
#import <GLKit/GLKit.h>
#import <MetalKit/MetalKit.h>
#import <tsr_client/TSRPass.h>
#import <tsr_client/TSRSdk.h>
#import <tsr_client/TIEPass.h>
#import "VideoRenderer.h"

#define APPID -1
#define AUTH_ID 0

#define USE_METAL true

@interface VideoPlayViewController : UIViewController<GLKViewDelegate, TSRSdkLicenseVerifyResultCallback>

@property (nonatomic, strong) AVPlayer *player;
@property (nonatomic, strong) AVPlayerItemVideoOutput *videoOutput;

@property (nonatomic, strong) UIButton *playPauseButton;
@property (nonatomic, strong) UIButton *proSRExtButton;
@property (nonatomic, strong) UIButton *proSRButton;
@property (nonatomic, strong) UIButton *proIEButton;
@property (nonatomic, strong) UIButton *playDirectlyButton;
@property (nonatomic, strong) UIButton *standardSRButton;
@property (nonatomic, strong) UIButton *standardSRExtButton;
@property (nonatomic, strong) UIButton *standardIEButton;

@property (nonatomic, strong) UILabel *infoLabel;
@property (nonatomic, strong) GLKView *glkView;
@property (nonatomic, strong) MTKView *mtkView;
@property (nonatomic, strong) UIView *whiteView;
@property (nonatomic, strong) UIImageView *imageView;
@property (nonatomic, strong) CADisplayLink *displayLink;

@property (nonatomic, strong) TSRPass* tsr_pass_standard;
@property (nonatomic, strong) TSRPass* tsr_pass_standard_ext;
@property (nonatomic, strong) TSRPass* tsr_pass_professional;
@property (nonatomic, strong) TSRPass* tsr_pass_professional_ext;
@property (nonatomic, strong) TIEPass* tie_pass_standard;
@property (nonatomic, strong) TIEPass* tie_pass_professional;

@property (nonatomic, strong) EAGLContext *glContext;
@property (nonatomic, strong) VideoRenderer *renderer;
@property (nonatomic) CVOpenGLESTextureCacheRef textureCache;

@property (nonatomic, strong) id<MTLDevice> device;
@property (nonatomic, strong) id<MTLCommandQueue> commandQueue;
@property (nonatomic, strong) id<MTLRenderPipelineState> pipelineState;
@property (nonatomic, strong) id<MTLTexture> in_texture;
@property (nonatomic, strong) id<MTLTexture> sr_texture;
@property (nonatomic, strong) id<MTLTexture> ie_texture;

@property (nonatomic, assign) BOOL isUseMetal;

@property (nonatomic, strong) NSString* algorithm;
@property (nonatomic, assign) CGSize videoSize;
@property (nonatomic, assign) float srRatio;
@property (nonatomic, assign) BOOL isVideoLandscape;
@property (nonatomic, assign) int outputWidth;
@property (nonatomic, assign) int outputHeight;
@property (nonatomic, assign) bool srCreateDone;

@property (nonatomic, strong) AVAssetWriter *assetWriter;
@property (nonatomic, strong) AVAssetWriterInput *videoInput;
@property (nonatomic, strong) AVAssetWriterInputPixelBufferAdaptor *pixelBufferAdaptor;
@property (nonatomic, assign) CMTime lastFrameTime;
@property (nonatomic, assign) BOOL isRecording;
@property (nonatomic, strong) NSURL *outputURL;
@property (nonatomic, assign) int frameCount;
@property (nonatomic, assign) int fps;
@property (nonatomic, assign)float avgCost;



- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString*)algorithm isRecording:(BOOL)isRecording ;

@end

