# OES 纹理路径 Demo 架构说明

> **适用场景**：您的视频解码器通过 `Surface` 输出帧（例如 MediaCodec Surface 模式），希望接入 TieEngine 进行画质增强后再上屏渲染。

---

## 1. 整体架构

Demo 由 3 个核心类组成，职责分明：

```
┌─────────────────────────────────────────────────┐
│              OesTexturePathActivity              │
│  Activity + GLSurfaceView.Renderer              │
│  负责：生命周期 · 解码器 · 帧调度 · UI · 统计    │
└──────────┬──────────────────────┬───────────────┘
           │                      │
   ┌───────▼───────┐      ┌──────▼──────────┐
   │ VideoSizeProbe │      │ VideoEnhance    │
   │ 视频尺寸探测    │      │ Pipeline        │
   │ (一次性工具)    │      │ 增强链路编排     │
   └───────────────┘      └─────────────────┘
```

| 类名 | 角色 | 线程 |
|------|------|------|
| `OesTexturePathActivity` | Activity 入口，编排整体流程 | 主线程 + GL 线程 |
| `VideoEnhancePipeline` | 增强链路门面：拆分 Y/UV → TieEngine 增强 → 合成上屏 | GL 线程（渲染）+ 后台线程（引擎 init） |
| `VideoSizeProbe` | 轻量探测视频宽高，避免循环依赖 | 调用方线程（一次性） |

---

### 依赖的 GL 原语层

以上三个类工作在 **编排层**，它们依赖 `com.tencent.mps.srplayer.common.gl` 包下的 **GL 原语层**，后者是真正可跨链路复用的底层组件：

| GL 原语 | 职责 | 与 demo 的关系 |
|------|------|------|
| `OesTextureFactory` | 创建 OES 外部纹理（`GL_TEXTURE_EXTERNAL_OES`） | `VideoEnhancePipeline.initGl()` 调用它创建 OES 纹理 |
| `OesYuvSplitter` | OES 纹理 → Y(ByteBuffer) + UV(GPU 纹理)，基于 FBO + shader 拆分 | `VideoEnhancePipeline.drawFrame()` 用它拆帧 |
| `Nv12GpuRenderer` | Y(CPU) + UV(GPU) → NV12 纹理 → 屏幕 | `VideoEnhancePipeline.drawFrame()` 用它合成上屏 |
| `Nv12Shaders` | Shader 编译与链接工具（被 `OesYuvSplitter`、`Nv12GpuRenderer` 内部引用） | 间接依赖 |

这些 GL 原语只依赖 `TsrSdk` 的 `TieEngine` 和 Android GLES，不引用任何 app 业务代码，可被多条链路（如 OES 路径、Texture2D 路径、EnhanceSurface 路径）共享。

**分层关系图：**

```
┌─────────────────────────────────────────────────────────┐
│  编排层 (demo/oestexture)                               │
│  OesTexturePathActivity  ·  VideoEnhancePipeline        │
│  VideoSizeProbe                                        │
├─────────────────────────────────────────────────────────┤
│  GL 原语层 (common/gl)   ← 可跨链路复用                 │
│  OesTextureFactory  ·  OesYuvSplitter                   │
│  Nv12GpuRenderer    ·  Nv12Shaders                      │
├─────────────────────────────────────────────────────────┤
│  SDK 层 (TsrSdk)                                       │
│  TieEngine  ·  TsrPass                                  │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 端到端数据流

每一帧从解码器输出到最终上屏，走以下路径：

```
┌──────────────┐
│  MediaCodec   │  解码器将解码帧写入 Surface
│ (Decode2Surface)│
└──────┬───────┘
       │ Surface
       ▼
┌──────────────┐
│ SurfaceTexture │  OES 外部纹理（GPU 可读，无需 CPU 拷贝）
│  (GL_TEXTURE_  │
│   EXTERNAL_OES)│
└──────┬───────┘
       │ updateTexImage() 将最新帧绑定到纹理
       ▼
┌──────────────────────────────────────────┐
│         OesYuvSplitter.splitFrame()       │
│                                            │
│  OES 纹理 ──► Y 平面 (ByteBuffer, CPU)    │
│           ──► UV 平面 (2D纹理, GPU)       │
│                                            │
│  拆帧方式：GPU shader 将 OES 采样后        │
│  分别写入两个离屏 FBO，Y 通过 readPixels   │
│  回读到 CPU，UV 留在 GPU 纹理中。          │
└──────┬───────────────────┬───────────────┘
       │ Y(ByteBuffer)     │ UV(纹理ID)
       ▼                   │
