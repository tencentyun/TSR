package com.tencent.mps.srplayer.utils;

import android.util.Log;

public class GpuUtils {

    private static final String TAG = "GpuUtils";

    /**
     * 获取 GPU 使用率
     * 注意：此方法在不同设备上可能有不同的表现，某些设备可能无法获取
     *
     * @return GPU 使用率百分比，如果无法获取则返回 -1
     */
    public static float getGpuUsage() {
        try {
            // 尝试读取常见的 GPU 使用率文件路径
            String[] gpuPaths = {
                    // 高通 Adreno GPU
                    "/sys/class/kgsl/kgsl-3d0/gpubusy",
                    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",

                    // ARM Mali GPU（需 root）
                    "/sys/class/misc/mali0/device/utilization",
                    "/sys/devices/platform/mali.0/utilization",
                    "/sys/devices/platform/soc/mali.0/utilization",

                    // 华为
                    "/sys/devices/platform/gpusysfs/gpu_busy",
                    "/sys/class/devfreq/gpufreq/gpu_busy"
            };

            for (String path : gpuPaths) {
                float usage = readGpuFromPath(path);
                if (usage >= 0) {
                    return usage;
                }
            }

            // 如果所有路径都失败，尝试读取高通芯片的详细信息
            return readQualcommGpuUsage();

        } catch (Exception e) {
            Log.e(TAG, "Error reading GPU usage: " + e.getMessage());
            return -1;
        }
    }

    /**
     * 从指定路径读取 GPU 使用率
     */
    private static float readGpuFromPath(String path) {
        java.io.BufferedReader reader = null;
        try {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) {
                return -1;
            }

            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line = reader.readLine();

            if (line != null && !line.isEmpty()) {
                line = line.trim();

                // 处理不同格式的输出
                if (line.contains("%")) {
                    // 格式：xx%
                    return Float.parseFloat(line.replace("%", "").trim());
                } else if (line.contains(" ")) {
                    // 格式：busy_time total_time（高通芯片常见格式）
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        long busyTime = Long.parseLong(parts[0]);
                        long totalTime = Long.parseLong(parts[1]);
                        if (totalTime > 0) {
                            return (busyTime * 100.0f) / totalTime;
                        }
                    }
                } else {
                    // 直接是百分比数值
                    float value = Float.parseFloat(line);
                    // 如果值大于100，可能是其他单位，需要转换
                    if (value > 100) {
                        return -1;
                    }
                    return value;
                }
            }
        } catch (Exception e) {
            // 忽略异常，尝试下一个路径
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException e) {
                    // 忽略关闭异常
                }
            }
        }
        return -1;
    }

    /**
     * 读取高通芯片的 GPU 使用率（特殊处理）
     */
    private static float readQualcommGpuUsage() {
        try {
            // 读取两次数据，计算差值
            long[] data1 = readQualcommGpuData();
            if (data1 == null) {
                return -1;
            }

            // 等待一小段时间
            Thread.sleep(100);

            long[] data2 = readQualcommGpuData();
            if (data2 == null) {
                return -1;
            }

            long busyDiff = data2[0] - data1[0];
            long totalDiff = data2[1] - data1[1];

            if (totalDiff > 0) {
                return (busyDiff * 100.0f) / totalDiff;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading Qualcomm GPU usage: " + e.getMessage());
        }
        return -1;
    }

    /**
     * 读取高通芯片的 GPU 数据
     *
     * @return [busyTime, totalTime] 或 null
     */
    private static long[] readQualcommGpuData() {
        java.io.BufferedReader reader = null;
        try {
            java.io.File file = new java.io.File("/sys/class/kgsl/kgsl-3d0/gpubusy");
            if (!file.exists()) {
                return null;
            }

            reader = new java.io.BufferedReader(new java.io.FileReader(file));
            String line = reader.readLine();

            if (line != null && line.contains(" ")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    long busyTime = Long.parseLong(parts[0]);
                    long totalTime = Long.parseLong(parts[1]);
                    return new long[]{busyTime, totalTime};
                }
            }
        } catch (Exception e) {
            // 忽略异常
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (java.io.IOException e) {
                    // 忽略关闭异常
                }
            }
        }
        return null;
    }
}
