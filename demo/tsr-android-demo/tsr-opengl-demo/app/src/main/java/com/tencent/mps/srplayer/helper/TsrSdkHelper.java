package com.tencent.mps.srplayer.helper;

import android.content.Context;
import android.util.Log;
import com.tencent.mps.srplayer.BuildConfig;
import com.tencent.mps.tie.api.TSRSdk;
import com.tencent.mps.tie.api.TSRSdk.TSRSdkLicenseStatus;

/**
 * TSR SDK 辅助工具类
 * <p>
 * 负责管理TSR SDK的初始化流程和状态维护，采用单例模式确保全局唯一实例。
 * 提供SDK初始化状态查询和初始化操作接口。
 */
public class TsrSdkHelper {

    private static final String TAG = "TsrSdkHelper";  // 日志标签
    private static final TsrSdkHelper INSTANCE = new TsrSdkHelper(); // 单例实例
    private volatile Boolean isInit; // null 代表还未初始化过，true 代表初始化成功，false 代表初始化失败

    // 私有构造方法防止外部实例化
    private TsrSdkHelper() {
    }

    /**
     * 获取 TsrSdkHelper 单例实例
     *
     * @return TsrSdkHelper 单例实例
     */
    public static TsrSdkHelper getInstance() {
        return INSTANCE;
    }

    /**
     * 获取TSR SDK初始化状态
     *
     * @return Boolean 初始化状态：
     *         null 表示尚未完成初始化，
     *         true 表示初始化成功，
     *         false 表示初始化失败
     */
    public Boolean isInit() {
        return isInit;
    }

    /**
     * 初始化TSR SDK
     * <p>
     * 初始化结果通过回调处理并更新isInit状态
     *
     * @param context 应用程序上下文对象，用于SDK的初始化操作
     */
    public void init(Context context) {
        TSRSdk.getInstance().init(context.getApplicationContext(), BuildConfig.APP_ID, BuildConfig.AUTH_ID, status -> {
            if (status == TSRSdkLicenseStatus.AVAILABLE) {
                Log.i(TAG, "TSRSdk LicenseVerify success");
                isInit = true;
            } else {
                Log.e(TAG, "TSRSdk LicenseVerify fail: " + status);
                isInit = false;
            }
        }, (logLevel, tag, msg) -> {
            switch (logLevel) {
                case Log.VERBOSE:
                    Log.v(tag, msg);
                    break;
                case Log.DEBUG:
                    Log.d(tag, msg);
                    break;
                case Log.INFO:
                    Log.i(tag, msg);
                    break;
                case Log.WARN:
                    Log.w(tag, msg);
                    break;
                case Log.ERROR:
                    Log.e(tag, msg);
                    break;
                default:
                    Log.d(tag, msg);
                    break;
            }
        });
    }
}
