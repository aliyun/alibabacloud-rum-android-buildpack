package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.InterceptorContext;

/**
 * 同步回调期间借用的原始上下文；不得跨线程保留或修改原生对象。
 * custom.event / custom.log 的 originalParameters 槽为原入口参数 Map。
 * resource.manual 的同名槽包含 type、url、method、statusCode、errorMessage、success、provider、
 * traceContext、measuring 原参数；标量值及原对象只在回调中借用，容器只读。
 * 手动 Resource 没有 request / response 槽；不会为缺失对象创建网络请求或读取 headers/body。
 * 缺失、类型不匹配或回调已结束时，getNativeObject 返回 null。
 * SDK 不为此额外读取、复制或自动导出原生 headers/body。
 */
public final class EventContext {
    private final InterceptorContext context;

    EventContext(InterceptorContext context) {
        this.context = context;
    }

    @NonNull
    public String getSource() {
        return context.getSource();
    }

    @Nullable
    public <T> T getNativeObject(@NonNull String name, @NonNull Class<T> type) {
        return context.getNativeObject(name, type);
    }
}
