//
//  Logger.m
//  tsr-ios-demo
//
//

#import <Foundation/Foundation.h>

// MyLogger.m
#import "Logger.h"

@implementation Logger

- (void)logWithLevel:(TSRLogLevel)logLevel log:(NSString *)log {
    NSString *levelString = @"";
    switch (logLevel) {
        case TSRLogLevelError:
            levelString = @"Error";
            break;
        case TSRLogLevelWarning:
            levelString = @"Warning";
            break;
        case TSRLogLevelInfo:
            levelString = @"Info";
            break;
        case TSRLogLevelDebug:
            levelString = @"Debug";
            break;
        default:
            break;
    }
    
    NSLog(@"[%@] %@", levelString, log);
}

@end
