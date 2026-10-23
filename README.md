# Android RUM buildpack

公开入口为 `com.alibabacloud.rum.AlibabaCloudRum`。原生 Custom event/log 的统一加工接口位于
`com.alibabacloud.rum.event`，用法与生命周期、候选提交、只读属性及上下文借用约束见工作区的
[EventInterceptor 接入说明](../docs/android/event-interceptor.md) 和各公开类型的 Javadoc。

## 验证公开产物

先将本轮内部 SDK 的 `:alibabacloud-rum-android-sdk:assembleDistribute` 产物放到
`alibabacloud-rum-android-sdk/alibabacloud-rum-android-sdk-distribute.aar`，然后执行：

```bash
./gradlew :alibabacloud-android-rum-sdk:testDebugUnitTest :alibabacloud-android-rum-sdk:assembleRelease
python3 scripts/verify-event-api.py \
  alibabacloud-android-rum-sdk/build/outputs/aar/alibabacloud-android-rum-sdk-release.aar
```

脚本从指定 AAR 提取实际类，验证 Java/Kotlin SAM、可空读取、boolean setter 与构造、克隆、
通用写入等负例，并检查公开签名没有泄漏内部事件模型。证据记录 AAR 的 SHA-256，默认保存在
`~/tmp/`。需要 JDK，以及本机 Gradle 缓存中的 Kotlin 1.9.22 编译器及其运行依赖；缺少依赖时
明确失败。`--output` 可指定证据目录，`--kotlin-version` 可指定已缓存的兼容编译器版本。

消费检查不能代替真实宿主的 R8、Custom 场景或最终导出验证。发布链必须使用本轮 SDK →
buildpack → 宿主产物，不以源码接缝测试或旧 AAR 代替。

模块 `build.gradle` 将内嵌 SDK AAR 的 SHA-256 登记为 TransformR 输入，避免 fat-aar 1.3.8
增量刷新时遗漏新增类；二进制未变化时保留增量构建。验收应冻结实际 AAR 副本并记录哈希，
避免 fat-aar 再打包的 ZIP 元数据变化影响宿主消费证据。
