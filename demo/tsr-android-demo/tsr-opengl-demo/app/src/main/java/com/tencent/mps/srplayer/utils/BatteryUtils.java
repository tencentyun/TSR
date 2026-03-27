package com.tencent.mps.srplayer.utils;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import java.util.Locale;

public class BatteryUtils {

    /**
     * 获取完整的电池信息字符串
     * 格式：电池剩余电量/电池总容量，温度
     * 例如："3500/5000mAh，36.5°C"
     *
     * @param context 上下文
     * @return 格式化的电池信息字符串
     */
    public static String getBatteryInfoString(Context context) {
        StringBuilder batteryInfo = new StringBuilder();

        // 获取电池状态
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = context.registerReceiver(null, ifilter);

        if (batteryStatus == null) {
            return "无法获取电池信息";
        }

        // 获取电池百分比
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        float batteryPercentage = -1;

        if (level >= 0 && scale > 0) {
            batteryPercentage = level * 100 / (float) scale;
        }

        // 获取剩余电量
        int remainingCapacity = getBatteryRemainingCapacity(context);

        // 计算总容量
        int totalCapacity = -1;
        if (remainingCapacity > 0 && batteryPercentage > 0) {
            totalCapacity = (int) (remainingCapacity / (batteryPercentage / 100));
        }

        // 获取电池温度
        int temperature = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        float temperatureCelsius = -1;
        if (temperature > 0) {
            temperatureCelsius = temperature / 10.0f;
        }

        // 构建剩余电量/总容量部分

        if (remainingCapacity > 0) {
            batteryInfo.append(remainingCapacity);
        } else {
            batteryInfo.append("未知");
        }

        batteryInfo.append("/");

        if (totalCapacity > 0) {
            batteryInfo.append(totalCapacity);
        } else {
            batteryInfo.append("未知");
        }

        batteryInfo.append("mAh");

        // 添加剩余百分比
        if (batteryPercentage >= 0) {
            batteryInfo.append("(")
                    .append(String.format(Locale.getDefault(), "%.1f%%", batteryPercentage))
                    .append(")");
        }

        // 添加温度部分
        batteryInfo.append(", 温度");

        if (temperatureCelsius > 0) {
            batteryInfo.append(String.format(Locale.getDefault(), "%.1f°C", temperatureCelsius));
        } else {
            batteryInfo.append("未知");
        }

        return batteryInfo.toString();
    }

    /**
     * 获取当前电池剩余电量（mAh）
     * 注意：此方法需要Android 5.0+且部分设备可能不支持
     */
    private static int getBatteryRemainingCapacity(Context context) {
        try {
            BatteryManager batteryManager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            // 获取剩余电量（微安时）。不要用 BATTERY_PROPERTY_CAPACITY，这个是出厂标称值，不是当前实际最大容量。
            long remainingCapacity = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (remainingCapacity > 0) {
                return (int) (remainingCapacity / 1000); // 转换为毫安时
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return -1; // 获取失败
    }
}