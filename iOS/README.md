# TSR iOS Demo

基于腾讯 TSR SDK 的 iOS 端画质增强与超分演示应用。

## 目录结构

```
iOS/
├── README.md
├── .gitattributes          # Git LFS 配置
├── TsrDemo/                # Demo Xcode 工程
│   └── TsrDemo.xcodeproj/
│   └── TsrDemo/
│       ├── Application/    # AppDelegate、main.m、Info.plist
│       ├── Modules/        # 各功能演示页面
│       │   ├── MainViewController       # 首页：鉴权 + 功能入口
│       │   ├── PlayViewController       # 增强/超分演示
│       │   ├── VideoRendererMetal       # Metal 上屏渲染器
│       ├── Resources/
│       │   ├── Videos/      # 测试视频素材
│       │   └── Shaders/     # Demo 专用 Metal Shader
│       └── Configurations/
│           ├── Config.xcconfig.example   # 配置文件模板
└── Frameworks/
    └── tsr_client.framework  # TSR SDK 预编译 Framework
```

## 快速开始

### 环境要求

- macOS 15+ / Xcode 26+
- iOS 12.0+ 真机（模拟器不支持）
- [Git LFS](https://git-lfs.com/)（用于拉取 Framework 和模型文件）

### 编译运行

```bash
# 1. 安装 Git LFS（如已安装可跳过）
brew install git-lfs

# 2. 克隆仓库（自动拉取 LFS 文件）
git clone <repo-url>
cd tsr-github-v3/iOS

# 3. 配置鉴权凭据
cp TsrDemo/TsrDemo/Configurations/Config.xcconfig.example \
   TsrDemo/TsrDemo/Configurations/Config.xcconfig
# 编辑 Config.xcconfig，填入从腾讯申请获得的 AppId 和 AuthId

# 4. 打开工程，选择真机，编译运行
open TsrDemo/TsrDemo.xcodeproj
```

### 鉴权说明

Demo 启动后首先进行 SDK 鉴权。请将 `Config.xcconfig` 中的 `APP_ID` 和 `AUTH_ID` 替换为从腾讯申请的正式凭据。鉴权通过后才能使用增强/超分等功能。

## SDK 公开头文件

Framework 内置以下公开头文件（位于 `tsr_client.framework/Headers/`）：

| 头文件 | 说明 |
|--------|------|
| `TieSdk.h` | 鉴权接口 |
| `TieEnhancer.h` | 增强/超分功能接口 |
| `TieYuvToRgbPass.h` | NV12→BGRA 色彩空间转换 |
| `TieLogger.h` | 日志回调协议 |
