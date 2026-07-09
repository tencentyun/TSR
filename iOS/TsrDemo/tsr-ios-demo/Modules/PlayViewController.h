//
//  TIEPassV2ViewController.h
//  tsr-ios-demo
//

#import <UIKit/UIKit.h>
#import <tsr_client/TieEnhancer.h>

NS_ASSUME_NONNULL_BEGIN

@interface PlayViewController : UIViewController

/// 指定初始化方法：传入视频 URL、可读名称和引擎类型。
- (instancetype)initWithVideoURL:(NSURL *)videoURL
                     displayName:(NSString *)displayName
                      engineType:(TieEnhancerType)engineType;

/// 禁用默认 init
- (instancetype)init NS_UNAVAILABLE;

@end

NS_ASSUME_NONNULL_END
