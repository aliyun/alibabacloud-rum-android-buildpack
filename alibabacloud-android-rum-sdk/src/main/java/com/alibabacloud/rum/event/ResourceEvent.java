package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 已接入来源的原生 Resource 候选。具体来源见 EventContext；URL、关联、采样与计时字段只读。
 * setter 只在本次同步回调中有效；非法输入返回 false，不改变此前成功的修改。
 */
public final class ResourceEvent extends RumEvent {
    private final EventCandidate candidate;

    ResourceEvent(EventCandidate candidate) {
        super(candidate);
        this.candidate = candidate;
    }

    @Nullable
    public String getName() {
        return candidate.getName();
    }

    @Nullable
    public String getMessage() {
        return candidate.getMessage();
    }

    @Nullable
    public String getSnapshots() {
        return candidate.getSnapshots();
    }

    /** 缺失或不能无损表示为 signed 64-bit 时返回 null。 */
    @Nullable
    public Long getStatusCode() {
        return candidate.getStatusCode();
    }

    /** 保留原整数值，包括三态枚举以外的值；缺失或不能无损表示时返回 null。 */
    @Nullable
    public Integer getSuccess() {
        return candidate.getSuccess();
    }

    public boolean setName(@NonNull String name) {
        return candidate.setName(name);
    }

    public boolean setMessage(@NonNull String message) {
        return candidate.setMessage(message);
    }

    /** 整体替换字符串；不解析 JSON。空串有效，null 返回 false，不能删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }

    /**
     * 原子修改状态码与结果。接受完整 signed 64-bit 范围，不推导或修正 success。
     * success 为 null 时返回 false，两个字段均保持原值。
     */
    public boolean setResourceResult(long statusCode, @NonNull ResourceSuccess success) {
        return success != null && candidate.setResourceResult(statusCode, success.toInternal());
    }
}
