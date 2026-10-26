package com.alibabacloud.rum.event;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.alibabacloud.rum.core.event.InterceptorContext;

/**
 * 同步回调期间借用的原始上下文；不得跨线程保留或修改原生对象。
 * custom.event / custom.log 的 originalParameters 槽为原入口参数 Map。
 * exception.manual / exception.native 提供当前入口已有的 exception（Throwable，可空）与
 * originalParameters（只读原参数 Map）槽。字符串入口没有 Throwable，不为补齐上下文制造对象。
 * 立即 Throwable 入口原参数只有 exception；立即字符串入口为 type、crashName、crashMessage。
 * 普通 reportException 原参数为 type、subType、causedBy、stacktrace；reportError 为
 * name、subType、message、stacktrace、source、file、line、column，保留原值而不随候选修改。
 * 既有启动缓冲仅保存事件字段，drain 时两槽均可空；SDK 不为 interceptor 额外持有原参数或 Throwable。
 * 修改业务异常输出不改变 SDK 自监控使用的原始异常归因；fatal/native/ANR 恢复来源未在本类型首批接入。
 * action.native 按实际入口提供可空 targetView（View）、dialog（Dialog）、menuItem（MenuItem）、
 * motionEvent（MotionEvent）、keyEvent（KeyEvent）槽；originalParameters 为 actionName、actionType 等
 * 当前入口已有的标量参数 Map。手势完成时 motionEvent 是既有生命周期内的 UP/CANCEL 副本。
 * application.start 的 originalParameters 为 appStartType（String）、appStartDuration（Long，毫秒）、
 * isFirstLaunch（Boolean，可空）原参数 Map；application.state 的同名槽含 changeType（String）。
 * 两者在既有启动/状态完成线程当前同步回调，本轮没有 application 或 activity 原生对象槽；
 * API29+ cold/hot 计时回调也不保证存在 Activity，不会为补齐上下文获取或强留 UI 对象。
 * 在实际操作完成点的当前线程同步执行；不会切换到主线程补读 UI，也不为缺失上下文强留 UI 对象。
 * 不得主动回收原生事件，或在后台线程读取只能在主线程访问的 UI；回调结束后所有原生槽均不可访问。
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
