# 1 Run the Demo
## 1.1 SDK Authorization Application
Please first activate the Media Processing Service (MPS) [Console](https://console.cloud.tencent.com/mps) on the Tencent Cloud official website. Then follow the [guide](https://cloud.tencent.com/document/product/862/109789) to self-service activate SDK test authorization, obtain the Authorization ID, and bind the App bundle name.    
<img src="./docs/license.png" height="100">    
In Tencent Cloud - Account Center - [Account Information](https://console.cloud.tencent.com/developer), obtain your account's APPID.    
<img src="./docs/APPID.png" height="100">    

## 1.2 Demo Project Compilation and Running
Download the Demo project [source code](./demo/tsr-ios-demo).    
Change the Demo bundle name to the bundle name bound in the previous "SDK Authorization Application" step.    
Rename the Config.xcconfig.example file under the Configurations directory to Config.xcconfig, and modify the file content, filling in the Authorization ID and APPID you obtained.
Then you can compile and run the Demo.

*Note: The SDK version integrated in the Demo project may be outdated. You can contact your Tencent Cloud business representative to obtain the latest version.*

---


# 2 **App SDK Integration**
## 2.1 Add and Configure SDK
Add tsr_client.framework to your Xcode project:
1. Drag tsr_client.framework into the project's Frameworks directory.
2. In the project's **General → Frameworks, Libraries, and Embedded Content**, set tsr_client.framework to **Embed & Sign**.

## **2.2 Using the SDK**
It is recommended to refer to the [MainViewController.m](./demo/tsr-ios-demo/tsr-ios-demo/Modules/MainViewController.m) class and [ProfileViewController.m](./demo/tsr-ios-demo/tsr-ios-demo/Modules/ProfileViewController.m) class in the Demo project for the simplest way to initialize and use the SDK.

Below are detailed descriptions of some interfaces.

<img src=./docs/tsr-work-flow.png width=50% />

### **2.2.1 TSRSdk**
TSRSdk includes the `initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:` and `deInit` methods. The `initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:` method is used to initialize the SDK, and the `deInit` method is used to release resources.
1. To initialize TSRSdk with online authentication, you need to pass in **APPID and AUTH_ID** for authentication. The `initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:` method requires passing in TSRSdkLicenseVerifyResultCallback to obtain the online authentication result. Additionally, you need to pass in a TSRLogger to obtain SDK logs. Below is the example code:
```
- (void)onTSRSdkLicenseVerifyResult:(TSRSdkLicenseStatus)status {
   if (status == TSRSdkLicenseStatusAvailable) {
   // Creating TSRPass for super-resolution rendering
   } else {
   // Do something when the verification of sdk's license failed.
   }
}

// Init TSRSdk and verify the online license
[TSRSdk.getInstance initWithAppId:APPID authId:AUTH_ID sdkLicenseVerifyResultCallback:self tsrLogger:[[Logger alloc] init]];
```

2. When you no longer need to use TSRSdk, you can call the deInit method of TSRSdk.

### **2.2.2 TSRPass**

TSRPass is a class used for super-resolution rendering, which includes `init`, `render`, and `reInit` methods. When creating a TSRPass, you need to pass in `TSRAlgorithmType` to set the super-resolution algorithm type.

In the `TSRAlgorithmType` enumeration, there are four algorithm running modes:
1. **TSRAlgorithmTypeStandard**: Provides fast super-resolution processing speed, suitable for scenes with high real-time requirements. In this mode, significant image quality improvement can be achieved.
2. **TSRAlgorithmTypeStandardColorRetouchingExt**: Optimizes color performance on top of standard super-resolution.
3. **TSRAlgorithmTypeProfessional**: Ensures high image quality while requiring higher device performance. It is suitable for scenes with high image quality requirements and is recommended for use on mid-to-high-end smartphones.
4. **TSRAlgorithmTypeProfessionalColorRetouchingExt**: Optimizes color performance on top of professional super-resolution.

**Note:**
- TSRPass uses the OpenGL framework for super-resolution rendering and requires the device to support OpenGL ES 3.0.
- TSRPass is not thread-safe and must be called in the same thread.
- The Professional algorithm `TSRAlgorithmTypeProfessional` requires iOS system version 16.0 or above to take effect.

Before using TSRPass, you need to call the `initWithTSRAlgorithmType:glContext:inputWidth:inputHeight:srRatio:initStatusCode:` method to initialize.
```objective-c
 TSRInitStatusCode initStatus;

 _tsr_pass_standard = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
 
 _tsr_pass_standard_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
 
 _tsr_pass_professional = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

 _tsr_pass_professional_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessionalColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
```

If you need to adjust the input image dimensions or super-resolution magnification factor during use, you can call the `reInit` method to reinitialize.
```objective-c
// Reinitializing TSRPass with new dimensions and super-resolution ratio
TSRInitStatusCode reInitStatus = [_tsr_pass reInit:newInputWidth inputHeight:newInputHeight srRatio:newSrRatio];
if (reInitStatus == TSRInitStatusCodeSuccess) {
    // Continue with rendering or other operations
} else {
    // Handle reinitialization failure
}
```

* The `render:commandBuffer:` method applies a super-resolution rendering process to the input image, improving its quality. The processed image is rendered onto an MTLTexture within the TSRPass object. The returned value is the MTLTexture that has undergone super-resolution rendering.
```
   _sr_texture = [_tsr_pass render:_in_texture commandBuffer:commandBuffer];
```

* When you no longer need to use TSRPass, you need to call the deInit method of TSRPass to release resources.
```
// Release resources when the TSRPass object is no longer needed.
[_tsr_pass deInit];
```

The TSRPass class also provides interfaces for managing and optimizing the professional super-resolution (Pro SR) functionality during the super-resolution rendering process. Below is a detailed introduction to these four interfaces:

1. **enableProSRAutoFallback:timeoutDurationMs:fallbackListener:**
   This method enables the automatic fallback mechanism for professional super-resolution. You can set the number of consecutive timeout frames (consecutiveTimeoutFrames) and the timeout duration (timeoutDurationMs) so that if the super-resolution processing does not complete within the specified time, it will automatically fall back to the default processing method. Additionally, you can pass in a fallback listener (fallbackListener) to handle callbacks for fallback events. This allows for more flexible handling of performance issues during processing, ensuring a smooth user experience.

2. **disableProSRAutoFallback:**
   This method disables the automatic fallback mechanism for professional super-resolution. If you wish to avoid using the automatic fallback feature during super-resolution processing, you can call this method. This is particularly useful in scenarios where strict control over the rendering process is required.

3. **benchmarkProSR:**
   This method benchmarks professional super-resolution. You need to provide the input image's width (inputWidth), height (inputHeight), and super-resolution ratio (srRatio). The method will return an integer value representing the processing performance at the given size and super-resolution ratio. This can help developers assess processing efficiency under different image sizes and super-resolution settings, thereby optimizing application performance.

4. **forceProSRFallback:**
   This method forcibly enables or disables the fallback functionality of professional super-resolution. By passing a boolean value (enable), you can control whether to enforce the fallback mechanism during processing. This is very useful for debugging and testing scenarios, allowing developers to quickly switch to the default processing method when needed.

These interfaces provide developers with flexible control options to optimize the performance and user experience of super-resolution rendering.

### **2.2.3 TIEPass**
TIEPass is a class used for image enhancement rendering, **only available in the Professional Edition SDK**. It includes `init`, `render`, `renderWithPixelBuffer`, `reInit`, and `deInit` methods. Before using TIEPass, you need to call the `init` method to initialize. When creating a TIEPass, you need to pass in `TIEAlgorithmType` to set the image enhancement algorithm type.

**Note:**
- TIEPass is not thread-safe and must be called in the same thread.
- The Professional algorithm `TIEAlgorithmTypeProfessional` requires iOS system version 16.0 or above to take effect.

* Before using TIEPass, you need to call the `initWithTIEAlgorithmType:algorithmType:glContext:inputWidth:inputHeight:initStatusCode:` method to initialize.

```
 TIEInitStatusCode tieInitStatus;
 
 // STANDARD
_tie_pass_standard = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
 
 // PROFESSIONAL
 _tie_pass_professional = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
```

* If you need to adjust the input image dimensions during use, you can call the `reInit` method to reinitialize.

```objective-c
// Reinitialize TIEPass to adapt to new image dimensions
TIEInitStatusCode reInitStatus = [_tie_pass reInit:newInputWidth inputHeight:newInputHeight];
if (reInitStatus == TIEInitStatusCodeSuccess) {
    // Continue with image processing or other operations
} else {
    // Handle reinitialization failure
}
```

* When you no longer need to use TIEPass, you need to call the deInit method of TIEPass to release resources.
```
// Release resources when the TIEPass object is no longer needed.
[_tie_pass deInit];
```

The TIEPass class also provides interfaces for managing and optimizing the professional image enhancement (Pro IE) functionality during the image enhancement process. Below is a detailed introduction to these four interfaces:

1. **enableProIEAutoFallback:timeoutDurationMs:fallbackListener:**
   This method enables the automatic fallback mechanism for professional image enhancement. You can set the number of consecutive timeout frames (consecutiveTimeoutFrames) and the timeout duration (timeoutDurationMs) so that if the image enhancement processing does not complete within the specified time, it will automatically fall back to the default processing method. Additionally, you can pass in a fallback listener (fallbackListener) to handle callbacks for fallback events. This allows for more flexible handling of performance issues during processing, ensuring a smooth user experience.

2. **disableProIEAutoFallback:**
   This method disables the automatic fallback mechanism for professional image enhancement. If you wish to avoid using the automatic fallback feature during image enhancement processing, you can call this method. This is particularly useful in scenarios where strict control over the image processing workflow is required.

3. **benchmarkProIE:**
   This method benchmarks professional image enhancement. You need to provide the input image's width (inputWidth) and height (inputHeight). The method will return an integer value representing the processing performance at the given size. This can help developers assess processing efficiency under different image sizes, thereby optimizing application performance.

4. **forceProIEFallback:**
   This method forcibly enables or disables the fallback functionality of professional image enhancement. By passing a boolean value (enable), you can control whether to enforce the fallback mechanism during processing. This is very useful for debugging and testing scenarios, allowing developers to quickly switch to the default processing method when needed.

These interfaces provide developers with flexible control options to optimize the performance and user experience of image enhancement.

### **2.2.4 TSRLogger**
TSRLogger is used to receive logs from the SDK internals. Please write these logs to a file for external network problem positioning.

# **3 SDK API Documentation**
You can click on the link to view the TSRSDK API documentation, which contains interface comments and usage examples.

[TSRSDK IOS API Documentation](https://tencentyun.github.io/TSR/ios-docs/latest/index.html)
