package com.alibabacloud.rum.event;

import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 本次回调的事件候选。仅 SDK 创建；显式返回同一对象才提交修改。
 * 属性及嵌套容器为隔离的只读数据，缺失字段返回 null。
 */
public abstract class RumEvent {
    private final EventCandidate candidate;

    RumEvent(EventCandidate candidate) {
        this.candidate = candidate;
    }

    @NonNull
    public final String getEventType() {
        return candidate.getEventType();
    }

    @Nullable
    public final Object getAttribute(@NonNull String key) {
        return candidate.getAttribute(key);
    }

    @NonNull
    public final Map<String, Object> getAttributes() {
        return candidate.getAttributes();
    }
}
