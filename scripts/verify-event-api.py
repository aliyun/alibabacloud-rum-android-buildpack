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
    jars = []
    with zipfile.ZipFile(args.aar) as archive:
        for name in archive.namelist():
            if name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                destination = output / Path(name).name
                destination.write_bytes(archive.read(name))
                jars.append(destination)
    if not jars:
        raise RuntimeError("AAR contains no Java classes")
    classpath = os.pathsep.join(map(str, jars + [annotation, stdlib]))
    compiler_classpath = os.pathsep.join(map(str, [compiler, stdlib, runtime, reflect, trove, annotations]))
    evidence = {"aar": str(args.aar.resolve()), "sha256": hashlib.sha256(args.aar.read_bytes()).hexdigest(),
                "kotlin_version": args.kotlin_version, "checks": []}

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
            return event;
        });
        return registered && AlibabaCloudRum.setEventInterceptor(null);
    }
}
""")
    check("KotlinConsumer", "kotlin", """
import com.alibabacloud.rum.AlibabaCloudRum
import com.alibabacloud.rum.event.CustomEvent
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
    event
}
fun clear(): Boolean = AlibabaCloudRum.setEventInterceptor(null)
""")
    java_prefix = "import com.alibabacloud.rum.event.*;\n"
    negative_java = {
        "NoConstruct": "class NoConstruct { Object create() { return new CustomEvent(null); } }",
        "NoSubclass": "class NoSubclass extends RumEvent { NoSubclass() { super(null); } }",
        "NoGenericWrite": "class NoGenericWrite { void edit(RumEvent e) { e.setAttribute(\"session.id\", \"x\"); } }",
        "NoRemove": "class NoRemove { void edit(RumEvent e) { e.removeAttribute(\"custom.name\"); } }",
        "NoClone": "class NoClone { Object copy(CustomEvent e) { return e.clone(); } }",
        "NoInternalAccessor": "class NoInternalAccessor { Object raw(RumEvent e) { return e.getCandidate(); } }",
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

    for name in ("RumEvent", "CustomEvent", "EventContext", "EventInterceptor"):
        result = subprocess.run(["javap", "-public", "-classpath", classpath,
                                 "com.alibabacloud.rum.event." + name], capture_output=True, text=True, check=True)
        (output / f"{name}.api.txt").write_text(result.stdout)
        if "com.alibabacloud.rum.core." in result.stdout:
            raise RuntimeError(f"Internal type leaked in public {name} signature")
        if f"public com.alibabacloud.rum.event.{name}(" in result.stdout:
            raise RuntimeError(f"Public constructor leaked on {name}")
    (output / "result.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps({"passed": len(evidence["checks"]), "sha256": evidence["sha256"], "evidence": str(output)}))


if __name__ == "__main__":
    main()
