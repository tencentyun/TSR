package com.tencent.mps.srplayer.common.playback;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 基于视频 PTS 的帧率调度器，负责把"下一帧应该在何时 requestRender"这件事
 * 与具体的渲染管线解耦。两条播放路径（ByteBuffer / OES）共用同一份逻辑。
 *
 * <p>用法：</p>
 * <pre>
 *   FrameScheduler scheduler = new FrameScheduler(() -> glSurfaceView.requestRender());
 *   // 开始播放
 *   scheduler.start();
 *   // 每帧渲染完成
 *   scheduler.onFrameRendered(currentPtsUs);
 *   // 无帧可用（dequeue 失败）
 *   scheduler.onFrameUnavailable();
 *   // 停止播放
 *   scheduler.stop();
 * </pre>
 *
 * <p>线程模型：构造时持有主线程 Handler；{@link #onFrameRendered} / {@link #onFrameUnavailable}
 * 通常在 GL 线程被调用，内部仅做 {@code postDelayed}，是线程安全的。</p>
 */
public class FrameScheduler {

    /** 无帧可用时的重试延迟（毫秒） */
    private static final long RETRY_DELAY_MS = 5L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTrigger;

    private volatile boolean mActive = false;

    // PTS 相关时间基准；单位与 Android API 一致（ms / us）
    private long mPlayStartTimeMs = -1; // 播放起始墙钟时间（ms）
    private long mFirstFramePtsUs = -1; // 首帧 PTS（us）
    private long mLastFramePtsUs = -1;  // 上一帧 PTS（us），用于检测循环播放

    public FrameScheduler(Runnable renderTrigger) {
        // 仅在调度激活时才真正触发，stop() 后挂起回调即便被执行也不会 requestRender
        mTrigger = () -> {
            if (mActive) {
                renderTrigger.run();
            }
        };
    }

    /** 启动调度：重置时间基准，立即触发首次渲染。 */
    public void start() {
        mActive = true;
        mPlayStartTimeMs = -1;
        mFirstFramePtsUs = -1;
        mLastFramePtsUs = -1;
        mHandler.post(mTrigger);
    }

    /** 停止调度：幂等。 */
    public void stop() {
        mActive = false;
        mHandler.removeCallbacks(mTrigger);
    }

    /**
     * 当前帧渲染完成后调用：根据 PTS 计算下一帧的延迟并 post。
     *
     * @param currentPtsUs 当前帧的 PTS（微秒）
     */
    public void onFrameRendered(long currentPtsUs) {
        if (!mActive) {
            return;
        }

        // 检测循环播放：当前帧 PTS 小于上一帧，说明视频已循环，重置时间基准
        if (mLastFramePtsUs >= 0 && currentPtsUs < mLastFramePtsUs) {
            mPlayStartTimeMs = -1;
            mFirstFramePtsUs = -1;
        }
        mLastFramePtsUs = currentPtsUs;

        // 首帧：记录时间基准，立即调度下一帧
        if (mPlayStartTimeMs < 0) {
            mPlayStartTimeMs = SystemClock.elapsedRealtime();
            mFirstFramePtsUs = currentPtsUs;
            mHandler.post(mTrigger);
            return;
        }

        // 计算当前帧 PTS 对应的理论播放时间点（相对于首帧）
        long framePtsMs = (currentPtsUs - mFirstFramePtsUs) / 1000;
        long elapsedMs = SystemClock.elapsedRealtime() - mPlayStartTimeMs;
        long delayMs = framePtsMs - elapsedMs;

        if (delayMs <= 0) {
            mHandler.post(mTrigger);
        } else {
            mHandler.postDelayed(mTrigger, delayMs);
        }
    }

    /** dequeue 失败 / yuvData 为空时调用：短暂延迟后重试。 */
    public void onFrameUnavailable() {
        if (!mActive) {
            return;
        }
        mHandler.postDelayed(mTrigger, RETRY_DELAY_MS);
    }
}
