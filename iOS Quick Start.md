# Quick Start



## 1.1 SDK Licensing Application



### 1.1.1 Required Authorization Information



Please reach out to your Tencent Cloud business representative to activate TSR service. You will need to provide the following information regarding the app that will integrate the SDK: Tencent Cloud account APPID, APP Bundle Identifier.



Your APPID can be found within your Tencent Cloud "Account Center" -> "Account Information" -> "Basic Information".

The Bundle Identifier can be viewed within an Xcode project by navigating to "TARGETS" -> "General" -> "Identity" -> "Bundle Identifier".



Required Information
<table>
<tr>
<td rowspan="1" colSpan="1" >Info</td>

<td rowspan="1" colSpan="1" >Value</td>
</tr>

<tr>
<td rowspan="1" colSpan="1" >APPID</td>

<td rowspan="1" colSpan="1" >12345678</td>
</tr>

<tr>
<td rowspan="1" colSpan="1" >Bundle Identifier</td>

<td rowspan="1" colSpan="1" >com.tencent.mps.ios-demo</td>
</tr>

<tr>
<td rowspan="1" colSpan="1" >SDK edition</td>

<td rowspan="1" colSpan="1" >Standard Edition/ Professional Edition</td>
</tr>
</table>




The authorization scheme involves application and verification. Application happens once per validity period, allowing online authentication during TSRSDK initialization if the APP has internet access. The service must be renewed upon expiry.



### 1.1.2 Activate the MPS Console