┌──────────────┐           │
│  TieEngine    │  增强 Y  │
│  .process()   │  平面    │
└──────┬───────┘           │
       │ 增强后 Y          │
       │ (ByteBuffer)      │
       ▼                   ▼
┌──────────────────────────────────────────┐
│       Nv12GpuRenderer.render()            │
│                                            │
│  增强Y(CPU) + UV(GPU) ──► NV12纹理 ──► 屏幕 │
└──────────────────────────────────────────┘
```

**关键设计决策**：
- **只将 Y 平面回读到 CPU**，UV 全程留在 GPU。原因是 TieEngine 只需要 Y 通道做增强，这样可以省去一次 GPU→CPU 的大数据量拷贝（UV 占帧数据量的 50%）。

---

## 3. 初始化流程

初始化需要解决一个循环依赖问题：**解码器需要 Surface → Surface 需要 OES 纹理 → OES 纹理和相关 GL 资源需要视频尺寸**。Demo 通过 `VideoSizeProbe` 打破这个循环：

```
onSurfaceCreated (GL线程)
  │
  ├─ ① VideoSizeProbe.probe()
  │     用 MediaExtractor 轻量读取视频宽高
  │     不创建解码器，不需要 Surface
  │
  ├─ ② new VideoEnhancePipeline(type, w, h)
  │     构造增强门面，不预选档位
  │
  ├─ ③ pipeline.initGl(w, h) → 返回 OES 纹理 ID
  │     内部创建：OesYuvSplitter + Nv12GpuRenderer
  │
  ├─ ④ new SurfaceTexture(oesTexId) → new Surface()
  │     用 pipeline 返回的纹理 ID 建 Surface
  │
  ├─ ⑤ new Decode2Surface().init(uri, surface)
  │     解码器拿到 Surface，开始解码
  │
  ├─ ⑥ 主线程 tryInitTieY()
  │     投递到后台线程：pipeline.initEngineBlocking(context)
  │     内部自动选档；分辨率不支持时返回 CODE_UNSUPPORTED_SIZE
  │
  └─ ⑦ startPlay() 开始帧调度
```

---

## 4. 每帧渲染流程 (`onDrawFrame`)

```
mDecoder.dequeueOutputFrameToSurface()   ── 解码器吐一帧到 Surface
    │
mSurfaceTexture.updateTexImage()          ── 绑定最新帧到 OES 纹理
mSurfaceTexture.getTransformMatrix()      ── 获取纹理变换矩阵
    │
mPipeline.drawFrame(oesTexId, stMatrix, enhance)
    │
    ├─ splitter.splitFrame()
    │      OES → Y(ByteBuffer) + UV(纹理ID)
    │
    ├─ engine.process(yBuffer)
    │      增强 Y 平面（仅 enhance && engineReady 时）
    │
    └─ renderer.render(yBuffer, uvTexId)
           NV12 合成 → 上屏
    │
mScheduler.onFrameRendered(ptsUs)         ── 按 PTS 调度下一帧
```

---

## 5. 线程模型

```
┌─────────────────┐     ┌─────────────────────┐     ┌─────────────────┐
│     主线程        │     │      GL 线程          │     │    后台线程       │
│  (UI / 生命周期)  │     │  (SurfaceView渲染)    │     │  (单线程池)       │
├─────────────────┤     ├─────────────────────┤     ├─────────────────┤
│ • onClick 事件   │     │ • onSurfaceCreated   │     │ • TieEngine.init │
│ • 状态栏刷新      │     │ • onDrawFrame        │     │ • TieEngine      │
│ • 生命周期回调    │     │ • 帧调度 callback     │     │   .release       │
│ • 投递后台任务    │     │ • GL 资源创建/释放    │     │                  │
└─────────────────┘     └─────────────────────┘     └─────────────────┘
```

**线程安全说明**：
- `TieEngine.init` 可能长时间阻塞，必须在后台线程调用（demo 使用 `Threads.getSingleExecutor()`）
- 所有 GL 操作（initGl、drawFrame、releaseGl）必须在 GL 线程
- 状态标志 `mTieInitReady`、`mTieSizeSupported` 使用 `volatile` 保证跨线程可见性

---

## 6. `VideoEnhancePipeline` 详解

这是 demo 中最重要的类，也是建议客户直接复用或参考的核心编排。

### 6.1 构造参数

```java
new VideoEnhancePipeline(TieEngine.Type type, int videoWidth, int videoHeight)
```

- `type`：`IE_Y`（同尺寸增强）或 `SR_Y`（超分辨率）
- `videoWidth / videoHeight`：视频实际分辨率（正偶数）

### 6.2 关键方法

| 方法 | 线程 | 说明 |
|------|------|------|
| `initGl(w, h)` | GL | 创建 OES 纹理 + 拆分器 + 渲染器，返回纹理 ID |
| `initEngineBlocking(ctx)` | 后台 | 初始化 TieEngine（阻塞），内部自动选档 |
| `drawFrame(texId, matrix, enhance)` | GL | 一帧处理：拆分→增强→上屏 |
| `releaseGl()` | GL | 释放 GL 资源（拆分器 + 渲染器） |
| `releaseEngine()` | 后台 | 释放 TieEngine |

### 6.3 内部组件

```
VideoEnhancePipeline
  ├── OesTextureFactory     创建 OES 外部纹理（GL_TEXTURE_EXTERNAL_OES）
  ├── OesYuvSplitter        将 OES 纹理拆成 Y 平面 + UV 纹理
  │                          - Y 输出：ByteBuffer（CPU 内存）
  │                          - UV 输出：2D 纹理 ID（GPU）
  └── Nv12GpuRenderer       将 Y(CPU) + UV(GPU) 合成 NV12 纹理上屏
