#!/usr/bin/env python3
"""Compile Java/Kotlin consumers against the supplied release AAR, including forbidden APIs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def cached_jar(cache, group, artifact, version):
    matches = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if not matches:
        raise RuntimeError(f"Missing cached dependency: {group}:{artifact}:{version}")
    return matches[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--kotlin-version", default="1.9.22")
    parser.add_argument("--okhttp2", action="store_true", help="Also compile typed OkHttp 2.7.5 context consumers")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    temporary_root = Path.home() / "tmp"
    temporary_root.mkdir(exist_ok=True)
    output = args.output or Path(tempfile.mkdtemp(prefix="rum-event-api-", dir=temporary_root))
    output.mkdir(parents=True, exist_ok=True)
    cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    annotation = cached_jar(cache, "androidx.annotation", "annotation", "1.5.0")
    stdlib = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-stdlib", args.kotlin_version)
    compiler = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-compiler-embeddable", args.kotlin_version)
    runtime = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-script-runtime", args.kotlin_version)
    reflect = cached_jar(cache, "org.jetbrains.kotlin", "kotlin-reflect", "1.6.10")
    trove = cached_jar(cache, "org.jetbrains.intellij.deps", "trove4j", "1.0.20200330")
    annotations = cached_jar(cache, "org.jetbrains", "annotations", "13.0")
    native_jars = [cached_jar(cache, "com.squareup.okhttp", "okhttp", "2.7.5")] if args.okhttp2 else []
    jars = []
    with zipfile.ZipFile(args.aar) as archive:
        for name in archive.namelist():
            if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                destination = output / Path(name).name
                destination.write_bytes(archive.read(name))
                jars.append(destination)
    if not jars:
        raise RuntimeError("AAR contains no Java classes")
    classpath = os.pathsep.join(map(str, jars + [annotation, stdlib] + native_jars))
    compiler_classpath = os.pathsep.join(map(str, [compiler, stdlib, runtime, reflect, trove, annotations]))
    evidence = {"aar": str(args.aar.resolve()), "sha256": hashlib.sha256(args.aar.read_bytes()).hexdigest(),
                "kotlin_version": args.kotlin_version,
                "application_sources": ["application.start", "application.state"],
                "exception_sources": ["exception.manual", "exception.native", "exception.java.recovered",
                                      "exception.native.recovered", "exception.anr.recovered"],
                "longtask_sources": ["longtask.watchdog"],
                "replay_sources": ["view.replay"],
                "native_context_sources": ["resource.urlconnection"] + (["resource.okhttp2"] if args.okhttp2 else []),
                "checks": []}

    def check(name, language, source, should_compile=True):
        extension = "java" if language == "java" else "kt"
        source_file = output / f"{name}.{extension}"
        source_file.write_text(source)
        classes = output / f"{name}-classes"
        classes.mkdir(exist_ok=True)
        if language == "java":
            command = ["javac", "-source", "8", "-target", "8", "-cp", classpath,
                       "-d", str(classes), str(source_file)]
        else:
            command = ["java", "-cp", compiler_classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                       "-no-stdlib", "-no-reflect", "-jvm-target", "1.8", "-classpath", classpath,
                       "-d", str(classes), str(source_file)]
        result = subprocess.run(command, capture_output=True, text=True)
        (output / f"{name}.log").write_text(result.stdout + result.stderr)
        success = (result.returncode == 0) == should_compile
        evidence["checks"].append({"name": name, "expected": "compile" if should_compile else "reject",
                                   "compiler_exit": result.returncode, "passed": success})
        if not success:
            raise RuntimeError(f"{name} did not {'compile' if should_compile else 'reject'}: {result.stderr}")

    check("JavaConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.*;
import java.util.Map;
final class JavaConsumer {
    boolean register() {
        boolean registered = AlibabaCloudRum.setEventInterceptor((event, context) -> {
            Map<String, Object> fields = event.getAttributes();
            Object raw = event.getAttribute("custom.value");
            Map<?, ?> parameters = context.getNativeObject("originalParameters", Map.class);
            String source = context.getSource();
            if (event instanceof CustomEvent) {
                CustomEvent custom = (CustomEvent) event;
                String name = custom.getName(), group = custom.getGroup(), snapshots = custom.getSnapshots();
                boolean changed = custom.setName("") && custom.setGroup(" ") && custom.setSnapshots("plain text");
                if (!changed) return null;
            }
            if (event instanceof ResourceEvent) {
                ResourceEvent resource = (ResourceEvent) event;
                String name = resource.getName(), message = resource.getMessage(), snapshots = resource.getSnapshots();
                Long statusCode = resource.getStatusCode();
                Integer success = resource.getSuccess();
                boolean changed = resource.setName("") && resource.setMessage(" ")
                    && resource.setSnapshots("plain text")
                    && resource.setResourceResult(Long.MIN_VALUE, ResourceSuccess.UNKNOWN)
                    && resource.setResourceResult(Long.MAX_VALUE, ResourceSuccess.SUCCESS)
                    && resource.setResourceResult(1001L, ResourceSuccess.FAILED);
                int unknown = ResourceSuccess.UNKNOWN.getValue();
                if (!changed) return null;
            }
            if (event instanceof ViewEvent) {
                ViewEvent view = (ViewEvent) event;
                String name = view.getName(), snapshots = view.getSnapshots();
                boolean changed = view.setName("") && view.setSnapshots("plain text");
                if (!changed) return event;
            }
            return event;
        });
        return registered && AlibabaCloudRum.setEventInterceptor(null);
    }
}
""")
    check("KotlinConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.CustomEvent
import com.alibabacloud.rum.event.ResourceEvent
import com.alibabacloud.rum.event.ResourceSuccess
import com.alibabacloud.rum.event.ViewEvent
fun register(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    val source: String = context.source
    val parameters: Map<*, *>? = context.getNativeObject("originalParameters", Map::class.java)
    val raw: Any? = event.getAttribute("custom.value")
    if (event is CustomEvent) {
        val name: String? = event.name
        val group: String? = event.group
        val snapshots: String? = event.snapshots
        if (!event.setName("") || !event.setGroup(" ") || !event.setSnapshots("plain text")) {
            return@setEventInterceptor null
        }
    }
    if (event is ResourceEvent) {
        val name: String? = event.name
        val message: String? = event.message
        val snapshots: String? = event.snapshots
        val statusCode: Long? = event.statusCode
        val success: Int? = event.success
        val unknown: Int = ResourceSuccess.UNKNOWN.value
        if (!event.setName("") || !event.setMessage(" ") || !event.setSnapshots("plain text")
            || !event.setResourceResult(Long.MIN_VALUE, ResourceSuccess.UNKNOWN)
            || !event.setResourceResult(Long.MAX_VALUE, ResourceSuccess.SUCCESS)
            || !event.setResourceResult(1001L, ResourceSuccess.FAILED)) {
            return@setEventInterceptor null
        }
    }
    if (event is ViewEvent) {
        val name: String? = event.name
        val snapshots: String? = event.snapshots
        if (!event.setName("") || !event.setSnapshots("plain text")) {
            return@setEventInterceptor event
        }
    }
    event
}
fun clear(): Boolean = AlibabaCloudRum.setEventInterceptor(null)
""")
    check("JavaReplayViewConsumer", "java", """
import java.util.Map;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ViewEvent;
final class JavaReplayViewConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ViewEvent && "view.replay".equals(context.getSource())) {
                ViewEvent replay = (ViewEvent) event;
                String name = replay.getName(), snapshots = replay.getSnapshots();
                Object subtype = replay.getAttribute("view.type");
                Object count = replay.getAttribute("view.records_count");
                Object recordsEnd = replay.getAttribute("view.records_end");
                Map<?, ?> record = context.getNativeObject("recoveredRecord", Map.class);
                if (record != null) {
                    Long timestamp = (Long) record.get("timestamp");
                    String originalName = (String) record.get("view.name");
                }
                if (!replay.setName("checkout.replay") || !replay.setSnapshots("not JSON")) return event;
            }
            return event;
        });
    }
}
""")
    check("KotlinReplayViewConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ViewEvent
fun registerReplayView(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ViewEvent && context.source == "view.replay") {
        val name: String? = event.name
        val snapshots: String? = event.snapshots
        val subtype: Any? = event.getAttribute("view.type")
        val count: Any? = event.getAttribute("view.records_count")
        val recordsEnd: Any? = event.getAttribute("view.records_end")
        val record: Map<*, *>? = context.getNativeObject("recoveredRecord", Map::class.java)
        val timestamp: Long? = record?.get("timestamp") as? Long
        val originalName: String? = record?.get("view.name") as? String
        if (!event.setName("checkout.replay") || !event.setSnapshots("not JSON")) {
            return@setEventInterceptor event
        }
    }
    event
}
""")
    check("JavaActionConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ActionEvent;
final class JavaActionConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ActionEvent) {
                ActionEvent action = (ActionEvent) event;
                String name = action.getName(), snapshots = action.getSnapshots();
                Object type = action.getAttribute("action.type");
                String source = context.getSource();
                boolean changed = action.setName("checkout.confirmed") && action.setSnapshots("plain text");
                if (!changed) return null;
            }
            return event;
        });
    }
}
""")
    check("KotlinActionConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ActionEvent
fun registerAction(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ActionEvent) {
        val name: String? = event.name
        val snapshots: String? = event.snapshots
        val type: Any? = event.getAttribute("action.type")
        val source: String = context.source
        if (!event.setName("checkout.confirmed") || !event.setSnapshots("plain text")) {
            return@setEventInterceptor null
        }
    }
    event
}
""")
    check("JavaLongTaskConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.LongTaskEvent;
final class JavaLongTaskConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof LongTaskEvent && "longtask.watchdog".equals(context.getSource())) {
                LongTaskEvent longTask = (LongTaskEvent) event;
                String message = longTask.getMessage(), snapshots = longTask.getSnapshots();
                Object stack = longTask.getAttribute("longtask.stack");
                Object diagnostic = context.getNativeObject("longtaskInfo", Object.class);
                Object[] threadBeans = context.getNativeObject("threadBeans", Object[].class);
                if (!longTask.setMessage("") || !longTask.setSnapshots("not JSON")) return null;
            }
            return event;
        });
    }
}
""")
    check("KotlinLongTaskConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.LongTaskEvent
fun registerLongTask(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is LongTaskEvent && context.source == "longtask.watchdog") {
        val message: String? = event.message
        val snapshots: String? = event.snapshots
        val stack: Any? = event.getAttribute("longtask.stack")
        val diagnostic: Any? = context.getNativeObject("longtaskInfo", Any::class.java)
        val threadBeans: Array<Any>? = context.getNativeObject("threadBeans", Array<Any>::class.java)
        if (!event.setMessage(" ") || !event.setSnapshots("not JSON")) return@setEventInterceptor null
    }
    event
}
""")
    check("JavaApplicationConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ApplicationEvent;
import java.util.Map;
final class JavaApplicationConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ApplicationEvent) {
                ApplicationEvent application = (ApplicationEvent) event;
                String snapshots = application.getSnapshots();
                Object state = application.getAttribute("application.state");
                Object duration = application.getAttribute("application.duration");
                String source = context.getSource();
                Map<?, ?> parameters = context.getNativeObject("originalParameters", Map.class);
                if (!application.setSnapshots("plain text")) return null;
            }
            return event;
        });
    }
}
""")
    check("KotlinApplicationConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ApplicationEvent
fun registerApplication(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ApplicationEvent) {
        val snapshots: String? = event.snapshots
        val state: Any? = event.getAttribute("application.state")
        val duration: Any? = event.getAttribute("application.duration")
        val source: String = context.source
        val parameters: Map<*, *>? = context.getNativeObject("originalParameters", Map::class.java)
        if (!event.setSnapshots("plain text")) return@setEventInterceptor null
    }
    event
}
""")
    check("JavaExceptionConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ExceptionEvent;
import java.util.Map;
final class JavaExceptionConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ExceptionEvent) {
                ExceptionEvent exception = (ExceptionEvent) event;
                String name = exception.getName(), message = exception.getMessage();
                String causedBy = exception.getCausedBy(), snapshots = exception.getSnapshots();
                Object stack = exception.getAttribute("exception.stack");
                String source = context.getSource();
                Throwable original = context.getNativeObject("exception", Throwable.class);
                Map<?, ?> parameters = context.getNativeObject("originalParameters", Map.class);
                if (!exception.setName("") || !exception.setMessage(" ")
                    || !exception.setCausedBy("business reason") || !exception.setSnapshots("not JSON")) return null;
            }
            return event;
        });
    }
}
""")
    check("KotlinExceptionConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ExceptionEvent
fun registerException(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ExceptionEvent) {
        val name: String? = event.name
        val message: String? = event.message
        val causedBy: String? = event.causedBy
        val snapshots: String? = event.snapshots
        val stack: Any? = event.getAttribute("exception.stack")
        val source: String = context.source
        val original: Throwable? = context.getNativeObject("exception", Throwable::class.java)
        val parameters: Map<*, *>? = context.getNativeObject("originalParameters", Map::class.java)
        if (!event.setName("") || !event.setMessage(" ")
            || !event.setCausedBy("business reason") || !event.setSnapshots("not JSON")) {
            return@setEventInterceptor null
        }
    }
    event
}
""")
    check("JavaRecoveredExceptionConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ExceptionEvent;
import java.util.Map;
final class JavaRecoveredExceptionConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ExceptionEvent && "exception.java.recovered".equals(context.getSource())) {
                Map<?, ?> record = context.getNativeObject("recoveredRecord", Map.class);
                Throwable unavailable = context.getNativeObject("exception", Throwable.class);
                if (record != null && "checkout failed".equals(record.get("exceptionMessage"))) {
                    ExceptionEvent exception = (ExceptionEvent) event;
                    if (!exception.setName("checkout.failure") || !exception.setMessage("payment failed")
                        || !exception.setCausedBy("checkout validation") || !exception.setSnapshots("not JSON")) {
                        return null;
                    }
                }
            }
            return event;
        });
    }
}
""")
    check("KotlinRecoveredExceptionConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ExceptionEvent
fun registerRecoveredException(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ExceptionEvent && context.source == "exception.java.recovered") {
        val record: Map<*, *>? = context.getNativeObject("recoveredRecord", Map::class.java)
        val unavailable: Throwable? = context.getNativeObject("exception", Throwable::class.java)
        if (record?.get("exceptionMessage") == "checkout failed") {
            if (!event.setName("checkout.failure") || !event.setMessage("payment failed")
                || !event.setCausedBy("checkout validation") || !event.setSnapshots("not JSON")) {
                return@setEventInterceptor null
            }
        }
    }
    event
}
""")
    check("JavaNativeCrashRecoveryConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ExceptionEvent;
import java.util.List;
import java.util.Map;
final class JavaNativeCrashRecoveryConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ExceptionEvent && "exception.native.recovered".equals(context.getSource())) {
                Map<?, ?> record = context.getNativeObject("recoveredRecord", Map.class);
                Throwable unavailable = context.getNativeObject("exception", Throwable.class);
                if (record != null && "SIGSEGV / SEGV_MAPERR".equals(record.get("crashReason"))) {
                    Long address = (Long) record.get("crashAddress");
                    Integer requestingThread = (Integer) record.get("requestingThread");
                    Long capturedSeconds = (Long) record.get("timeDateStamp");
                    Map<?, ?> images = (Map<?, ?>) record.get("binaryImages");
                    List<?> frames = (List<?>) record.get("stackTraces");
                    ExceptionEvent exception = (ExceptionEvent) event;
                    if (!exception.setName("checkout.native") || !exception.setMessage("payment failed")
                        || !exception.setCausedBy("checkout validation") || !exception.setSnapshots("not JSON")) {
                        return null;
                    }
                }
            }
            return event;
        });
    }
}
""")
    check("KotlinNativeCrashRecoveryConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ExceptionEvent
fun registerNativeCrashRecovery(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ExceptionEvent && context.source == "exception.native.recovered") {
        val record: Map<*, *>? = context.getNativeObject("recoveredRecord", Map::class.java)
        val unavailable: Throwable? = context.getNativeObject("exception", Throwable::class.java)
        if (record?.get("crashReason") == "SIGSEGV / SEGV_MAPERR") {
            val address: Long? = record["crashAddress"] as? Long
            val requestingThread: Int? = record["requestingThread"] as? Int
            val capturedSeconds: Long? = record["timeDateStamp"] as? Long
            val images: Map<*, *>? = record["binaryImages"] as? Map<*, *>
            val frames: List<*>? = record["stackTraces"] as? List<*>
            if (!event.setName("checkout.native") || !event.setMessage("payment failed")
                || !event.setCausedBy("checkout validation") || !event.setSnapshots("not JSON")) {
                return@setEventInterceptor null
            }
        }
    }
    event
}
""")
    check("JavaANRRecoveryConsumer", "java", """
import java.util.Map;

import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ExceptionEvent;
final class JavaANRRecoveryConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ExceptionEvent && "exception.anr.recovered".equals(context.getSource())) {
                Map<?, ?> record = context.getNativeObject("recoveredRecord", Map.class);
                Throwable unavailable = context.getNativeObject("exception", Throwable.class);
                if (record != null && "ApplicationNotResponding".equals(record.get("name"))) {
                    String threadDump = (String) record.get("threadDump");
                    Long timestamp = (Long) record.get("timestamp");
                    Boolean viewSampled = (Boolean) record.get("viewSampled");
                    Map<?, ?> originalContext = (Map<?, ?>) record.get("eventContext");
                    ExceptionEvent exception = (ExceptionEvent) event;
                    if (!exception.setName("checkout.anr") || !exception.setMessage("checkout timeout")
                        || !exception.setCausedBy("checkout blocked") || !exception.setSnapshots("not JSON")) {
                        return null;
                    }
                }
            }
            return event;
        });
    }
}
""")
    check("KotlinANRRecoveryConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ExceptionEvent
fun registerANRRecovery(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ExceptionEvent && context.source == "exception.anr.recovered") {
        val record: Map<*, *>? = context.getNativeObject("recoveredRecord", Map::class.java)
        val unavailable: Throwable? = context.getNativeObject("exception", Throwable::class.java)
        if (record?.get("name") == "ApplicationNotResponding") {
            val threadDump: String? = record["threadDump"] as? String
            val timestamp: Long? = record["timestamp"] as? Long
            val viewSampled: Boolean? = record["viewSampled"] as? Boolean
            val originalContext: Map<*, *>? = record["eventContext"] as? Map<*, *>
            if (!event.setName("checkout.anr") || !event.setMessage("checkout timeout")
                || !event.setCausedBy("checkout blocked") || !event.setSnapshots("not JSON")) {
                return@setEventInterceptor null
            }
        }
    }
    event
}
""")
    check("JavaURLConnectionConsumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.ResourceEvent;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import javax.net.ssl.HttpsURLConnection;
final class JavaURLConnectionConsumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ResourceEvent && "resource.urlconnection".equals(context.getSource())) {
                HttpURLConnection connection = context.getNativeObject("connection", HttpURLConnection.class);
                HttpsURLConnection secureConnection = context.getNativeObject("connection", HttpsURLConnection.class);
                InputStream input = context.getNativeObject("inputStream", InputStream.class);
                OutputStream output = context.getNativeObject("outputStream", OutputStream.class);
                Throwable error = context.getNativeObject("error", Throwable.class);
                if (!((ResourceEvent) event).setName("/orders/:id")) return null;
            }
            return event;
        });
    }
}
""")
    check("KotlinURLConnectionConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ResourceEvent
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import javax.net.ssl.HttpsURLConnection
fun registerURLConnection(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ResourceEvent && context.source == "resource.urlconnection") {
        val connection: HttpURLConnection? = context.getNativeObject("connection", HttpURLConnection::class.java)
        val secureConnection: HttpsURLConnection? = context.getNativeObject("connection", HttpsURLConnection::class.java)
        val input: InputStream? = context.getNativeObject("inputStream", InputStream::class.java)
        val output: OutputStream? = context.getNativeObject("outputStream", OutputStream::class.java)
        val error: Throwable? = context.getNativeObject("error", Throwable::class.java)
        if (!event.setName("/orders/:id")) return@setEventInterceptor null
    }
    event
}
""")
    if args.okhttp2:
        check("JavaOkHttp2Consumer", "java", """