To ensure that the service can be properly authorized, you also need to activate the Media Processing Service (MPS) Console on the Tencent Cloud official website. Activation link:：[https://console.tencentcloud.com/mps](https://console.tencentcloud.com/mps)



## 1.2 Compilation and Execution of the Demo Project

Download the source code of the Demo project [here](https://github.com/tencentyun/TSR/tree/main/demo/tsr-ios-demo).

Alter the demo's Bundle Identifier and provide it to Tencent Cloud business services. Refer to the previous "SDK Authorization Application" steps to acquire the SDK and authorization files, then configure them within the Demo project. The procedure is as follows:


1. Open the project in Xcode and drag the SDK into the project directory. Ensure "Copy items if needed" is selected and verify that "Link Binary With Libraries" includes the SDK.


![ios-demo-step1-1](./docs/ios-demo-step1-1.png)
![ios-demo-step1-2](./docs/ios-demo-step1-2.png)
![ios-demo-step1-3](./docs/ios-demo-step1-3.png)

2. Within the "TARGETS" -> "General" -> "Frameworks, Libraries, and Embedded Content" section, configure the SDK's "Embed" setting to "Embed & Sign".

   ![ios-demo-step2](./docs/ios-demo-step2.png)
3. Drag the certificate into the project directory under tsr-ios-demo, and ensure that "Target Membership" is selected.

4. Fill in the licenseName and appId in VideoPlayViewController.h
   ![ios-demo-step4](./docs/ios-demo-step4.png)
5. Execute the demo.




## 1.3 Demo App Experience

None



# 2 SDK Integration Guide

### 2.1 Program Flow
<img src=./docs/tsr-work-flow.png width=50% />
### 2.1.1 TSRSdk
The TSRSdk encompasses two primary methods: ``initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:`` and ``deInit``. The ``initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:`` method is designated for initializing the SDK, while the ``deInit`` method is employed for the liberation of resources.


1. To initialize the TSRSdk for online authentication, you must provide the APPID and AUTH_ID for authentication purposes. The method ``initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:`` requires the input of `TSRSdkLicenseVerifyResultCallback` to retrieve the results of the online authentication. Additionally, a `TSRLogger` must be supplied to obtain the SDK's logs. Below is the sample code:

   ``` plaintext
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


2. When you no longer require the use of TSRSdk, it is necessary to invoke the deInit method of TSRSdk to release resources.

   ``` plaintext
   // Release resources when the TSRSdk object is no longer needed.
   [TSRSdk.getInstance deInit];
   ```


### 2.1.2 TSRPass

TSRPass is a class designed for executing super-resolution rendering, encompassing methods such as init, and render. Upon creating a TSRPass instance, it is imperative to specify the super-resolution algorithm type by passing in TSRAlgorithmType.

Within the TSRAlgorithmType enumeration, there are four algorithm execution modes: TSRAlgorithmTypeStandard, TSRAlgorithmTypeStandardColorRetouchingExt, TSRAlgorithmTypeProfessional, and TSRAlgorithmTypeProfessionalColorRetouchingExt.


1. **TSRAlgorithmTypeStandard** mode: Provides fast super-resolution processing speed, suitable for scenes with high real-time requirements. In this mode, significant image quality improvement can be achieved.
2. **TSRAlgorithmTypeStandardColorRetouchingExt** mode: The STANDARD mode integrates color retouching functionality on top of super-resolution processing, enhancing visual color performance while preserving real-time capabilities.
3. **TSRAlgorithmTypeProfessional** mode: Ensures faster processing speed while sacrificing some image quality. It is suitable for scenes with high real-time requirements and is recommended for use on mid-range smartphones.
4. **TSRAlgorithmTypeProfessionalColorRetouchingExt** mode: The PROFESSIONAL mode integrates color retouching functionality on top of super-resolution processing, enhancing visual color performance.




Please note:
- TSRPass employs the OpenGL for super-resolution rendering, necessitating devices that support OpenGL ES 3.0.

- TSRPass is not thread-safe and must be invoked within the same thread.

- The Professional version algorithm necessitates an iOS system version of 16.0 or higher to function properly.




Prior to utilizing TSRPass, it is imperative to initialize the system by invoking the method.`initWithTSRAlgorithmType:glContext:inputWidth:inputHeight:srRatio:initStatusCode:`


``` plaintext
 TSRInitStatusCode initStatus;

 _tsr_pass_standard = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
 
 _tsr_pass_standard_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
 
 _tsr_pass_professional = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

 _tsr_pass_professional_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessionalColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
```

If you need to adjust the input image dimensions or the super-resolution magnification factor during use, you can call the `reInit` method to reinitialize.

```objective-c
// Reinitializing TSRPass with new dimensions and super-resolution ratio
TSRInitStatusCode reInitStatus = [_tsr_pass reInit:newInputWidth inputHeight:newInputHeight srRatio:newSrRatio];
if (reInitStatus == TSRInitStatusCodeSuccess) {
    // Continue with rendering or other operations
} else {
    // Handle reinitialization failure
}
```

The ``render:commandBuffer:`` method applies a super-resolution rendering process to the input image, enhancing its quality. The processed image is rendered onto an `MTLTexture` within the `TSRPass` object. The method returns an `MTLTexture` that has undergone super-resolution rendering.


``` plaintext
   _sr_texture = [_tsr_pass render:_in_texture commandBuffer:commandBuffer];
```

When you no longer require the use of TSRPass, it is necessary to invoke the deInit method of TSRPass to release resources.
``` plaintext
// Release resources when the TSRPass object is no longer needed.
[_tsr_pass deInit];
```

The TSRPass class provides interfaces for managing and optimizing the professional super-resolution (Pro SR) functionality during the super-resolution rendering process. Below is a detailed introduction to these four interfaces:

1. **enableProSRAutoFallback:timeoutDurationMs:fallbackListener:**
   This method is used to enable the automatic fallback mechanism for professional super-resolution. You can set the number of consecutive timeout frames (consecutiveTimeoutFrames) and the timeout duration (timeoutDurationMs) so that if the super-resolution processing does not complete within the specified time, it will automatically fall back to the default processing method. Additionally, you can pass in a fallback listener (fallbackListener) to handle callbacks for fallback events. This allows for more flexible handling of performance issues during processing, ensuring a smooth user experience.

2. **disableProSRAutoFallback:**
   This method is used to disable the automatic fallback mechanism for professional super-resolution. If you wish to avoid using the automatic fallback feature during super-resolution processing, you can call this method. This is particularly useful in scenarios where strict control over the rendering process is required.

3. **benchmarkProSR:**
   This method is used to benchmark professional super-resolution. You need to provide the input image's width (inputWidth), height (inputHeight), and super-resolution ratio (srRatio). The method will return an integer value representing the processing performance at the given size and super-resolution ratio. This can help developers assess processing efficiency under different image sizes and super-resolution settings, thereby optimizing application performance.

4. **forceProSRFallback:**
   This method is used to forcibly enable or disable the fallback functionality of professional super-resolution. By passing a boolean value (enable), you can control whether to enforce the fallback mechanism during processing. This is very useful for debugging and testing scenarios, allowing developers to quickly switch to the default processing method when needed.

These interfaces provide developers with flexible control options to optimize the performance and user experience of super-resolution rendering.

### 2.1.3 TIEPass

TIEPass is a class designed for image enhancement rendering, available exclusively in the Professional Edition SDK. It encompasses the methods `init`, `render`, `renderWithPixelBuffer`, `reInit` and `deInit`. Prior to utilizing TIEPass, it is imperative to invoke the `init` method for initialization. When creating TIEPass, it is necessary to input `TIEAlgorithmType` to set the algorithm type for image enhancement.

Within the `TIEAlgorithmType` enumeration, there exist two algorithm execution modes:
1. **TIEAlgorithmTypeStandard**: The standard mode is the basic color enhancement mode, which has corresponding enhancements to brightness, contrast and saturation. The enhancement speed is very fast and is recommended for use on low-to-mid-end smartphones.
2. **TIEAlgorithmTypeProfessional**: The PROFESSIONAL mode ensures high image quality while requiring higher device performance. This mode is suitable for scenarios with high image quality requirements and is recommended for use on mid-to-high-end smartphones.

**Please be advised:**
- TIEPass is not thread-safe and must be invoked within the same thread.
- The Professional Edition algorithm, `TIEAlgorithmTypeProfessional`, necessitates an iOS system version of 16.0 or higher to be operational.

* Before utilizing TIEPass, it is imperative to initialize it by invoking the `initWithTIEAlgorithmType:algorithmType:glContext:inputWidth:inputHeight:initStatusCode:` method.
```objective-c
 TIEInitStatusCode tieInitStatus;
 
 // STANDARD
_tie_pass_standard = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
 
 // PROFESSIONAL
 _tie_pass_professional = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
```

* If you need to adjust the input image dimensions during use, you can call the `reInit` method to reinitialize.
```objective-c

TIEInitStatusCode reInitStatus = [_tie_pass reInit:newInputWidth inputHeight:newInputHeight];
if (reInitStatus == TIEInitStatusCodeSuccess) {
    // Continue with rendering or other operations
} else {
    // Handle reinitialization failure
}
```

* When you no longer require the use of TIEPass, it is necessary to invoke the deInit method of TIEPass to release resources.
``` plaintext
// Release resources when the TIEPass object is no longer needed.
[_tie_pass deInit];
```

The TIEPass class provides interfaces for managing and optimizing the professional image enhancement (Pro IE) functionality during the image enhancement process. Below is a detailed introduction to these four interfaces:

1. **enableProIEAutoFallback:timeoutDurationMs:fallbackListener:**
   This method is used to enable the automatic fallback mechanism for professional image enhancement. You can set the number of consecutive timeout frames (consecutiveTimeoutFrames) and the timeout duration (timeoutDurationMs) so that if the image enhancement processing does not complete within the specified time, it will automatically fall back to the default processing method. Additionally, you can pass in a fallback listener (fallbackListener) to handle callbacks for fallback events. This allows for more flexible handling of performance issues during processing, ensuring a smooth user experience.

2. **disableProIEAutoFallback:**
   This method is used to disable the automatic fallback mechanism for professional image enhancement. If you wish to avoid using the automatic fallback feature during image enhancement processing, you can call this method. This is particularly useful in scenarios where strict control over the image processing workflow is required.

3. **benchmarkProIE:**
   This method is used to benchmark professional image enhancement. You need to provide the input image's width (inputWidth) and height (inputHeight). The method will return an integer value representing the processing performance at the given size. This can help developers assess processing efficiency under different image sizes, thereby optimizing application performance.

4. **forceProIEFallback:**
   This method is used to forcibly enable or disable the fallback functionality of professional image enhancement. By passing a boolean value (enable), you can control whether to enforce the fallback mechanism during processing. This is very useful for debugging and testing scenarios, allowing developers to quickly switch to the default processing method when needed.

These interfaces provide developers with flexible control options to optimize the performance and user experience of image enhancement.

### 2.1.4 TSRLogger



TSRLogger is designed to capture internal logs from the SDK. Please ensure these logs are written to a file to facilitate the troubleshooting of external network issues.





## 3 SDK API Description

You may click the link to access the API documentation for TSRSDK, which includes annotations for the interfaces and examples of how to call them.

[TSRSDK IOS API Document](https://tencentyun.github.io/TSR/ios-docs/latest/index.html)

