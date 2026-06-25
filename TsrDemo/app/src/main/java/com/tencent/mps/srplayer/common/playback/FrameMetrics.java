package com.tencent.mps.srplayer.common.playback;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 性能统计器：FPS + 单帧总耗时 + 各分段耗时累加，到周期边界做一次快照并重置。
 * 两条播放路径（ByteBuffer / OES）共用同一份逻辑。
 *
 * <p>设计取舍：</p>
 * <ul>
 *     <li>纯数据载体 + 累加方法，<b>不引入</b>任何 Listener / 接口 / 抽象方法；</li>
 *     <li>快照值 ({@link #fps} / {@link #totalMs} / {@link #processMs}) 作为 public 字段直接暴露，
 *         由调用方在 GL 线程写、UI 线程读；依赖 {@code runOnUiThread} 形成 happens-before；</li>
 *     <li>{@link #processInfo} 由调用方瞬时写入（可能为推理细分等），volatile 保证可见性；</li>
 *     <li>所有累加单位严格为毫秒。单帧不足 1ms 的子段会被截断为 0，但按秒聚合后取平均误差可接受。</li>
 * </ul>
 *
 * <p>典型调用：</p>
 * <pre>
 *   // 每帧末尾
 *   LinkedHashMap&lt;String, Long&gt; segs = new LinkedHashMap&lt;&gt;();
 *   segs.put("process", processMs);
 *   segs.put("render",  renderMs);
 *   metrics.addFrame(frameMs, segs);
 *
 *   // 每周期（如 3s）边界
 *   String breakdown = metrics.snapshotAndReset(intervalMs);
 *   Log.d(TAG, "耗时: fps=" + metrics.fps + " total=" + metrics.totalMs + "ms" + breakdown);
 * </pre>
 */
public class FrameMetrics {

    /** 性能统计聚合周期（ms）。两条路径共用同一口径。 */
    public static final long FPS_CAL_INTERVAL_MS = 1000L;

    // ============== public 快照字段（GL 线程写、UI 线程读） ==============

    /** 上一周期的 FPS（按 intervalMs 聚合） */
    public int fps;
    /** 上一周期单帧平均总耗时（ms），= 各命名分段平均之和 + misc */
    public int totalMs;
    /** 上一周期 "process" 段的平均耗时（ms）；无该分段则为 0 */
    public int processMs;

    /** 调用方注入的瞬时信息片段（如最近一帧推理细分），输出日志时附加在末尾。空串表示不附加。 */
    public volatile String processInfo = "";

    // ============== 内部累加器（GL 线程独占） ==============

    private long mAccFrameMs;
    private int mAccFrameCount;
    /** 按 key 累加各分段耗时；使用 LinkedHashMap 保留插入顺序，保证日志输出顺序稳定。 */
    private final LinkedHashMap<String, Long> mAccSegments = new LinkedHashMap<>();

    /**
     * 累加一帧的耗时与各分段耗时。
     *
     * @param frameMs    本帧总耗时（ms）
     * @param segmentMs  本帧各命名分段耗时（ms），key 顺序决定日志输出顺序
     */
    public void addFrame(long frameMs, LinkedHashMap<String, Long> segmentMs) {
        mAccFrameMs += frameMs;
        mAccFrameCount++;
        if (segmentMs != null) {
            for (Map.Entry<String, Long> e : segmentMs.entrySet()) {
                Long acc = mAccSegments.get(e.getKey());
                mAccSegments.put(e.getKey(), (acc == null ? 0L : acc) + e.getValue());
            }
        }
    }

    /**
     * 周期边界调用一次：写入 {@link #fps} / {@link #totalMs} / {@link #processMs} 快照，
     * 返回形如 {@code " process=12ms render=3ms misc=2ms"} 的 breakdown 字符串（misc 永远附加在末尾），
     * 并清零所有累加器。
     *
     * <p>若周期内没有累计任何帧，返回空串，安全清零。</p>
     *
     * @param intervalMs 周期长度（ms），用于计算 fps
     * @return breakdown 字符串（带前导空格），或空串
     */
    public String snapshotAndReset(long intervalMs) {
        if (mAccFrameCount == 0) {
            resetAccumulators();
            fps = 0;
            totalMs = 0;
            processMs = 0;
            return "";
        }

        int frames = mAccFrameCount;
        fps = (int) (frames * 1000L / Math.max(intervalMs, 1L));
        totalMs = (int) Math.round((double) mAccFrameMs / frames);

        StringBuilder sb = new StringBuilder();
        int sumNamed = 0;
        int processAvg = 0;
        for (Map.Entry<String, Long> e : mAccSegments.entrySet()) {
            int avg = (int) Math.round((double) e.getValue() / frames);
            sb.append(' ').append(e.getKey()).append('=').append(avg).append("ms");
            sumNamed += avg;
            if ("process".equals(e.getKey())) {
                processAvg = avg;
            }
        }
        processMs = processAvg;
        int miscMs = totalMs - sumNamed;
        sb.append(' ').append("misc=").append(miscMs).append("ms");

        resetAccumulators();
        return sb.toString();
    }

    /** 重置全部累加器和快照字段。在播放开始时调用。 */
    public void reset() {
        resetAccumulators();
        fps = 0;
        totalMs = 0;
        processMs = 0;
        processInfo = "";
    }

    private void resetAccumulators() {
        mAccFrameMs = 0;
        mAccFrameCount = 0;
        mAccSegments.clear();
    }
}
