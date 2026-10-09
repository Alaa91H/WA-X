#!/usr/bin/env python3
"""Gate a same-package modern WA X APK. Never silently co-package legacy/modern loaders."""
import argparse
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
    required = (
        'providers.gradleProperty("modernXposed").orNull == "true"',
        'implementation(project(":modern-runtime"))',
        'compileOnly(libs.libxposed.modern.api)',
        'MODERN_XPOSED',
        'if (!modernXposedPackage) excludes += "META-INF/**"',
        "prepareModernXposedSources",
        "outputAssets.copy",  # purposely checked below via alternative
    )
    for token in required[:-1]:
        if token not in build:
            raise ValueError(f"Modern production build contract missing: {token}")
    if 'sourceAssets.copyRecursively(outputAssets' not in build:
        raise ValueError("Modern APK must copy existing Manager assets without xposed_init")
    if 'file("$outputAssets/xposed_init").delete()' not in build:
        raise ValueError("Modern APK must remove the legacy Xposed entry file")
    if 'resources.srcDir("../modern-canary/src/main/resources")' not in build:
        raise ValueError("Same-package modern loader metadata not included")
    if not (ROOT / "app/src/main/assets/xposed_init").is_file():
        raise ValueError("Do not disrupt the shipping Legacy APK")
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    if 'android:name="xposedminversion"' not in manifest:
        raise ValueError("Shipping manifest's Legacy loader changed")
    print("Modern main APK opt-in sources OK; Legacy main build untouched")


def verify_generated():
    output = ROOT / "app/build/generated/modernXposed"
    manifest = (output / "AndroidManifest.xml").read_text(encoding="utf-8")
    for name in ("xposedmodule", "xposeddescription", "xposedminversion",
                 "xposedsharedprefs", "xposedscope"):
        if f'android:name="{name}"' in manifest:
            raise ValueError(f"Modern manifest retains legacy metadata: {name}")
    if not (output / "assets/www/prism.js").exists():
        raise ValueError("Modern app lost its original UI assets")
    if (output / "assets/xposed_init").exists():
        raise ValueError("Modern app contains legacy loader")
    print("Generated Manager manifest/assets validated for single modern loader")


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
    mode.add_argument("--generated-only", action="store_true")
    mode.add_argument("--apk", type=Path)
    args = parser.parse_args()
    try:
        if args.source_only:
            verify_sources()
        elif args.generated_only:
            verify_generated()
        else:
            verify_apk(args.apk)
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile) as exc:
        print(f"Modern WA X main-APK check FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
