# **1 快速开始**
## 1.1 **SDK授权申请**
为了服务能够正常授权，您还需要在腾讯云官网开通【媒体处理（MPS）控制台】。开通链接：[https://console.cloud.tencent.com/mps](https://console.cloud.tencent.com/mps)

开通【媒体处理（MPS）控制台】后，可以参照[文档教程](https://cloud.tencent.com/document/product/862/109789?from=copy)。的方式，自行开通测试授权。

## 1.2 **Demo工程编译运行**

下载Demo工程的[源码](https://github.com/tencentyun/TSR/tree/main/demo/tsr-android-demo)。

将前面“SDK授权申请”步骤获取的SDK和授权文件，配置到Demo工程中。操作如下：

1. 将SDK放在工程的./SRPlayer/app/libs文件夹下。

2. 在MainActivity.java下配置初始化参数，在校验初始化需要腾讯云APPID。

   ![verification-params.png](./docs/verification-params.png)

3. 运行demo

## **1.3 Demo App体验**
以下是Demo工程编译好的App安装包，可以直接进行[下载](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-demo-android/MPSDemo_v0.5.6-12-f17f911_202411071807.apk)安装体验。

<img src=./docs/android-demo-qrcode.png width=16% />

# **2 SDK 接入指南**
## **2.1 App工程添加权限**
```
 <uses-permission android:name="android.permission.INTERNET"/>

 //如果 Android targetSdkVersion 大于等于 31，需要添加以下标签，否则专业版功能无法使用
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
## **2.2 程序流程**
<img src=./docs/tsr-work-flow.png width=50% />

### **2.2.1 TSRSdk**
[TSRSdk](https://tencentyun.github.io/TSR/android-docs/latest/com/tencent/mps/tie/api/TSRSdk.html)包括init和deInit两个方法。init方法用于初始化SDK，deInit方法用于释放资源。

1. 在线鉴权初始化TSRSdk，您需要传入**APPID和AUTH_ID**进行在线鉴权，还需要传入TSRSdk.TSRSdkLicenseVerifyResultCallback用于获取在线鉴权的结果。除此之外，还需要传入一个TSRLogger，用于获取SDK的日志。下面是示例代码：

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


2. 当您已经不需要使用TSRSdk时，需要调用TSRSdk的deInit方法，释放资源。<font color="red">**注意：在调用TSRSdk的deInit方法前，确保所有TSRPass已经释放资源，否则会有意想不到的问题。**</font>
```
  // If you have created TSRPass, you should release it before release TSRSdk.
  tsrPass.deInit();
  // Release resources when the TSRSdk object is no longer needed.
  TSRSdk.getInstance().deInit();
```

### **2.2.2 TSRPass**
[TSRPass](https://tencentyun.github.io/TSR/android-docs/latest/com/tencent/mps/tie/api/TSRPass.html) 是用于进行超分辨率渲染的类，在创建 TSRPass 时，您需要传入 TSRAlgorithmType 设置超分的算法类型。

**注意：TSRPass 不是线程安全的，必须在同一个线程中调用 TSRPass 的方法。**

在 TSRAlgorithmType 枚举中，有 STANDARD、STANDARD_COLOR_RETOUCHING_EXT、PROFESSIONAL和PROFESSIONAL四个算法运行模式：
1. **STANDARD（标准）模式**：提供快速的超分辨率处理速度，适用于高实时性要求的场景。在这种模式下，可以实现显著的图像质量改善。
2. **STANDARD_COLOR_RETOUCHING_EXT（标准-色彩调节）模式**：在标准版超分辨率的基础上优化色彩表现。
3. **PROFESSIONAL（专业）模式**：确保了高图像质量，同时需要更高的设备性能。它适合于有高图像质量要求的场景，并推荐在中高端智能手机上使用。
4. **PROFESSIONAL_COLOR_RETOUCHING_EXT（专业-色彩调节）模式**：在专业版超分辨率的基础上优化色彩表现。

它包括了 `init`, `reInit`, `render` 和 `deInit` 方法。在使用 TSRPass 前，您需要调用 `init` 方法进行初始化。如果需要在不创建新的 TSRPass 实例的情况下更新输入图像的尺寸或缩放比例，可以使用 `reInit` 方法。在使用结束后，您需要调用 `deInit` 方法释放资源。


以下是超分代码示例：
```
// Create a TSRPass object using the constructor.
TSRPass tsrPass = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL); // STANDARD, STANDARD_COLOR_RETOUCHING_EXT, PROFESSIONAL, PROFESSIONAL_COLOR_RETOUCHING_EXT

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

TSRPass类还提供了接口用于管理和优化超分辨率渲染过程中的专业版超分辨率（Pro SR）功能。以下是对这些接口的详细介绍：

1. **enableProSRAutoFallback(int consecutiveTimeoutFrames, int timeoutDurationMs, FallbackListener listener):**
   该方法用于启用超分辨率处理的自动回退机制，并设置相应的参数。此方法应在调用初始化方法之前调用。它配置了自动回退的参数，如果连续超时帧数超过指定的consecutiveTimeoutFrames，系统将触发回退。请注意，此方法仅在创建TSRPass时使用的算法类型不设置为STANDARD时生效。此外，可以提供一个回退监听器来处理回退事件。当触发回退时，将调用回退监听器的onFallback()方法，允许用户实现自定义行为以响应回退事件。

2. **disableProSRAutoFallback():**
   该方法用于禁用超分辨率处理的自动回退机制。此方法应在之前使用enableProSRAutoFallback启用的自动回退功能关闭后调用。一旦调用此方法，系统将不再根据配置的参数触发回退。

3. **benchmarkProSR(int inputWidth, int inputHeight, float srRatio):**
   该方法用于评估专业版算法的渲染时间消耗。此方法根据给定的输入尺寸评估专业版算法的执行时间（以毫秒为单位）。此方法不应在主线程上调用，因为它可能需要大约2到5秒才能完成。此方法仅在创建TSRPass时使用的算法类型不设置为STANDARD时生效。如果算法执行因任何原因失败，此方法将返回-1。

4. **forceProSRFallback(boolean enable):**
   该方法用于在专业版和标准算法之间切换。当enable为true时，系统将切换到标准算法；否则，将使用专业版算法。此方法仅在创建TSRPass时使用的算法类型不设置为STANDARD时生效。

这些接口为开发者提供了灵活的控制选项，以优化超分辨率渲染的性能和用户体验。

### **2.2.3 TIEPass**
[TIEPass](https://tencentyun.github.io/TSR/android-docs/latest/com/tencent/mps/tie/api/TIEPass.html) 是用于进行图像增强渲染的类，**只在专业版SDK可用**。在创建 TIEPass 时，您需要传入 TIEAlgorithmType 设置图像增强的算法类型。它包括 `init`, `reInit`, `render` 和 `deInit` 方法。在使用 TIEPass 前，您需要调用 `init` 方法进行初始化。如果需要在不创建新的 TIEPass 实例的情况下更新输入图像的尺寸，可以使用 `reInit` 方法。在使用结束后，您需要调用 `deInit` 方法释放资源。


**注意：TIEPass 不是线程安全的，必须在同一个线程中调用 TIEPass 的方法。**

以下是代码示例：
```
// Create a TIEPass object using the constructor.
TIEPass tiePass = new TIEPass(TIEPass.TIEAlgorithmType.PROFESSIONAL);


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

TIEPass类提供了接口用于管理和优化图像增强过程中的专业版图像增强（Pro IE）功能。以下是对这些接口的详细介绍：

1. **enableProIEAutoFallback(int consecutiveTimeoutFrames, int timeoutDurationMs, FallbackListener listener):**
   该方法用于启用图像增强过程的自动回退机制，并设置相应的参数。此方法应在调用初始化方法之前调用。它配置了自动回退的参数，如果连续超时帧数超过指定的consecutiveTimeoutFrames，系统将触发回退。请注意，此方法仅在创建TIEPass时使用的算法类型不设置为STANDARD时生效。此外，可以提供一个回退监听器来处理回退事件。当触发回退时，将调用回退监听器的onFallback()方法，允许用户实现自定义行为以响应回退事件。

2. **disableProIEAutoFallback():**
   该方法用于禁用图像增强过程的自动回退机制。此方法应在之前使用enableProIEAutoFallback启用的自动回退功能关闭后调用。一旦调用此方法，系统将不再根据配置的参数触发回退。

3. **benchmarkProIE(int inputWidth, int inputHeight):**
   该方法用于评估专业版算法的渲染时间消耗。此方法根据给定的输入尺寸评估专业版算法的执行时间（以毫秒为单位）。此方法不应在主线程上调用，因为它可能需要大约2到5秒才能完成。此方法仅在创建TIEPass时使用的算法类型不设置为STANDARD时生效。如果算法执行因任何原因失败，此方法将返回-1。

4. **forceProIEFallback(boolean enable):**
   该方法用于在专业版和标准算法之间切换。当enable为true时，系统将切换到标准算法；否则，将使用专业版算法。此方法仅在创建TIEPass时使用的算法类型不设置为STANDARD时生效。

这些接口为开发者提供了灵活的控制选项，以优化图像增强过程的性能和用户体验。

### **2.2.4 TSRLogger**
[TSRLogger](https://tencentyun.github.io/TSR/android-docs/latest/com/tencent/mps/tie/api/TSRLogger.html)用于接收SDK内部的日志，请将这些日志写到文件，以便定位外网问题。

# **3 SDK API描述**
您可以点击连接查看TSRSDK的API文档，内含接口注释与调用示例。

[TSRSDK ANDROID API文档](https://tencentyun.github.io/TSR/android-docs/1.15/index.html)


