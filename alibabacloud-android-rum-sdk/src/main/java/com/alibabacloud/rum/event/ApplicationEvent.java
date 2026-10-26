package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 原生启动与前后台事件的候选；仅允许设置 application.snapshots。
 * 状态、时长、启动指标、线程、身份及关联字段通过基类只读方法访问。
 * 显式返回本次候选才提交修改；返回 null 丢弃本次输出，异常或其它实例回退原事件。
 * 修改或丢弃输出不改变应用生命周期、启动时间线与 Session 状态。
 * setter 仅在同步回调中有效；不要在 interceptor 内调用生成业务事件的 SDK API。
 */
public final class ApplicationEvent extends RumEvent {
    private final EventCandidate candidate;

    ApplicationEvent(EventCandidate candidate) {
        super(candidate);
        this.candidate = candidate;
    }

    @Nullable
    public String getSnapshots() {
        return candidate.getSnapshots();
    }

    /** 整体替换字符串，不解析或合并 JSON；空串有效，null 返回 false，不删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }
}
