//
//  TSRPass.h
//  tsr-client
//
//  Created by Junfeng Gao on 2025/3/20.
//

#ifndef TSRPass_h
#define TSRPass_h

#import <Metal/Metal.h>
#import <AVFoundation/AVFoundation.h>

/*!
 * @brief Enum representing various initialization errors for TSRPass.
 *
 * TSRInitStatusCodeSuccess -> Initialization was successful.
 *
 * TSRInitStatusCodeSDKLicenseStatusNotAvailable -> SDK license verification failed or was not verified, fallback to normal playback.
 *
 * TSRInitStatusCodeAlgorithmTypeInvalid -> The initialization parameter TSRAlgorithmType is invalid, fallback to normal playback.
 *
 * TSRInitStatusCodeMLModelInitFailed -> Machine learning model module initialization failed, fallback to TSRAlgorithmType.STANDARD.
 *
 * TSRInitStatusCodeInputResolutionInvalid -> The input resolution is invalid; it must be between 8 to 4096.
 *
 * TSRInitStatusCodeInternalErr -> Internal error.
 */
typedef NS_ENUM(NSInteger, TSRInitStatusCode) {
    TSRInitStatusCodeSuccess = 0,
    TSRInitStatusCodeSDKLicenseStatusNotAvailable = -10002,
    TSRInitStatusCodeAlgorithmTypeInvalid = -10003,
    TSRInitStatusCodeMLModelInitFailed = -10004,
    TSRInitStatusCodeInputResolutionInvalid = -10006,
    TSRInitStatusCodeInternalErr = -10009,
    TSRInitStatusCodeInvalidParams = -10010,
    TSRInitStatusCodeMetalInitFailed = -10011
};

/*!
 * @brief The TSRAlgorithmType enum value representing the algorithm running mode to be used.
 *
 * TSRAlgorithmTypeStandard -> The TSRAlgorithmTypeStandard mode provides fast super-resolution processing speed, suitable for scenarios with high real-time requirements. It can achieve significant image quality improvements while prioritizing performance.
 *
 * TSRAlgorithmTypeStandardColorRetouchingExt -> The TSRAlgorithmTypeStandard mode integrates color retouching functionality on top of super-resolution processing, enhancing visual color performance while preserving real-time capabilities.
 *
 * TSRAlgorithmTypeProfessional -> The TSRAlgorithmTypeProfessional mode ensures high image quality while requiring higher device performance. It is suitable for scenarios with high image quality requirements and is recommended for use on mid-to-high-end smartphones.
 *
 * TSRAlgorithmTypeProfessionalColorRetouchingExt -> The TSRAlgorithmTypeProfessional mode integrates color retouching functionality on top of super-resolution processing, enhancing visual color performance.
 *
 * TSRAlgorithmTypeProfessionalHighQuality -> See TSRAlgorithmTypeProfessionalColorRetouchingExt.
 */
typedef NS_ENUM(NSInteger, TSRAlgorithmType) {
    TSRAlgorithmTypeStandard = 0,
    TSRAlgorithmTypeStandardColorRetouchingExt = 1,
    TSRAlgorithmTypeProfessional API_AVAILABLE(ios(16.0)) = 2,
    TSRAlgorithmTypeProfessionalColorRetouchingExt API_AVAILABLE(ios(16.0)) = 3,
    TSRAlgorithmTypeProfessionalHighQuality API_AVAILABLE(ios(16.0)) DEPRECATED_ATTRIBUTE = 3 ,
};

/**
 * @class TSRPass
 * @abstract Real-time super-resolution processor with cross-API support
 * @discussion Provides GPU-accelerated image upscaling through Metal and OpenGL ES implementations.
 * Supports dynamic algorithm selection and automatic performance adaptation.
 *
 * <h2>Dual API Architecture</h2>
 * The class provides separate initialization paths for different graphics APIs:
 *
 * - Metal (iOS 12+) : Optimized for Apple silicon with low-overhead architecture
 *
 * - OpenGL ES 3.0 (iOS 10+) : Compatibility layer for legacy systems
 *
 * <b>Critical Usage Requirements:</b><br>
 * 1. API isolation: Do not mix Metal/OpenGL operations on same instance.<br>
 * 2. Thread confinement:<br>
 *    - Metal: All calls must originate from the creating thread.<br>
 *    - OpenGL: Requires consistent EAGLContext across operations.<br>
 * 3. Lifecycle management: Call deInit before releasing resources.<br>
 *
 * <code>
 * // Metal Usage
 *
 * id<MTLDevice> metalDevice = MTLCreateSystemDefaultDevice();
 *
 * TSRPass *metalProcessor = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessional
 *                                                             device:metalDevice
 *                                                         inputWidth:1920
 *                                                        inputHeight:1080
 *                                                           srRatio:2.0
 *                                                    initStatusCode:&status];
 *
 *
 * // OpenGL ES Usage
 *
 * EAGLContext *glContext = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
 *
 * TSRPass *glProcessor = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessional
 *                                                    glContext:glContext
 *                                                    inputWidth:1280
 *                                                   inputHeight:720
 *                                                      srRatio:1.5
 *                                               initStatusCode:&status];
 * </code>
 */
