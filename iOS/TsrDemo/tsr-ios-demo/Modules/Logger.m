//
//  Logger.m
//  tsr-ios-demo
//
//  TieLogger 协议实现：将 SDK 日志按级别格式化后经 NSLog 输出。
//  Verbose 级别被过滤（避免刷屏），Error/Warning 直接输出。
//

#import <Foundation/Foundation.h>
#import "Logger.h"

@implementation Logger

- (void)logWithLevel:(TieLogLevel)logLevel log:(NSString *)log {
    NSString *levelString = @"";
    switch (logLevel) {
        case TieLogLevelError:
            levelString = @"Error";
            break;
        case TieLogLevelWarning:
            levelString = @"Warning";
            break;
        case TieLogLevelInfo:
            levelString = @"Info";
            break;
        case TieLogLevelDebug:
            levelString = @"Debug";
            break;
        default:
            break;
    }
    
    NSLog(@"[%@] %@", levelString, log);
}

@end