```

### 6.4 两种使用方式

**方式一：直接使用**
如果您的链路与 demo 一致（解码器 Surface → OES → 增强 → 上屏），可以直接 `new VideoEnhancePipeline(...)` 开箱即用。

**方式二：参考编排后自定义**
如果您的场景不同（例如自管 EGL 上下文、OES 来自 Camera 或游戏渲染、需要在拆帧和上屏之间插入自定义处理），建议复制 `VideoEnhancePipeline` 的编排逻辑，替换或裁剪其中的步骤。真正可复用的基础组件在 `com.tencent.mps.srplayer.common.gl` 包下：
- `OesYuvSplitter` — 职责单一，只做 OES→Y/UV 拆分
- `Nv12GpuRenderer` — 职责单一，只做 Y+UV→NV12 上屏
- `OesTextureFactory` — 创建 OES 纹理的工具方法

---

## 7. `VideoSizeProbe` 工具类

解决"先有鸡还是先有蛋"的问题：

```
没有 VideoSizeProbe 时：
  解码器.configure() → 需要 Surface → 需要 OES 纹理 → 需要视频尺寸 → 需要解码器.configure()

有 VideoSizeProbe 后：
  ① VideoSizeProbe.probe() → 拿到视频尺寸（无需解码器/纹理）
  ② 按正确顺序建资源：纹理 → Surface → 解码器
```

实现原理：只用 `MediaExtractor` 读取视频轨道元数据中的 `width`/`height`，不创建 `MediaCodec`，因此不需要 Surface，速度极快。

---

## 8. 帧调度与性能统计

- **帧调度**：使用 `FrameScheduler`，基于解码器吐帧的 PTS（Presentation Time Stamp）计算下一帧的渲染时机，而非按固定帧率驱动。这样能保证播放节奏与视频原始帧率一致。
- **性能统计**：使用 `FrameMetrics` 聚合每帧耗时，按固定周期（`FPS_CAL_INTERVAL_MS`）输出 FPS 和分段耗时（updateTexImage / split / process），方便客户评估性能表现。

---

## 9. 增强开关逻辑

增强是否生效取决于三个条件同时满足：

| 条件 | 含义 | 何时确定 |
|------|------|----------|
| `mEnhance` | 用户 toggle 开关打开 | 用户点击按钮 |
| `mTieInitReady` | TieEngine 初始化成功 | 后台线程 init 返回 CODE_OK |
| `mTieSizeSupported` | 视频分辨率在支持的档位范围内 | 后台线程 init 返回结果 |

任一条件不满足时，`drawFrame` 的第 3 个参数传 `false`，pipeline 会跳过 TieEngine 处理，直接上屏原始 Y 画面（"透传模式"）。这样即使引擎初始化失败或不支持当前分辨率，视频也能正常播放。

---

## 10. 资源释放

```
onDestroy (主线程)
  ├── scheduler.stop()
  ├── decoder.release()                          // 主线程释放解码器
  ├── executor.submit(pipeline::releaseEngine)    // 后台线程释放 TieEngine
  └── glSurfaceView.queueEvent(this::releaseGlResources)  // GL 线程释放
         ├── pipeline.releaseGl()       → 释放 splitter + renderer
         ├── surfaceTexture.release()
         ├── decoderSurface.release()
         └── glDeleteTextures(oesTexId)
