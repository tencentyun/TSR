//
//  TSRSdk.h
//  tsr-client
//
//  Created by 高峻峰 on 2024/1/23.
//

#ifndef TSRSDK_IOS_TSRSDK_H
#define TSRSDK_IOS_TSRSDK_H

#import <Foundation/Foundation.h>
#import <tsr_client/TSRLogger.h>

NS_ASSUME_NONNULL_BEGIN

/*!
 * @brief Enum representing the status of an SDK license.
 */
typedef NS_ENUM(NSInteger, TSRSdkLicenseStatus) {
    /*!
     * init status
     */
    TSRSdkLicenseStatusUnknown,

    /*!
     * internal operating logic error
     */
    TSRSdkLicenseStatusInternalErr,

    /*!
     * app signature or packagename(android)、bundleId(macos/ios) not matched.
     * the license verification matches the app information and the SDK built-in
     * information before the authorization verification can be performed.
     */
    TSRSdkLicenseStatusAppInfoNotMatched,

    /*!
     *  net abort, trials
     */
    TSRSdkLicenseStatusTrials,

    /*!
     * license server verify failed
     * invalid appid/sdkid， or disabled
     */
    TSRSdkLicenseStatusErrorInvalid,

    /*!
     * the number of license authorized devices exceeds
     */
    TSRSdkLicenseStatusDeviceCountExceeded,

    /*!
     * license no available for user req
     * Request authorization license online,
     * no available license is available.
     */
    TSRSdkLicenseStatusNoAvailableAuth,

    /*!
     * license expires
     */
    TSRSdkLicenseStatusExpires,

    /*!
     * license certificate policy sdk version information does not match
     */
    TSRSdkLicenseStatusSdkFeatureNotMatched,

    /*!
     * license binding certificate policy signature information does not match
     */
    TSRSdkLicenseStatusSignNotMatched,

    /*!
     * License binding certificate policy devid information does not match
     */
    TSRSdkLicenseStatusDevidNotMatched,

    /*!
     * license verify failed
     * The certificate information does not match, is modified,
     * or the certificate format is incorrect.
     */
    TSRSdkLicenseStatusUnavailable,

    /*!
     * verify successful
     */
    TSRSdkLicenseStatusAvailable
};

#pragma mark --- This interface represents the sdk verification's status callbacks of the TsrSdk. ---
/*!
 * TSRSdkLicenseVerifyResultCallback is a protocal for handling the result of TSRSDK license verification.
 * Implement this protocal to define custom actions that should be executed when the license verification process
 * is completed.
 */
@protocol TSRSdkLicenseVerifyResultCallback <NSObject>
@required
/*!
 * This method is called when the TSRSDK license verification process is completed.
 *
 * @param status The TSRSdkLicenseStatus code of the license verification result.
 */
- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status;
@end

/*!
 * TSRSdk is a class responsible for managing and verifying the license of the super-resolution SDK.
 * The class provides methods for initializing the SDK with online license verification,
 * and releasing the resources when the SDK is no longer needed. It also allows setting a custom
 * logger for logging purposes.
 * <p><b>Usage:</b></p>
 * 1. Get an instance of TSRSdk using the getInstance method.
 * 2. Initialize the TSRSdk object with online license verification using the initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger: methods.
 * 3. Perform super-resolution rendering using the TSRPass class.
 * 4. Release resources when the TSRSdk object is no longer needed by calling the deInit method.
 *
 * <p>Example for online verification:</p>
 * <pre>
 * <code>
 * - (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
 *      if (status == TSRSdkLicenseStatusAvailable) {
 *          // Creating TSRPass for super-resolution rendering
 *      } else {
 *          // Do something when the verification of sdk's license failed.
 *      }
 *  }
 *  
 * // Init TSRSdk and verify the online license
 * [TSRSdk.getInstance initWithAppId:APPID authId:AUTH_ID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
 * // Release resources when the TSRSdk object is no longer needed.
 * [TSRSdk.getInstance deInit];
 * </code>
 * </pre>
 */
@interface TSRSdk : NSObject

+ (instancetype)getInstance;

/*!
 * Initializes the TSRSdk with online license verification.
 *
 * @param appId Tencent Cloud account's APPID, You can click on the link https://github.com/tencentyun/TSR/blob/main/%E6%8E%A5%E5%85%A5%E6%8C%87%E5%8D%97.md  to learn how to find the APPID.
 * @param authId The authentication ID of the TSRSDK. You can contact the Tencent Cloud MPS Team to get the authentication ID.
 * @param sdkLicenseVerifyResultCallback SdkLicenseVerifyResultCallback is an interface for handling the result of SDK license verification.
 * @param tsrLogger Set a TSRLogger for the TSRSdk. The TSRLogger must not be null.
 */
- (void)initWithAppId:(long)appId authId:(int)authId sdkLicenseVerifyResultCallback:(id<TSRSdkLicenseVerifyResultCallback>)sdkLicenseVerifyResultCallback tsrLogger:(id<TSRLogger>)tsrLogger;

/*!
 * Initializes the TSRSdk with offline license verification.
 *
 * @param appId Tencent Cloud account's APPID, You can click on the link https://github.com/tencentyun/TSR/blob/main/%E6%8E%A5%E5%85%A5%E6%8C%87%E5%8D%97.md  to learn how to find the APPID.
 * @param licenseUrl The url of the license
 * @param tsrLogger Set a TSRLogger for the TSRSdk. The TSRLogger must not be null.
 * @return The result of the verification of sdk's license.
 */
- (TSRSdkLicenseStatus)initWithAppId:(long)appId licenseUrl:(NSURL *)licenseUrl tsrLogger:(id<TSRLogger>)tsrLogger;

/*!
 * Releases the resources associated with the TSRSdk instance.
 * <p>
 * This method should be called when the TSRSdk instance is no longer needed, to free up memory and other
 * resources.
 * </p>
 * <p>
 * After calling this method, you must re-initialize the TSRSdk instance using the initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger: methods before using it again.
 * </p>
 */
-(void)deInit;

@end

NS_ASSUME_NONNULL_END

#endif /* TsrSdk_h */
