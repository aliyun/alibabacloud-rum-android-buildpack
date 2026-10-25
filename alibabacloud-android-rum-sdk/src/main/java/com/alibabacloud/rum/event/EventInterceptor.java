package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 在采集安全点的当前线程同步执行；不同事件可并发进入，规则需线程安全且快速无阻塞。
 * 返回本次 event 提交修改，返回 null 丢弃 Custom 或手动 Resource 输出；异常或其它实例回退原事件。
 * 不要在此调用 setCustomEvent、setCustomLog、setCustomMetric、setCustomException
 * 或 reportCustomResource 等会生成业务事件的 API；允许注册、替换或清除 interceptor。
 */
@FunctionalInterface
public interface EventInterceptor {
    @Nullable
    RumEvent intercept(@NonNull RumEvent event, @NonNull EventContext context);
}
