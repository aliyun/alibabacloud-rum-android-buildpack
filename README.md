# Android RUM buildpack

公开入口为 `com.alibabacloud.rum.AlibabaCloudRum`。原生 Custom event/log、View、Action、手动 Resource、OkHttp2 与 URLConnection Resource 的统一加工接口位于
`com.alibabacloud.rum.event`，用法与生命周期、候选提交、只读属性及上下文借用约束见工作区的
[EventInterceptor 接入说明](../docs/android/event-interceptor.md) 和各公开类型的 Javadoc。

## Resource 加工

当前支持既有 `AlibabaCloudRum.reportCustomResource(...)` 产生的手动 Resource，
`EventContext.getSource()` 为 `resource.manual`；OkHttp2 自动来源使用 `resource.okhttp2`，
HttpURLConnection / HttpsURLConnection 共用 `resource.urlconnection`。OkHttp3 等其它自动来源仍按各自既有流程处理，
不能由 `ResourceEvent` 类型存在推断这些来源已支持 interceptor。

```java
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ResourceEvent;
import com.alibabacloud.rum.event.ResourceSuccess;

AlibabaCloudRum.setEventInterceptor((event, context) -> {
    if (event instanceof ResourceEvent) {
        ResourceEvent resource = (ResourceEvent) event;
        resource.setName("checkout");
        resource.setMessage("");
        resource.setSnapshots("business state: declined");
        resource.setResourceResult(1001L, ResourceSuccess.FAILED);
    }
    return event;
});
```

`ResourceEvent` 提供可空的 `String getName()/getMessage()/getSnapshots()`、`Long getStatusCode()` 和
`Integer getSuccess()`；缺失或不能无损表示的数值返回 `null`，success 的原始整数不强制映射到枚举。
专用 setter 均返回 `boolean`；文本接受空串、空白和非 JSON 字符串，`setSnapshots` 整串替换、允许补充缺失值，
不合并、不解析、不重跑 Provider，也不增加长度门禁。`null` 返回 `false`，不删除字段，并保留此前成功修改。

`setResourceResult(long, ResourceSuccess)` 原子更新 `resource.status_code` 与 `resource.success`。
状态码接受完整有符号 64 位范围，包括 `Long.MIN_VALUE`、`Long.MAX_VALUE`、负业务码与 `1001`；
`UNKNOWN`、`FAILED`、`SUCCESS` 的 `getValue()` 分别为 `-1`、`0`、`1`。
不校验 HTTP 状态范围，不按状态码推导结果，`null` 枚举使两个字段均保持原值。
既有 `reportCustomResource` 的输入签名保持原样；64 位结果通过候选的专用方法设置。

只允许修改 name、message、snapshots 与成对结果。URL、domain/scheme、trace、身份、关联、采样与计时字段只读；
`getAttributes()` 及嵌套容器也不能改写。客户不能构造、继承、克隆候选或取得内部模型。
必须显式返回本次传入的同一 `event` 才提交；返回 `null` 丢弃这份输出，回调异常或返回另一候选则整体回退原事件。
回调返回后保留的候选不能继续修改最终输出。

手动来源的 `context.getNativeObject("originalParameters", Map.class)` 提供只读原参数容器：

| 参数键 | 借用的数据 |
| --- | --- |
| `type`、`url`、`method`、`errorMessage`、`provider` | 原字符串参数 |
| `statusCode`、`success` | 原标量参数，与修改后的候选字段独立 |
| `traceContext`、`measuring` | 实际处理或 drain 时的参数对象；公开入口沿用默认值处理，`measuring=null` 会先创建默认 `Measuring` |

手动入口没有原生 request/response/error 对象，这些槽返回 `null`；缺少对象不跳过加工。
容器及其中对象仅在同步回调期借用，不保留、改写或跨线程使用。回调在实际 API 处理或缓冲 drain 的当前线程同步执行，
不同事件可能并发进入，规则应线程安全、快速且无阻塞。SDK 不为此额外读取、提取、复制、解析、缓存或自动导出 headers/body，
上下文不依赖 SnapshotProvider；用户主动写入 snapshots 的内容将作为事件字段导出。

不要在 interceptor 中调用 `setCustomEvent`、`setCustomLog`、`setCustomMetric`、`setCustomException`、
`reportCustomResource` 等生成业务事件的 API。该约束通过接入文档说明，不新增运行时递归 guard。
注册、替换与 `setEventInterceptor(null)` 清除仍可在任何生命周期阶段调用，之后的实际调用使用当时的注册。

## OkHttp2 原生上下文