@interface TSRPass: NSObject

typedef void (^FallbackListener)(int, int);

/*!
 * OpenGL ES Initializer for Super-Resolution Render Pass
 *
 * @param algorithmType The TSRAlgorithmType enum value representing the algorithm running mode to be used.
 * @param context OpenGL ES context, must match rendering thread
 * @param inputWidth The width of the input texture. This value should be between 8 to 4096.
 * @param inputHeight The height of the input texture. This value should be between 8 to 4096.
 * @param srRatio The magnification factor for super-resolution.
 * @param initStatusCode A pointer to a TSRInitStatusCode enum value that will store the result of the initialization process. If initialization is successful, the value will be TSRInitStatusCodeSuccess. If initialization fails, the value will indicate the specific error that occurred.
 */
- (instancetype)initWithAlgorithmType:(TSRAlgorithmType)algorithmType glContext:(EAGLContext*)context inputWidth:(int32_t)inputWidth inputHeight:(int32_t)inputHeight srRatio:(float_t)srRatio initStatusCode:(TSRInitStatusCode*)initStatusCode;

/**
 * @brief Dictionary-Configured OpenGL ES Initializer for Super-Resolution Render Pass
 *
 * @discussion Supported configuration parameters:
 *
 * <h2>Basic Parameters (Required)</h2>
 * | Key | Type | Constraints | Default | Required | Description |
 * |-----|------|-------------|---------|----------|-------------|
 * | RenderPassConfig.algorithmType | NSNumber (TSRAlgorithmType) | Valid enum value | None | Required | Algorithm type selection |
 * | RenderPassConfig.inputWidth | NSNumber (int32_t) | 8 ≤ value ≤ 4096 | None | Required | Input texture width in pixels |
 * | RenderPassConfig.inputHeight | NSNumber (int32_t) | 8 ≤ value ≤ 4096 | None | Required | Input texture height in pixels |
 * | RenderPassConfig.srRatio | NSNumber (float) | 1.0 ≤ value ≤ 4.0 | None | Required | Output scale factor (output = input * srRatio) |
 *
 * <h2>Auto Fallback Configuration (Optional)</h2>
 * | Key | Type | Constraints  | Description |
 * |-----|------|-------------|-------------|
 * | AutoFallbackConfig.consecutiveTimeoutFrames | NSInteger | ≥1 |  Consecutive timeout frames threshold |
 * | AutoFallbackConfig.timeoutDurationMs | NSInteger | ≥1 | Timeout duration per frame (milliseconds) |
 * | AutoFallbackConfig.listener | Block | - | Callback format: <code>^(NSInteger width, NSInteger height)</code> |
 *
 * @param config Configuration dictionary containing all required keys
 * @param glContext OpenGL ES context, must match rendering thread
 * @param statusCode Pointer to output initialization status code
 *
 * @note Example:
 *
 * <code>
 * AutoFallbackConfig *fallbackConfig = [AutoFallbackConfig configWithConsecutiveTimeoutFrames:5
 *                                                                     timeoutDurationMs:100
 *                                                                             listener:^(NSInteger w, NSInteger h) {
 *                                                                                 NSLog(@"Fallback: %ldx%ld", w, h);
 *                                                                             }];
 *
 * NSDictionary *config = @{
 *     RenderPassConfig.algorithmType: @(TSRAlgorithmTypeProfessional),
 *     RenderPassConfig.inputWidth: @1920,
 *     RenderPassConfig.inputHeight: @1080,
 *     RenderPassConfig.srRatio: @2.0f,
 *     RenderPassConfig.autoFallbackConfig: fallbackConfig
 * };
 *
 * TSRPass* tsrPass = [[TSRPass alloc] initWithRenderPassConfig:config glContext:context initStatusCode:&initStatus];
 * </code>
 *
 * @see TSRAlgorithmType
 * @see TSRInitStatusCode
 * @see AutoFallbackConfig
 */
