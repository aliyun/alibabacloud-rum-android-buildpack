package com.alibabacloud.rum;

/**
 * @author yulong.gyl
 * @date 2024/11/25
 */
public class AlibabaCloudRumBridge extends AlibabaCloudRum {
    private static AlibabaCloudRum rum = null;

    public static void setAppId(String appId) {
        rum = AlibabaCloudRum.withAppID(appId);
    }

    public static void setConfigAddress(String configAddress) {
        if (null == rum) {
            return;
        }

        rum.withConfigAddress(configAddress);
    }

    public static void setAppVersion(String appVersion) {
        if (null == rum) {
            return;
        }
        rum.withAppVersion(appVersion);
    }

    public static void setChannelID(String channelID) {
        if (null == rum) {
            return;
        }
        rum.withChannelID(channelID);
    }

    public static void setDeviceID(String deviceID) {
        if (null == rum) {
            return;
        }
        rum.withDeviceID(deviceID);
    }

    public static void setEnvironment(Env env) {
        if (null == rum) {
            return;
        }
        rum.withEnvironment(env);
    }

    public static void setFramework(int framework) {
        setAppFramework(Framework.values()[framework]);
    }

    public static void startSDK() {
        if (null == rum) {
            return;
        }

        rum.start();
    }

    public static void startSDKSync() {
        if (null == rum) {
            return;
        }

        rum.startSync();
    }

    public static void stopSDK() {
        if (null == rum) {
            return;
        }

        rum.stop();
    }

}