```

释放必须分线程进行：TieEngine 在后台线程释放、GL 资源在 GL 线程释放、解码器在主线程释放，避免跨线程操作导致的崩溃。

---

## 11. Texture2D 输入适配指南

> **适用场景**：您的增强源帧已经是 `GL_TEXTURE_2D`（普通 2D 纹理），而非 `GL_TEXTURE_EXTERNAL_OES`（OES 外部纹理）。例如：游戏引擎渲染输出、Camera2 的 SurfaceTexture 已由您的管线转为 Texture2D、自定义渲染管线中产生的中间纹理等。

### 11.1 与 OES 路径的核心差异

| 维度 | OES 路径 | Texture2D 路径 |
|------|----------|----------------|
| 纹理类型 | `GL_TEXTURE_EXTERNAL_OES` | `GL_TEXTURE_2D` |
| Shader 采样器 | `samplerExternalOES` | `sampler2D` |
| 纹理坐标变换 | 需要 `stMatrix`（4×4 变换矩阵） | 不需要，直接使用标准 UV 坐标 `[0,1]` |
| 帧同步方式 | `SurfaceTexture.updateTexImage()` | 无需（您的管线自行管理纹理更新） |
| OES 纹理创建 | 需要 `OesTextureFactory.createOesTexture()` | 不需要（您已有纹理） |
| 解码器 | 需要 `MediaCodec` + `Surface` 配合 | 不需要（帧数据来自您的管线） |
| `VideoSizeProbe` | 需要（解决 Surface→OES 纹理的循环依赖） | **不需要**（您已知道纹理尺寸） |

**本质差异只有一点**：`OesYuvSplitter` 的 shader 中用的是 `samplerExternalOES` + `stMatrix`，改为 Texture2D 后只需把 shader 换成 `sampler2D` + 标准 UV，其他所有步骤（FBO 渲染 → `glReadPixels` 回读 Y → TieEngine 增强 → NV12 合成上屏）**完全一致**。

### 11.2 改造步骤概览

```
您现有的管线
    │
    ▼
  Texture2D (RGBA, W×H)
    │
    ├─(1)─► 改造 OesYuvSplitter → Texture2dYuvSplitter
    │       只改 shader：samplerExternalOES→sampler2D，去掉 stMatrix
    │       输出：Y(ByteBuffer) + UV(GPU 纹理) —— 与原版完全相同
    │
    ├─(2)─► TieEngine.process(yBuffer) —— 不需要任何改动
    │
    └─(3)─► Nv12GpuRenderer.render(yBuffer, uvTexId) —— 不需要任何改动
```

只需要做一步改造：将 `OesYuvSplitter` 的 shader 从 OES 采样改为 Texture2D 采样。其余组件（`TieEngine`、`Nv12GpuRenderer`）都**零改动复用**。

### 11.3 关键改造：创建 `Texture2dYuvSplitter`

以下是基于 `OesYuvSplitter` 改造的最小 diff，只改 3 处：

#### 改动 ①：Vertex Shader 去掉 stMatrix

```glsl
// OES 版本的 vertex shader（需要 stMatrix 变换纹理坐标）：
uniform mat4 uStMatrix;
vTexCoord = (uStMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;

// Texture2D 版本的 vertex shader（直接透传 UV）：
vTexCoord = aTexCoord;
```

#### 改动 ②：Fragment Shader 将 `samplerExternalOES` 改为 `sampler2D`

```glsl
// ── OES 版本的 fragment shader（Y 通道） ──
#extension GL_OES_EGL_image_external_essl3 : require
uniform samplerExternalOES sOes;
// ...
vec3 rgb = texture(sOes, vTexCoord).rgb;

// ── Texture2D 版本的 fragment shader（Y 通道） ──
// 不需要 #extension 声明
uniform sampler2D sTex;
// ...
vec3 rgb = texture(sTex, vTexCoord).rgb;
```

UV 通道的 fragment shader 同理，只改类型声明和采样调用，颜色转换公式保持不变。

#### 改动 ③：`splitFrame` 方法签名去掉 `stMatrix` 参数，GPU 纹理绑定改为 `GL_TEXTURE_2D`

```java
// OES 版本：
public boolean splitFrame(int oesTextureId, float[] stMatrix) {
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
    // 传入 stMatrix 给 shader ...
}

// Texture2D 版本：
public boolean splitFrame(int tex2dId) {
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex2dId);
    // 不需要 stMatrix，shader 也不接收 stMatrix uniform ...
}
```

**完整改造量**：两个 shader 各约 3 行改动 + 一个方法签名的参数精简，整个类的其他代码（FBO 管理、`glReadPixels` 回读 Y、UV 纹理管理、资源释放）**完全不变**。

### 11.4 改造 `VideoEnhancePipeline` 适配 Texture2D

现有 `VideoEnhancePipeline` 的三个方法需要微调：

| 方法 | 改动 |
|------|------|
| `initGl(w, h)` | 内部 `new OesYuvSplitter()` → `new Texture2dYuvSplitter()`；**去掉** `OesTextureFactory.createOesTexture()`，不再返回 OES 纹理 ID（您已有纹理，返回任意标识或 void） |
| `drawFrame(texId, stMatrix, enhance)` | 去掉 `stMatrix` 参数，内部调用 `splitter.splitFrame(texId)` |
| `getLastFrameInfo()` 等查询方法 | 无需改动 |

改造后的 `drawFrame` 核心伪代码：

```java
public boolean drawFrame(int tex2dId, boolean enhance) {
    // 1) Texture2D → Y(ByteBuffer) + UV(GPU 纹理)
    if (!mSplitter.splitFrame(tex2dId)) {
        return false;
    }

    ByteBuffer yBuffer = mSplitter.getYBuffer();
    int uvTextureId = mSplitter.getUvTextureId();

    // 2) TieEngine 增强 Y —— 零改动
    ByteBuffer yToUse = yBuffer;
    int yStrideToUse = mVideoWidth;
    boolean enhanced = false;
    if (enhance && mEngineReady) {
        ByteBuffer out = mEngine.process(yBuffer, mVideoWidth, mVideoHeight);
        // ... 校验、缓存耗时信息，与原版完全一致 ...
        yToUse = out;
        yStrideToUse = mOutputStride;
        enhanced = true;
    }

    // 3) NV12 合成上屏 —— 零改动
    mRenderer.render(yToUse, yStrideToUse, uvTextureId);
    return true;
}
```

### 11.5 完整调用示例（Texture2D 路径）

假设您的管线已经产出了一个 RGBA 的 `GL_TEXTURE_2D` 纹理（尺寸 W×H），下面是完整的接入伪代码：

```java
// ========== GL 线程：初始化 ==========
int videoW = 1920, videoH = 1080;  // 您的纹理尺寸