- (instancetype)initWithRenderPassConfig:(NSDictionary *)config
                               glContext:(EAGLContext *)glContext
                          initStatusCode:(TSRInitStatusCode *)statusCode;

/*!
 * Metal Initializer for Super-Resolution Render Pass
 *
 * @param algorithmType The TSRAlgorithmType enum value representing the algorithm running mode to be used. It can be either STANDARD (fast mode) or PROFESSIONAL (quality mode). STANDARD mode prioritizes better performance in terms of speed, possibly sacrificing some result accuracy. PROFESSIONAL mode prioritizes result accuracy, even if the running speed is slower.
 * @param device The MTLDevice which you are using.
 * @param inputWidth The width of the input texture. This value should be between 8 to 4096.
 * @param inputHeight The height of the input texture. This value should be between 8 to 4096.
 * @param srRatio The magnification factor for super-resolution.
 * @param initStatusCode A pointer to a TSRInitStatusCode enum value that will store the result of the initialization process. If initialization is successful, the value will be TSRInitStatusCodeSuccess. If initialization fails, the value will indicate the specific error that occurred.
 */
- (instancetype)initWithTSRAlgorithmType:(TSRAlgorithmType)algorithmType device:(id<MTLDevice>)device inputWidth:(int32_t)inputWidth inputHeight:(int32_t)inputHeight srRatio:(float_t)srRatio initStatusCode:(TSRInitStatusCode*)initStatusCode;

/**
 * Reinitializes the TSRPass object for super-resolution rendering. The input parameters specify the new dimensions of the input image to be processed and the new magnification factor for super-resolution.
 *
 * @param inputWidth  The new width of the input image to be processed. This value should be between [8, 4096].
 * @param inputHeight The new height of the input image to be processed. This value should be between [8, 4096].
 * @param srRatio     The new magnification factor for super-resolution. This value should be greater than zero and determines the scaling applied to the input image during the rendering process.
 * @return TSRInitStatusCode enum value that will store the result of the reinitialization process. If reinitialization is successful, the value will be TSRInitStatusCodeSuccess. If reinitialization fails, the value will indicate the specific error that occurred.
 */
- (TSRInitStatusCode) reInit:(int32_t)inputWidth inputHeight: (int32_t)inputHeight srRatio:(float_t)srRatio;

/**
 * Configures and activates the automatic fallback mechanism for super-resolution processing.
 *
 * This method will determine a timeout when the processing time exceeds the specified
 * <code>timeoutDurationMs</code>. If the number of consecutive timeout frames reaches the
 * configured threshold <code>consecutiveTimeoutFrames</code>, an automatic fallback operation
 * will be triggered.
 *
 * The threshold for consecutive timeout frames, <code>consecutiveTimeoutFrames</code>, is
 * dynamically adjusted based on the device's performance. In cases of lower device performance,
 * the threshold will be reduced. The adjustment mechanism is as follows:
 *
 * - If the processing time is twice <code>timeoutDurationMs</code>, the threshold will be
 *   halved.
 * - If the processing time is three times <code>timeoutDurationMs</code>, the threshold will
 *   be reduced to one-third, and so on.
 *
 * The minimum value for the threshold is 2. The formula for updating the threshold is:
 * <code>consecutiveTimeoutFrames = max(2, consecutiveTimeoutFrames / (timeConsume / timeoutDurationMs))</code>,
 * where <code>timeConsume</code> is the actual processing time.
 *
 * Through this dynamic adjustment mechanism, the system can flexibly respond to the current
 * performance conditions, ensuring stability and efficiency during the processing.
 *
 * @param consecutiveTimeoutFrames The initial threshold for the number of consecutive timeout frames
 *                                 before triggering a fallback. This value may be dynamically adjusted
 *                                 based on performance.
 * @param timeoutDurationMs The duration in milliseconds that defines the timeout threshold for
 *                          processing. If processing exceeds this duration, it is considered a timeout.
 * @param fallbackListener The listener that will be notified when a fallback event occurs. The listener's
 *                        <code>onFallback</code> method will be called with the relevant parameters.
 */
- (void) enableProSRAutoFallback:(int)consecutiveTimeoutFrames timeoutDurationMs:(int)timeoutDurationMs fallbackListener:(FallbackListener)fallbackListener;

