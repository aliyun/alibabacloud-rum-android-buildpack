package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 普通、手动原生异常及 Java fatal / Native crash / ANR 安全恢复的候选。
 * 仅允许修改 name、message、causedBy、snapshots。
 * 类型、堆栈、线程、身份、关联及采样字段只读；修改 message 不联动 causedBy 或堆栈。
 * 显式返回本次候选才提交；返回 null 丢弃本份业务输出，异常或其它实例回退原事件。
 * 业务输出修改不改变 SDK 自监控使用的原始异常归因。
 * setter 仅在同步回调期间有效；不要在 interceptor 内调用生成业务事件的 SDK API。
 */
public final class ExceptionEvent extends RumEvent {
    private final EventCandidate candidate;

    ExceptionEvent(EventCandidate candidate) {
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
    public String getCausedBy() {
        return candidate.getCausedBy();
    }

    @Nullable
    public String getSnapshots() {
        return candidate.getSnapshots();
    }

    public boolean setName(@NonNull String name) {
        return candidate.setName(name);
    }

    public boolean setMessage(@NonNull String message) {
        return candidate.setMessage(message);
    }

    public boolean setCausedBy(@NonNull String causedBy) {
        return candidate.setCausedBy(causedBy);
    }

    /** 整串替换，不解析或合并 JSON；空串有效，null 返回 false，不删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }
}
