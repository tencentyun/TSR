# 1 运行 Demo
## 1.1 SDK授权申请
请先在腾讯云官网开通 媒体处理 [控制台](https://console.cloud.tencent.com/mps)。然后根据[指南](https://cloud.tencent.com/document/product/862/109789)，自助开通SDK测试授权，获取 授权ID，绑定 App 包名。    
<img src="./docs/license.png" height="100">    
在 腾讯云-账号中心-[账号信息](https://console.cloud.tencent.com/developer)，获取你的账号的 APPID。    
<img src="./docs/APPID.png" height="100">    

## 1.2 Demo工程编译运行
下载Demo工程[源码](./demo/tsr-ios-demo)。    
将 Demo 包名修改为前面“SDK授权申请”步骤绑定的包名。    
将 Configurations 目录下的 Config.xcconfig.example 文件重命名为 Config.xcconfig 文件，并修改文件内容，填入你获取的 授权ID 和 APPID。
然后就可以编译运行 Demo 了。

*备注：Demo工程集成的 SDK 版本可能较旧，可以联系你的腾讯云商务代表获取最新版本。*

---


# 2 **App 接入 SDK**
## 2.1 添加和配置 SDK
将 tsr_client.framework 添加到您的 Xcode 工程中：
1. 将 tsr_client.framework 拖拽到工程的 Frameworks 目录下。
2. 在工程的 **General → Frameworks, Libraries, and Embedded Content** 中，将 tsr_client.framework 设置为 **Embed & Sign**。

## **2.2 使用 SDK**
建议参考Demo工程里的[MainViewController.m](./demo/tsr-ios-demo/tsr-ios-demo/Modules/MainViewController.m)类 和 [ProfileViewController.m](./demo/tsr-ios-demo/tsr-ios-demo/Modules/ProfileViewController.m)类，以最简单的方式初始化和使用 SDK。

以下是一些接口的详细说明。

<img src=./docs/tsr-work-flow.png width=50% />

### **2.2.1 TSRSdk**
TSRSdk包括`initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:`和`deInit`两个方法。`initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:`方法用于初始化SDK，`deInit`方法用于释放资源。
1. 在线鉴权初始化TSRSdk，您需要传入**APPID和AUTH_ID**用于鉴权，`initWithAppId:authId:sdkLicenseVerifyResultCallback:tsrLogger:`需要传入TSRSdkLicenseVerifyResultCallback用于获取在线鉴权的结果，除此之外，还需要传入一个 TSRLogger，用于获取SDK的日志。下面是示例代码：
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

2. 当您已经不需要使用TSRSdk时，可以调用TSRSdk的deInit方法。

### **2.2.2 TSRPass**

TSRPass是用于进行超分辨率渲染的类，它包括了`init`、`render`和`reInit`方法。在创建TSRPass时，您需要传入`TSRAlgorithmType`设置超分的算法类型。

在`TSRAlgorithmType`枚举中，有以下四个算法运行模式：
1. **TSRAlgorithmTypeStandard**：提供快速的超分辨率处理速度，适用于高实时性要求的场景。在这种模式下，可以实现显著的图像质量改善。
2. **TSRAlgorithmTypeStandardColorRetouchingExt**：在标准版超分辨率的基础上优化色彩表现。
3. **TSRAlgorithmTypeProfessional**：确保了高图像质量，同时需要更高的设备性能。它适合于有高图像质量要求的场景，并推荐在中高端智能手机上使用。
4. **TSRAlgorithmTypeProfessionalColorRetouchingExt**：在专业版超分辨率的基础上优化色彩表现。

**注意：**
- TSRPass使用OpenGL框架进行超分辨率渲染，需要设备支持OpenGL ES 3.0。
- TSRPass不是线程安全的，必须在同一个线程中调用TSRPass的方法。
- 专业版算法`TSRAlgorithmTypeProfessional`需要iOS系统版本在16.0或以上才生效。

在使用TSRPass前，您需要调用`initWithTSRAlgorithmType:glContext:inputWidth:inputHeight:srRatio:initStatusCode:`方法进行初始化。
```objective-c
 TSRInitStatusCode initStatus;

 _tsr_pass_standard = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
 
 _tsr_pass_standard_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeStandardColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
 
 _tsr_pass_professional = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];

 _tsr_pass_professional_ext = [[TSRPass alloc] initWithAlgorithmType:TSRAlgorithmTypeProfessionalColorRetouchingExt glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio initStatusCode:&initStatus];
```

如果在使用过程中需要调整输入图像的尺寸或超分辨率的放大因子，可以调用`reInit`方法进行重新初始化。
```objective-c
// Reinitializing TSRPass with new dimensions and super-resolution ratio
TSRInitStatusCode reInitStatus = [_tsr_pass reInit:newInputWidth inputHeight:newInputHeight srRatio:newSrRatio];
if (reInitStatus == TSRInitStatusCodeSuccess) {
    // Continue with rendering or other operations
} else {
    // Handle reinitialization failure
}
```

* `render:commmandBufffer:`方法将超分辨率渲染过程应用于输入图像，提高其质量。处理后的图像渲染在TSRPass对象内的MTLTexture上。返回的是已执行超分辨率渲染的MTLTexture。
```
   _sr_texture = [_tsr_pass render:_in_texture commandBuffer:commandBuffer];
```

* 当您已经不需要使用TSRPass时，需要调用TSRPass的deInit方法，释放资源。
```
// Release resources when the TSRPass object is no longer needed.
[_tsr_pass deInit];
```

TSRPass类还提供了接口用于管理和优化超分辨率渲染过程中的专业版超分辨率（Pro SR）功能。以下是对这四个接口的详细介绍：

1. **enableProSRAutoFallback:timeoutDurationMs:fallbackListener:**
   该方法用于启用专业版超分辨率的自动回退机制。您可以设置连续超时帧数（consecutiveTimeoutFrames）和超时持续时间（timeoutDurationMs），以便在超分辨率处理未能在指定时间内完成时自动回退到默认处理方式。此外，您还可以传入一个回退监听器（fallbackListener），用于处理回退事件的回调。这使得在处理过程中能够更灵活地应对性能问题，确保用户体验的流畅性。

2. **disableProSRAutoFallback:**
   该方法用于禁用专业版超分辨率的自动回退机制。如果您希望在超分辨率处理过程中不使用自动回退功能，可以调用此方法。这对于需要严格控制渲染流程的场景非常有用。

3. **benchmarkProSR:**
   该方法用于对专业版超分辨率进行基准测试。您需要传入输入图像的宽度（inputWidth）、高度（inputHeight）和超分辨率比率（srRatio），该方法将返回一个整数值，表示在给定尺寸和超分辨率比率下的处理性能。这可以帮助开发者评估不同图像尺寸和超分辨率设置下的处理效率，从而优化应用的性能。

4. **forceProSRFallback:**
   该方法用于强制启用或禁用专业版超分辨率的回退功能。通过传入一个布尔值（enable），您可以控制是否在处理过程中强制使用回退机制。这对于调试和测试场景非常有用，允许开发者在需要时快速切换到默认处理方式。

这些接口为开发者提供了灵活的控制选项，以优化超分辨率渲染的性能和用户体验。

### **2.2.3 TIEPass**
TIEPass是用于进行图像增强渲染的类，**只在专业版SDK可用**。它包括`init`、`render`、`renderWithPixelBuffer`、`reInit`和`deInit`方法。在使用TIEPass前，您需要调用`init`方法进行初始化。在创建TIEPass时，您需要传入`TIEAlgorithmType`设置图像增强的算法类型。

**注意：**
- TIEPass不是线程安全的，必须在同一个线程中调用TIEPass的方法。
- 专业版算法`TIEAlgorithmTypeProfessional`需要iOS系统版本在16.0或以上才生效。

* 在使用TIEPass前，您需要调用`initWithTIEAlgorithmType:algorithmType:glContext:inputWidth:inputHeight:initStatusCode:`方法进行初始化。

```
 TIEInitStatusCode tieInitStatus;
 
 // STANDARD
_tie_pass_standard = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeStandard glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
 
 // PROFESSIONAL
 _tie_pass_professional = [[TIEPass alloc] initWithAlgorithmType:TIEAlgorithmTypeProfessional glContext: context inputWidth:_videoSize.width inputHeight:_videoSize.height initStatusCode:&tieInitStatus];
```

* 如果在使用过程中需要调整输入图像的尺寸，可以调用`reInit`方法进行重新初始化。

```objective-c
// 重新初始化TIEPass以适应新的图像尺寸
TIEInitStatusCode reInitStatus = [_tie_pass reInit:newInputWidth inputHeight:newInputHeight];
if (reInitStatus == TIEInitStatusCodeSuccess) {
    // 继续进行图像处理或其他操作
} else {
    // 处理重新初始化失败的情况
}
```

* 当您已经不需要使用TIEPass时，需要调用TIEPass的deInit方法，释放资源。
```
// Release resources when the TIEPass object is no longer needed.
[_tie_pass deInit];
```

TIEPass类还提供了接口用于管理和优化图像增强过程中的专业版图像增强（Pro IE）功能。以下是对这四个接口的详细介绍：

1. **enableProIEAutoFallback:timeoutDurationMs:fallbackListener:**
   该方法用于启用专业版图像增强的自动回退机制。您可以设置连续超时帧数（consecutiveTimeoutFrames）和超时持续时间（timeoutDurationMs），以便在图像增强处理未能在指定时间内完成时自动回退到默认处理方式。此外，您还可以传入一个回退监听器（fallbackListener），用于处理回退事件的回调。这使得在处理过程中能够更灵活地应对性能问题，确保用户体验的流畅性。

2. **disableProIEAutoFallback:**
   该方法用于禁用专业版图像增强的自动回退机制。如果您希望在图像增强过程中不使用自动回退功能，可以调用此方法。这对于需要严格控制图像处理流程的场景非常有用。

3. **benchmarkProIE:**
   该方法用于对专业版图像增强进行基准测试。您需要传入输入图像的宽度（inputWidth）和高度（inputHeight），该方法将返回一个整数值，表示在给定尺寸下的处理性能。这可以帮助开发者评估不同图像尺寸下的处理效率，从而优化应用的性能。

4. **forceProIEFallback:**
   该方法用于强制启用或禁用专业版图像增强的回退功能。通过传入一个布尔值（enable），您可以控制是否在处理过程中强制使用回退机制。这对于调试和测试场景非常有用，允许开发者在需要时快速切换到默认处理方式。

这些接口为开发者提供了灵活的控制选项，以优化图像增强的性能和用户体验。

### **2.2.4 TSRLogger**
TSRLogger用于接收SDK内部的日志，请将这些日志写到文件，以便定位外网问题。

# **3 SDK 接口文档**
您可以点击连接查看TSRSDK的API文档，内含接口注释与调用示例。

[TSRSDK IOS API文档](https://tencentyun.github.io/TSR/ios-docs/latest/index.html)


