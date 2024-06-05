# 实时画质增强SDK
实时画质增强SDK是一款功能强大的画面处理工具，包含超分辨率和画质增强功能。适应手机终端算力、能耗、机型兼容、包体增量的限制，以极高性能和极低功耗实现终端上最佳的超分辨率与画质增强质量。

提供标准版和专业版两个版本，支持Android和iOS系统。

## 算法效果
### 超分辨率
540P -> 1080P

左：专业版超分；中：标准版超分；右：直接播放

可点击链接对比查看：https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-sr-cmp-v/index.html
<img src=./docs/pro-tsr-cmp.png />

### 画质增强
左：专业版画质增强；右：原图

可点击链接对比查看：https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-ie-cmp-v/index.html
<img src=./docs/pro-ie-cmp.png />

## 性能
### 标准版超分辨率
<img src=docs/standard-sr-performance.png width=60% />

### 专业版超分辨率
<img src=docs/pro-sr-performance.png width=80% />

### 专业版画质增强
<img src=docs/pro-ie-performance.png width=80% />

## SDK版本
| SDK版本     | 标准版超分辨率 | 专业版超分辨率 | 专业版画质增强 |
| :- |:-|:--------|:--------|
| 标准版   | ✅    | ❌       | ❌       |
| 专业版   | ✅    | ✅       | ✅       |
* 标准版：提供标准版超分辨率功能，实现快速的超分辨率处理速度，适用于高实时性要求的场景。在这种模式下，可以实现显著的图像质量改善。

* 专业版：提供的功能包括标准版超分辨率、专业版超分辨率、专业版画质增强。专业版超分辨率和画质增强适用于高质量要求的场景，但对设备性能有一定要求，建议在中高端智能手机上使用。

## 兼容性
* Android平台：Android5.0以上（API 21，OpenGL ES 3.1），兼容目前Android市场上99.6%的手机。

* iOS平台：适用于 iPhone 5s及更高版本的设备，最低系统版本为iOS 12。

## 包大小
* 标准版：Android AAR约0.6M（含arm64-v8a和armeabi-v7a）；iOS Framework 1.69MB。

* 专业版：Android AAR 6.46MB（含arm64-v8a和armeabi-v7a）；iOS Framework 6.88MB。

## 体验Demo
用手机系统浏览器打开，下载安装   
|Android|
| :- |
| <img src=./docs/android-demo-qrcode.png width=30% />|


## 接入SDK
<img src=./docs/integrate1.png width=40% />

参考[接入指南](接入指南.md)
