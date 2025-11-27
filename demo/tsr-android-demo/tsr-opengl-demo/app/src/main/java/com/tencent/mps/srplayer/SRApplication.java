package com.tencent.mps.srplayer;

import android.app.Application;
import android.content.Context;
import com.tencent.mps.srplayer.helper.TsrSdkHelper;

public class SRApplication extends Application {
    private static Context sAppContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sAppContext = getApplicationContext();
        TsrSdkHelper.getInstance().init(sAppContext);
    }

    public static Context getAppContext() {
        return sAppContext;
    }
}
