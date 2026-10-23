package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 原生 Custom event/log 的候选。日志正文、级别、数值及公共关联字段只读。
 * setter 只在本次同步回调中有效；非法输入返回 false，不改变此前成功的修改。
 */
public final class CustomEvent extends RumEvent {
    private final EventCandidate candidate;

    CustomEvent(EventCandidate candidate) {
        super(candidate);
        this.candidate = candidate;
    }

    @Nullable
    public String getName() {
        return candidate.getName();
    }

    @Nullable
    public String getGroup() {
        return candidate.getGroup();
    }

    @Nullable
    public String getSnapshots() {
        return candidate.getSnapshots();
    }

    public boolean setName(@NonNull String name) {
        return candidate.setName(name);
    }

    public boolean setGroup(@NonNull String group) {
        return candidate.setGroup(group);
    }

    /** 整体替换字符串；不解析 JSON。空串有效，null 返回 false，不能删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }
}
