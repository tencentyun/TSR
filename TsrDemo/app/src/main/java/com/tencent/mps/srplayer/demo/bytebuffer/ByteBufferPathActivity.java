package com.tencent.mps.srplayer.demo.bytebuffer;

import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.tencent.mps.srplayer.common.TieSdkHelper;
import com.tencent.mps.srplayer.common.Threads;
import com.tencent.mps.tie.api.TieEngine.Config;
import com.tencent.mps.tie.api.TieEngine;
import com.tencent.mps.tie.api.TieEngine.InitResult;
import com.tencent.mps.tie.api.TieEngine.ProcessInfo;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * <b>ByteBuffer 同尺寸增强 demo</b>：MediaCodec ByteBuffer 解码 → CPU 拿到 NV12 →
 * {@link TieEngine.Type#IE_Y} 增强 Y 通道 → {@code Nv12Renderer} 把 Y/UV 都从 CPU 上传渲染。
 */
public class ByteBufferPathActivity extends ByteBufferPathBaseActivity {

    private static final String TAG = "ByteBufferPathActivity";

    private final TieEngine mTieY = new TieEngine();

    // 是否已提交 init 任务，防止重入
    private boolean mInitSubmitted = false;

    // init 是否已完成
    private volatile boolean mTieInitReady = false;

    // 视频分辨率是否能被 TieEngine 的任一模型档位覆盖（由 init 错误码 CODE_UNSUPPORTED_SIZE 判定）
    private volatile boolean mTieSizeSupported = true;

    // process buffer 行 stride（= 模型档位宽，IE 倍率 1；模型档位 > 视频时 > 视频宽）。
    // 由 InitResult.outputStride 回传，用作上传纹理的 GL_UNPACK_ROW_LENGTH 抠左上角有效区。
    private volatile int mEnhancedYStrideFromInit;

    @Override
    protected void onGetVideoSize(int width, int height) {
        super.onGetVideoSize(width, height);
        Log.i(TAG, "onGetVideoSize() " + width + "x" + height);

        if (mInitSubmitted) {
            return; // 已提交过，避免重入
        }

        // 选型/选档/支持性判定全部下沉到 SDK init：调用方只传处理意图 + 视频宽高。
        mInitSubmitted = true;
        final int videoW = width;
        final int videoH = height;
        Threads.getSingleExecutor().submit(() -> {
            if (isFinishing()) {
                return; // Activity 即将销毁，不再占用资源跑 init
            }
            long t0 = SystemClock.elapsedRealtime();
            InitResult result = mTieY.init(ByteBufferPathActivity.this,
                    new Config(TieEngine.Type.IE_Y, videoW, videoH));
            long initCostMs = SystemClock.elapsedRealtime() - t0;
            boolean ok = result.code == InitResult.CODE_OK;
            Log.i(TAG, "TieY init result cost=" + initCostMs + "ms"
                    + ", code=" + result.code
                    + ", inferMs=" + result.inferMs
                    + ", out=" + result.outputWidth + "x" + result.outputHeight
                    + ", msg=" + result.msg);

            // 分辨率不支持：单独处理，关闭增强（与原 pickSupportSize==null 等价）
            if (result.code == InitResult.CODE_UNSUPPORTED_SIZE) {
                mTieSizeSupported = false;
                markToggleUnsupported();
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(ByteBufferPathActivity.this, "当前分辨率 " + videoW + "x" + videoH
                                + " 超出 TieY 支持范围，已关闭增强", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            if (TieSdkHelper.isTieEngineInitReady(result)) {
                mEnhancedYStrideFromInit = result.outputStride;
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
                        Toast.makeText(ByteBufferPathActivity.this,
                                "TieY 初始化失败，已关闭增强：" + reason,
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
        ByteBuffer enhanced = mTieY.process(yBuffer, yStride, yHeight);
        if (enhanced == null) {
            mEnhancedYStride = 0;
            return null;
        }
        // 子类把推理细分耗时拼成人类可读片段注入到 mMetrics.processInfo，由基类每秒输出 breakdown 时拼接。
        // 这里写入的是当前帧的瞬时值（pre/run 单位 ms），与基类的 process/render 秒均值口径略有差异，
        // 但稳态下抖动很小，可读性足够。
        ProcessInfo info = mTieY.getLastProcessInfo();
        mMetrics.processInfo = String.format(Locale.getDefault(),
                "[pre=%dms run=%dms post=%dms yStride=%d yHeight=%d]",
                info.preMs, info.runMs, info.postMs,
                info.yStride, info.yHeight);
        // 模型输出布局为 (模型档位宽 × 模型档位高) 的紧凑 buffer（IE 同尺寸，倍率 1）。当模型档位 > 视频时，
        // 不在 CPU 做裁剪，而是告诉基类 stride=outputStride（模型档位宽），让 GL 通过 GL_UNPACK_ROW_LENGTH
        // 直接抠左上角 videoW×videoH 上传纹理。
        mEnhancedYStride = mEnhancedYStrideFromInit;
        return enhanced;
    }

    @Override
    protected void updateStatusText() {
        runOnUiThread(() -> {
            mTvStatus1.setText(String.format(Locale.getDefault(), "耗时=%dms, process=%dms",
                    mMetrics.totalMs, mMetrics.processMs));
            mTvStatus2.setText(String.format(Locale.getDefault(),
                    "增强：ByteBuffer路径 | FPS=%d | 视频=%dx%d, 视图=%dx%d, TieY=%s",
                    mMetrics.fps, mVideoWidth, mVideoHeight, mViewWidth, mViewHeight,
                    mTieInitReady ? "ready" : (mTieSizeSupported ? "init中" : "不支持该分辨率")));
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Threads.getSingleExecutor().submit(mTieY::release);
    }
}
