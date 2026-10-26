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
                 "ApplicationEvent",
                 "EventContext", "EventInterceptor"):
        result = subprocess.run(["javap", "-public", "-classpath", classpath,
                                 "com.alibabacloud.rum.event." + name], capture_output=True, text=True, check=True)
        (output / f"{name}.api.txt").write_text(result.stdout)
        if "com.alibabacloud.rum.core." in result.stdout or "com.alibabacloud.rum.otel." in result.stdout:
            raise RuntimeError(f"Internal type leaked in public {name} signature")
        if f"public com.alibabacloud.rum.event.{name}(" in result.stdout:
            raise RuntimeError(f"Public constructor leaked on {name}")
    (output / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps({"passed": len(evidence["checks"]), "sha256": evidence["sha256"], "evidence": str(output)}))


if __name__ == "__main__":
    main()
