#!/usr/bin/env python3
"""Mutation tests for the API102 loader verifier. Every broken APK must be rejected."""
import importlib.util
import tempfile
import zipfile
from pathlib import Path

script = Path(__file__).with_name("check_modern_canary_apk.py")
spec = importlib.util.spec_from_file_location("canary_verifier", script)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

GOOD = {
    "AndroidManifest.xml": b"manifest",
    "classes.dex": b"dex\nLcom/wax/module/modern/ModernXposedEntry;\n",
    "META-INF/xposed/java_init.list": b"com.wax.module.modern.ModernXposedEntry\n",
    "META-INF/xposed/module.prop": (
        b"minApiVersion=102\ntargetApiVersion=102\n"
        b"staticScope=true\nautoHotReload=false\nexceptionMode=protective\n"
    ),
    "META-INF/xposed/scope.list": b"com.whatsapp\ncom.whatsapp.w4b\n",
}


def check_case(title, changes, should_pass):
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "module.apk"
        content = dict(GOOD)
        changes(content)
        with zipfile.ZipFile(path, "w") as out:
            for name, data in content.items():
                out.writestr(name, data)
        try:
            module.verify_apk(path)
            passed = True
        except (ValueError, OSError):
            passed = False
        if passed != should_pass:
            raise AssertionError(f"{title}: expected {'pass' if should_pass else 'fail'}")
        print(f"[pass] {title}")


def drop(key):
    return lambda mapping: mapping.pop(key)


check_case("complete modern APK", lambda m: None, True)
check_case("legacy loader must be absent", lambda m: m.update({"assets/xposed_init": b"legacy"}), False)
check_case("missing modern entry metadata", drop("META-INF/xposed/java_init.list"), False)
check_case("missing module config", drop("META-INF/xposed/module.prop"), False)
check_case("missing scope", drop("META-INF/xposed/scope.list"), False)
check_case("DEX missing modern entry", lambda m: m.update({"classes.dex": b"dex-no-entry"}), False)
check_case("wrong entry target", lambda m: m.update({"META-INF/xposed/java_init.list": b"wrong.Entry"}), False)
check_case("widened scope", lambda m: m.update({"META-INF/xposed/scope.list": b"com.whatsapp\nsystem\n"}), False)
check_case("hot reload accidentally enabled", lambda m: m.update({"META-INF/xposed/module.prop": GOOD["META-INF/xposed/module.prop"].replace(b"autoHotReload=false", b"autoHotReload=true")}), False)
check_case("API downgrade", lambda m: m.update({"META-INF/xposed/module.prop": GOOD["META-INF/xposed/module.prop"].replace(b"minApiVersion=102", b"minApiVersion=93")}), False)
print("Modern canary APK verifier self-tests passed.")