OkHttp2 的 Resource 使用同一 `ResourceEvent` 与专用 setter。在 `chain.proceed` 成功或失败后、
响应或原错误交还宿主前，SDK 在当前请求线程同步调用规则。可通过
`context.getNativeObject("request", com.squareup.okhttp.Request.class)`、
`getNativeObject("response", com.squareup.okhttp.Response.class)` 和
`getNativeObject("error", java.io.IOException.class)` 读取该现场已有的引用；成功时 error 为空，
失败时 response 为空。`originalParameters` 槽不适用于此来源。

原生对象只在回调期间借用，回调结束后 context 不再提供这些引用。此访问不依赖 SnapshotProvider，
SDK 不为此读取、复制、缓存或导出 headers/body。body 可能已消费、关闭或不可安全读取；规则不得
消费/关闭宿主流，也不得改写请求或响应。业务结果可依据安全可读的 response header 设置，
修改或过滤 RUM 输出不会改变宿主收到的响应与错误。规则应快速完成，耗时会延长当前请求返回。

## URLConnection 原生上下文

HttpURLConnection 与 HttpsURLConnection 使用同一 `ResourceEvent` 和
`source=resource.urlconnection`，无需 OkHttp 依赖。正常 EOF、流 close、错误与 disconnect
在触发终结的当前线程同步加工；同一资源的多个终结入口只处理一次。

| 槽 | 原生类型 | 可用条件 |
| --- | --- | --- |
| `connection` | `java.net.HttpURLConnection`；HTTPS 实际为 `javax.net.ssl.HttpsURLConnection` | 该采集包装器的实际 delegate |
| `inputStream` | `java.io.InputStream` | 宿主此前实际取得的底层输入流；尚未取得时为空 |
| `outputStream` | `java.io.OutputStream` | 宿主此前实际取得的底层输出流；尚未取得时为空 |
| `error` | `java.lang.Throwable` | 终结时已有的原始错误；正常终结时为空 |

没有独立的 `request`、`response` 或 `originalParameters`，这些槽返回 `null`。按类型安全方式读取：

```java
AlibabaCloudRum.setEventInterceptor((event, context) -> {
    if (event instanceof ResourceEvent && "resource.urlconnection".equals(context.getSource())) {
        java.net.HttpURLConnection connection =
            context.getNativeObject("connection", java.net.HttpURLConnection.class);
        java.io.InputStream input = context.getNativeObject("inputStream", java.io.InputStream.class);
        java.io.OutputStream output = context.getNativeObject("outputStream", java.io.OutputStream.class);
        Throwable error = context.getNativeObject("error", Throwable.class);
        ((ResourceEvent) event).setName("/orders/:id");
    }
    return event;
});
```

这些引用只在回调期借用，结束后访问返回 `null`，且不依赖 SnapshotProvider。SDK 不调用
`getInputStream`、`getOutputStream` 或 `getErrorStream` 补取流，也不读取、复制、缓存或重放 body。
EOF、close、disconnect 或失败时，原生流可能已消费、关闭、部分可用或不可读取；有流引用不保证
body 可读或完整。规则不能消费、关闭、改写宿主流，不能重新连接或修改连接配置；缺少流也继续加工。
修改、丢弃或规则异常不改变宿主的返回、错误与清理行为。回调耗时不计入已经固定的网络指标，
但仍会延长当前终结操作，规则须支持并发且快速完成。

若 `finalize` 是首次终结入口，SDK 跳过用户 interceptor，继续既有默认采集与清理；
不会把这条输出当作 drop，也不通过后台补调。若正常终结已完成，后到的 finalizer 不重复加工。

## Action 加工与操作收尾

原生操作来源使用 `source=action.native`，通过独立的 `ActionEvent` 识别。
只允许 `setName` 修改 `action.name`、`setSnapshots` 修改 `action.snapshots`；
操作类型、其它分类、身份、View/Session 关联、采样与计时字段只读。
`String getName()/getSnapshots()` 可返回 `null`，成功修改后立即读取候选的新值。

```java
import android.view.View;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ActionEvent;

AlibabaCloudRum.setEventInterceptor((event, context) -> {
    if (event instanceof ActionEvent && "action.native".equals(context.getSource())) {
        ActionEvent action = (ActionEvent) event;
        View target = context.getNativeObject("targetView", View.class);
        Object actionType = action.getAttribute("action.type");
        action.setName("checkout.confirmed");
        action.setSnapshots("business state: confirmed");
    }
    return event;
});
```

两个 setter 返回 `boolean`，接受空字符串、纯空白及非 JSON 文本；snapshots 整串替换，不解析、合并或重跑 Provider，
原字段缺失时也能显式设置。`null` 返回 `false`、不删除字段，并保留此前成功的修改。没有通用写入、remove/clear、
独立类型 setter 或客户可用的构造、继承、clone/builder 能力。
必须显式返回本次传入的同一 `event` 才提交；返回 `null` 丢弃这份输出，异常或返回另一候选整体回退原事件。
回调返回后候选不能再修改 final。修改、丢弃及回调失败均不改变宿主操作、原回调和内部操作结束/状态清理。

