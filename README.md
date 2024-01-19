# 超分辩率
## 播放器场景
终端播放器集成超分辨率能力，提高播放清晰度。

<img src=./docs/scenario_play.png/>

540P图像双线性放大1.5倍（test540\_1.5\_bilerp）和超分放大1.5倍（test540\_1.5\_tsr）的[效果对比](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/personal/handleychen/icat-test540.html)
## 监控场景
受益于播放端的超分功能，可以降低视频监控上行的带宽和存储。

<img src=./docs/scenario-monitor.png width=60% />


## 性能
测试手机Google Pixel 6（芯片Tensor, GPU Mali-G78，性能约等于骁龙870）。720P图像2倍超分耗时约0.5ms，1080P图像2倍超分耗时约0.9ms。对播放器渲染帧率几乎无影响。
更多性能数据如下
|原始分辨率|超分后分辨率|耗时|
| :- | :- | :- |
|352x640|704x1280|0.2ms|
|544x960|1088x1920|0.3ms|
|720x1280|1440x2560|0.5ms|
|1080x2400|2160x4800|1ms|

## 兼容性
Android平台：Android5.0以上（API 21，OpenGL ES 3.1），兼容目前Android市场上99.6%的手机。

iOS平台：适用于 iPhone 5s及更高版本的设备，最低系统版本为iOS 12。

## 包大小
Android AAR约0.6M（含arm64-v8a和armeabi-v7a）。iOS Framework约1.6M。

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
