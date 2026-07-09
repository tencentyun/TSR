//
//  TieSdk.h
//  tsr-client
//
//  鉴权域：SDK 许可证管理与校验，进程级单例。
//  使用流程：sharedInstance → initWithConfig:(在线鉴权)
//          → 鉴权通过(licenseStatus==Available) 后方可创建 TieEnhancer 等推理能力。
//

#ifndef TieSdk_h
#define TieSdk_h

#import <Foundation/Foundation.h>
#import "TieLogger.h"

NS_ASSUME_NONNULL_BEGIN

/// 许可证鉴权状态。业务侧通常只需判断是否为 Available。
typedef NS_ENUM(NSInteger, TieLicenseStatus) {
    TieLicenseStatusUnknown = 0,            ///< 未发起/鉴权中/未识别状态
    TieLicenseStatusInternalErr,            ///< SDK 内部错误
    TieLicenseStatusAppInfoNotMatched,      ///< AppId/签名/BundleId 不匹配
    TieLicenseStatusTrials,                 ///< 试用（视为不可用）
    TieLicenseStatusErrorInvalid,           ///< 授权无效
    TieLicenseStatusDeviceCountExceeded,    ///< 授权设备数超限
    TieLicenseStatusNoAvailableAuth,        ///< 无可用授权
    TieLicenseStatusExpires,                ///< 已过期
    TieLicenseStatusSdkFeatureNotMatched,   ///< SDK 能力与授权不匹配
    TieLicenseStatusSignNotMatched,         ///< 签名不匹配
    TieLicenseStatusDevidNotMatched,        ///< 设备标识不匹配
    TieLicenseStatusUnavailable,            ///< 鉴权未通过（未归类）
    TieLicenseStatusAvailable,              ///< 鉴权成功，可使用推理能力
};

/// 鉴权结果回调。回调线程由内部鉴权流程决定，更新 UI 请自行切主线程。
@protocol TieSdkLicenseCallback <NSObject>
@required
- (void)onTieLicenseResult:(TieLicenseStatus)status;
@end

/// 在线鉴权配置。
@interface TieSdkConfig : NSObject
@property (nonatomic, assign) long appId;                          ///< 腾讯云 AppId
@property (nonatomic, assign) int  authId;                         ///< 授权 Id
@property (nonatomic, weak, nullable) id<TieLogger> logger;        ///< 日志回调（nil → 默认 NSLog）
@property (nonatomic, weak, nullable) id<TieSdkLicenseCallback> callback;  ///< 鉴权结果回调

+ (instancetype)configWithAppId:(long)appId
                         authId:(int)authId
                         logger:(nullable id<TieLogger>)logger
                       callback:(nullable id<TieSdkLicenseCallback>)callback;
@end

@interface TieSdk : NSObject

+ (instancetype)sharedInstance;

/// 在线鉴权（异步，结果经 config.callback 回调，也可轮询 licenseStatus）。
/// 注意：鉴权是异步的，不要在调用后立即检查 licenseStatus 或创建 TieEnhancer。
- (void)initWithConfig:(TieSdkConfig *)config;

/// 当前 license 状态。
@property (nonatomic, readonly) TieLicenseStatus licenseStatus;

/// 释放资源。close 后可再次调用 initWithConfig: 重新鉴权（如切换 AppId）。
- (void)close;

@end

NS_ASSUME_NONNULL_END

#endif /* TieSdk_h */
