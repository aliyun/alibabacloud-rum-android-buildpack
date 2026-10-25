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
 * resource.okhttp2 提供 request（com.squareup.okhttp.Request）、response（成功时的
 * com.squareup.okhttp.Response）和 error（失败时已有的 IOException）三个槽；originalParameters 为空。
 * 此回调在 chain.proceed 完成后、响应或原错误交还宿主前，于当前请求线程同步执行。
 * resource.urlconnection 同时适用于 HTTP/HTTPS，提供 connection（实际的 HttpURLConnection，
 * HTTPS 为 HttpsURLConnection）、inputStream（已取得的原始 InputStream）、
 * outputStream（已取得的原始 OutputStream）和 error（已有的终结 Throwable）四个可空槽；
 * 没有独立 request、response 或 originalParameters。SDK 不补取或创建流。
 * 回调在 EOF、close、error、disconnect 的当前终结线程同步执行，同一资源只加工一次；
 * finalize 首次终结时跳过用户回调，继续默认采集与清理，不在后台补调。
 * headers/body 通过原生对象访问；body 可能已消费、关闭或不可安全读取，不保证完整可读。
 * 不得消费、关闭或改写宿主的请求/响应流；SnapshotProvider 开关不影响原生对象访问。
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