import com.alibabacloud.rum.AlibabaCloudRum;
import com.alibabacloud.rum.event.*;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;
final class JavaOkHttp2Consumer {
    boolean register() {
        return AlibabaCloudRum.setEventInterceptor((event, context) -> {
            if (event instanceof ResourceEvent && "resource.okhttp2".equals(context.getSource())) {
                Request request = context.getNativeObject("request", Request.class);
                Response response = context.getNativeObject("response", Response.class);
                IOException error = context.getNativeObject("error", IOException.class);
                if (request != null && response != null && error == null
                    && "1001".equals(response.header("X-Test-Business-Code"))) {
                    ((ResourceEvent) event).setResourceResult(1001L, ResourceSuccess.FAILED);
                }
            }
            return event;
        });
    }
}
""")
        check("KotlinOkHttp2Consumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.ResourceEvent
import com.alibabacloud.rum.event.ResourceSuccess
import com.squareup.okhttp.Request
import com.squareup.okhttp.Response
import java.io.IOException
fun registerOkHttp2(): Boolean = AlibabaCloudRum.setEventInterceptor { event, context ->
    if (event is ResourceEvent && context.source == "resource.okhttp2") {
        val request: Request? = context.getNativeObject("request", Request::class.java)
        val response: Response? = context.getNativeObject("response", Response::class.java)
        val error: IOException? = context.getNativeObject("error", IOException::class.java)
        if (request != null && response != null && error == null
            && response.header("X-Test-Business-Code") == "1001") {
            event.setResourceResult(1001L, ResourceSuccess.FAILED)
        }
    }
    event
}
""")
    java_prefix = "import com.alibabacloud.rum.event.*;\n"
    negative_java = {
        "NoConstruct": "class NoConstruct { Object create() { return new CustomEvent(null); } }",
        "NoSubclass": "class NoSubclass extends RumEvent { NoSubclass() { super(null); } }",
        "NoGenericWrite": "class NoGenericWrite { void edit(RumEvent e) { e.setAttribute(\"session.id\", \"x\"); } }",
        "NoRemove": "class NoRemove { void edit(RumEvent e) { e.removeAttribute(\"custom.name\"); } }",
        "NoClone": "class NoClone { Object copy(CustomEvent e) { return e.clone(); } }",
        "NoInternalAccessor": "class NoInternalAccessor { Object raw(RumEvent e) { return e.getCandidate(); } }",
        "NoResourceConstruct": "class NoResourceConstruct { Object create() { return new ResourceEvent(null); } }",
        "NoResourceSubclass": "class NoResourceSubclass extends ResourceEvent {"
                              " NoResourceSubclass() { super(null); } }",
        "NoResourceClone": "class NoResourceClone { Object copy(ResourceEvent e) { return e.clone(); } }",
        "NoStatusCodeSetter": "class NoStatusCodeSetter { void edit(ResourceEvent e) { e.setStatusCode(1001L); } }",
        "NoSuccessSetter": "class NoSuccessSetter { void edit(ResourceEvent e) {"
                           " e.setSuccess(ResourceSuccess.FAILED); } }",
        "NoUrlSetter": "class NoUrlSetter { void edit(ResourceEvent e) { e.setUrl(\"https://example.invalid\"); } }",
        "NoBaseResourceResult": "class NoBaseResourceResult { void edit(RumEvent e) {"
                                " e.setResourceResult(1001L, ResourceSuccess.FAILED); } }",
        "NoCustomResourceResult": "class NoCustomResourceResult { void edit(CustomEvent e) {"
                                  " e.setResourceResult(1001L, ResourceSuccess.FAILED); } }",
        "NoResourceGroup": "class NoResourceGroup { void edit(ResourceEvent e) { e.setGroup(\"x\"); } }",
        "NoViewConstruct": "class NoViewConstruct { Object create() { return new ViewEvent(null); } }",
        "NoViewSubclass": "class NoViewSubclass extends ViewEvent { NoViewSubclass() { super(null); } }",
        "NoViewClone": "class NoViewClone { Object copy(ViewEvent e) { return e.clone(); } }",
        "NoViewIdSetter": "class NoViewIdSetter { void edit(ViewEvent e) { e.setId(\"x\"); } }",
        "NoViewResourceResult": "class NoViewResourceResult { void edit(ViewEvent e) {"
                                " e.setResourceResult(1001L, ResourceSuccess.FAILED); } }",
        "NoViewMessageSetter": "class NoViewMessageSetter { void edit(ViewEvent e) { e.setMessage(\"x\"); } }",
        "NoViewGenericWrite": "class NoViewGenericWrite { void edit(ViewEvent e) {"
                              " e.setAttribute(\"view.id\", \"x\"); } }",
        "NoViewRemove": "class NoViewRemove { void edit(ViewEvent e) { e.removeAttribute(\"view.name\"); } }",
        "NoViewClear": "class NoViewClear { void edit(ViewEvent e) { e.clearSnapshots(); } }",
        "NoViewSpanAccessor": "class NoViewSpanAccessor { Object raw(ViewEvent e) { return e.getSpan(); } }",
        "NoReplayTypeSetter": "class NoReplayTypeSetter { void edit(ViewEvent e) { e.setType(\"view\"); } }",
        "NoReplayRecordsCountSetter": "class NoReplayRecordsCountSetter {"
                                     " void edit(ViewEvent e) { e.setRecordsCount(1); } }",
        "NoReplayRecordsEndSetter": "class NoReplayRecordsEndSetter {"
                                   " void edit(ViewEvent e) { e.setRecordsEnd(123L); } }",
        "NoReplayFullSnapshotSetter": "class NoReplayFullSnapshotSetter {"
                                     " void edit(ViewEvent e) { e.setHasFullSnapshot(0); } }",
        "NoReplaySeparateEvent": "class NoReplaySeparateEvent { ReplayEvent value; }",
        "NoReplayRecordSetter": "class NoReplayRecordSetter { void edit(EventContext c) {"
                                " c.setNativeObject(\"recoveredRecord\", null); } }",
        "NoActionConstruct": "class NoActionConstruct { Object create() { return new ActionEvent(null); } }",
        "NoActionSubclass": "class NoActionSubclass extends ActionEvent { NoActionSubclass() { super(null); } }",
        "NoActionClone": "class NoActionClone { Object copy(ActionEvent e) { return e.clone(); } }",
        "NoActionIdSetter": "class NoActionIdSetter { void edit(ActionEvent e) { e.setId(\"x\"); } }",
        "NoActionTypeSetter": "class NoActionTypeSetter { void edit(ActionEvent e) { e.setType(\"x\"); } }",
        "NoActionDurationSetter": "class NoActionDurationSetter { void edit(ActionEvent e) { e.setDuration(1L); } }",
        "NoActionMessageSetter": "class NoActionMessageSetter { void edit(ActionEvent e) { e.setMessage(\"x\"); } }",
        "NoActionGroupSetter": "class NoActionGroupSetter { void edit(ActionEvent e) { e.setGroup(\"x\"); } }",
        "NoActionResourceResult": "class NoActionResourceResult { void edit(ActionEvent e) {"
                                  " e.setResourceResult(1001L, ResourceSuccess.FAILED); } }",
        "NoActionGenericWrite": "class NoActionGenericWrite { void edit(ActionEvent e) {"
                                " e.setAttribute(\"action.type\", \"x\"); } }",
        "NoActionRemove": "class NoActionRemove { void edit(ActionEvent e) { e.removeAttribute(\"action.name\"); } }",
        "NoActionClear": "class NoActionClear { void edit(ActionEvent e) { e.clearSnapshots(); } }",
        "NoActionSpanAccessor": "class NoActionSpanAccessor { Object raw(ActionEvent e) { return e.getSpan(); } }",
        "NoApplicationConstruct": "class NoApplicationConstruct {"
                                  " Object create() { return new ApplicationEvent(null); } }",
        "NoApplicationSubclass": "class NoApplicationSubclass extends ApplicationEvent {"
                                 " NoApplicationSubclass() { super(null); } }",
        "NoApplicationClone": "class NoApplicationClone {"
                              " Object copy(ApplicationEvent e) { return e.clone(); } }",
        "NoApplicationStateSetter": "class NoApplicationStateSetter {"
                                    " void edit(ApplicationEvent e) { e.setState(\"x\"); } }",
        "NoApplicationDurationSetter": "class NoApplicationDurationSetter {"
                                       " void edit(ApplicationEvent e) { e.setDuration(1L); } }",
        "NoApplicationNameSetter": "class NoApplicationNameSetter {"
                                   " void edit(ApplicationEvent e) { e.setName(\"x\"); } }",
        "NoApplicationMessageSetter": "class NoApplicationMessageSetter {"
                                      " void edit(ApplicationEvent e) { e.setMessage(\"x\"); } }",
        "NoApplicationGroupSetter": "class NoApplicationGroupSetter {"
                                    " void edit(ApplicationEvent e) { e.setGroup(\"x\"); } }",
        "NoApplicationResourceResult": "class NoApplicationResourceResult { void edit(ApplicationEvent e) {"
                                       " e.setResourceResult(1001L, ResourceSuccess.FAILED); } }",
        "NoApplicationGenericWrite": "class NoApplicationGenericWrite { void edit(ApplicationEvent e) {"
                                     " e.setAttribute(\"application.state\", \"x\"); } }",
        "NoApplicationRemove": "class NoApplicationRemove {"
                               " void edit(ApplicationEvent e) { e.removeAttribute(\"application.snapshots\"); } }",
        "NoApplicationClear": "class NoApplicationClear {"
                              " void edit(ApplicationEvent e) { e.clearSnapshots(); } }",
        "NoApplicationSpanAccessor": "class NoApplicationSpanAccessor {"
                                     " Object raw(ApplicationEvent e) { return e.getSpan(); } }",
        "NoExceptionConstruct": "class NoExceptionConstruct { Object create() { return new ExceptionEvent(null); } }",
        "NoExceptionSubclass": "class NoExceptionSubclass extends ExceptionEvent {"
                               " NoExceptionSubclass() { super(null); } }",
        "NoExceptionClone": "class NoExceptionClone { Object copy(ExceptionEvent e) { return e.clone(); } }",
        "NoExceptionTypeSetter": "class NoExceptionTypeSetter { void edit(ExceptionEvent e) { e.setType(\"x\"); } }",
        "NoExceptionStackSetter": "class NoExceptionStackSetter { void edit(ExceptionEvent e) { e.setStack(\"x\"); } }",
        "NoExceptionBinaryImagesSetter": "class NoExceptionBinaryImagesSetter {"
                                         " void edit(ExceptionEvent e) { e.setBinaryImages(\"x\"); } }",
        "NoExceptionResourceResult": "class NoExceptionResourceResult { void edit(ExceptionEvent e) {"
                                     " e.setResourceResult(1001L, ResourceSuccess.FAILED); } }",
        "NoExceptionGenericWrite": "class NoExceptionGenericWrite { void edit(ExceptionEvent e) {"
                                   " e.setAttribute(\"thread.id\", 1L); } }",
        "NoExceptionRemove": "class NoExceptionRemove {"
                             " void edit(ExceptionEvent e) { e.removeAttribute(\"exception.message\"); } }",
        "NoExceptionClear": "class NoExceptionClear { void edit(ExceptionEvent e) { e.clearSnapshots(); } }",
        "NoExceptionSpanAccessor": "class NoExceptionSpanAccessor {"
                                   " Object raw(ExceptionEvent e) { return e.getSpan(); } }",
        "NoMismatchedExceptionType": "class NoMismatchedExceptionType { void read(EventContext c) {"
                                     " java.io.IOException value ="
                                     " c.getNativeObject(\"exception\", Throwable.class); } }",
        "NoMismatchedRecoveredRecordType": "class NoMismatchedRecoveredRecordType { void read(EventContext c) {"
                                          " Throwable value = c.getNativeObject(\"recoveredRecord\", java.util.Map.class);"
                                          " } }",
        "NoLongTaskConstruct": "class NoLongTaskConstruct { Object create() { return new LongTaskEvent(null); } }",
        "NoLongTaskSubclass": "class NoLongTaskSubclass extends LongTaskEvent {"
                              " NoLongTaskSubclass() { super(null); } }",
        "NoLongTaskClone": "class NoLongTaskClone { Object copy(LongTaskEvent e) { return e.clone(); } }",
        "NoLongTaskNameSetter": "class NoLongTaskNameSetter { void edit(LongTaskEvent e) { e.setName(\"x\"); } }",
        "NoLongTaskIdSetter": "class NoLongTaskIdSetter { void edit(LongTaskEvent e) { e.setId(\"x\"); } }",
        "NoLongTaskTypeSetter": "class NoLongTaskTypeSetter { void edit(LongTaskEvent e) { e.setType(\"x\"); } }",
        "NoLongTaskDurationSetter": "class NoLongTaskDurationSetter {"
                                    " void edit(LongTaskEvent e) { e.setDuration(1L); } }",
        "NoLongTaskStackSetter": "class NoLongTaskStackSetter {"
                                 " void edit(LongTaskEvent e) { e.setStack(\"x\"); } }",
        "NoLongTaskGenericWrite": "class NoLongTaskGenericWrite { void edit(LongTaskEvent e) {"
                                  " e.setAttribute(\"longtask.source\", \"x\"); } }",
        "NoLongTaskRemove": "class NoLongTaskRemove {"
                            " void edit(LongTaskEvent e) { e.removeAttribute(\"longtask.message\"); } }",
        "NoLongTaskClear": "class NoLongTaskClear { void edit(LongTaskEvent e) { e.clearSnapshots(); } }",
        "NoLongTaskSpanAccessor": "class NoLongTaskSpanAccessor {"
                                  " Object raw(LongTaskEvent e) { return e.getSpan(); } }",
        "NoLongTaskDiagnosticSetter": "class NoLongTaskDiagnosticSetter { void edit(EventContext c) {"
                                      " c.setNativeObject(\"longtaskInfo\", new Object()); } }",
        "NoBaseNameSetter": "class NoBaseNameSetter { void edit(RumEvent e) { e.setName(\"x\"); } }",
        "NoBaseSnapshotsSetter": "class NoBaseSnapshotsSetter { void edit(RumEvent e) { e.setSnapshots(\"x\"); } }",
        "NoContextConstruct": "class NoContextConstruct { Object create() { return new EventContext(null); } }",
        "NoContextSubclass": "class NoContextSubclass extends EventContext {"
                             " NoContextSubclass() { super(null); } }",
        "NoContextSourceSetter": "class NoContextSourceSetter { void edit(EventContext c) {"
                                 " c.setSource(\"resource.urlconnection\"); } }",
        "NoNativeObjectSetter": "class NoNativeObjectSetter { void edit(EventContext c) {"
                                " c.setNativeObject(\"connection\", new Object()); } }",
        "NoMismatchedNativeType": "class NoMismatchedNativeType { void read(EventContext c) {"
                                  " java.net.HttpURLConnection value ="
                                  " c.getNativeObject(\"connection\", java.io.InputStream.class); } }",
    }
    for name, source in negative_java.items():
        check(name, "java", java_prefix + source, False)
    check("NoNativeCrashRecordMapAssignment", "java",
          "import com.alibabacloud.rum.capture.nativecrash.NativeCrashInfo;\n"
          "import com.alibabacloud.rum.event.EventContext;\n"
          "class NoNativeCrashRecordMapAssignment { NativeCrashInfo value(EventContext c) {"
          " return c.getNativeObject(\"recoveredRecord\", java.util.Map.class); } }", False)
    check("KotlinNoConstruct", "kotlin",
          "import com.alibabacloud.rum.event.CustomEvent\nfun create() = CustomEvent(null)", False)
    check("KotlinNullableGetter", "kotlin",
          "import com.alibabacloud.rum.event.CustomEvent\nfun name(e: CustomEvent): String = e.name", False)
    check("KotlinNoGenericWrite", "kotlin",
          "import com.alibabacloud.rum.event.RumEvent\n"
          "fun edit(e: RumEvent) { e.setAttribute(\"session.id\", \"x\") }", False)
    check("KotlinBooleanSetter", "kotlin",
          "import com.alibabacloud.rum.event.CustomEvent\nfun edit(e: CustomEvent) { e.name = \"x\" }", False)
    check("KotlinNoResourceConstruct", "kotlin",
          "import com.alibabacloud.rum.event.ResourceEvent\nfun create() = ResourceEvent(null)", False)
    for suffix, expected_type, getter in (("Name", "String", "name"), ("Message", "String", "message"),
                                           ("Snapshots", "String", "snapshots"),
                                           ("StatusCode", "Long", "statusCode"), ("Success", "Int", "success")):
        check("KotlinResourceNullable" + suffix, "kotlin",
              "import com.alibabacloud.rum.event.ResourceEvent\n"
              f"fun value(e: ResourceEvent): {expected_type} = e.{getter}", False)
    check("KotlinResourceBooleanSetter", "kotlin",
          "import com.alibabacloud.rum.event.ResourceEvent\nfun edit(e: ResourceEvent) { e.message = \"x\" }", False)
    check("KotlinResourceNoResultAssignment", "kotlin",
          "import com.alibabacloud.rum.event.ResourceEvent\nfun edit(e: ResourceEvent) { e.statusCode = 1001L }", False)
    check("KotlinNoViewConstruct", "kotlin",
          "import com.alibabacloud.rum.event.ViewEvent\nfun create() = ViewEvent(null)", False)
    for getter in ("name", "snapshots"):
        check("KotlinViewNullable" + getter.title(), "kotlin",
              "import com.alibabacloud.rum.event.ViewEvent\n"
              f"fun value(e: ViewEvent): String = e.{getter}", False)
        check("KotlinViewBoolean" + getter.title() + "Setter", "kotlin",
              "import com.alibabacloud.rum.event.ViewEvent\n"
              f"fun edit(e: ViewEvent) {{ e.{getter} = \"x\" }}", False)
    check("KotlinNoContextConstruct", "kotlin",
          "import com.alibabacloud.rum.event.EventContext\nfun create() = EventContext(null)", False)
    check("KotlinNoReplayTypeSetter", "kotlin",
          "import com.alibabacloud.rum.event.ViewEvent\n"
          "fun edit(e: ViewEvent) { e.setType(\"view\") }", False)
    check("KotlinNoReplayRecordsCountSetter", "kotlin",
          "import com.alibabacloud.rum.event.ViewEvent\n"
          "fun edit(e: ViewEvent) { e.setRecordsCount(1) }", False)
    check("KotlinNoReplaySeparateEvent", "kotlin",
          "import com.alibabacloud.rum.event.ReplayEvent\nfun value(e: ReplayEvent) = e", False)
    check("KotlinNoActionConstruct", "kotlin",
          "import com.alibabacloud.rum.event.ActionEvent\nfun create() = ActionEvent(null)", False)
    for getter in ("name", "snapshots"):
        check("KotlinActionNullable" + getter.title(), "kotlin",
              "import com.alibabacloud.rum.event.ActionEvent\n"
              f"fun value(e: ActionEvent): String = e.{getter}", False)
        check("KotlinActionBoolean" + getter.title() + "Setter", "kotlin",
              "import com.alibabacloud.rum.event.ActionEvent\n"
              f"fun edit(e: ActionEvent) {{ e.{getter} = \"x\" }}", False)
    check("KotlinNoApplicationConstruct", "kotlin",
          "import com.alibabacloud.rum.event.ApplicationEvent\nfun create() = ApplicationEvent(null)", False)
    check("KotlinApplicationNullableSnapshots", "kotlin",
          "import com.alibabacloud.rum.event.ApplicationEvent\n"
          "fun value(e: ApplicationEvent): String = e.snapshots", False)
    check("KotlinApplicationBooleanSnapshotsSetter", "kotlin",
          "import com.alibabacloud.rum.event.ApplicationEvent\n"
          "fun edit(e: ApplicationEvent) { e.snapshots = \"x\" }", False)
    check("KotlinNoExceptionConstruct", "kotlin",
          "import com.alibabacloud.rum.event.ExceptionEvent\nfun create() = ExceptionEvent(null)", False)
    for getter in ("name", "message", "causedBy", "snapshots"):
        suffix = getter[0].upper() + getter[1:]
        check("KotlinExceptionNullable" + suffix, "kotlin",
              "import com.alibabacloud.rum.event.ExceptionEvent\n"
              f"fun value(e: ExceptionEvent): String = e.{getter}", False)
        check("KotlinExceptionBoolean" + suffix + "Setter", "kotlin",
              "import com.alibabacloud.rum.event.ExceptionEvent\n"
              f"fun edit(e: ExceptionEvent) {{ e.{getter} = \"x\" }}", False)
    check("KotlinNoLongTaskConstruct", "kotlin",
          "import com.alibabacloud.rum.event.LongTaskEvent\nfun create() = LongTaskEvent(null)", False)
    for getter in ("message", "snapshots"):
        suffix = getter[0].upper() + getter[1:]
        check("KotlinLongTaskNullable" + suffix, "kotlin",
              "import com.alibabacloud.rum.event.LongTaskEvent\n"
              f"fun value(e: LongTaskEvent): String = e.{getter}", False)
        check("KotlinLongTaskBoolean" + suffix + "Setter", "kotlin",
              "import com.alibabacloud.rum.event.LongTaskEvent\n"
              f"fun edit(e: LongTaskEvent) {{ e.{getter} = \"x\" }}", False)
    check("KotlinLongTaskNullableDiagnostic", "kotlin",
          "import com.alibabacloud.rum.event.EventContext\n"
          "fun value(c: EventContext): Any = c.getNativeObject(\"longtaskInfo\", Any::class.java)", False)
    check("KotlinExceptionNullableObject", "kotlin",
          "import com.alibabacloud.rum.event.EventContext\n"
          "fun value(c: EventContext): Throwable = c.getNativeObject(\"exception\", Throwable::class.java)", False)
    check("KotlinRecoveredRecordNullable", "kotlin",
          "import com.alibabacloud.rum.event.EventContext\n"
          "fun value(c: EventContext): Map<*, *> = c.getNativeObject(\"recoveredRecord\", Map::class.java)", False)
    check("KotlinRecoveredRecordTypeMismatch", "kotlin",
          "import com.alibabacloud.rum.event.EventContext\n"
          "fun value(c: EventContext): Throwable? = c.getNativeObject(\"recoveredRecord\", Map::class.java)", False)
    check("KotlinNoNativeCrashRecordMapAssignment", "kotlin",
          "import com.alibabacloud.rum.capture.nativecrash.NativeCrashInfo\n"
          "import com.alibabacloud.rum.event.EventContext\n"
          "fun value(c: EventContext): NativeCrashInfo? = c.getNativeObject(\"recoveredRecord\", Map::class.java)", False)
    check("KotlinNoContextSourceAssignment", "kotlin",
          "import com.alibabacloud.rum.event.EventContext\n"
          "fun edit(c: EventContext) { c.source = \"resource.urlconnection\" }", False)
    for suffix, native_type, slot in (("Connection", "java.net.HttpURLConnection", "connection"),
                                      ("Input", "java.io.InputStream", "inputStream"),
                                      ("Output", "java.io.OutputStream", "outputStream"),
                                      ("Error", "Throwable", "error")):
        check("KotlinNativeNullable" + suffix, "kotlin",
              "import com.alibabacloud.rum.event.EventContext\n"
              f"fun value(c: EventContext): {native_type} = c.getNativeObject(\"{slot}\", {native_type}::class.java)",
              False)

    for name in ("RumEvent", "CustomEvent", "ResourceEvent", "ResourceSuccess", "ViewEvent", "ActionEvent",
                 "ApplicationEvent", "ExceptionEvent", "LongTaskEvent",
                 "EventContext", "EventInterceptor"):
        result = subprocess.run(["javap", "-public", "-classpath", classpath,
                                 "com.alibabacloud.rum.event." + name], capture_output=True, text=True, check=True)
        (output / f"{name}.api.txt").write_text(result.stdout)
        if any(prefix in result.stdout for prefix in ("com.alibabacloud.rum.core.",
                                                       "com.alibabacloud.rum.otel.",
                                                       "com.alibabacloud.rum.capture.")):
            raise RuntimeError(f"Internal type leaked in public {name} signature")
        if f"public com.alibabacloud.rum.event.{name}(" in result.stdout:
            raise RuntimeError(f"Public constructor leaked on {name}")
    (output / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps({"passed": len(evidence["checks"]), "sha256": evidence["sha256"], "evidence": str(output)}))


if __name__ == "__main__":
    main()
