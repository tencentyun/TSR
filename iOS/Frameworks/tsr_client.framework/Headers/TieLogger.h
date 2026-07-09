//
//  TieLogger.h
//  tsr-client
//
//  SDK 日志协议：调用方实现此协议注入日志回调。
//

#ifndef TieLogger_h
#define TieLogger_h

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// 日志级别。
typedef NS_ENUM(NSUInteger, TieLogLevel) {
    TieLogLevelVerbose,
    TieLogLevelDebug,
    TieLogLevelInfo,
    TieLogLevelWarning,
    TieLogLevelError,
};

/// SDK 日志回调协议。实现此协议并将实例传入 TieSdkConfig.logger 即可接收 SDK 日志。
@protocol TieLogger <NSObject>
@required
- (void)logWithLevel:(TieLogLevel)logLevel log:(NSString *)log;
@end

NS_ASSUME_NONNULL_END

#endif /* TieLogger_h */
