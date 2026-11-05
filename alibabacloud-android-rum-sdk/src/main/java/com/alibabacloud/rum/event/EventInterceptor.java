package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 在采集安全点的当前线程同步执行；不同事件可并发进入，规则需线程安全且快速无阻塞。
 * 返回本次 event 提交修改；View 返回 null 保留原 View，其它已接入类型返回 null 丢弃输出。
 * 异常或其它实例回退原事件。
 * 不要在此调用 setCustomEvent、setCustomLog、setCustomMetric、setCustomException
 * 或 reportCustomResource 等会生成业务事件的 API；允许注册、替换或清除 interceptor。
 */
@FunctionalInterface
public interface EventInterceptor {
    @Nullable
    RumEvent intercept(@NonNull RumEvent event, @NonNull EventContext context);
}
