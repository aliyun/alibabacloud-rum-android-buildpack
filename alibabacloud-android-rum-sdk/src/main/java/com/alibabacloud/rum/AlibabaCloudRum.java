package com.alibabacloud.rum;

import android.content.Context;
import com.openrum.sdk.agent.OpenRum;

/**
 * @author yulong.gyl
 * @date 2024/4/19
 * @noinspection unused
 */
public class AlibabaCloudRum {

    private OpenRum openRum;

    private static class SingletonHolder {
        private static final AlibabaCloudRum INSTANCE = new AlibabaCloudRum();
    }

    private AlibabaCloudRum() {

    }

    public static AlibabaCloudRum withAppID(String appID) {
        SingletonHolder.INSTANCE.openRum = OpenRum.withAppID(appID);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withConfigAddress(String configAddress) {
        openRum.withConfigAddress(configAddress);
        return this;
    }

    public AlibabaCloudRum withAppVersion(String appVersion) {
        openRum.withAppVersion(appVersion);
        return this;
    }

    public AlibabaCloudRum withDeviceID(String deviceID) {
        openRum.withDeviceID(deviceID);
        return this;
    }

    public AlibabaCloudRum withChannelID(String channelID) {
        openRum.withChannelID(channelID);
        return this;
    }

    public AlibabaCloudRum start() {
        openRum.start();
        return this;
    }

    public AlibabaCloudRum start(Context context) {
        openRum.start(context);
        return this;
    }

    public static void setUserName(String userID) {
        OpenRum.setUserID(userID);
    }
}
