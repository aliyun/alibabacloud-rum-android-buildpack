package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 已接入来源的原生 View 候选。身份、关联、采样、计时及生命周期字段只读。
 * 显式返回本次候选才提交修改；返回 null、其它实例或抛出异常均保留原 View。
 * setter 只在本次同步回调中有效；非法输入返回 false，不改变此前成功的修改。
 */
public final class ViewEvent extends RumEvent {
    private final EventCandidate candidate;

    ViewEvent(EventCandidate candidate) {
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

    /** 合法提交时，仅在 view.id 仍匹配当前活动页面时更新其名称，供后续新建事件关联。 */
    public boolean setName(@NonNull String name) {
        return candidate.setName(name);
    }

    /** 整体替换字符串；不解析 JSON。空串有效，null 返回 false，不能删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }
}
