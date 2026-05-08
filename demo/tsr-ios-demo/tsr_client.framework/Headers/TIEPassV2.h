//
//  TIEPassV2.h
//  tsr-client
//
//  V2: CoreML-based image enhancement (no ie_model_invoker_ dependency)
//  Uses TSRSDK-Demo inference pattern with full authentication support.
//

#ifndef TIEPassV2_h
#define TIEPassV2_h

#import <Metal/Metal.h>
#import <CoreVideo/CoreVideo.h>
#import "TIEPass.h"
#import "TSRSdk.h"

#pragma mark - License Verification Callback

/*!
 * @protocol TIEPassV2LicenseCallback
 * @brief Callback protocol for TIEPassV2 license verification result.
 */
@protocol TIEPassV2LicenseCallback <NSObject>
@required
/*!
 * Called when the license verification process completes.
 * @param status The license verification result status.
 */
- (void)onTIEPassV2LicenseVerifyResult:(TSRSdkLicenseStatus)status;
@end

#pragma mark - TIEPassV2

@interface TIEPassV2 : NSObject

/*!
 * @property colorEnhanceEnabled
 * @brief Enable/disable color-enhance post-processing shader (edge enhancement + color adjustments).
 *
 * When enabled, the output of renderWithPixelBuffer: will be a BGRA-format CVPixelBuffer
 * (after color-enhance shader post-processing). When disabled, the output remains NV12 format
 * (CoreML Y-channel enhancement only).
 *
 * Default value is NO.
 */
@property (nonatomic, assign) BOOL colorEnhanceEnabled;


/*!
 * @brief Initialize TIEPassV2 with an externally provided MTLDevice.
 *
 * Use this initializer when the caller already has a MTLDevice (e.g., from a Metal rendering pipeline).
 * The caller must separately initialize TSRSdk before using this initializer.
 *
 * @param device      The MTLDevice to use (must not be nil).
 * @param inputWidth  The width of the input image (8~4096).
 * @param inputHeight The height of the input image (8~4096).
 * @param initStatusCode Output status code.
 */
- (instancetype)initWithDevice:(id<MTLDevice>)device
                    inputWidth:(int32_t)inputWidth
                   inputHeight:(int32_t)inputHeight
                initStatusCode:(TIEInitStatusCode *)initStatusCode;

/*!
 * @brief Initialize TIEPassV2 with an internally created default MTLDevice.
 *
 * Use this initializer when the caller does not have a MTLDevice.
 * Internally calls MTLCreateSystemDefaultDevice() to obtain the device.
 * The caller must separately initialize TSRSdk before using this initializer.
 *
 * @param inputWidth  The width of the input image (8~4096).
 * @param inputHeight The height of the input image (8~4096).
 * @param initStatusCode Output status code.
 */
- (instancetype)initWithInputWidth:(int32_t)inputWidth
                       inputHeight:(int32_t)inputHeight
                    initStatusCode:(TIEInitStatusCode *)initStatusCode;

/// Render with CVPixelBuffer input (NV12 format: 420YpCbCr8BiPlanarVideoRange).
/// Performs CoreML Y-channel enhancement and writes enhanced Y + original CbCr to output.
///
/// Supports two operating modes:
/// - **Full mode** (CoreML model loaded): CoreML Y-channel inference + optional shader post-processing.
/// - **Shader-only mode** (CoreML model failed, initStatusCode == TIEInitStatusCodeMLModelInitFailedShaderOnly):
///   Skips CoreML inference; when colorEnhanceEnabled=YES, applies shader directly to the input NV12 buffer.
///
/// @return Enhanced output pixel buffer (caller is responsible for releasing if it differs from input).
///         - When colorEnhanceEnabled=NO (full mode): output is NV12 format.
///         - When colorEnhanceEnabled=YES: output is BGRA format (after color-enhance shader post-processing).
///         - Returns the original pixelBuffer if enhancement is not available (do NOT release in that case).
- (CVPixelBufferRef)renderWithPixelBuffer:(CVPixelBufferRef)pixelBuffer;

/// Release all resources (including TSRSdk if initialized internally).
- (void)deInit;

@end

#endif /* TIEPassV2_h */
