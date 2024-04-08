//
//  AppDelegate.m
//  tsr-ios-demo
//
//

#import "AppDelegate.h"
#import "SettingsViewController.h"

@interface AppDelegate ()

@end

@implementation AppDelegate


- (BOOL)application:(UIApplication *)application didFinishLaunchingWithOptions:(NSDictionary *)launchOptions {
    self.window = [[UIWindow alloc] initWithFrame:[UIScreen mainScreen].bounds];
    self.window.rootViewController = [[SettingsViewController alloc] init];
    [self.window makeKeyAndVisible];
    return YES;
}


@end
