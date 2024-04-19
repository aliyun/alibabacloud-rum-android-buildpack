package com.aliyun.rum.android;

import android.app.Application;
import com.openrum.sdk.agent.OpenRum;

/**
 * @author yulong.gyl
 * @date 2024/4/18
 */
public class RumApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        OpenRum
            .withAppID("b590lhguqs@b314ff9fa1116f1")
            .withConfigAddress("https://b590lhguqs-default-cn.rum.aliyuncs.com/RUM/config")
            .withChannelID("cn-hangzhou");
    }
}
