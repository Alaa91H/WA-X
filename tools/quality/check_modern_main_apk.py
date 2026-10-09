#!/usr/bin/env python3
"""Gate a same-package modern WA X APK. Never silently co-package legacy/modern loaders."""
import argparse
import re
import importlib.util
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
API = "102"
MODERN_ENTRY = b"Lcom/wax/module/modern/ModernXposedEntry;"
MANAGER_ENTRY = b"Lcom/wax/module/ModuleApplication;"


def verify_sources():
    build = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    required = (
        "val modernXposedPackage = true",
        'implementation(project(":modern-runtime"))',
        "compileOnly(libs.libxposed.modern.api)",
        "Legacy Xposed APK packaging was removed",
        "proguardFiles(file(\"proguard-modern-rules.pro\"))",
        "versionName = resolvedVersionName",
        'buildConfigField("boolean", "MODERN_XPOSED", modernXposedPackage.toString())',
        'excludes += "META-INF/LICENSE*"',
        'excludes += "META-INF/NOTICE*"',
    )
    for token in required:
        if token not in build:
            raise ValueError(f"Official API102 build contract missing: {token}")
    if "include(\":modern-canary\")" in settings or (ROOT / "modern-canary").exists():
        raise ValueError("The obsolete Canary module must not remain in the project")
    if "excludes += \"META-INF/**\"" in build:
        raise ValueError("Modern loader metadata must not be stripped")
    if (ROOT / "app/src/main/assets/xposed_init").exists():
        raise ValueError("The original package still contains the obsolete Legacy loader")
    for legacy in ("xposedmodule", "xposeddescription", "xposedminversion",
                   "xposedsharedprefs", "xposedscope"):
        if f'android:name="{legacy}"' in manifest:
            raise ValueError(f"Legacy manifest module metadata remains: {legacy}")
    metadata = ROOT / "app/src/main/resources/META-INF/xposed"
    for name in ("java_init.list", "module.prop", "scope.list"):
        if not (metadata / name).is_file():
            raise ValueError(f"Missing production API102 loader metadata: {name}")
    if (metadata / "java_init.list").read_text(encoding="utf-8").strip() != \
            "com.wax.module.modern.ModernXposedEntry":
        raise ValueError("Incorrect production modern entry point")
    props = (metadata / "module.prop").read_text(encoding="utf-8").splitlines()
    for required in ("minApiVersion=102", "targetApiVersion=102",
                     "staticScope=true", "autoHotReload=false"):
        if required not in props:
            raise ValueError(f"Invalid production API102 property: {required}")
    scope = (metadata / "scope.list").read_text(encoding="utf-8").splitlines()
    if scope != ["com.whatsapp", "com.whatsapp.w4b"]:
        raise ValueError("Invalid production API102 scope")
    if "com.wax.module" not in build or "com.wax.module" not in manifest:
        # The main manifest may use a relative application class, so the build
        # applicationId is authoritative; no Canary application is acceptable.
        if 'applicationId = "com.wax.module"' not in build:
            raise ValueError("WA X original application identity changed")
    print("Official WA X API102-only source and package contracts OK")


def verify_apk(path):
    with zipfile.ZipFile(path) as archive:
        files = archive.namelist()
        if len(files) != len(set(files)):
            raise ValueError("Duplicate APK entries")
        if "assets/xposed_init" in files:
            raise ValueError("Legacy loader present in production modern APK")
        if "assets/www/prism.js" not in files:
            raise ValueError("Production Manager lost original UI assets")
        for name in ("java_init.list", "module.prop", "scope.list"):
            if "META-INF/xposed/" + name not in files:
                raise ValueError(f"Missing API102 loader file: {name}")
        if archive.read("META-INF/xposed/java_init.list").decode().strip() != \
                "com.wax.module.modern.ModernXposedEntry":
            raise ValueError("Wrong modern entry point")
        props = archive.read("META-INF/xposed/module.prop").decode()
        for required in ("minApiVersion=102", "targetApiVersion=102", "staticScope=true"):
            if required not in props.splitlines():
                raise ValueError(f"Incorrect API102 manifest property: {required}")
        scope = archive.read("META-INF/xposed/scope.list").decode().splitlines()
        if scope != ["com.whatsapp", "com.whatsapp.w4b"]:
            raise ValueError("Unexpected modern injection scope")
        dex = [name for name in files if name.startswith("classes") and name.endswith(".dex")]
        if not dex:
            raise ValueError("APK has no DEX files")
        for desc in (MODERN_ENTRY, MANAGER_ENTRY):
            if not any(desc in archive.read(filename) for filename in dex):
                raise ValueError(f"Missing modern/runtime or Manager class in DEX: {desc!r}")
        binary = archive.read("AndroidManifest.xml")
        if b"xposedminversion" in binary or "xposedminversion".encode("utf-16le") in binary:
            raise ValueError("Legacy manifest metadata leaked into modern APK")
    print(f"Same-package modern loader APK verified: {path}")


def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--source-only", action="store_true")
    mode.add_argument("--apk", type=Path)
    args = parser.parse_args()
    try:
        if args.source_only:
            verify_sources()
        else:
            verify_apk(args.apk)
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile) as exc:
        print(f"Modern WA X main-APK check FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
