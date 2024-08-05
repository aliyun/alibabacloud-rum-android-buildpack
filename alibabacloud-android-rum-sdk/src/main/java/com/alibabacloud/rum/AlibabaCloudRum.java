package com.alibabacloud.rum;

import java.util.Map;

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

    public static void setCustomException(Throwable exception) {
        OpenRum.setCustomException(exception);
    }

    public static void setCustomException(String type, String caseBy, String message) {
        OpenRum.setCustomException(type, caseBy, message);
    }

    public static void setCustomMetric(String name, long value) {
        OpenRum.setCustomMetric(name, value);
    }

    public static void setCustomMetric(String name, long value, String param) {
        OpenRum.setCustomMetric(name, value, param);
    }

    public static void setCustomLog(String info) {
        OpenRum.setCustomLog(info);
    }

    public static void setCustomLog(String info, String param) {
        OpenRum.setCustomLog(info, param);
    }

    public static void setCustomEvent(String eventId, String eventName) {
        OpenRum.setCustomEvent(eventId, eventName);
    }

    public static void setCustomEvent(String eventId, String eventName, String param) {
        OpenRum.setCustomEvent(eventId, eventName, param);
    }

    public static void setCustomEvent(String eventId, String eventName, String param, Map<String, Object> info) {
        OpenRum.setCustomEvent(eventId, eventName, param, info);
    }

    public static void setCustomEvent(String eventId, String eventName, Map<String, Object> info) {
        OpenRum.setCustomEvent(eventId, eventName, info);
    }
}
