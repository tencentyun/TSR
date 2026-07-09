package com.tencent.mps.srplayer.common;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 渲染性能仪表：同时统计 SDK Pass 处理耗时与渲染帧率（毫秒精度）。
 *
 * <p>典型用法：在 GL 线程的 onDrawFrame 中——
 * <ul>
 *   <li>每帧渲染成功时调用 {@link #recordFrame()} 记录帧（用于 fps）；</li>
 *   <li>用 {@link SystemClock#elapsedRealtime()} 包住 SDK 的 {@code process} /
 *       {@code processToCurrentFramebuffer} 调用，把毫秒差值喂给 {@link #recordMs(long)}；</li>
 *   <li>用 {@link #shouldUpdateUi()} 节流后 post 一次状态文案刷新到 UI。</li>
 * </ul>
 *
 * <p>统计采用 EMA（指数移动平均，α=0.15），既能反映近期变化又不会逐帧抖动；UI 刷新默认节流到
 * 1000ms 一次，避免 60fps 刷新造成 UI 卡顿。所有读写线程安全，可在 GL 线程写、UI 线程读。</p>
 */
public class PassTimingMeter {

    private static final long UPDATE_INTERVAL_MS = 1000L;
    private static final float EMA_ALPHA = 0.15f; // 影响均值平滑效果 α=0.05（太迟钝），α=0.15（推荐），α=0.5（太抖）

    // ── SDK 耗时统计 ──────────────────────────────────────────
    private volatile float mEmaMs = 0f;
    private volatile boolean mHasTimingData = false;

    // ── fps 统计（基于帧间隔的 EMA）────────────────────────────
    private volatile float mEmaFrameIntervalMs = 0f;
    private volatile boolean mHasFpsData = false;
    private volatile long mLastFrameTimeMs = 0;

    private volatile int mFrameCount = 0;
    private final AtomicLong mLastUiUpdateMs = new AtomicLong(0);

    /** 记录一次 SDK 调用耗时（毫秒）。在 GL 线程调用。 */
    public void recordMs(long elapsedMs) {
        float ms = (float) elapsedMs;
        if (mHasTimingData) {
            mEmaMs = mEmaMs + EMA_ALPHA * (ms - mEmaMs);
        } else {
            mEmaMs = ms;
            mHasTimingData = true;
        }
    }

    /**
     * 记录一帧渲染完成。用于 fps 统计——在 GL 线程每帧渲染成功（dequeue 到新帧）时调用。
     * 与 {@link #recordMs} 独立：fps 统计所有渲染帧（含直通模式），耗时只统计 SDK pass 调用。
     */
    public void recordFrame() {
        long now = SystemClock.elapsedRealtime();
        if (mLastFrameTimeMs > 0) {
            float interval = now - mLastFrameTimeMs;
            if (interval > 0) {
                if (mHasFpsData) {
                    mEmaFrameIntervalMs = mEmaFrameIntervalMs + EMA_ALPHA * (interval - mEmaFrameIntervalMs);
                } else {
                    mEmaFrameIntervalMs = interval;
                    mHasFpsData = true;
                }
            }
        }
        mLastFrameTimeMs = now;
        mFrameCount++;
    }

    /** 返回 SDK 耗时 EMA 四舍五入到整数毫秒；尚无样本时返回 -1。 */
    public long getAvgMsRounded() {
        return mHasTimingData ? Math.round(mEmaMs) : -1L;
    }

    /** 返回 fps EMA 四舍五入到整数；尚无样本时返回 -1。 */
    public long getFpsRounded() {
        if (!mHasFpsData || mEmaFrameIntervalMs <= 0) return -1L;
        return Math.round(1000f / mEmaFrameIntervalMs);
    }

    public boolean hasTimingData() {
        return mHasTimingData;
    }

    public boolean hasFpsData() {
        return mHasFpsData;
    }

    public int getFrameCount() {
        return mFrameCount;
    }

    /** 清空统计。例如 toggle 关闭增强后调用，让显示回到"未采样"。 */
    public void reset() {
        mEmaMs = 0f;
        mHasTimingData = false;
        mEmaFrameIntervalMs = 0f;
        mHasFpsData = false;
        mLastFrameTimeMs = 0;
        mFrameCount = 0;
        mLastUiUpdateMs.set(0);
    }

    /**
     * 节流判断：距上次返回 true 是否已超过 {@value #UPDATE_INTERVAL_MS} 毫秒。
     * 用 CAS 防止多线程同时通过。在 GL 线程调用。
     */
    public boolean shouldUpdateUi() {
        long now = SystemClock.elapsedRealtime();
        long last = mLastUiUpdateMs.get();
        if (now - last < UPDATE_INTERVAL_MS) return false;
        return mLastUiUpdateMs.compareAndSet(last, now);
    }
}
