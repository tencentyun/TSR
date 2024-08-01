# **1 快速开始**
## 1.1 **SDK授权申请**
### 1.1.1 **授权所需信息**
请联系您的腾讯云商务开通服务。您需要提供将要集成SDK的App的这些信息：腾讯云账号APPID、App签名证书信息(签名证书的序列号、发布者、所有者)、App包名。

APPID可以在您的腾讯云【账号中心】->【账号信息】->【基本信息】中查看。
App签名证书信息可以使用keytool命令查看，例如
```keytool -list -v -keystore test.keystore```

![cert.png](./docs/cert.png)

除此之外，您还需要告知接入的SDK版本。SDK版本分为标准版与专业版： 
* 标准版：提供标准版超分辨率功能，实现快速的超分辨率处理速度，适用于高实时性要求的场景。在这种模式下，可以实现显著的图像质量改善。 
* 专业版：提供的功能包括标准版超分辨率、专业版超分辨率、专业版图像增强。专业版超分辨率和图像增强适用于高质量要求的场景，但对设备性能有一定要求，建议在中高端智能手机上使用。

提供的信息
|信息|值|
| :- | :- |
|APPID|12345678|
|包名|com.tencent.mps.srplayer|
|序列号|17ccecf2|
|所有者|test|
|发布者|test|
|SDK版本|标准版/专业版|

授权方案分为授权申请和授权验证两个过程，其中授权申请在授权有效期内，只会进行一次。授权服务开通后，您可以在初始化TSRSDK时使用在线方式进行鉴权，APP需要有访问网络权限。授权服务具有有效期限，当授权过期失效后需要重新获取授权。

### 1.1.2 **开通MPS控制台**
为了服务能够正常授权，您还需要在腾讯云官网开通【媒体处理（MPS）控制台】。开通链接：https://console.cloud.tencent.com/mps

## 1.2 **Demo工程编译运行**

下载Demo工程的[源码](https://github.com/tencentyun/TSR/tree/main/demo/tsr-android-demo)。

将前面“SDK授权申请”步骤获取的SDK和授权文件，配置到Demo工程中。操作如下：

1. 将SDK放在工程的./SRPlayer/app/libs文件夹下。

2. 在MainActivity.java下配置初始化参数，在校验初始化需要腾讯云APPID。

   ![verification-params.png](./docs/verification-params.png)


3. 对APK进行签名
- 在Android Studio中，找到【File】-> 【Project Structure】 -> 【Modules】-> 【Signing Configs】中配置您的签名证书。
- 或者您可以在【Build】-> 【Generate Signed Bundle / APK】使用证书生成签名的APK
- 无论您使用哪种方式，<font color="red">**请确保配置的签名证书与提供给我们的信息一致。**</font>

4. 运行demo

## **1.3 Demo App体验**
以下是Demo工程编译好的App安装包，可以直接进行[下载](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-demo-android/SRPlayer.apk)安装体验。

<img src=./docs/android-demo-qrcode.png width=16% />

# **2 SDK 接入指南**
## **2.1 App工程添加权限**
```
 <uses-permission android:name="android.permission.INTERNET"/>

 //如果 Android targetSdkVersion 大于等于 31，需要添加以下标签，否则专业版功能无法使用
 <application>
 <uses-native-library android:name="libOpenCL.so" android:required="false"/>
 </application>
```
## **2.2 程序流程**
<img src=./docs/tsr-work-flow.png width=50% />

### **2.2.1 TSRSdk**
[TSRSdk](https://tencentyun.github.io/TSR/android-docs/1.5/com/tencent/mps/tie/api/TSRSdk.html)包括init和deInit两个方法。init方法用于初始化SDK，deInit方法用于释放资源。

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
  TSRSdk.getInstance().init(appId, authId, callback, logger);
```


2. 当您已经不需要使用TSRSdk时，需要调用TSRSdk的deInit方法，释放资源。<font color="red">**注意：在调用TSRSdk的deInit方法前，确保所有TSRPass已经释放资源，否则会有意想不到的问题。**</font>
```
  // If you have created TSRPass, you should release it before release TSRSdk.
  tsrPass.deInit();
  // Release resources when the TSRSdk object is no longer needed.
  TSRSdk.getInstance().deInit();
```

### **2.2.2 TSRPass**
[TSRPass](https://tencentyun.github.io/TSR/android-docs/1.5/com/tencent/mps/tie/api/TSRPass.html)是用于进行超分辨率渲染的类，在创建TSRPass时，您需要传入TSRAlgorithmType设置超分的算法类型。

在TSRAlgorithmType枚举中，有STANDARD、PROFESSIONAL两个算法运行模式：
1. STANDARD（标准）模式：提供快速的超分辨率处理速度，适用于高实时性要求的场景。在这种模式下，可以实现显著的图像质量改善。
2. PROFESSIONAL（专业）模式：更注重超分辨率效果，适用于高质量要求的场景。这种模式的超分辨率效果优于 STANDARD 模式，但对设备性能有一定要求，建议在中高端智能手机上使用。**需要注意的是，这种模式仅在专业版SDK中提供支持。**

它包括了init、render和deInit方法。在使用TSRPass前，您需要调用init方法进行初始化。在使用结束后，您需要调用deInit方法释放资源。

以下是标准版超分代码示例：
```
TSRPass tsrPass =  new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);

// The code below must be executed in glThread.
//----------------------GL Thread---------------------//
tsrPass.init(inputWidth, inputHeight, srRatio);

// Optional. Sets the brightness, saturation and contrast level of the TSRPass. The default value is set to (50, 50, 50). 
// Here we set these parameters to slightly enhance the image.
tsrPass.setParameters(52, 55, 60);

int outputTextureId = tsrPass.render(inputTextureId);

//----------------------GL Thread---------------------//

// Release resources when the TSRPass object is no longer needed.
tsrPass.deInit();
```

以下是专业版超分代码示例：
```
TSRPass tsrPassProfessional = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL);

// The code below must be executed in glThread.
//----------------------GL Thread---------------------//
tsrPass.init(inputWidth, inputHeight, srRatio);

int outputTextureId = tsrPass.render(inputTextureId);

//----------------------GL Thread---------------------//

// Release resources when the TSRPass object is no longer needed.
tsrPass.deInit();
```

### **2.2.3 TIEPass**
[TIEPass](https://tencentyun.github.io/TSR/android-docs/1.5/com/tencent/mps/tie/api/TIEPass.html)是用于进行图像增强渲染的类，**只在专业版SDK可用**。它包括init、render和deInit方法。在使用TIEPass前，您需要调用init方法进行初始化。在使用结束后，您需要调用release方法释放资源。

以下是代码示例：
```
// Create a TIEPass object using the constructor.
TIEPass tiePass = new TIEPass();

// The code below must be executed in glThread.
//----------------------GL Thread---------------------//

// Init TIEPass
tiePass.init(inputWidth, inputHeight);
// If the type of inputTexture is TextureOES, you must transform it to Texture2D.
int outputTextureId = tiePass.render(inputTextureId);

//----------------------GL Thread---------------------//

// Release resources when the TIEPass object is no longer needed.
tiePass.deInit();
```

### **2.2.4 TSRLogger**
[TSRLogger](https://tencentyun.github.io/TSR/android-docs/1.5/com/tencent/mps/tie/api/TSRLogger.html)用于接收SDK内部的日志，请将这些日志写到文件，以便定位外网问题。

# **3 SDK API描述**
您可以点击连接查看TSRSDK的API文档，内含接口注释与调用示例。

[TSRSDK ANDROID API文档](https://tencentyun.github.io/TSR/android-docs/1.5/index.html)


