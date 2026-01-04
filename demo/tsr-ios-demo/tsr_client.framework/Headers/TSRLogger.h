//
//  TsrLogger.h
//  tsr-client
//
//  Created by 高峻峰 on 2024/1/29.
//

#ifndef TSRSDK_IOS_TSRLOGGER_H
#define TSRSDK_IOS_TSRLOGGER_H

/*!
 * Log level.
 */
typedef NS_ENUM(NSUInteger, TSRLogLevel){
    TSRLogLevelVerbose,
    TSRLogLevelDebug,
    TSRLogLevelInfo,
    TSRLogLevelWarning,
    TSRLogLevelError,
};

#pragma mark --- This interface represents the log callbacks of the TsrSdk. ---
/*!
 * This protocol represents the log callbacks of the TsrSdk. You can implement the protocol to get the logs from tsrsdk.
 */
@protocol TSRLogger <NSObject>
@required
/*!
 This interface represents the log callbacks of the TsrSdk.
 @param logLevel Log level
 @param log Log message.
 */
- (void)logWithLevel:(TSRLogLevel)logLevel log:(NSString *)log;
@end

#endif /* TSRSDK_IOS_TSRLOGGER_H */
