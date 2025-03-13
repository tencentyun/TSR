# 终端视频增强SDK
[[English]](https://github.com/tencentyun/TSR/blob/main/README_en.md)

终端视频增强SDK，基于高效的图像处理算法和AI模型推理能力，实现终端视频播放超分辨率、画质增强等功能。超分辨率是指在尽量保持画质的前提下将原始视频进行高效的上采样，以适应显示设备的播放分辨率。画质增强是指改善图像的视觉质量，使其更加清晰细腻和真实。  


<img src="./docs/pro-tsr-cmp.png"/>
<a href="https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/personal/handleychen/202409%E5%AF%B9%E5%A4%96%E8%AF%84%E6%B5%8B/%E7%94%B5%E5%BD%B1%E7%89%87%E6%AE%B5landscape/%E5%8E%9F%E6%99%AE%E6%A0%87%E6%A0%87%E4%B8%93_540land.html">[视频对比]</a>

<br>
<br>
终端视频增强SDK分为标准版与专业版：

* 标准版的特点是性能高，以极低的计算耗时和功耗，实现良好的超分辨率效果。适配所有性能的手机。标准版里也提供了对图像亮度、色彩饱和度和对比度调整增强的能力。

* 专业版的特点是效果更好，除了上采样外，专业版还利用AI模型推理生成原图像缺失的纹理细节，实现更好的图像增强和超分辨率效果。但它有算力要求，建议只在中端机以上使用。

### 核心功能矩阵： 

| 功能点           | 标准版 | 专业版 |
|---------------| ------ | ------ |
| 标准画质增强        | 支持   | 支持   |
| 标准超分辨率        | 支持   | 支持   |
| 标准超分辨率 + 色彩优化 | 支持   | 支持   |
| 专业画质增强        |        | 支持   |  
| 专业超分辨率        |        | 支持   |
| 专业超分辨率 + 色彩优化 |        | 支持   |

### 版本选择建议
* 标准版：适用于性能敏感型设备，满足基础画质提升需求 
* 专业版：为中高端设备设计，通过深度学习模型实现高画质体验


## 1. 使用场景

### (1) 终端播放质量增强
通过实时超分与画质修复技术，将480P/720P视频提升至1080P显示效果，适配高分辨率移动设备屏幕，改善流媒体播放清晰度与流畅度体验。

<img src="./docs/scenario_pipeline.png" width=50% />

### (2) 带宽成本优化
在云游戏、直播推流等场景中，通过端侧超分技术实现"低码率传输+高清晰度呈现"的解决方案。实际测试表明，传输720P(5.6Mbps)视频经端侧超分至1080P，视觉效果接近原生1080P(8.2Mbps)流，带宽节省达30%。

<img src="./docs/scenario_video_trans.png" width=70% />

<img src="./docs/scenario_case_game.png"/>
<a href="https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/personal/handleychen/202409%E5%AF%B9%E5%A4%96%E8%AF%84%E6%B5%8B/%E5%8E%9F%E7%A5%9E%E6%B8%B8%E6%88%8Flandscape/%E5%8E%9F%E6%99%AE%E6%A0%87%E6%A0%87%E4%B8%93%E4%B8%93%E7%9B%AE_720land.html">[视频对比]</a>

### (3) 多源视频标准化处理
在视频编辑、多画面合成等场景中，通过智能超分统一不同分辨率素材，避免传统插值算法导致的边缘模糊问题，保证合成视频的视觉一致性。

## 2. 产品优势
<table>
  <tr>
    <th width=100px>类目</th>
    <th>说明 </th>
  </tr>
  <tr height=180px;>
    <td align=center>性能</td>
    <td>
      <li>场景广泛：能处理各种分辨率、适用各种场景。
      <li>高效处理：标准版算法在2016年千元机上处理720P超分1.5倍至1080P平均每帧耗时为1ms以内；专业版算法在2020年千元机上处理540P超分2倍至1080P平均帧耗时为20ms，720P超分1.5倍平均帧耗时为29ms。
      <li>使用灵活：具备根据设备性能自适应选择算法的能力，在清晰播放的同时拒绝卡顿。
    </td>
  </tr>
  <tr height=180px;>
    <td align=center>兼容性</td>
    <td><li>支持市面上绝大多数Android与iOS主流机型。</td>
  </tr>
</table>


## 3.产品性能参考
### 标准版
<img src=docs/standard-sr-performance.png width=70% />

### 专业版
<img src=docs/pro-sr-performance.png width=70% />
 
<img src=docs/pro-ie-performance.png width=70% />


## 4. 设备兼容性
| 版本  | Android要求 | iOS要求 |
|-----|-----------|-------|
| 标准版 | ≥5.0（OpenGL ES 3.1+）        | ≥12   |
| 专业版 | ≥5.0（OpenCL 1.2+）        | ≥16   |


## 5. 包大小
| 操作系统         | 标准版   | 专业版   |
|--------------|-------|-------|
| Android（单架构） | 0.3MB | 2.1MB |
| iOS（真机）      | 0.4MB | 4.2MB |

## 6. 体验Demo

<div>
<table>
  <tr align=center>
    <th width=150px;>系统</th>
    <th width=150px;>二维码</th>
    <th width=150px;>链接</th>
  </tr>
  <tr align=center>
    <td>Android</td>
    <td><img src="./docs/android-demo-qrcode.png"/></td>
    <td> <a href="https://cg-sdk-1258344699.cos.ap-nanjing.myqcloud.com/tsr/pro-demo-android/MPSDemo.apk">MPSDemo</a> </td>
  </tr>
</table>
</div>
<br>
<div style="display:flex;">
  <img src=docs/android-demo-1.png width="30%" /> 
  <img src=docs/android-demo-2.png width="30%" margin-left=40px />
</div>

## 7. 接入指引
参考[Android](https://github.com/tencentyun/TSR/blob/main/Android%20%E6%8E%A5%E5%85%A5%E6%8C%87%E5%8D%97.md)、[iOS](https://github.com/tencentyun/TSR/blob/main/iOS%20%E6%8E%A5%E5%85%A5%E6%8C%87%E5%8D%97.md)接入指南。