| `source` | 槽 | 原生类型 | 可用条件 |
| --- | --- | --- | --- |
| `action.native` | `targetView` | `android.view.View` | View/RadioGroup/List/Expandable 点击、触摸入口的实际控件，或手势完成点仍有效的目标控件 |
| `action.native` | `dialog` | `android.app.Dialog` | 原生 Dialog 点击入口已有的 Dialog |
| `action.native` | `menuItem` | `android.view.MenuItem` | 原生菜单入口的实际参数 |
| `action.native` | `motionEvent` | `android.view.MotionEvent` | 触摸原参数；手势完成点使用既有生命周期内的 UP/CANCEL 副本 |
| `action.native` | `keyEvent` | `android.view.KeyEvent` | 返回键入口已有的按键参数 |
| `action.native` | `originalParameters` | `java.util.Map` | 当前入口已有的 `actionName`、`actionType` 等标量参数，只读外层容器 |

`originalParameters` 包含加工前的 `actionName`、`actionType`；Dialog 另有 `whichButton`，RadioGroup 有 `checkedId`，
List 有 `position`，Expandable 有 `parentPosition`/`childPosition`，Tab 有 `tabName`，手势有
`startTimestamp`/`startTimeNano`。这些值与候选修改独立，不在该容器中放入 UI 对象。

上述槽均可空，Tab 或既有内部操作入口可能没有活 UI 对象，仍正常加工；缺失、类型不匹配或回调结束后访问返回 `null`。
上下文仅回调期借用，不保留、改写、主动回收或放入异步任务/导出数据；访问不依赖 SnapshotProvider。
SDK 在真实完成点的当前线程同步执行规则，不切换主线程补读 UI，也不为补齐上下文延长控件生命周期。
只能在原生对象允许的线程上读取对象；多个事件可并发进入规则，处理必须线程安全、快速且无阻塞。
用户回调不计入已固定的操作计时，耗时仍会延长当前完成路径。

不要在 interceptor 中调用 `setCustomEvent`、`setCustomLog`、`setCustomMetric`、`setCustomException`、
`reportCustomResource` 等会生成业务事件的 API；规则内部可以读取数据、使用专用 setter 或注册、替换、清除 interceptor。

## 验证公开产物

先将本轮内部 SDK 的 `:alibabacloud-rum-android-sdk:assembleDistribute` 产物放到
`alibabacloud-rum-android-sdk/alibabacloud-rum-android-sdk-distribute.aar`，然后执行：

```bash
./gradlew :alibabacloud-android-rum-sdk:testDebugUnitTest :alibabacloud-android-rum-sdk:assembleRelease
python3 scripts/verify-event-api.py \
  alibabacloud-android-rum-sdk/build/outputs/aar/alibabacloud-android-rum-sdk-release.aar --okhttp2
```

脚本从指定 AAR 提取实际类，验证 Java/Kotlin SAM、Custom/Resource/View/Action 可空读取、64 位成对结果、boolean setter，
以及 JDK 原生 URLConnection 四槽的类型安全读取；验证构造、克隆、通用写入、独立结果 setter、URL 改写、
context 写入及错误原生类型赋值等负例，并检查公开签名没有泄漏内部事件模型。
consumer rules 保留公开事件类型（含 ActionEvent）和三态枚举。证据记录 AAR 的 SHA-256，默认保存在
`~/tmp/`。需要 JDK，以及本机 Gradle 缓存中的 Kotlin 1.9.22 编译器及其运行依赖；缺少依赖时
明确失败。`--output` 可指定证据目录，`--kotlin-version` 可指定已缓存的兼容编译器版本。
`--okhttp2` 额外使用缓存的 OkHttp 2.7.5 编译 Java/Kotlin 原生 request/response/error 读取样例，
不改变公共 API 的基础依赖。

消费检查不能代替真实宿主的 R8、Custom/手动 Resource/OkHttp2/URLConnection/View/Action 场景或最终导出验证。发布链必须使用本轮 SDK →
buildpack → 宿主产物，不以源码接缝测试或旧 AAR 代替。

模块 `build.gradle` 将内嵌 SDK AAR 的 SHA-256 登记为 TransformR 输入，避免 fat-aar 1.3.8
增量刷新时遗漏新增类；二进制未变化时保留增量构建。验收应冻结实际 AAR 副本并记录哈希，
避免 fat-aar 再打包的 ZIP 元数据变化影响宿主消费证据。
