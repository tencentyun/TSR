# **1 Quick Start**
## 1.1 **SDK Authorization Application**
### 1.1.1 **Information required for authorization**
Please contact your Tencent Cloud business to open the service. You need to provide the following information of the App that will integrate the SDK: Tencent Cloud account APPID, App signature certificate information (serial number, publisher, and owner of the signature certificate), App package name.

APPID can be found in your Tencent Cloud【Account Center】->【Account Information】->【Basic Information】.
App signature certificate information can be viewed using the keytool command, for example:
```keytool -list -v -keystore test.keystore```

![cert.png](./docs/cert.png)

In addition, you also need to inform the SDK version to be accessed. The SDK version is divided into standard and professional editions:
* Standard Edition: Provides standard super-resolution function, achieves fast super-resolution processing speed, suitable for scenes with high real-time requirements. In this mode, significant image quality improvement can be achieved.
* Professional Edition: The functions provided include standard super-resolution, professional super-resolution, and professional image enhancement. Professional super-resolution and image enhancement are suitable for scenes with high-quality requirements, but with certain requirements for device performance, it is recommended to use on mid-to-high-end smartphones.

Information provided
| Information | Value |
| :- | :- |
| APPID | 12345678 |
| Package name | com.tencent.mps.srplayer |
| Serial number | 17ccecf2 |
| Owner | test |
| Publisher | test |
| SDK version | Standard Edition/Professional Edition |

The authorization scheme is divided into two processes: authorization application and authorization verification. The authorization application will only be performed once during the authorization validity period. After the authorization service is opened, you can use the online method for authentication when initializing TSRSDK. The APP needs to have access to the network. The authorization service has a validity period. When the authorization expires, it needs to re-obtain the authorization.

### 1.1.2 **Open MPS Console**
In order for the service to authorize normally, you also need to open the【Media Processing (MPS) Console】on the Tencent Cloud official website. Open link: https://console.cloud.tencent.com/mps

## 1.2 **Demo Project Compilation and Running**

