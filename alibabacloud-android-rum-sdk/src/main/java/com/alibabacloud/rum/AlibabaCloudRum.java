package com.alibabacloud.rum;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import android.content.Context;
import android.text.TextUtils;
import com.openrum.sdk.agent.OpenRum;
import com.openrum.sdk.agent.OpenRum.AppEnvironment;
import org.json.JSONException;
import org.json.JSONObject;

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

    public static void setUserExtraInfo(Map<String, Object> extraInfo) {
        //noinspection deprecation
        OpenRum.setExtraInfo(extraInfo);
    }

    // region ===== exception =====
    public static void setCustomException(Throwable exception) {
        OpenRum.setCustomException(exception);
    }
    // endregion

    public static void setCustomException(String type, String caseBy, String message) {
        OpenRum.setCustomException(type, caseBy, message);
    }

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
}
