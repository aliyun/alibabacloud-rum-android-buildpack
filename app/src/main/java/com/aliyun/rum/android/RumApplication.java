package com.aliyun.rum.android;

import android.app.Application;
import android.util.Log;
import com.openrum.sdk.agent.OpenRum;

/**
 * @author yulong.gyl
 * @date 2024/4/18
 */
public class RumApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("RUMDEBUG", "onCreate start.");

        final String appID = "********";
        final String configAddress = "********";
        OpenRum
            .withAppID(appID)
            .withConfigAddress(configAddress)
            .start(getApplicationContext());

        Log.e("RUMDEBUG", "onCreate end.");
    }
}
