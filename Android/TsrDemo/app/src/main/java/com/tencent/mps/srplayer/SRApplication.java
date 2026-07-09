package com.tencent.mps.srplayer;

import android.app.Application;
import android.content.Context;

import com.tencent.mps.srplayer.common.TieSdkHelper;

/**
 * Demo Application：负责进程级初始化。
 * <p>这里集中完成 TieSdk 在线鉴权，避免把鉴权样板代码散落在各个 Demo Activity 中。
 * 各 Demo 进入前由 {@link HomeActivity} 通过 {@link TieSdkHelper#isInit()} 校验 license 是否可用。</p>
 */
public class SRApplication extends Application {

    private static Context sAppContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
        // 启动即触发 TieSdk 在线鉴权，结果由 TieSdkHelper 内部回调更新。
        TieSdkHelper.getInstance().init(sAppContext);
    }

    public static Context getAppContext() {
        return sAppContext;
    }
}
