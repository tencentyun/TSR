package com.tencent.mps.srplayer.demo.bytebuffer;

import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.tencent.mps.srplayer.common.TieSdkHelper;
import com.tencent.mps.srplayer.common.gl.Nv12Renderer;
import com.tencent.mps.srplayer.common.Threads;
import com.tencent.mps.tie.api.TieEngine.Config;
import com.tencent.mps.tie.api.TieEngine;
import com.tencent.mps.tie.api.TieEngine.InitResult;
import com.tencent.mps.tie.api.TieEngine.ProcessInfo;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * <b>SR 超分 demo</b>：MediaCodec ByteBuffer 解码 → CPU 拿到 NV12 → {@link TieEngine} 把 Y 通道宽高放大
 * {@code upscale} 倍 → {@link Nv12Renderer} 用 Y(upscale·W × upscale·H) + UV(W/2×H/2) 上屏。
 *
 * <p><b>倍率由 SDK 统一决策</b>：调用方只声明 {@link TieEngine.Type#SR_Y} + 视频宽高，2×/3× 选型、档位选择
 * 与必要回退全部在 {@link TieEngine#init} 内部完成；实际倍率、输出尺寸和输出 stride 由
 * {@link InitResult#outputWidth} / {@link InitResult#outputHeight} / {@link InitResult#outputStride} 回传。
 * 超分 GL 渲染器按回传的输出尺寸在 init 成功后惰性创建（init 失败则不创建，自动回退 1x）。</p>
 *
 * <p>与 {@link ByteBufferPathActivity}（同尺寸增强）的关键差异：</p>
 * <ul>
 *     <li>使用 {@link TieEngine.Type#SR_Y}，输出尺寸由 {@link InitResult} 回传；</li>
 *     <li>自持一个 Y 尺寸独立配置的 {@link Nv12Renderer}，通过重写基类渲染钩子将超分结果上屏，基类本身不感知超分。</li>
 * </ul>
 *
 * <p>本类继承 {@link ByteBufferPathBaseActivity}，复用其播放、调度、FPS 统计逻辑。</p>
 */
public class ByteBufferSrPathActivity extends ByteBufferPathBaseActivity {

    private static final String TAG = "ByteBufferSrPathActivity";

    /** 超分引擎：无参构造，倍率/档位在 init 内部决策。 */
    private final TieEngine mTieSrY = new TieEngine();

    /** 有效超分输出尺寸（=视频×倍率），由 {@link InitResult} 回传；渲染器纹理与上屏区域以此为准。 */
    private volatile int mOutputW;
    private volatile int mOutputH;
    /** process buffer 行 stride（=模型档位宽×倍率，可能 > mOutputW），由 {@link InitResult#outputStride} 回传。 */
    private volatile int mOutputStride;

    /** 超分渲染器：Y = outputW × outputH，UV = videoW/2×videoH/2。在 init 成功后于 GL 线程惰性创建。 */
    private Nv12Renderer mNv12SrRenderer;

    // 是否已提交 init 任务，防止重入
    private boolean mInitSubmitted = false;

    // init 是否已完成
    private volatile boolean mTieInitReady = false;

    // 视频分辨率是否能被 TieEngine 超分模型档位覆盖（由 init 错误码 CODE_UNSUPPORTED_SIZE 判定）
    private volatile boolean mTieSizeSupported = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (mToggleButton != null) {
            mToggleButton.setTextOff("超分 关");
            mToggleButton.setTextOn("超分 开");
            mToggleButton.setText(mToggleButtonOn ? "超分 开" : "超分 关");
        }
    }

    @Override
    protected void onGetVideoSize(int width, int height) {
        super.onGetVideoSize(width, height);
        Log.i(TAG, "onGetVideoSize() " + width + "x" + height);

        if (mInitSubmitted) {
            return; // 已提交过，避免重入
        }

        // 倍率选型、档位选择、回退、支持性判定全部下沉到 SDK init：调用方只传 SR_Y 意图 + 视频宽高。
        mInitSubmitted = true;
        final int videoW = width;
        final int videoH = height;
        Threads.getSingleExecutor().submit(() -> {
            if (isFinishing()) {
                return; // Activity 即将销毁，不再占用资源跑 init
            }
            long t0 = SystemClock.elapsedRealtime();
            InitResult result = mTieSrY.init(ByteBufferSrPathActivity.this,
                    new Config(TieEngine.Type.SR_Y, videoW, videoH));
            long initMs = SystemClock.elapsedRealtime() - t0;
            boolean ok = result.code == InitResult.CODE_OK;
            Log.i(TAG, "TieSrY init result in " + initMs + "ms"
                    + ", code=" + result.code
                    + ", inferMs=" + result.inferMs
                    + ", out=" + result.outputWidth + "x" + result.outputHeight
                    + ", msg=" + result.msg);

            // 分辨率不支持：关闭超分（与原 pickSupportSize==null 等价）
            if (result.code == InitResult.CODE_UNSUPPORTED_SIZE) {
                mTieSizeSupported = false;
                markToggleUnsupported();
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(ByteBufferSrPathActivity.this, "当前分辨率 " + videoW + "x" + videoH
                                + " 超出 TieSrY 支持范围，已关闭超分", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            if (TieSdkHelper.isTieEngineInitReady(result)) {
                mOutputW = result.outputWidth;
                mOutputH = result.outputHeight;
                mOutputStride = result.outputStride;
                mTieInitReady = true;
                // init 成功后切回主线程 enable 开关（基类已封装主线程切换、isFinishing、enableAllowed 三重判断）
                markToggleReady();
            } else {
                // init 失败 / 性能不达标：弹 Toast 让用户感知，按钮保持 disable
                final String reason = !ok
                        ? ("init 失败 code=" + result.code + " msg=" + result.msg)
                        : ("init 推理耗时过高 inferMs=" + result.inferMs + "ms");
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(ByteBufferSrPathActivity.this,
                                "TieSrY 初始化失败，已关闭超分：" + reason,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    @Override
    protected ByteBuffer process(ByteBuffer yBuffer, int yStride, int yHeight) {
        if (!mToggleButtonOn || !mTieInitReady || !mTieSizeSupported) {
            mEnhancedYStride = 0;
            return null;
        }
        ByteBuffer enhanced = mTieSrY.process(yBuffer, yStride, yHeight);
        if (enhanced == null) {
            mEnhancedYStride = 0;
            return null;
        }
        // 子类把推理细分耗时拼成人类可读片段注入到 mMetrics.processInfo，由基类每秒输出 breakdown 时拼接。
        // 写入的是当前帧瞬时值（pre/run 单位 ms），与基类的 process/render 秒均值口径略有差异。
        ProcessInfo info = mTieSrY.getLastProcessInfo();
        mMetrics.processInfo = String.format(Locale.getDefault(),
                "[pre=%dms run=%dms post=%dms yStride=%d yHeight=%d]",
                info.preMs, info.runMs, info.postMs,
                info.yStride, info.yHeight);
        // 模型输出布局为 (模型档位宽×倍率) × (模型档位高×倍率) 的紧凑 buffer，其行 stride = mOutputStride。
        // 当模型档位 > 视频尺寸时（如视频 360x360 选 SR3 的 360x640 档），buffer 比有效画面大，
        // 有效区仅左上角 mOutputW × mOutputH；用 stride=mOutputStride 让 GL_UNPACK_ROW_LENGTH 抠出有效区。
        // 注：这里写入 mEnhancedYStride 纯为诊断用；SR 逻辑在 renderFrame 里自己接管。
        mEnhancedYStride = mOutputStride;
        return enhanced;
    }

    // ---- 渲染器钩子：子类自持 SR 渲染器 ----

    // 缓存上次 SR 渲染器构建时的视口，惰性创建后用于 onSurfaceChanged 补发。
    private int mPendingViewW;
    private int mPendingViewH;

    @Override
    protected void initRenderers(int videoWidth, int videoHeight) {
        // SR 渲染器依赖 init 回传的输出尺寸（倍率未知前无法定 Y 纹理大小），故不在此创建；
        // 改为在首个增强帧到来（init 已成功）时于 renderFrame 内惰性创建。
    }

    @Override
    protected void onRendererSurfaceChanged(int viewWidth, int viewHeight) {
        mPendingViewW = viewWidth;
        mPendingViewH = viewHeight;
        if (mNv12SrRenderer != null) {
            mNv12SrRenderer.onSurfaceChanged(viewWidth, viewHeight);
        }
    }

    /** 在 GL 线程惰性创建 SR 渲染器：Y = outputW × outputH，UV = videoW/2 × videoH/2（NV12 半采样）。 */
    private void ensureSrRendererOnGl() {
        if (mNv12SrRenderer != null) {
            return;
        }
        mNv12SrRenderer = new Nv12Renderer();
        mNv12SrRenderer.init(mOutputW, mOutputH, mVideoWidth / 2, mVideoHeight / 2);
        // 与基类内置的 1x 渲染器使用同一个色彩空间（同一份解码 NV12 输出，公式必须一致）
        mNv12SrRenderer.setColorSpace(mDetectedColorSpace);
        if (mPendingViewW > 0 && mPendingViewH > 0) {
            mNv12SrRenderer.onSurfaceChanged(mPendingViewW, mPendingViewH);
        }
    }

    @Override
    protected void renderFrame(ByteBuffer yBuffer, int yStride, int yHeight,
                               ByteBuffer uvBuffer, int uvOffset, int uvStride, int uvHeight,
                               ByteBuffer processedY) {
        // 超分开关关 / init 未就绪 / 不支持当前分辨率 → processedY 为 null，走基类默认 1x 渲染
        if (processedY == null) {
            super.renderFrame(yBuffer, yStride, yHeight, uvBuffer, uvOffset, uvStride, uvHeight, null);
            return;
        }
        // init 已成功（processedY != null 隐含 mTieInitReady），按回传有效输出尺寸惰性建 SR 渲染器
        ensureSrRendererOnGl();
        // 超分路径：有效画面 mOutputW × mOutputH；buffer 行 stride = mOutputStride（>= mOutputW，含 padding 放大区）。
        // 用 stride 让 GL_UNPACK_ROW_LENGTH 从 buffer 抠出左上角 mOutputW × mOutputH 上屏。
        int srStride = mOutputStride > 0 ? mOutputStride : mOutputW;
        int srHeight = mOutputH;
        // 校验：读取有效区最后一行（第 srHeight-1 行）的前 mOutputW 像素不越界。
        int needed = srStride * (srHeight - 1) + mOutputW;
        if (processedY.capacity() < needed) {
            Log.e(TAG, "SR Y buffer capacity=" + processedY.capacity()
                    + " < expected=" + needed + " (stride=" + srStride + "), fallback to 1x");
            super.renderFrame(yBuffer, yStride, yHeight, uvBuffer, uvOffset, uvStride, uvHeight, null);
            return;
        }
        mNv12SrRenderer.render(processedY, srStride, srHeight, uvBuffer, uvOffset, uvStride, uvHeight);
    }

    @Override
    protected void releaseRenderers() {
        if (mNv12SrRenderer != null) {
            mNv12SrRenderer.release();
            mNv12SrRenderer = null;
        }
    }

    @Override
    protected void updateStatusText() {
        runOnUiThread(() -> {
            mTvStatus1.setText(String.format(Locale.getDefault(),
                    "耗时=%dms, process=%dms", mMetrics.totalMs, mMetrics.processMs));
            String srInfo = (mToggleButtonOn && mTieInitReady && mTieSizeSupported) ? (mOutputW + "x" + mOutputH) : "-";
            mTvStatus2.setText(String.format(Locale.getDefault(),
                    "超分：ByteBuffer路径 | FPS=%d | 视频=%dx%d, 输出=%s, 视图=%dx%d, TieSrY=%s",
                    mMetrics.fps, mVideoWidth, mVideoHeight, srInfo, mViewWidth, mViewHeight,
                    mTieInitReady ? "ready" : (mTieSizeSupported ? "init中" : "不支持该分辨率")));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Threads.getSingleExecutor().submit(mTieSrY::release);
    }
}