Download the [source code](https://github.com/tencentyun/TSR/tree/offline-verification/demo/tsr-android-demo) of the Demo project.

Configure the SDK and authorization file obtained in the "SDK Authorization Application" step into the Demo project. The operation is as follows:

1. Put the SDK in the ./SRPlayer/app/libs folder of the project.

2. Configure the initialization parameters under MainActivity.java, and APPID of Tencent Cloud is required for verification initialization.

   ![verification-params.png](./docs/verification-params.png)

3. Run the demo

## **1.3 Demo App Experience**
The following is the compiled App installation package of the Demo project, which can be [downloaded](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-demo-android/SRPlayer.apk) and installed directly for experience.

<img src=./docs/android-demo-qrcode.png width=16% />

# **2 SDK Access Guide**
## **2.1 Add permissions to the App project**
``` 
<uses-permission android:name="android.permission.INTERNET"/>

// If Android targetSdkVersion is greater than or equal to 31, you need to add the following tags, otherwise the professional version features will not be available
 <application>
     <uses-native-library
         android:name="libOpenCL.so"
         android:required="false" />

     <uses-native-library
         android:name="libOpenCL-car.so"
         android:required="false" />

     <uses-native-library
         android:name="libOpenCL-pixel.so"
         android:required="false" />
 </application>
```

## **2.2 Program Flow**
<img src=./docs/tsr-work-flow.png width=50% />

### **2.2.1 TSRSdk**
[TSRSdk](https://tencentyun.github.io/TSR/android-docs/1.12/com/tencent/mps/tie/api/TSRSdk.html) includes init and deInit methods. The init method is used to initialize the SDK, and the deInit method is used to release resources.

1. To initialize the TSRSdk for online authentication, you need to pass in the APPID and AUTH_ID for online authorization, and also pass in the TSRSdk.TSRSdkLicenseVerifyResultCallback to obtain the results of online authentication. In addition, you need to pass in a TSRLogger to obtain the SDK logs. Here is an example code:
```
    TSRSdkLicenseVerifyResultCallback callback = new TSRSdkLicenseVerifyResultCallback() {
    public void onTSRSdkLicenseVerifyResult(TSRSdkLicenseStatus status) {
        if (status == TSRSdkLicenseStatus.AVAILABLE) {
           // Creating TSRPass for super-resolution rendering
        } else {
           // Do something when the verification of sdk's license failed.
        }
    }
  };
  TSRSdk.getInstance().init(context, appId, authId, callback, logger);
```

2. When you no longer need to use TSRSdk, you need to call the deInit method of TSRSdk to release resources. <font color="red">**Note: Before calling the deInit method of TSRSdk, make sure that all TSRPasses have released resources, otherwise unexpected problems may occur.**</font>
```
  // If you have created TSRPass, you should release it before release TSRSdk.
  tsrPass.deInit();
  // Release resources when the TSRSdk object is no longer needed.
  TSRSdk.getInstance().deInit();
```

### **2.2.2 TSRPass**
[TSRPass](https://tencentyun.github.io/TSR/android-docs/1.12/com/tencent/mps/tie/api/TSRPass.html) is a class used for super-resolution rendering. When creating a TSRPass, you need to pass in TSRAlgorithmType to set the super-resolution algorithm type.

**Note: TSRPass is not thread-safe, and the methods of TSRPass must be called in the same thread.**

In the TSRAlgorithmType enumeration, there are STANDARD, STANDARD_COLOR_RETOUCHING_EXT, PROFESSIONAL, and PROFESSIONAL_COLOR_RETOUCHING_EXT four algorithm running modes:
1. **STANDARD** mode: Provides fast super-resolution processing speed, suitable for scenes with high real-time requirements. In this mode, significant image quality improvement can be achieved.
2. **STANDARD_COLOR_RETOUCHING_EXT** mode: The STANDARD mode integrates color retouching functionality on top of super-resolution processing, enhancing visual color performance while preserving real-time capabilities.
3. **PROFESSIONAL** mode: Ensures faster processing speed while sacrificing some image quality. It is suitable for scenes with high real-time requirements and is recommended for use on mid-range smartphones.
4. **PROFESSIONAL_COLOR_RETOUCHING_EXT** mode: The PROFESSIONAL mode integrates color retouching functionality on top of super-resolution processing, enhancing visual color performance.
The class includes `init`, `reInit`, `render`, and `deInit` methods. Before using TSRPass, you need to call the `init` method to initialize. If you need to update the input image dimensions or scaling factor without creating a new TSRPass instance, you can use the `reInit` method. After using it, you need to call the `deInit` method to release resources.


The following is an example of using STANDARD super-resolution algorithm code:
```
// Create a TSRPass object using the constructor.
TSRPass tsrPass = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);

// The code below must be executed in the same glThread.
//----------------------GL Thread---------------------//

// Initialize TSRPass and set the input image width, height, and srRatio.
TSRPass.TSRInitStatusCode initStatus = tsrPass.init(inputWidth, inputHeight, srRatio);

if (initStatus == TSRPass.TSRInitStatusCode.SUCCESS) {

   // Perform super-resolution rendering and get the enhanced texture ID.
   int outputTextureId = tsrPass.render(inputTextureId);

   // Reinitialize if there are changes in image dimensions or srRatio.
   TSRPass.TSRInitStatusCode reInitStatus = tsrPass.reInit(newInputWidth, newInputHeight, newSrRatio);
   if (reInitStatus == TSRPass.TSRInitStatusCode.SUCCESS) {
      outputTextureId = tsrPass.render(inputTextureId);
   } else {
      // Handle reinitialization failure
   }

   // Release resources when no longer needed.
   tsrPass.deInit();
} else {
   // Handle initialization failure
}

//----------------------GL Thread---------------------//
```

The following is an example of using PROFESSIONAL super-resolution algorithm code:
```
// Create a TSRPass object with the desired algorithm type.
TSRPass tsrPass = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL);

// Before initializing the TSRPass, configure the maximum input resolution for super-resolution processing.
// This configuration step is crucial as it helps to allocate memory and optimize performance.
// Here, we set the maximum resolution to 1920x1920 pixels.
TSRPass.TSRInitStatusCode configStatus = tsrPass.configureProSRMaxInputResolution(1920, 1920);

// The code below must be executed in the same glThread.
//----------------------GL Thread---------------------//

// Initialize TSRPass with the specified parameters.
TSRPass.TSRInitStatusCode initStatus = tsrPass.init(inputWidth, inputHeight, srRatio);

if (initStatus == TSRPass.TSRInitStatusCode.SUCCESS) {
   // Perform super-resolution rendering and get the enhanced texture ID.
   int outputTextureId = tsrPass.render(inputTextureId);

   // Reinitialize if there are changes in image dimensions or srRatio.
   TSRPass.TSRInitStatusCode reInitStatus = tsrPass.reInit(newInputWidth, newInputHeight, newSrRatio);
   if (reInitStatus == TSRPass.TSRInitStatusCode.SUCCESS) {
      outputTextureId = tsrPass.render(inputTextureId);
   } else {
      // Handle reinitialization failure
   }

   // Release resources when no longer needed.
   tsrPass.deInit();
} else {
   // Handle initialization failure
}

//----------------------GL Thread---------------------//
```

The TSRPass class provides interfaces for managing and optimizing the professional super-resolution (Pro SR) functionality during the super-resolution rendering process. Below is a detailed introduction to these interfaces:

1. **enableProSRAutoFallback(int consecutiveTimeoutFrames, int timeoutDurationMs, FallbackListener listener):**
   This method enables the automatic fallback mechanism for the super-resolution process and sets the corresponding parameters. This method should be called before invoking the initialization method. It configures the parameters for automatic fallback; if the number of consecutive timeout frames exceeds the specified consecutiveTimeoutFrames, the system will trigger a fallback. Note that this method only takes effect if the algorithm type used to create the TSRPass is not set to STANDARD. Additionally, a fallback listener can be provided to handle fallback events. When a fallback is triggered, the fallback listener's onFallback() method will be called, allowing the user to implement custom behavior in response to the fallback event.

2. **disableProSRAutoFallback():**
   This method disables the automatic fallback mechanism for the super-resolution process. This method should be called to turn off the automatic fallback feature that was previously enabled using enableProSRAutoFallback. Once this method is invoked, the system will no longer trigger a fallback based on the configured parameters.

3. **benchmarkProSR(int inputWidth, int inputHeight, float srRatio):**
   This method evaluates the rendering time consumption of the PROFESSIONAL algorithm. It assesses the execution time in milliseconds for the PROFESSIONAL algorithm based on the given input dimensions. This method should not be called on the main thread, as it may take approximately 2 to 5 seconds to complete. This method only takes effect if the algorithm type used to create the TSRPass is not set to STANDARD. If the execution of the algorithm fails for any reason, this method will return -1.

4. **forceProSRFallback(boolean enable):**
   This method switches between the PROFESSIONAL and STANDARD algorithms. When enable is true, the system will switch to the STANDARD algorithm; otherwise, it will use the PROFESSIONAL algorithm. This method only takes effect if the algorithm type used to create the TSRPass is not set to STANDARD.

These interfaces provide developers with flexible control options to optimize the performance and user experience of super-resolution rendering.

### **2.2.3 TIEPass**
[TIEPass](https://tencentyun.github.io/TSR/android-docs/1.12/com/tencent/mps/tie/api/TIEPass.html) is a class used for image enhancement rendering, **only available in the Professional Edition SDK**. When creating a TIEPass, you need to pass in TIEAlgorithmType to set the image enhancement algorithm type. It includes `init`, `reInit`, `render`, and `deInit` methods. Before using TIEPass, you need to call the `init` method to initialize. If you need to update the input image dimensions without creating a new TIEPass instance, you can use the `reInit` method. After using it, you need to call the `deInit` method to release resources.

In the TIEAlgorithmType enumeration, there are two algorithm running modes:
1. **STANDARD**: The standard mode is the basic color enhancement mode, which has corresponding enhancements to brightness, contrast and saturation. The enhancement speed is very fast and is recommended for use on low-to-mid-end smartphones.
2. **PROFESSIONAL**: The PROFESSIONAL mode ensures high image quality while requiring higher device performance. This mode is suitable for scenarios with high image quality requirements and is recommended for use on mid-to-high-end smartphones. Configuration requirements: The device must have total RAM >= 4GB and available RAM >= 512MB; otherwise, it will revert to the STANDARD version of the algorithm.

**Note: TIEPass is not thread-safe, and TIEPass methods must be called in the same thread.**

The following is a code example:

```
// Create a TIEPass object using the constructor.
TIEPass tiePass = new TIEPass(TIEPass.TIEAlgorithmType.PROFESSIONAL);

// Before initializing the TIEPass, configure the maximum input resolution for super-resolution processing.
// This configuration step is crucial as it helps to allocate memory and optimize performance.
// Here, we set the maximum resolution to 1920x1920 pixels.
TIEInitStatusCode configStatus = tiePass.configureProIEMaxInputResolution(1920, 1920);


// The code below must be executed in the same glThread.
//----------------------GL Thread---------------------//

// Initialize TIEPass and set the input image width and height.
TIEPass.TIEInitStatusCode initStatus = tiePass.init(inputWidth, inputHeight);

if (initStatus == TIEPass.TIEInitStatusCode.SUCCESS) {
   // If the type of inputTexture is TextureOES, you must transform it to Texture2D.
   // Conversion code can be written according to actual requirements.
   
   // Perform image enhancement rendering on the input OpenGL texture and get the enhanced texture ID.
   int outputTextureId = tiePass.render(inputTextureId);
   
   // Reinitialize with new dimensions if needed.
   TIEPass.TIEInitStatusCode reInitStatus = tiePass.reInit(newInputWidth, newInputHeight);
   if (reInitStatus == TSRPass.TSRInitStatusCode.SUCCESS) {
      outputTextureId = tiePass.render(inputTextureId);
   } else {
      // Handle reinitialization failure
   }

   // Release resources when the TIEPass object is no longer needed.
   tiePass.deInit();
} else {
   // Handle initialization failure
}

//----------------------GL Thread---------------------//
```

The TIEPass class provides interfaces for managing and optimizing the professional image enhancement (Pro IE) functionality during the image enhancement process. Below is a detailed introduction to these interfaces:

1. **enableProIEAutoFallback(int consecutiveTimeoutFrames, int timeoutDurationMs, FallbackListener listener):**
   This method enables the automatic fallback mechanism for the image enhancement process and sets the corresponding parameters. This method should be called before invoking the initialization method. It configures the parameters for automatic fallback; if the number of consecutive timeout frames exceeds the specified consecutiveTimeoutFrames, the system will trigger a fallback. Note that this method only takes effect if the algorithm type used to create the TIEPass is not set to STANDARD. Additionally, a fallback listener can be provided to handle fallback events. When a fallback is triggered, the fallback listener's onFallback() method will be called, allowing the user to implement custom behavior in response to the fallback event.

2. **disableProIEAutoFallback():**
   This method disables the automatic fallback mechanism for the image enhancement process. This method should be called to turn off the automatic fallback feature that was previously enabled using enableProIEAutoFallback. Once this method is invoked, the system will no longer trigger a fallback based on the configured parameters.

3. **benchmarkProIE(int inputWidth, int inputHeight):**
   This method evaluates the rendering time consumption of the PROFESSIONAL algorithm. It assesses the execution time in milliseconds for the PROFESSIONAL algorithm based on the given input dimensions. This method should not be called on the main thread, as it may take approximately 2 to 5 seconds to complete. This method only takes effect if the algorithm type used to create the TIEPass is not set to STANDARD. If the execution of the algorithm fails for any reason, this method will return -1.

4. **forceProIEFallback(boolean enable):**
   This method switches between the PROFESSIONAL and STANDARD algorithms. When enable is true, the system will switch to the STANDARD algorithm; otherwise, it will use the PROFESSIONAL algorithm. This method only takes effect if the algorithm type used to create the TIEPass is not set to STANDARD.

These interfaces provide developers with flexible control options to optimize the performance and user experience of the image enhancement process.

### **2.2.4 TSRLogger**
[TSRLogger](https://tencentyun.github.io/TSR/android-docs/1.12/com/tencent/mps/tie/api/TSRLogger.html) is used to receive logs from the SDK internals. Please write these logs to a file for external network problem positioning.

# **3 SDK API Description**
You can click on the link to view the TSRSDK API documentation, which contains interface comments and usage examples.

[TSRSDK ANDROID API Documentation](https://tencentyun.github.io/TSR/android-docs/1.15/index.html)

