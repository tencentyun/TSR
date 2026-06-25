package com.tencent.mps.srplayer.common;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Threads {

    private static final ScheduledExecutorService sSINGLE_EXECUTOR = Executors.newSingleThreadScheduledExecutor();

    public static ScheduledExecutorService getSingleExecutor() {
        return sSINGLE_EXECUTOR;
    }

}
