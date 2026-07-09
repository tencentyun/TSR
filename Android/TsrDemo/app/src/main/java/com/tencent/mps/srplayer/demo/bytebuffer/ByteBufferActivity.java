package com.tencent.mps.srplayer.demo.bytebuffer;

import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.tencent.mps.srplayer.common.TieSdkHelper;
import com.tencent.mps.srplayer.common.Threads;
import com.tencent.mps.tie.api.TieBufferEnhancer.Config;
import com.tencent.mps.tie.api.TieBufferEnhancer;
import com.tencent.mps.tie.api.TieBufferEnhancer.EnhancerType;
import com.tencent.mps.tie.api.TieBufferEnhancer.InitResult;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * <b>ByteBuffer 同尺寸增强 demo</b>：MediaCodec ByteBuffer 解码 → CPU 拿到 NV12 →
 * {@link EnhancerType#IE_Y} 增强 Y 通道 → {@code Nv12Renderer} 把 Y/UV 都从 CPU 上传渲染。
 */
public class ByteBufferActivity extends ByteBufferBaseActivity {

    private static final String TAG = "ByteBufferActivity";

    private final TieBufferEnhancer mTieBufferEnhancer = new TieBufferEnhancer();

    // 是否已提交 init 任务，防止重入
    private boolean mInitSubmitted = false;

    // init 是否已完成
    private volatile boolean mTieInitReady = false;

    // 视频分辨率是否能被 TieBufferEnhancer 的任一模型档位覆盖（由 init 错误码 CODE_UNSUPPORTED_SIZE 判定）
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
            InitResult result = mTieBufferEnhancer.init(ByteBufferActivity.this,
                    new Config(EnhancerType.IE_Y, videoW, videoH));
            long initCostMs = SystemClock.elapsedRealtime() - t0;
            boolean ok = result.code == InitResult.CODE_OK;
            Log.i(TAG, "init result cost=" + initCostMs + "ms"
                    + ", code=" + result.code
                    + ", inferMs=" + result.inferMs
                    + ", out=" + result.outputWidth + "x" + result.outputHeight
                    + ", msg=" + result.msg);

            // 分辨率不支持：记录失败详情并刷新状态栏
            if (result.code == InitResult.CODE_UNSUPPORTED_SIZE) {
                Log.e(TAG, "init failed: UNSUPPORTED_SIZE, video=" + videoW + "x" + videoH
                        + ", msg=" + result.msg);
                mTieSizeSupported = false;
                markToggleUnsupported();
                onInitFailed("分辨率 " + videoW + "x" + videoH + " 不支持增强");
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(ByteBufferActivity.this, "当前分辨率 " + videoW + "x" + videoH
                                + " 不支持增强，已关闭", Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }

            if (TieSdkHelper.isTieInitReady(result)) {
                mEnhancedYStrideFromInit = result.outputStride;
                mTieInitReady = true;
                onInitFailed(null); // 清空失败详情，刷新状态栏
                markToggleReady();
            } else {
                // init 失败 / 性能不达标：记录详情并刷新状态栏，弹 Toast 辅助
                Log.e(TAG, "init failed: code=" + result.code + ", msg=" + result.msg
                        + ", video=" + videoW + "x" + videoH);
                final String failMsg = !ok
                        ? ("code=" + result.code + " | " + result.msg)
                        : "性能不达标";
                onInitFailed(failMsg);
                final String toastText = !ok ? "初始化失败" : "性能不达标";
                runOnUiThread(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(ByteBufferActivity.this, toastText,
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
        ByteBuffer enhanced = mTieBufferEnhancer.process(yBuffer, yStride, yHeight);
        if (enhanced == null) {
            mEnhancedYStride = 0;
            return null;
        }
        // 模型输出布局为 (模型档位宽 × 模型档位高) 的紧凑 buffer（IE 同尺寸，倍率 1）。当模型档位 > 视频时，
        // 不在 CPU 做裁剪，而是告诉基类 stride=outputStride（模型档位宽），让 GL 通过 GL_UNPACK_ROW_LENGTH
        // 直接抠左上角 videoW×videoH 上传纹理。
        mEnhancedYStride = mEnhancedYStrideFromInit;
        return enhanced;
    }

    @Override
    protected String buildStaticText() {
        if (mInitFailMsg != null) {
            return "ByteBuffer | " + mInitFailMsg;
        }
        return String.format(Locale.getDefault(),
                "ByteBuffer 增强 | video=%dx%d | view=%dx%d | ready=%s",
                mVideoWidth, mVideoHeight, mViewWidth, mViewHeight,
                mTieInitReady ? "yes" : (mTieSizeSupported ? "init中" : "不支持该分辨率"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Threads.getSingleExecutor().submit(mTieBufferEnhancer::release);
    }
}
