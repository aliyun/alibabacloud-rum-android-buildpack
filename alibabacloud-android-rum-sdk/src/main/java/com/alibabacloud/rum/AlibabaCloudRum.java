package com.alibabacloud.rum;

import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.text.TextUtils;

/**
 * @author yulong.gyl
 * @date 2024/4/19
 * @noinspection unused
 */
public class AlibabaCloudRum {
    private static final String SDK_VERSION_PREFIX = "_sv_";

    private static class Agent extends com.alibabacloud.rum.android.sdk.AlibabaCloudRum {}

    public enum Env {
        NONE(0),
        PROD(1),
        GRAY(2),
        PRE(3),
        DAILY(4),
        LOCAL(5);

        static final Map<Integer, String> sEnvStringMap = new HashMap<Integer, String>() {
            {
                put(Env.NONE.getValue(), "none");
                put(Env.PROD.getValue(), "prod");
                put(Env.GRAY.getValue(), "gray");
                put(Env.PRE.getValue(), "pre");
                put(Env.DAILY.getValue(), "daily");
                put(Env.LOCAL.getValue(), "local");
            }
        };

        private final int value;

        Env(int i) {
            this.value = i;
        }

        public final int getValue() {
            return this.value;
        }

        public final String stringValue() {
            return sEnvStringMap.get(this.value);
        }
    }

    public enum Framework {
        FLUTTER(0),
        REACT_NATIVE(1),
        UNITY(2);

        private final int value;

        Framework(int value) {
            this.value = value;
        }

        public final int getValue() {
            return this.value;
        }

        public final String getDescription() {
            switch (this) {
                case FLUTTER:
                    return "Flutter";
                case REACT_NATIVE:
                    return "ReactNative";
                case UNITY:
                    return "Unity";
            }
            return null;
        }
    }

    private String env = Env.PROD.stringValue();
    private String endpoint = "";
    private String appId = "";

    private static class SingletonHolder {
        private static final AlibabaCloudRum INSTANCE = new AlibabaCloudRum();
    }

    AlibabaCloudRum() {

    }

    public AlibabaCloudRum withConfigAddress(String configAddress) {
        return withEndpoint(configAddress);
    }

    public AlibabaCloudRum withEndpoint(String endpointAddress) {
        if (TextUtils.isEmpty(endpointAddress)) {
            return SingletonHolder.INSTANCE;
        }

        this.endpoint = endpointAddress;
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withWorkspace(String workspace) {
        Agent.setWorkspace(workspace);
        return SingletonHolder.INSTANCE;
    }

    /**
     * @noinspection UnusedReturnValue
     */
    public static AlibabaCloudRum withAppID(String appID) {
        if (TextUtils.isEmpty(appID)) {
            return SingletonHolder.INSTANCE;
        }

        SingletonHolder.INSTANCE.appId = appID;
        return SingletonHolder.INSTANCE;
    }

    /** @noinspection UnusedReturnValue*/
    public AlibabaCloudRum withEnvironment(Env env) {
        this.env = env.stringValue();
        Agent.setEnvironment(this.env);
        return SingletonHolder.INSTANCE;
    }

    /** @noinspection UnusedReturnValue*/
    public AlibabaCloudRum withCustomEnvironment(String env) {
        if (TextUtils.isEmpty(env)) {
            return SingletonHolder.INSTANCE;
        }

        this.env = env;
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withAppVersion(String appVersion) {
        Agent.setAppVersion(appVersion);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withDeviceID(String deviceID) {
        Agent.setDeviceId(deviceID);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withChannelID(String channelID) {
        Agent.setAppChannel(channelID);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum start() {
        Agent.start(endpoint, appId);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum startSync() {
        Agent.start(endpoint, appId);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum start(Context context) {
        Agent.start(context, endpoint, appId);
        return SingletonHolder.INSTANCE;
    }

    public static void stop() {
        Agent.stop();
    }

    public static void setUserName(String userID) {
        Agent.setUserName(userID);
    }

    public static void setExtraInfo(Map<String, Object> extraInfo) {
        Agent.setExtraInfo(extraInfo);
    }

    public static void addExtraInfo(Map<String, Object> extraInfo) {
        Agent.addExtraInfo(extraInfo);
    }

    public static void setUserExtraInfo(Map<String, Object> extraInfo) {
        Agent.setUserExtraInfo(extraInfo);
    }

    public static void addUserExtraInfo(Map<String, Object> extraInfo) {
        Agent.setUserExtraInfo(extraInfo);
    }

    public static void setAppFramework(Framework framework) {
        Agent.setAppFramework(framework.getDescription());
    }

    // region ===== exception =====
    public static void setCustomException(Throwable exception) {
        Agent.setCustomException(exception);
    }

    public static void setCustomException(String type, String caseBy, String message) {
        Agent.setCustomException(type, caseBy, message);
    }
    // endregion

    // region ===== metric =====
    public static void setCustomMetric(String name, long value) {
        // todo do nothing
    }

    public static void setCustomMetric(String name, long value, String param) {
        // todo do nothing
    }
    // endregion

    // region ===== log =====
    public static void setCustomLog(String content) {
        setCustomLog(content, null);
    }

    public static void setCustomLog(String content, String name) {
        setCustomLog(content, name, null);
    }

    public static void setCustomLog(String content, String name, String snapshots) {
        setCustomLog(content, name, snapshots, "INFO", null);
    }

    public static void setCustomLog(String content, String name, String snapshots, String level,
        Map<String, Object> attributes) {
        Agent.setCustomLog(content, name, snapshots, level, attributes);
    }
    // endregion

    // region ===== event =====
    public static void setCustomEvent(String eventName) {
        setCustomEvent(eventName, null);
    }

    public static void setCustomEvent(String eventName, String group) {
        setCustomEvent(eventName, group, (String)null);
    }

    public static void setCustomEvent(String eventName, String group, String snapshots) {
        setCustomEvent(eventName, group, snapshots, 0);
    }

    public static void setCustomEvent(String eventName, String group, double value) {
        setCustomEvent(eventName, group, null, value);
    }

    public static void setCustomEvent(String eventName, String group, Map<String, Object> attributes) {
        setCustomEvent(eventName, group, null, attributes);
    }

    public static void setCustomEvent(String eventName, String group, String snapshots, double value) {
        setCustomEvent(eventName, group, snapshots, value, null);
    }

    public static void setCustomEvent(String eventName, String group, String snapshots,
        Map<String, Object> attributes) {
        setCustomEvent(eventName, group, snapshots, 0, attributes);
    }

    public static void setCustomEvent(String eventName, String group, double value, Map<String, Object> info) {
        setCustomEvent(eventName, group, null, value, info);
    }

    public static void setCustomEvent(String eventName, String group, String snapshots, double value,
        Map<String, Object> attributes) {
        Agent.setCustomEvent(eventName, String.valueOf(value), group, snapshots, attributes);
    }
    // endregion

    public static String getDeviceId() {
        return Agent.getDeviceId();
    }

    public static void setDebuggable(boolean debuggable) {
        Agent.setDebuggable(debuggable);
    }
}
