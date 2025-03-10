package com.alibabacloud.rum;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import android.content.Context;
import android.text.TextUtils;
import com.openrum.sdk.agent.OpenRum;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @author yulong.gyl
 * @date 2024/4/19
 * @noinspection unused
 */
public class AlibabaCloudRum {
    private static final String CUSTOM_ATTRIBUTES_PREFIX = "_attr_";
    private static final String SDK_VERSION_PREFIX = "_sv_";
    private static final String SDK_FRAMEWORK = "_frmk_";

    public enum Env {
        NONE(0),
        PROD(1),
        GRAY(2),
        PRE(3),
        DAILY(4),
        LOCAL(5);

        static Map<Integer, String> sEnvStringMap = new HashMap<Integer, String>() {
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

        private Env(int i) {
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

    private OpenRum openRum;
    private Framework framework = null;
    private String env = Env.PROD.stringValue();

    private final Map<String, Object> cachedExtraInfo = new LinkedHashMap<>();

    {
        cachedExtraInfo.put(SDK_VERSION_PREFIX, BuildConfig.RUM_SDK_VERSION);
    }

    private static class SingletonHolder {
        private static final AlibabaCloudRum INSTANCE = new AlibabaCloudRum();
    }

    AlibabaCloudRum() {

    }

    private static void putJSONOpt(JSONObject object, String key, Object value) {
        if (TextUtils.isEmpty(key) || null == value) {
            return;
        }

        try {
            object.putOpt(key, value);
        } catch (JSONException e) {
            // ignore
        }
    }

    public static AlibabaCloudRum withAppID(String appID) {
        SingletonHolder.INSTANCE.openRum = OpenRum.withAppID(appID);
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withEnvironment(Env env) {
        this.env = env.stringValue();
        return SingletonHolder.INSTANCE;
    }

    public AlibabaCloudRum withCustomEnvironment(String env) {
        if (TextUtils.isEmpty(env)) {
            return SingletonHolder.INSTANCE;
        }

        this.env = env;
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
        //noinspection deprecation
        OpenRum.setExtraInfo(cachedExtraInfo);
        return this;
    }

    public AlibabaCloudRum startSync() {
        openRum.withAppEnvironment(env);
        openRum.withSyncStart(true).start();
        //noinspection deprecation
        OpenRum.setExtraInfo(cachedExtraInfo);
        return this;
    }

    public AlibabaCloudRum start(Context context) {
        openRum.withAppEnvironment(env);
        openRum.start(context);
        //noinspection deprecation
        OpenRum.setExtraInfo(cachedExtraInfo);
        return this;
    }

    public void stop() {
        OpenRum.stopSDK();
    }

    public static void setUserName(String userID) {
        OpenRum.setUserID(userID);
    }

    public static void setExtraInfo(Map<String, Object> extraInfo) {
        internalSetExtraInfo(extraInfo, false, false);
    }

    public static void addExtraInfo(Map<String, Object> extraInfo) {
        internalSetExtraInfo(extraInfo, false, true);
    }

    public static void setUserExtraInfo(Map<String, Object> extraInfo) {
        internalSetExtraInfo(extraInfo, true, false);
    }

    public static void addUserExtraInfo(Map<String, Object> extraInfo) {
        internalSetExtraInfo(extraInfo, true, true);
    }

    public static void setAppFramework(Framework framework) {
        SingletonHolder.INSTANCE.framework = framework;
        if (null != framework) {
            SingletonHolder.INSTANCE.cachedExtraInfo.put(SDK_FRAMEWORK, framework.getDescription());
        }
    }

    private static void internalSetExtraInfo(Map<String, Object> extraInfo, boolean user, boolean append) {
        if (null == extraInfo || extraInfo.isEmpty()) {
            return;
        }

        Map<String, Object> cachedExtraInfo = SingletonHolder.INSTANCE.cachedExtraInfo;
        //noinspection unchecked
        Map<String, Object> global = (Map<String, Object>)cachedExtraInfo.get(CUSTOM_ATTRIBUTES_PREFIX);
        if (null == global) {
            global = new LinkedHashMap<>();
        }

        if (!append) {
            Iterator<Entry<String, Object>> iterator = cachedExtraInfo.entrySet().iterator();
            while (iterator.hasNext()) {
                Entry<String, Object> next = iterator.next();
                if (null == next.getKey()) {
                    continue;
                }

                // keep SDK_VERSION_PREFIX
                if (SDK_VERSION_PREFIX.equals(next.getKey())) {
                    continue;
                }

                // keep SDK_FRAMEWORK
                if (SDK_FRAMEWORK.equals(next.getKey())) {
                    continue;
                }

                if (user) {
                    // in user attributes mode
                    // remove kv if is not global attributes
                    if (!next.getKey().equals(CUSTOM_ATTRIBUTES_PREFIX)) {
                        iterator.remove();
                    }
                } else {
                    // not in user attributes mode
                    // remove kv if is global attributes
                    if (next.getKey().equals(CUSTOM_ATTRIBUTES_PREFIX)) {
                        global.clear();
                        iterator.remove();
                    }
                }
            }
        }

        if (user) {
            cachedExtraInfo.putAll(extraInfo);
        } else {
            global.putAll(extraInfo);
        }

        if (!global.isEmpty()) {
            cachedExtraInfo.put(CUSTOM_ATTRIBUTES_PREFIX, global);
        }

        //noinspection deprecation
        OpenRum.setExtraInfo(cachedExtraInfo);
    }

    // region ===== exception =====
    public static void setCustomException(Throwable exception) {
        OpenRum.setCustomException(exception);
    }

    public static void setCustomException(String type, String caseBy, String message) {
        OpenRum.setCustomException(type, caseBy, message);
    }
    // endregion

    // region ===== metric =====
    public static void setCustomMetric(String name, long value) {
        OpenRum.setCustomMetric(name, value);
    }

    public static void setCustomMetric(String name, long value, String param) {
        OpenRum.setCustomMetric(name, value, param);
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
        JSONObject params = new JSONObject();

        putJSONOpt(params, "_ll", TextUtils.isEmpty(level) ? "INFO" : level);
        putJSONOpt(params, "_ln", name);
        putJSONOpt(params, "_lc", content);
        if (null != attributes && !attributes.isEmpty()) {
            for (Entry<String, Object> entry : attributes.entrySet()) {
                putJSONOpt(params, entry.getKey(), entry.getValue());
            }
        }

        OpenRum.setCustomLog(params.toString(), snapshots);
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
        if (null == attributes) {
            attributes = new HashMap<>();
        }

        // store custom value into attributes with '_orcv' field
        attributes.put("_orcv", value);
        OpenRum.setCustomEventWithLabel(UUID.randomUUID().toString(), eventName, group, snapshots, attributes);
    }
    // endregion

    public static String getDeviceId() {
        return OpenRum.getDeviceID();
    }
}
