package com.tencent.mps.srplayer.common;

import android.content.Context;
import android.util.Log;
import com.tencent.mps.srplayer.BuildConfig;
import com.tencent.mps.tie.api.TieBufferEnhancer;
import com.tencent.mps.tie.api.TieBufferEnhancer.InitResult;
import com.tencent.mps.tie.api.TieSdk;
import com.tencent.mps.tie.api.TieSdk.LicenseStatus;
import com.tencent.mps.tie.api.TieSdk.Config;

/**
 * TsrSdk 鉴权辅助类。
 * <p>Demo 在 Application 阶段集中调用 {@link TieSdk#init}，并把鉴权结果缓存为可查询状态；
 * 各播放页面进入前只需要读取 {@link #isInit()}，避免在每个 Activity 中重复编写鉴权样板代码。</p>
 */
public class TieSdkHelper {

    private static final String TAG = "TieSdkHelper";
    private static final TieSdkHelper INSTANCE = new TieSdkHelper();

    /** null 表示尚未收到鉴权结果，true 表示 license 可用，false 表示鉴权失败或 SDK 初始化异常。 */
    private volatile Boolean isInit;

    private TieSdkHelper() {
    }

    public static TieSdkHelper getInstance() {
        return INSTANCE;
    }

    /**
     * 判断 {@link TieBufferEnhancer#init} 的结果是否达到本 Demo 的"可启用增强"标准：
     * <ul>
     *   <li>错误码为 {@link InitResult#CODE_OK}；</li>
     *   <li>预估单帧处理耗时小于 20ms。</li>
     * </ul>
     * <p>20ms 是 Demo 为保证播放流畅设置的启用阈值，不是 SDK 的 API 契约；客户可按自身帧率目标调整。</p>
     *
     * @param result TieBufferEnhancer.init 返回值；为 null 时返回 false。
     */
    public static boolean isTieInitReady(InitResult result) {
        return result != null
                && result.code == InitResult.CODE_OK
                && result.inferMs < 20;
    }

    /**
     * 获取 TieSdk 鉴权状态。
     *
     * @return null 表示尚未收到鉴权结果，true 表示可用，false 表示不可用
     */
    public Boolean isInit() {
        return isInit;
    }

    /**
     * 初始化 TieSdk 在线鉴权。鉴权结果通过回调写回 {@link #isInit()} 状态。
     *
     * @param context 应用上下文，内部会自动取 ApplicationContext
     */
    public void init(Context context) {
        TieSdk.LicenseCallback callback = status -> {
            if (status == LicenseStatus.AVAILABLE) {
                Log.i(TAG, "TieSdk LicenseVerify success");
                isInit = Boolean.TRUE;
            } else {
                Log.e(TAG, "TieSdk LicenseVerify fail: " + status);
                isInit = Boolean.FALSE;
            }
        };
        Config config = new Config(BuildConfig.APP_ID, BuildConfig.AUTH_ID, Log::println, callback);
        TieSdk.getInstance().init(context.getApplicationContext(), config);
    }
}