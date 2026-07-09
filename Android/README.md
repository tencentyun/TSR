# TSR Android Demo

基于腾讯 TSR SDK 的 Android 端画质增强与超分演示应用。

## 目录结构

```
Android/
├── README.md
└── TsrDemo/                       # Demo Gradle 工程
    ├── settings.gradle
    ├── build.gradle               # 根构建脚本（鉴权参数注入）
    ├── gradlew / gradlew.bat
    ├── local.properties            # (gitignored) 填入 APP_ID / AUTH_ID
    ├── app/
    │   ├── build.gradle           # 应用构建脚本（SDK 依赖声明）
    │   └── src/main/
    │       ├── AndroidManifest.xml
    │       ├── java/com/tencent/mps/srplayer/
    │       │   ├── HomeActivity.java          # 首页：鉴权 + 视频源 + 入口
    │       │   ├── SRApplication.java          # Application：SDK 鉴权初始化
    │       │   ├── common/                     # 通用工具
    │       │   │   ├── TieSdkHelper.java       # 鉴权辅助
    │       │   │   ├── AssetsFileCopier.java   # Assets 视频拷贝
    │       │   │   ├── gl/                     # OpenGL 工具
    │       │   │   └── video/                  # 视频解码/调度
    │       │   └── demo/                       # 各 Demo 场景
    │       │       ├── bytebuffer/             # ByteBuffer 模式
    │       │       ├── surface/                # Surface 模式
    │       │       └── texture/                # Texture 模式
    │       └── res/                            # 布局/资源
    └── gradle/
        └── wrapper/                            # Gradle Wrapper
```

## 快速开始

### 环境要求

- Android Studio (最新稳定版)
- Android SDK 35+ / NDK
- ARM64 真机（SDK 仅支持 arm64-v8a 架构）

### 编译运行

```bash
# 1. 克隆仓库
git clone <repo-url>
cd repo/Android

# 2. 配置鉴权凭据（在 local.properties 中添加）
echo "App_Id=<你的 AppId>" >> TsrDemo/local.properties
echo "Auth_Id=<你的 AuthId>" >> TsrDemo/local.properties

# 3. 用 Android Studio 打开 TsrDemo 目录，编译运行
```

> `local.properties` 已在 `.gitignore` 中忽略，不会提交到仓库。

### 鉴权说明

Demo 启动时 `SRApplication` 通过 `TieSdk.init()` 自动发起在线鉴权。请将 `App_Id` 和 `Auth_Id` 替换为从腾讯申请的正式凭据。鉴权通过后 `HomeActivity` 中的功能入口才可使用。

## SDK 依赖

Demo 通过 Maven 坐标引入 TSR SDK：

```groovy
// app/build.gradle
dependencies {
    implementation "com.tencent.tcr:tsrsdk:1.2.0"
}
```

## 视频源

Demo 支持两种视频来源：
- **内置视频**：`app/src/main/assets/video/` 下的 `.mp4` 文件，首次启动自动拷贝到外部存储
- **本地视频**：通过系统文件选择器（SAF）选取

## 构建配置

| 配置项 | 值 |
|--------|-----|
| Application ID | `com.tencent.mps.srplayer` |
| minSdk | 24 |
| targetSdk / compileSdk | 35 |
| ABI | arm64-v8a |
| Java | 11 |
| Gradle | 9.3 (wrapper) |