/**
 * Disables the automatic fallback behavior for the super-resolution process.
 * This method should be called to turn off the automatic fallback feature that was previously enabled
 * using `enableProSRAutoFallback:timeoutDurationMs:fallbackListener:`. Once this method is invoked,
 * the system will no longer trigger a fallback based on the configured parameters.
 */
- (void) disableProSRAutoFallback;

/**
 * Evaluates the rendering time consumption of the PROFESSIONAL algorithm.
 * This method assesses the execution time in milliseconds for the PROFESSIONAL algorithm based on the given
 * input dimensions. This method should not be called on the main thread, as it may take approximately
 * 2 to 5 seconds to complete.
 *
 * This method only takes effect if the `TSRAlgorithmType` used to init the `TSRPass` is not set to
 * `TSRAlgorithmTypeStandard`. If the algorithm type is STANDARD, the evaluation may not be applicable.
 * If the execution of the algorithm fails for any reason, this method will return -1.
 *
 * @param inputWidth The width of the input.
 * @param inputHeight The height of the input.
 * @param srRatio The super-resolution ratio.
 * @return The estimated rendering time consumption in milliseconds, or -1 if the execution fails.
 */
- (int) benchmarkProSR:(int)inputWidth inputHeight: (int)inputHeight srRatio: (float)srRatio;

/**
 * Switches between the PROFESSIONAL and STANDARD algorithms.
 * This method enables or disables the use of the STANDARD algorithm. When `enable` is true, the system
 * will switch to the STANDARD algorithm; otherwise, it will use the PROFESSIONAL algorithm.
 * This method only takes effect if the `TSRAlgorithmType` used to init the `TSRPass` is not set to
 * `TSRAlgorithmTypeStandard`.
 *
 * @param enable `true` to switch to the STANDARD algorithm; `false` to use the PROFESSIONAL algorithm.
 */
- (void) forceProSRFallback:(bool)enable;

/*!
 * This method performs super-resolution rendering on the input image using OpenGL ES.
 * <br>
 * This method applies the super-resolution rendering process to the input image, improving its quality. The processed image is rendered onto a OpenGL texture within
 * the TSRPass object. And the return is the OpenGL texture which has preformed super-resolution rendering .
 * <br>
 *
 * @param texture The OpenGL texture of the input image that needs to be processed for super-resolution.
 * @return The OpenGL texture that will be performed super-resolution rendering, which is stored inside the TSRPass
 * object. The size of the output texture is (inputWidth * srRatio, inputHeight * srRatio).
 */
- (int)render:(int)texture;

/*!
 * This method performs super-resolution rendering on the input image using Metal.
 * <br>
 * This method applies the super-resolution rendering process to the input image, improving its quality. The processed image is rendered onto a MTLTexture within
 * the TSRPass object. And the return is the MTLTexture which has preformed super-resolution rendering .
 * <br>
 *
 * @param texture The MTLTexture of the input image that needs to be processed for super-resolution.
 * @param commandBuffer The MTLCommandBuffer which you are using.
 * @return The MTLTexture that will be performed super-resolution rendering, which is stored inside the TSRPass
 * object. The size of the output texture is (inputWidth * srRatio, inputHeight * srRatio).
 */
- (id<MTLTexture>)render:(id<MTLTexture>)texture commandBuffer:(id<MTLCommandBuffer>)commandBuffer;

/*!
 * This method performs the image enhance rendering operation on the input pixel buffer.
 *
 * This method applies the image enhance rendering process to the input pixel buffer, improving its quality. The processed pixel buffer is created and returned as a new CVPixelBufferRef.
 *
 * @note The SDK internally handles the lifecycle of the returned CVPixelBufferRef, therefore, the caller absolutely should not attempt to manually release the returned CVPixelBufferRef.
 *
 * @param pixelBuffer The CVPixelBufferRef of the input image that needs to be processed for image enhance. The width and height of the pixel buffer must match the inputWidth and inputHeight set during initialization, and the pixel format must be BGRA.
 * @return The CVPixelBufferRef that has been performed image enhance rendering. The size of the output pixel buffer is (inputWidth, inputHeight).
 */
- (CVPixelBufferRef)renderWithPixelBuffer:(CVPixelBufferRef)pixelBuffer;

/**
 * Releases the resources.
 * <br>
 * This method should be called when the TSRPass object is no longer needed, to free up the memory and other resources.
 */
- (void)deInit;

- (void)setParametersWithBrightness:(float)brightness saturation:(float)saturation contrast:(float)contrast sharpness:(float)sharpness DEPRECATED_ATTRIBUTE;

@end

#endif /* TSRPass_h */
