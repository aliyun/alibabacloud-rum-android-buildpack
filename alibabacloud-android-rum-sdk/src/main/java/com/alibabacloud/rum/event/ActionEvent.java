package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 原生 Action 的候选。操作类型、身份、关联、采样、计时及收尾状态只读。
 * 显式返回本次候选才提交修改；返回 null 丢弃本次输出，异常或其它实例回退原 Action。
 * setter 只在本次同步回调中有效；非法输入返回 false，不改变此前成功的修改。
 * 修改或丢弃输出不改变原操作的执行、结束与内部状态清理。
 */
public final class ActionEvent extends RumEvent {
    private final EventCandidate candidate;

    ActionEvent(EventCandidate candidate) {
        super(candidate);
        this.candidate = candidate;
    }

    @Nullable
    public String getName() {
        return candidate.getName();
    }

    @Nullable
    public String getSnapshots() {
        return candidate.getSnapshots();
    }

    public boolean setName(@NonNull String name) {
        return candidate.setName(name);
    }

    /** 整体替换字符串；不解析 JSON。空串有效，null 返回 false，不能删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }
}
