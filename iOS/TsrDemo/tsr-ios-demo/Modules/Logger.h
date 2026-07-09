//
//  Logger.h
//  tsr-ios-demo
//
//  TieLogger 协议的 Demo 实现：将 SDK 日志转为 NSLog 输出。
//  客户参考此实现接入自有日志系统（如 CocoaLumberjack / XLog）。
//

#ifndef Logger_h
#define Logger_h

#import <Foundation/Foundation.h>
#import <tsr_client/TieLogger.h>

@interface Logger : NSObject <TieLogger>
@end

#endif /* Logger_h */
