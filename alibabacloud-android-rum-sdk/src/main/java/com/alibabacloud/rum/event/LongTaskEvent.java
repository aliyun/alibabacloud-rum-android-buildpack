package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.EventCandidate;

/**
 * 原生 watchdog 形成的长任务候选；wire 类型保持 longTask，字段使用 longtask. 前缀。
 * 仅允许修改 message、snapshots；堆栈、线程、身份、关联、采样和计时只读。
 * 显式返回本次候选才提交；返回 null 丢弃本份输出，异常或其它实例回退原事件。
 * 加工不改变卡顿检测结果、计时或后续 watchdog 检测。规则在 watchdog 当前线程同步执行，
 * 应保持快速且不阻塞；不要操作主线程 UI，或在 interceptor 内调用生成业务事件的 SDK API。
 * setter 仅在同步回调期间有效。
 */
public final class LongTaskEvent extends RumEvent {
    private final EventCandidate candidate;

    LongTaskEvent(EventCandidate candidate) {
        super(candidate);
        this.candidate = candidate;
    }

    @Nullable
    public String getMessage() {
        return candidate.getMessage();
    }

    @Nullable
    public String getSnapshots() {
        return candidate.getSnapshots();
    }

    public boolean setMessage(@NonNull String message) {
        return candidate.setMessage(message);
    }

    /** 整串替换，不解析或合并 JSON；空串有效，null 返回 false，不删除字段。 */
    public boolean setSnapshots(@NonNull String snapshots) {
        return candidate.setSnapshots(snapshots);
    }
}
