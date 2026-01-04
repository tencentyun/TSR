#import <UIKit/UIKit.h>
#import <tsr_client/TSRSdk.h>

@interface ProfileViewController : UIViewController

- (instancetype)initWithVideoURL:(NSURL *)videoURL srRatio:(float)srRatio algorithm:(NSString *)algorithm;

@end

