package com.tencent.mps.srplayer.utils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

public class FpsUtil {

    /**
     * 用于存储帧耗时，计算帧率
     */
    private static Deque<Integer> costQueue = new ArrayDeque<>();
    /**
     * 用于存储帧率，计算平均帧率
     */
    private static Deque<Integer> fpsQueue = new ArrayDeque<>();
    private volatile static float fpsCount = 0;

    private volatile static int frameCount = 0;

    private volatile static long mLastTimestamp;

    public static class FpsData {
        private float fps;
        private float avgFps;

        public FpsData(float fps, float avgFps) {
            this.fps = fps;
            this.avgFps = avgFps;
        }

        public float getFps() {
            return fps;
        }

        public float getAvgFps() {
            return avgFps;
        }
    }

    public static FpsData tryGetFPS() {
        FpsData fpsData = null;
        int fps = frameCount;
        long timestamp = System.currentTimeMillis();
        if (mLastTimestamp == 0) {
            mLastTimestamp = timestamp;
            return fpsData;
        }
        if (timestamp - mLastTimestamp > 1000) {
            mLastTimestamp = timestamp;
            fps = frameCount;
            frameCount = 0;

            // 计算平均帧率
            fpsCount += fps;
            fpsQueue.add(fps);
            if (fpsQueue.size() > 60) {
                fpsCount -= fpsQueue.poll();
            }
            float avgFps = fpsCount / fpsQueue.size();
            fpsData = new FpsData(fps, avgFps);
        }

        frameCount++;
        return fpsData;
    }


    public static void reset() {
        fpsCount = 0;
        frameCount = 0;
        mLastTimestamp = 0;
        if (costQueue != null) {
            costQueue.clear();
        }
        if (fpsQueue != null) {
            fpsQueue.clear();
        }
    }
}
