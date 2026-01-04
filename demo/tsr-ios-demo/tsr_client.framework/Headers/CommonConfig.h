//  CommonConfig.h
//  tsr-client
//
//  Created by Junfeng Gao on March 19, 2025.
//

#ifndef CommonConfig_h
#define CommonConfig_h

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

#pragma mark - Auto Fallback Configuration

/**
 * @class AutoFallbackConfig
 * @brief Configuration for automatic fallback strategy
 * @discussion Implements NSCopying and NSSecureCoding protocols for safe data handling
 */
@interface AutoFallbackConfig : NSObject <NSCopying, NSSecureCoding>

/// Minimum consecutive timeout frames threshold (Default: 1)
@property (nonatomic, assign) NSInteger consecutiveTimeoutFrames;

/// Single frame timeout duration in milliseconds (Minimum: 1ms)
@property (nonatomic, assign) NSInteger timeoutDurationMs;

/// Fallback event callback block
@property (nonatomic, copy, nullable) void (^listener)(NSInteger width, NSInteger height);

/**
 * @brief Creates pre-validated configuration instance
 * @param consecutiveTimeoutFrames Number of consecutive timeout frames triggering fallback
 * @param timeoutDurationMs Timeout duration per frame in milliseconds
 * @param listener Callback executed when fallback occurs
 */
+ (instancetype)configWithConsecutiveTimeoutFrames:(NSInteger)consecutiveTimeoutFrames
                               timeoutDurationMs:(NSInteger)timeoutDurationMs
                                       listener:(nullable void (^)(NSInteger, NSInteger))listener;

@end

#pragma mark - Render Configuration Keys

/**
 * @class RenderPassConfig
 * @brief Centralized management for render pass configuration keys
 */
@interface RenderPassConfig : NSObject

/// @return NSString* Algorithm type key (Required)
+ (NSString *)algorithmType;

/// @return NSString* Input width key (Required, Valid range: 64-4096)
+ (NSString *)inputWidth;

/// @return NSString* Input height key (Required, Valid range: 64-4096)
+ (NSString *)inputHeight;

/// @return NSString* Super-resolution scale factor key (Optional, Valid range: 1.0-4.0)
+ (NSString *)srRatio;

/// @return NSString* Auto fallback configuration key (Optional)
+ (NSString *)autoFallbackConfig;

@end

NS_ASSUME_NONNULL_END

#endif /* CommonConfig_h */
