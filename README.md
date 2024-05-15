# 超分辩率
## 播放器场景
终端播放器集成超分辨率能力，提高播放清晰度。

<img src=./docs/scenario_play.png />

540P图像双线性放大1.5倍（test540\_1.5\_bilerp）和超分放大1.5倍（test540\_1.5\_tsr）的[效果对比](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/personal/handleychen/icat-test540.html)
## 监控场景
受益于播放端的超分功能，可以降低视频监控上行的带宽和存储。

<img src=./docs/scenario-monitor.png width=60% />

## 支持的平台
| SDK版本     | Android   |iOS| Windows|
| :- | :- | :- | :- |
| 标准版   | ✅  |✅| ❓规划中|
| 专业版   | ✅ | ❌开发中 | ❓规划中|

## 支持的算法
| SDK版本     | 标准版超分辨率 | 专业版超分辨率 | 专业版图像增强 |
| :- |:-|:--------| :- |
| 标准版   | ✅    | ❌       | ❌|
| 专业版   | ✅    | ✅       | ✅ |
* 标准版：提供标准版超分辨率功能，实现快速的超分辨率处理速度，适用于高实时性要求的场景。在这种模式下，可以实现显著的图像质量改善。

* 专业版：提供的功能包括标准版超分辨率、专业版超分辨率、专业版图像增强。专业版超分辨率和图像增强适用于高质量要求的场景，但对设备性能有一定要求，建议在中高端智能手机上使用。

## 画面效果
### 标准版——超分辨率
左：标准版超分，右：直接播放

可点击链接对比查看：https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-sr-cmp/index.html
<img src=./docs/standard-tsr-cmp.png />

### 专业版
#### 超分辨率
左：专业版超分；中：标准版超分；右：直接播放

可点击链接对比查看：https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-sr-cmp/index.html
<img src=./docs/pro-tsr-cmp.png />

#### 图像增强
左：专业版增强；右：原图

可点击链接对比查看：https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-ie-cmp/index.html
<img src=./docs/pro-ie-cmp.png />

## 性能
### 标准版
<img src=docs/standard-sdk-performance.png width=60% />

### 专业版
<img src=docs/pro-sdk-performance.png width=60% />

## 兼容性
Android平台：Android5.0以上（API 21，OpenGL ES 3.1），兼容目前Android市场上99.6%的手机。

iOS平台：适用于 iPhone 5s及更高版本的设备，最低系统版本为iOS 12。

## 包大小
* 标准版：Android AAR约0.6M（含arm64-v8a和armeabi-v7a）。iOS Framework约1.6M。

* 专业版：Android AAR 6.46MB

## 优势
适应手机终端算力、能耗、机型兼容、包体增量的限制，以极高性能和极低功耗实现终端上最佳的超分辨率质量。

## 体验Demo
用手机系统浏览器打开，下载安装   
|Android|
| :- |
| <img src=./docs/android-demo-qrcode.png width=30% />|


## 接入SDK
<img src=./docs/integrate1.png width=40% />

参考[接入指南](接入指南.md)
