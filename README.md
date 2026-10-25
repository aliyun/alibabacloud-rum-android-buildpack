# Android RUM buildpack

公开入口为 `com.alibabacloud.rum.AlibabaCloudRum`。原生 Custom event/log 与手动 Resource 的统一加工接口位于
`com.alibabacloud.rum.event`，用法与生命周期、候选提交、只读属性及上下文借用约束见工作区的
[EventInterceptor 接入说明](../docs/android/event-interceptor.md) 和各公开类型的 Javadoc。

## 手动 Resource 加工

当前支持既有 `AlibabaCloudRum.reportCustomResource(...)` 产生的手动 Resource，
`EventContext.getSource()` 为 `resource.manual`。OkHttp2、OkHttp3、URLConnection 等自动来源仍按各自既有流程处理，
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

## 验证公开产物

先将本轮内部 SDK 的 `:alibabacloud-rum-android-sdk:assembleDistribute` 产物放到
`alibabacloud-rum-android-sdk/alibabacloud-rum-android-sdk-distribute.aar`，然后执行：

```bash
./gradlew :alibabacloud-android-rum-sdk:testDebugUnitTest :alibabacloud-android-rum-sdk:assembleRelease
python3 scripts/verify-event-api.py \
  alibabacloud-android-rum-sdk/build/outputs/aar/alibabacloud-android-rum-sdk-release.aar
```

脚本从指定 AAR 提取实际类，验证 Java/Kotlin SAM、Custom/Resource 可空读取、64 位成对结果、boolean setter，
以及构造、克隆、通用写入、独立结果 setter、URL 改写等负例，并检查公开签名没有泄漏内部事件模型。
consumer rules 保留公开 Resource 类型和三态枚举。证据记录 AAR 的 SHA-256，默认保存在
`~/tmp/`。需要 JDK，以及本机 Gradle 缓存中的 Kotlin 1.9.22 编译器及其运行依赖；缺少依赖时
明确失败。`--output` 可指定证据目录，`--kotlin-version` 可指定已缓存的兼容编译器版本。

消费检查不能代替真实宿主的 R8、Custom/手动 Resource 场景或最终导出验证。发布链必须使用本轮 SDK →
buildpack → 宿主产物，不以源码接缝测试或旧 AAR 代替。

模块 `build.gradle` 将内嵌 SDK AAR 的 SHA-256 登记为 TransformR 输入，避免 fat-aar 1.3.8
增量刷新时遗漏新增类；二进制未变化时保留增量构建。验收应冻结实际 AAR 副本并记录哈希，
避免 fat-aar 再打包的 ZIP 元数据变化影响宿主消费证据。
