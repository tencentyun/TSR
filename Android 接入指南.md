
# Android 接入指南
---

# 1 运行 Demo
## 1.1 SDK授权申请
请先在腾讯云官网开通 媒体处理 [控制台](https://console.cloud.tencent.com/mps)。然后根据[指南](https://cloud.tencent.com/document/product/862/109789)，自助开通SDK测试授权，获取 授权ID，绑定 App 包名。    
<img src="./docs/license.png" height="100">    
在 腾讯云-账号中心-[账号信息](https://console.cloud.tencent.com/developer)，获取你的账号的 APPID。    
<img src="./docs/APPID.png" height="100">    

## 1.2 Demo工程编译运行
下载Demo工程[源码](./demo/tsr-android-demo/tsr-opengl-demo)。    
将 Demo 包名修改为前面“SDK授权申请”步骤绑定的包名。    
将获取的 授权ID 和 APPID，配置到Demo工程里：可以在工程根目录下的 local.properties 文件里添加
```
App_Id=你的APPID
Auth_Id=你的授权ID
```
或者 也可以直接写到工程的 TsrSdkHelper.java 文件里    
<img src="./docs/verification-params.png">    
然后就可以编译运行 Demo 了。

*备注：Demo工程的 ./SRPlayer/app/libs 文件夹下的 SDK 文件可能较旧，可以联系你的腾讯云商务代表获取最新版本。*

---

# 2 App 接入 SDK
## 2.1 添加和配置 SDK

将 TsrSdk 的相关 AAR 放入 App 工程的 libs 文件夹下。
在 App 的 build.gradle 中配置
```
android {
    packagingOptions {
        // 如有有多个，只需集成一个。
        pickFirst '**/libc++_shared.so'
        // 必须声明排除这个 so
        excludes += ['**/libqqneuroedge*.so']
        // 如果 app 没有用到 libmmkv.so，可以排除 armeabi-v7a 架构的，减少包大小。注意需要保留 arm64-v8a 架构的。
        excludes += ['**/armeabi-v7a/libmmkv.so']
    }
}

dependencies {
     implementation fileTree(dir: "libs", include: ["*.jar", "*.aar"])
}
```
在 AndroidManifest.xml 里配置
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

---

# 3. 使用 SDK

## 3.1 初始化

建议参考示例工程中的封装方式，通过单例类统一管理初始化状态：[TsrSdkHelper](./demo/tsr-android-demo/tsr-opengl-demo/app/src/main/java/com/tencent/mps/srplayer/helper/TsrSdkHelper.java)

当前 Demo 通过 `TSRSdk.getInstance().init(...)` 完成 SDK 授权初始化：

```java
public void init(Context context) {
    TSRSdk.getInstance().init(context.getApplicationContext(), BuildConfig.APP_ID, BuildConfig.AUTH_ID, status -> {
        if (status == TSRSdkLicenseStatus.AVAILABLE) {
            isInit = Boolean.TRUE;
        } else {
            isInit = Boolean.FALSE;
        }
    }, (logLevel, tag, msg) -> {
        // 转发 SDK 日志
    });
}
```

说明：
- `TSRSdk.init(...)` 是异步授权校验流程。
- `TsrSdkHelper.isInit()` 是三态值：
  - `null`：尚未完成初始化
  - `true`：初始化成功，License 可用
  - `false`：初始化失败或 License 不可用

建议在 `Application.onCreate()` 中尽早初始化：

```java
public class SRApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        TsrSdkHelper.getInstance().init(this);
    }
}
```

在使用任何 TIE / TSR 能力前，先检查初始化状态：

```java
Boolean init = TsrSdkHelper.getInstance().isInit();
if (!Boolean.TRUE.equals(init)) {
    // 提示用户“TSR SDK 尚未初始化完成或 License 不可用”
    return;
}
```

---

## 3.2 图像增强（TIE）

使用 `v2` 接口

### 3.2.1 `TieStd`：标准版纹理增强

`TieStd` 适合直接处理 OpenGL 纹理输入，调用方式简单。

主要接口：
- 构造：`new TieStd()`
- 初始化：`ErrorCode init(int width, int height)`
- 处理：`int process(int textureId)`
- 释放：`void release()`

线程要求：
- `TieStd` 所有方法都必须在**同一个拥有有效 OpenGL ES 上下文的 GL 线程**中调用。

### 3.2.2 `TiePro`：专业版Y通道增强

`TiePro` **不直接处理 GL 纹理**，而是处理 **Y 通道内存数据**。

主要接口：
- 构造：`new TiePro()`
- 初始化：`ErrorCode init(int width, int height)`
- 处理：`void process(ByteBuffer yData, int width, int height)`
- 释放：`void release()`

调用约束：
- `init(...)` 可能阻塞，**建议放到后台线程执行**。
- `process(...)` 会对传入的 `ByteBuffer` 做**原地处理**。
- `yData` 建议使用 direct `ByteBuffer`。

如果你的业务链路本身就能拿到 YUV / Y 平面内存数据，可以直接使用。
如果你的输入是 GL 纹理，而不是原始 Y 数据，那么需要像 Demo 一样增加一个桥接层：
- 先把输入 RGBA 纹理转换为 Y 通道数据
- 调用 `TiePro.process(...)` 做 NPU 增强
- 再把增强后的 Y 通道与原始 RGBA 合成为输出纹理。

Demo 中对应的桥接实现是 [TieProGlProcessor](./demo/tsr-android-demo/tsr-opengl-demo/app/src/main/java/com/tencent/mps/srplayer/pass/TieProGlProcessor.java)。

它的处理流程如下：
1. 后台线程调用 `mTiePro.init(width, height)` 初始化 NPU 模型
2. GL 线程调用 `mTieProProcessor.init(width, height)` 初始化 OpenGL 资源
3. 每帧调用 `mTieProProcessor.process(inputTextureId)` 获取增强后的输出纹理
4. 退出时释放 `mTieProProcessor` 和 `mTiePro`

---

## 3.3 超分辨率（TSR）

### 3.3.1 `TsrStd`：标准版纹理超分

`TsrStd` 用于将输入纹理放大到指定输出分辨率。

主要接口：
- 构造：`new TsrStd()`
- 初始化：`ErrorCode init(int inputWidth, int inputHeight, int outputWidth, int outputHeight)`
- 处理：`int process(int textureId)`
- 释放：`void release()`

线程要求：
- `TsrStd` 的所有方法都必须在**同一个拥有有效 OpenGL ES 上下文的 GL 线程**中调用。

---

## 3.4 生命周期与线程建议

- 在创建任何 `TieStd`、`TiePro`、`TsrStd` 实例前，先确认 `TsrSdkHelper.getInstance().isInit()` 为 `true`。
- `TieStd`、`TsrStd` 的 `init/process/release` 必须在同一个 GL 线程调用。
- `TiePro.init(...)` 建议放在后台线程；如果配合 `TieProGlProcessor` 使用，则 `TieProGlProcessor.init/process/release` 必须在 GL 线程调用。
- 同一个实例不要重复 `init(...)`；如果分辨率发生变化，建议释放旧实例后按新分辨率重新创建。
- 页面退出、播放器销毁或 GL 上下文销毁前，请及时调用 `release()` 释放资源。

---

# 4. 示例

参考 [Demo](./demo/tsr-android-demo/tsr-opengl-demo)

---
