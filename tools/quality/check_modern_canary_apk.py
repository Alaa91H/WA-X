#!/usr/bin/env python3
"""Strict API 102 APK loader gate; never mistake a compiled class for a loadable module."""
import argparse
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PREFIX = "META-INF/xposed/"
ENTRY = "com.wax.module.modern.ModernXposedEntry"
SCOPE = ("com.whatsapp", "com.whatsapp.w4b")
PROP = {
    "minApiVersion": "102",
    "targetApiVersion": "102",
    "staticScope": "true",
    "autoHotReload": "false",
    "exceptionMode": "protective",
}


def parse_lines(content):
    return [line.strip() for line in content.splitlines() if line.strip() and not line.startswith("#")]


def check_metadata(get_file):
    entries = parse_lines(get_file(PREFIX + "java_init.list"))
    if entries != [ENTRY]:
        raise ValueError(f"exactly one known modern Xposed entry expected: {entries!r}")
    scopes = parse_lines(get_file(PREFIX + "scope.list"))
    if scopes != list(SCOPE):
        raise ValueError(f"incorrect modern scope: {scopes!r}")
    pairs = {}
    for line in parse_lines(get_file(PREFIX + "module.prop")):
        if "=" not in line:
            raise ValueError(f"malformed module property: {line}")
        k, value = line.split("=", 1)
        if k in pairs:
            raise ValueError(f"duplicate module property: {k}")
        pairs[k] = value
    for key, expected in PROP.items():
        if pairs.get(key) != expected:
            raise ValueError(f"module property {key} must be {expected!r}")


def verify_sources():
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    if 'providers.gradleProperty("enableModernCanary")' not in settings:
        raise ValueError("modern-canary must be behind an opt-in Gradle property")
    if 'include(":modern-canary")' not in settings:
        raise ValueError("modern-canary module not declared")
    build = (ROOT / "modern-canary/build.gradle.kts").read_text(encoding="utf-8")
    if 'applicationId = "com.wax.module.modern.canary"' not in build:
        raise ValueError("canary must install independently from WA X legacy package")
    if 'implementation(project(":modern-runtime"))' not in build:
        raise ValueError("modern Runtime must be packaged in canary")
    if "compileOnly(libs.libxposed.modern.api)" not in build:
        raise ValueError("modern Xposed API must not be bundled in canary")
    if 'implementation(libs.libxposed.modern.service)' not in build:
        raise ValueError("manager must contain modern framework service")
    if list((ROOT / "modern-canary/src").glob("**/assets/xposed_init")):
        raise ValueError("canary must never carry legacy loader metadata")
    metadata = ROOT / "modern-canary/src/main/resources"
    check_metadata(lambda filename: (metadata / filename).read_text(encoding="utf-8"))
    print("modern APK source metadata: valid API102 entry, exact target scope, independent package")


def verify_apk(path):
    with zipfile.ZipFile(path) as archive:
        files = archive.namelist()
        if len(files) != len(set(files)):
            raise ValueError("APK contains duplicate ZIP entries")
        if "assets/xposed_init" in files:
            raise ValueError("modern canary APK incorrectly contains the legacy loader")
        if "AndroidManifest.xml" not in files:
            raise ValueError("APK has no compiled Android manifest")
        for filename in ("java_init.list", "module.prop", "scope.list"):
            if PREFIX + filename not in files:
                raise ValueError(f"modern loader metadata absent from actual APK: {filename}")
        check_metadata(lambda name: archive.read(name).decode("utf-8"))
        dexes = [name for name in files if name.startswith("classes") and name.endswith(".dex")]
        if not dexes:
            raise ValueError("APK has no DEX bytecode")
        descriptor = ("L" + ENTRY.replace(".", "/") + ";").encode("ascii")
        if not any(descriptor in archive.read(name) for name in dexes):
            raise ValueError("modern entry named in metadata is missing from APK DEX")
    print(f"modern APK loader verified: {path}, API102, entry class present, no legacy loader")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--source-only", action="store_true")
    args = parser.parse_args()
    if (args.apk is None) == (not args.source_only):
        parser.error("specify exactly one of --apk or --source-only")
    try:
        if args.source_only:
            verify_sources()
        else:
            verify_apk(args.apk)
    except (OSError, ValueError, UnicodeError, zipfile.BadZipFile) as exc:
        print(f"modern APK loader contract FAILED: {exc}", file=sys.stderr)
        raise SystemExit(1) from exc


if __name__ == "__main__":
    main()
