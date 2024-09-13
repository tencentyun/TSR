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

3. Sign the APK
- In Android Studio, find【File】-> 【Project Structure】-> 【Modules】-> 【Signing Configs】and configure your signature certificate.
- Or you can use the certificate to generate a signed APK in【Build】-> 【Generate Signed Bundle / APK】
- No matter which method you use, <font color="red">**please make sure that the configured signature certificate is consistent with the information provided to us.**</font>

4. Run the demo

## **1.3 Demo App Experience**
The following is the compiled App installation package of the Demo project, which can be [downloaded](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-demo-android/SRPlayer.apk) and installed directly for experience.

<img src=./docs/android-demo-qrcode.png width=16% />

# **2 SDK Access Guide**
## **2.1 Add permissions to the App project**
``` 
<uses-permission android:name="android.permission.INTERNET"/>

// If Android targetSdkVersion is greater than or equal to 31, you need to add the following tags, otherwise the professional version features will not be available
<application>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
</application>
```

## **2.2 Program Flow**
<img src=./docs/tsr-work-flow.png width=50% />

### **2.2.1 TSRSdk**
[TSRSdk](https://tencentyun.github.io/TSR/android-docs/1.8/com/tencent/mps/tie/api/TSRSdk.html) includes init and deInit methods. The init method is used to initialize the SDK, and the deInit method is used to release resources.

1. Offline authentication initializes TSRSdk, and you need to pass in **APPID and LICENSE_PATH** for offline authentication. In addition, you also need to pass in a TSRLogger to obtain the logs of the SDK. The following is an example code:
```
   TSRSdkLicenseStatus status = TSRSdk.getInstance().init(mAppId, mLicensePath, mTsrLogger);
   if (status == TSRSdkLicenseStatus.AVAILABLE) {
      // Do something when the verification of sdk's license success.
   } else {
      // Do something when the verification of sdk's license failed.
   }
```

2. When you no longer need to use TSRSdk, you need to call the deInit method of TSRSdk to release resources. <font color="red">**Note: Before calling the deInit method of TSRSdk, make sure that all TSRPasses have released resources, otherwise unexpected problems may occur.**</font>
```
  // If you have created TSRPass, you should release it before release TSRSdk.
  tsrPass.deInit();
  // Release resources when the TSRSdk object is no longer needed.
  TSRSdk.getInstance().deInit();
```

### **2.2.2 TSRPass**
[TSRPass](https://tencentyun.github.io/TSR/android-docs/1.8/com/tencent/mps/tie/api/TSRPass.html) is a class used for super-resolution rendering. When creating a TSRPass, you need to pass in TSRAlgorithmType to set the super-resolution algorithm type.

**Note: TSRPass is not thread-safe, and the methods of TSRPass must be called in the same thread.**

In the TSRAlgorithmType enumeration, there are STANDARD, PROFESSIONAL_HIGH_QUALITY, and PROFESSIONAL_FAST three algorithm running modes:
1. STANDARD (standard) mode: Provides fast super-resolution processing speed, suitable for scenes with high real-time requirements. In this mode, significant image quality improvement can be achieved.
2. PROFESSIONAL_HIGH_QUALITY (Professional Edition - High Quality) mode: PROFESSIONAL_HIGH_QUALITY mode ensures high image quality while requiring higher device performance. It is suitable for scenes with high image quality requirements and is recommended for use on mid-to-high-end smartphones.
3. PROFESSIONAL_FAST (Professional Edition - Fast) mode: PROFESSIONAL_FAST mode ensures faster processing speed while sacrificing some image quality. It is suitable for scenes with high real-time requirements and is recommended for use on mid-range smartphones.
   It includes init, render, and deInit methods. Before using TSRPass, you need to call the init method to initialize. After using it, you need to call the deInit method to release resources.

The following is an example of using STANDARD super-resolution algorithm code:
```
// The code below must be executed in the same glThread.
//----------------------GL Thread---------------------//

// Create a TSRPass object using the constructor.
TSRPass tsrPass = new TSRPass(TSRPass.TSRAlgorithmType.STANDARD);

// Initialize TSRPass and set the input image width, height and srRatio.
tsrPass.init(inputWidth, inputHeight, srRatio);

// Optional. Sets the brightness, saturation and contrast level of the TSRPass. The default value is set to (50, 50, 50). 
// Here we set these parameters to slightly enhance the image.
tsrPass.setParameters(52, 55, 60);

// If the type of inputTexture is TextureOES, you must transform it to Texture2D.
// Conversion code can be written according to actual requirements.

// Perform super resolution rendering on the input OpenGL texture and get the enhanced texture ID.
int outputTextureId = tsrPass.render(inputTextureId);

// Release resources when the TSRPass object is no longer needed.
tsrPass.deInit();

//----------------------GL Thread---------------------//
```

The following is an example of using PROFESSIONAL super-resolution algorithm code:
```
// The code below must be executed in the same glThread.
//----------------------GL Thread---------------------//

// Create a TSRPass object using the constructor.
TSRPass tsrPass = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL_HIGH_QUALITY);
// Alternatively, create a TSRPass object with the professional fast rendering type.
// TSRPass tsrPass = new TSRPass(TSRPass.TSRAlgorithmType.PROFESSIONAL_FAST);

// Initialize TSRPass and set the input image width, height and srRatio.
tsrPass.init(inputWidth, inputHeight, srRatio);

// Optional. Sets the brightness, saturation and contrast level of the TSRPass. The default value is set to (50, 50, 50). 
// Here we set these parameters to slightly enhance the image.
tsrPass.setParameters(52, 55, 60);

// If the type of inputTexture is TextureOES, you must transform it to Texture2D.
// Conversion code can be written according to actual requirements.

// Perform super resolution rendering on the input OpenGL texture and get the enhanced texture ID.
int outputTextureId = tsrPass.render(inputTextureId);

// Release resources when the TSRPass object is no longer needed.
tsrPass.deInit();

//----------------------GL Thread---------------------//
```

### **2.2.3 TIEPass**
[TIEPass](https://tencentyun.github.io/TSR/android-docs/1.8/com/tencent/mps/tie/api/TIEPass.html) is a class used for image enhancement rendering, **only available in the Professional Edition SDK**. When creating a TIEPass, you need to pass in TIEAlgorithmType to set the super-resolution algorithm type. It includes init, render, and deInit methods. Before using TIEPass, you need to call the init method to initialize. After using it, you need to call the release method to release resources.

In the TIEAlgorithmType enumeration, there are PROFESSIONAL_HIGH_QUALITY and PROFESSIONAL_FAST two algorithm running modes:
1. PROFESSIONAL_HIGH_QUALITY (Professional Edition - High Quality) mode: PROFESSIONAL_HIGH_QUALITY mode ensures high image quality while requiring higher device performance. It is suitable for scenes with high image quality requirements and is recommended for use on mid-to-high-end smartphones.
2. PROFESSIONAL_FAST (Professional Edition - Fast) mode: PROFESSIONAL_FAST mode ensures faster processing speed while sacrificing some image quality. It is suitable for scenes with high real-time requirements and is recommended for use on mid-range smartphones.

**Note: TIEPass is not thread-safe, and TIEPass methods must be called in the same thread.**

The following is a code example:

```
// The code below must be executed in the same glThread.
//----------------------GL Thread---------------------//

// Create a TIEPass object using the constructor.
TIEPass tiePass = new TIEPass(TIEPass.TIEAlgorithmType.PROFESSIONAL_HIGH_QUALITY);
// Alternatively, create a TIEPass object with the professional fast rendering type.
// TIEPass tiePass = new TIEPass(TIEPass.TIEAlgorithmType.PROFESSIONAL_FAST);

// Initialize TIEPass and set the input image width and height.
tiePass.init(inputWidth, inputHeight);

// If the type of inputTexture is TextureOES, you must transform it to Texture2D.
// Conversion code can be written according to actual requirements.

// Perform image enhancement rendering on the input OpenGL texture and get the enhanced texture ID.
int outputTextureId = tiePass.render(inputTextureId);

// Release resources when the TIEPass object is no longer needed.
tiePass.deInit();
//----------------------GL Thread---------------------//
```

### **2.2.4 TSRLogger**
[TSRLogger](https://tencentyun.github.io/TSR/android-docs/1.8/com/tencent/mps/tie/api/TSRLogger.html) is used to receive logs from the SDK internals. Please write these logs to a file for external network problem positioning.

# **3 SDK API Description**
You can click on the link to view the TSRSDK API documentation, which contains interface comments and usage examples.

[TSRSDK ANDROID API Documentation](https://tencentyun.github.io/TSR/android-docs/1.8/index.html)