VideoEnhancePipeline pipeline = new VideoEnhancePipeline(
    TieEngine.Type.IE_Y, videoW, videoH);

pipeline.initGl(videoW, videoH);  // 内部创建 Texture2dYuvSplitter + Nv12GpuRenderer
                                  // 不再需要返回 OES 纹理 ID

// ========== 后台线程：初始化 TieEngine ==========
executor.submit(() -> {
    InitResult result = pipeline.initEngineBlocking(context);
    if (result.code == InitResult.CODE_UNSUPPORTED_SIZE) {
        // 分辨率不支持，关闭增强，后续 drawFrame(..., false) 透传原画
    }
});

// ========== GL 线程：每帧渲染 ==========
// 假设您的纹理 ID 是 mInputTexId，每帧更新后调用：
pipeline.drawFrame(mInputTexId, enhanceEnabled);
// 无需 updateTexImage()，无需 stMatrix

// ========== 释放 ==========
// GL 线程：pipeline.releaseGl()
// 后台线程：pipeline.releaseEngine()
```

### 11.6 与 OES 路径的对比总结

```
═══════════════════════════════════════════════════════════════
                    OES 路径 vs Texture2D 路径
═══════════════════════════════════════════════════════════════

OES 路径                         Texture2D 路径
───────                          ─────────────
MediaCodec → Surface             [您的管线] → Texture2D
    │                                │
SurfaceTexture.updateTexImage()   （无需，纹理已是最新帧）
SurfaceTexture.getTransformMatrix()（无需，标准 UV 坐标）
    │                                │
OesYuvSplitter.splitFrame         Texture2dYuvSplitter.splitFrame
  (samplerExternalOES + stMatrix)   (sampler2D，无 stMatrix)
    │                                │
    ├── Y(ByteBuffer)                ├── Y(ByteBuffer)  ← 相同
    └── UV(GPU 纹理)                 └── UV(GPU 纹理)   ← 相同
    │                                │
TieEngine.process(yBuffer)         TieEngine.process(yBuffer)  ← 零改动
    │                                │
Nv12GpuRenderer.render            Nv12GpuRenderer.render       ← 零改动
    │                                │
    ▼                                ▼
  屏幕                             屏幕
═══════════════════════════════════════════════════════════════
```

**一句话总结**：Texture2D 路径只需将拆分器的 shader 从 OES 采样改为标准 2D 采样，其余所有组件（TieEngine、Nv12GpuRenderer、帧调度、性能统计）全部零改动复用。改造量约 **30 行代码**。
