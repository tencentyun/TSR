# **1 快速开始**
## 1.1 **SDK授权申请**
### 1.1.1 **授权所需信息**
请联系您的腾讯云商务开通服务。您需要提供将要集成SDK的APP的这些信息：**腾讯云账号APPID**、**APP的Bundle Identifier**。

* APPID可以在您的腾讯云【账号中心】->【账号信息】->【基本信息】中查看。
* Bundle Identifier可以在xcode项目中的【TARGETS】-> 【General】-> 【Identity】-> 【Bundle Identifier】查看。

#### 例：提供的信息
|信息|值|
| ------ | ----------- |
|APPID|12345678|
|Bundle Identifier|com.tencent.mps.ios-demo|
|SDK版本|标准版/专业版|

授权方案分为授权申请和授权验证两个过程，其中授权申请在授权有效期内，只会进行一次。授权服务开通后，您可以在初始化TSRSDK时使用在线方式进行鉴权，APP需要有访问网络权限。授权服务具有有效期限，当授权过期失效后需要重新获取授权。

### **1.1.2 开通MPS控制台**
为了服务能够正常授权，您还需要在腾讯云官网开通【媒体处理（MPS）控制台】。开通链接：https://console.cloud.tencent.com/mps

## 1.2 **Demo工程编译运行**

下载Demo工程的[源码](https://github.com/tencentyun/TSR/tree/main/demo/tsr-ios-demo)。

修改demo的Bundle Ientifier，并将Bundle Ientifier提供给腾讯云商务，参考前面“SDK授权申请”步骤，获取SDK和授权文件，配置到Demo工程中。操作如下：
1. 使用xcode打开工程项目，将sdk拖入工程的目录下。勾选Copy items if needed，并检查Link Binary With Libraries是否已经包含sdk
   ![ios-demo-step1-1](./docs/ios-demo-step1-1.png)
   ![ios-demo-step1-2](./docs/ios-demo-step1-2.png)
   ![ios-demo-step1-3](./docs/ios-demo-step1-3.png)
2. 在【TARGETS】-> 【General】-> 【Frameworks, Libraries, and Embedded Content】中设置SDK的【Embed】为"Embed & Sign"
   ![ios-demo-step2](./docs/ios-demo-step2.png)
3. 将证书拖入工程目录的tsr-ios-demo下，并确认【Target Membembership】已勾选。
4. 在VideoPlayViewController.h中填写appId
   ![ios-demo-step4](./docs/ios-demo-step4.png)
5. 运行demo

## **1.3 Demo App体验**
暂无


# 2 **SDK接入指南**
## **2.1 程序流程**
<img src=./docs/tsr-work-flow.png width=50% />

### **2.1.1 TSRSdk**
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


2. 当您已经不需要使用TSRSdk时，需要调用TSRSdk的deInit方法，释放资源。
```
// Release resources when the TSRSdk object is no longer needed.
[TSRSdk.getInstance deInit];
```
### **2.1.2 TSRPass**
**注意：这个类使用Metal框架进行超分辨率渲染，需要设备支持Metal。**

TSRPass是用于进行超分辨率渲染的类，它包括了init、setParametersWithBrightness、render方法。
* 在使用TSRPass前，您需要调用`initWithDevice:inputWidth:inputHeight:srRatio:`方法进行初始化。
```
 # 标准版
  _tsr_pass_standard = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeStandard device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
```
```
 # 专业版
 _tsr_pass_professional = [[TSRPass alloc] initWithTSRAlgorithmType:TSRAlgorithmTypeProfessional device:_device inputWidth:_videoSize.width inputHeight:_videoSize.height srRatio:_srRatio];
```

* 初始化TSRPass后，您可以通过调用`setParametersWithBrightness:saturation:contrast:`调整渲染的参数值(可选)
```
  // Optional. Sets the brightness, saturation and contrast level of the TSRPass. The default value is set to (50, 50, 50). 
  // Here we set these parameters to slightly enhance the image.
 [_tsr_pass setParametersWithBrightness:52 saturation:55 contrast:60];
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

### **2.1.3 TIEPass**
TIEPass是用于进行图像增强渲染的类，**只在专业版SDK可用**。它包括init和render方法。在使用TIEPass前，您需要调用init方法进行初始化。

* 在使用TIEPass前，您需要调用`initWithDevice:inputWidth:inputHeight:`方法进行初始化。

```
_tie_pass = [[TIEPass alloc] initWithDevice:mTLDevice inputWidth:width inputHeight:height];
```

* 当您已经不需要使用TIEPass时，需要调用TIEPass的deInit方法，释放资源。
```
// Release resources when the TIEPass object is no longer needed.
[_tie_pass deInit];
```

### **2.1.4 TSRLogger**
TSRLogger用于接收SDK内部的日志，请将这些日志写到文件，以便定位外网问题。

# **3 SDK API描述**
您可以点击连接查看TSRSDK的API文档，内含接口注释与调用示例。

[TSRSDK IOS API文档](https://tencentyun.github.io/TSR/ios-docs/1.5/index.html)


