# 终端视频增强SDK
- [[English]](README_en.md)
## 1.产品介绍
终端视频增强SDK，基于高效的图像处理算法和AI模型推理能力，实现终端视频超分辨率、画质增强等功能。超分辨率是指在终端播放时，在尽量保持画质的前提下将原始视频进行高效的上采样，以适应显示设备的播放分辨率。画质增强是指改善图像的视觉质量，使其更加清晰细腻和真实。  
各版本具体功能详情如下： 

| 功能点           | 标准版 | 专业版 |
| ---------------- | ------ | ------ |
| 标准超分辨率     | 支持   | 支持   |
| 标准超分+增强  <br> (亮度/色彩饱和度/对比度)| 支持   | 支持   |
| 专业超分辨率     |        | 支持   |
| AI画质增强      |        | 支持   |  

<br>
<img src=./docs/pro-tsr-cmp.png width=80%/>

>从左到右依次为540P原视频、普通播放、超分播放(标准版)、超分播放(标准版+增强)、超分播放(专业版)。
[链接](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/personal/handleychen/20240621%20%E5%AF%B9%E5%A4%96%E4%BB%8B%E7%BB%8D%E6%95%88%E6%9E%9C/index%28%E6%A8%A1%E7%89%88-%E5%8E%9F%E6%99%AE%E6%A0%87%E6%A0%87%E4%B8%93%29.html)
  
<br>
<img src=./docs/pro-ie-cmp.png width=50% />  

>从左到右依次为540P原视频、增强视频。[链接](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/personal/handleychen/20240621%20%E5%AF%B9%E5%A4%96%E4%BB%8B%E7%BB%8D%E6%95%88%E6%9E%9C/index%28%E6%A8%A1%E7%89%88-%E5%8E%9F%E5%A2%9E%29.html)

* 标准版的优势是性能，我们的算法能以极低的耗时和功耗，实现较好的超分辨率效果。适配几乎所有性能的手机。
* 标准版里还提供了图像增强能力，可以调整图像的亮度、色彩饱和度、对比度。
* 专业版的优势是效果，它通过AI模型推理，能生成原图像缺失的纹理细节，实现最好的图像增强和超分辨率效果。它对设备算力有要求，建议只在中高端手机上使用。

## 2.产品性能
### 标准版超分辨率
在Pixel6手机上测试耗时，标准超分辨率处理1帧图像的GPU耗时低于1ms。

<img src=docs/standard-sr-performance1.png width=30% />

在高中低端机型上测试性能，开启超分后，CPU/内存/GPU/耗电的增量很小，对帧率无影响。4K视频也能在中端手机上实时处理。

<img src=docs/standard-sr-performance2.png width=60% />

### 专业版超分辨率
<img src=docs/pro-sr-performance.png width=80% />

### AI画质增强
<img src=docs/pro-ie-performance.png width=80% />

## 3. 使用场景

1、终端播放器增强，提高视频播放的画质、流畅度体验。  
<img src=docs/scenario_pipeline.png width=50% />

2、节约成本，降低视频分发的分辨率和码率，再通过终端播放增强来减小体验损失。
<img src=docs/scenario_video_trans.png width=70% />

例如，云游戏场景，利用端上实时视频超分辨率的能力，可以降低云端渲染和编码的算力，并且节省传输带宽，节约成本。如下例子，云端传输720P(5.6Mbps)的游戏画面在终端播放实时超分到1080P，观看效果接近云端传输1080P(8.2Mbps)的画面，节省30%带宽。
<img src=docs/scenario_case_game.png width=80% />

## 4. 兼容性
* 标准版：
  - Android： 5.0及以上系统版本；
  - iOS：iPhone 5s且iOS 12，及以上。机型适配率高。
* 专业版：
  - Android： 5.0及以上系统版本，且支持OpenCL 1.2及以上版本；
  - iOS：低算力（FAST）算法iOS 15及以上，高算力（HIGH_QUALITY）算法iOS 16及以上。

## 5. 包大小
* 标准版：Android AAR约 0.3MB（单arm64-v8a架构）；iOS Framework 0.4MB。
* 专业版：Android AAR约 2.1MB（单arm64-v8a架构）；iOS Framework 1.9MB。

## 6. 体验Demo
[下载链接](https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-demo-android/SRPlayer.apk)  
用手机系统浏览器打开，下载安装   
|Android|
| :- |
| <img src=./docs/android-demo-qrcode.png width=30% />|

<div style="display:flex;">
  <img src="./docs/android_demo_page1.png" width="30%" />
  <img src="./docs/android_demo_page2.png" width="30%" />
  <img src="./docs/android_demo_page3.png" width="30%" />
</div>

## 7. 接入指引
参考[Android](Android%20接入指南.md)、[iOS](iOS%20接入指南.md)接入指南。
