package com.alibabacloud.rum;

import java.util.Map;

import android.content.Context;
import com.openrum.sdk.agent.OpenRum;
import com.openrum.sdk.agent.OpenRum.AppEnvironment;

/**
 * @author yulong.gyl
 * @date 2024/4/19
 * @noinspection unused
 */
public class AlibabaCloudRum {
    public enum Env {
        NONE(0),
        PROD(1),
        GRAY(2),
        PRE(3),
        DAILY(4),
        LOCAL(5);

        private final int value;

        private Env(int i) {
            this.value = i;
        }

        public final int getValue() {
            return this.value;
        }
    }

    private OpenRum openRum;

    private static class SingletonHolder {
        private static final AlibabaCloudRum INSTANCE = new AlibabaCloudRum();
    }

    private AlibabaCloudRum() {

    }

    public static AlibabaCloudRum withAppID(String appID) {
        SingletonHolder.INSTANCE.openRum = OpenRum.withAppID(appID).withAppEnvironment(AppEnvironment.PROD);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withEnvironment(Env env) {
        switch (env) {
            case NONE: {
                SingletonHolder.INSTANCE.openRum.withAppEnvironment(AppEnvironment.NONE);
                break;
            }
            case PROD: {
                SingletonHolder.INSTANCE.openRum.withAppEnvironment(AppEnvironment.PROD);
                break;
            }
            case GRAY: {
                SingletonHolder.INSTANCE.openRum.withAppEnvironment(AppEnvironment.GRAY);
                break;
            }
            case PRE: {
                SingletonHolder.INSTANCE.openRum.withAppEnvironment(AppEnvironment.PRE);
                break;
            }
            case DAILY: {
                SingletonHolder.INSTANCE.openRum.withAppEnvironment(AppEnvironment.DAILY);
                break;
            }
            case LOCAL: {
                SingletonHolder.INSTANCE.openRum.withAppEnvironment(AppEnvironment.LOCAL);
                break;
            }
        }

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

    public static void setExtraInfo(Map<String, Object> extraInfo) {
        OpenRum.setExtraInfo(extraInfo);
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
