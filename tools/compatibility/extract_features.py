#!/usr/bin/env python3
"""Derive compatibility facts for T01 from the module source.

This script does not invent compatibility data. It only *derives facts* that are
provable by reading the source tree:

  * the registered feature list, taken from the ``plugins()`` array in FeatureLoader
  * each feature's category, taken from its package
  * each feature's resolver dependencies, taken from the ``Unobfuscator.load*``
    calls inside that feature's own file
  * the declared supported version prefixes, taken from res/values/arrays.xml
  * SDK / ABI facts, taken from app/build.gradle.kts and gradle.properties

Anything that cannot be proven statically is emitted as ``unknown``. Declaring a
feature ``supported`` requires runtime resolver evidence, which this script does
not have; that evidence is collected at runtime and folded back in by T51+.

Normally this module is imported by validate_compatibility.py rather than run
directly. Run it standalone to inspect the facts:

    python3 tools/compatibility/extract_features.py            # print JSON
    python3 tools/compatibility/extract_features.py --out F    # write to F

To refresh compatibility.json's ``derived`` section, or to check it for drift:

    python3 tools/compatibility/validate_compatibility.py --sync
    python3 tools/compatibility/validate_compatibility.py
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

FEATURE_LOADER = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wmods/wppenhacer/xposed/core/FeatureLoader.kt"
)
FEATURES_DIR = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wmods/wppenhacer/xposed/features"
)
ARRAYS_XML = os.path.join(REPO_ROOT, "app/src/main/res/values/arrays.xml")
UNOBFUSCATOR = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wmods/wppenhacer/xposed/core/devkit/Unobfuscator.kt"
)
APP_BUILD_GRADLE = os.path.join(REPO_ROOT, "app/build.gradle.kts")

PACKAGE_IMPORT = re.compile(r"^import\s+([\w.]+)\s*$")
RESOLVER_CALL = re.compile(r"\bUnobfuscator\s*\.\s*(load\w+)")
RESOLVER_DECL = re.compile(r"\bfun\s+(load\w+)\s*\(")
ARRAY_ITEM = re.compile(r"<item>([^<]+)</item>")
GET_BOOLEAN = re.compile(r'\bgetBoolean\s*\(\s*"([^"]+)"')

# A feature reaches its hook targets through one or more of these internal layers.
# Recording which ones a feature touches is what makes its compatibility auditable:
# a feature that only uses ReflectionUtils still depends on resolution succeeding.
RESOLUTION_SOURCES = (
    "Unobfuscator",
    "UnobfuscatorCache",
    "ReflectionUtils",
    "WppCore",
)

# Maps the feature source package to the user facing category name used by the
# settings UI and by FeatureCatalog.
CATEGORY_BY_PACKAGE = {
    "customization": "customization",
    "general": "general",
    "media": "media",
    "others": "others",
    "privacy": "privacy",
    "listeners": "listeners",
    "providers": "providers",
}


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def find_feature_classes() -> list[tuple[str, str]]:
    """Return ``(simpleName, importedPackage)`` for every feature loader import."""
    found: list[tuple[str, str]] = []
    for line in read(FEATURE_LOADER).splitlines():
        match = PACKAGE_IMPORT.match(line)
        if not match:
            continue
        full = match.group(1)
        simple = full.rsplit(".", 1)[1]
        package = full.rsplit(".", 1)[0]
        if ".xposed.features." not in package:
            continue
        found.append((simple, package))
    return found


def find_registered_order() -> list[str]:
    """Return feature simple names in the exact order ``plugins()`` installs them."""
    source = read(FEATURE_LOADER)
    match = re.search(r"val classes = arrayOf\((.*?)\n\s*\)", source, re.DOTALL)
    if not match:
        raise SystemExit("could not locate the plugins() array in FeatureLoader.kt")
    return re.findall(r"([A-Za-z0-9_]+)::class\.java", match.group(1))


def resolvers_declared() -> set[str]:
    return set(RESOLVER_DECL.findall(read(UNOBFUSCATOR)))


def feature_resolver_usage() -> dict[str, list[str]]:
    """Map feature simple name -> sorted ``Unobfuscator.load*`` calls in its file."""
    usage: dict[str, list[str]] = {}
    for dirpath, _dirnames, filenames in os.walk(FEATURES_DIR):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            simple = filename[:-3]
            calls = sorted(set(RESOLVER_CALL.findall(read(os.path.join(dirpath, filename)))))
            if calls:
                usage[simple] = calls
    return usage


def feature_preference_keys() -> dict[str, list[str]]:
    """Map feature simple name -> preference keys it reads via ``prefs.getBoolean``."""
    keys: dict[str, list[str]] = {}
    for dirpath, _dirnames, filenames in os.walk(FEATURES_DIR):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            found = sorted(set(GET_BOOLEAN.findall(read(os.path.join(dirpath, filename)))))
            if found:
                keys[filename[:-3]] = found
    return keys


def feature_resolution_sources() -> dict[str, list[str]]:
    """Map feature simple name -> internal resolution layers it references."""
    sources: dict[str, list[str]] = {}
    for dirpath, _dirnames, filenames in os.walk(FEATURES_DIR):
        for filename in filenames:
            if not filename.endswith(".kt"):
                continue
            body = read(os.path.join(dirpath, filename))
            found = [name for name in RESOLUTION_SOURCES if re.search(r"\b%s\b" % name, body)]
            if found:
                sources[filename[:-3]] = found
    return sources


def supported_versions() -> dict[str, list[str]]:
    """Return the version prefixes declared in res/values/arrays.xml."""
    source = read(ARRAYS_XML)
    result: dict[str, list[str]] = {}
    for name in ("supported_versions_wpp", "supported_versions_business"):
        block = re.search(
            r'<string-array name="%s">(.*?)</string-array>' % name, source, re.DOTALL
        )
        if not block:
            raise SystemExit("missing string-array %s in arrays.xml" % name)
        result[name] = [item.strip() for item in ARRAY_ITEM.findall(block.group(1)) if item.strip()]
    return result


def module_facts() -> dict[str, Any]:
    source = read(APP_BUILD_GRADLE)

    def gradle_int(key: str) -> int | None:
        match = re.search(r"%s\s*=\s*(\d+)" % re.escape(key), source)
        return int(match.group(1)) if match else None

    abis_block = re.search(r"abiFilters(.*?)\n\s*\}", source, re.DOTALL)
    abis: list[str] = []
    if abis_block:
        abis = re.findall(r"[\"']([^\"']+)[\"']", abis_block.group(1))

    return {
        "minSdk": gradle_int("minSdk"),
        "targetSdk": gradle_int("targetSdk"),
        "compileSdk": gradle_int("compileSdk"),
        "abis": sorted(abis),
    }


def package_for(package: str) -> str | None:
    parts = package.split(".xposed.features.")[1].split(".")
    return CATEGORY_BY_PACKAGE.get(parts[0])


def build() -> dict[str, Any]:
    declared = resolvers_declared()
    order = find_registered_order()
    imported = {simple: package for simple, package in find_feature_classes()}
    usage = feature_resolver_usage()
    sources = feature_resolution_sources()
    pref_keys = feature_preference_keys()
    versions = supported_versions()

    missing = [name for name in order if name not in imported]
    if missing:
        raise SystemExit(
            "features registered in plugins() but not imported by FeatureLoader: %s"
            % ", ".join(missing)
        )

    unknown_resolvers: set[str] = set()
    features: list[dict[str, Any]] = []
    for name in order:
        package = imported[name]
        used = usage.get(name, [])
        for resolver in used:
            if resolver not in declared:
                unknown_resolvers.add("%s -> %s" % (name, resolver))
        features.append(
            {
                "id": name,
                "category": package_for(package) or "unknown",
                "sourcePackage": package,
                "resolverDependencies": used,
                "resolutionSources": sources.get(name, []),
                "preferenceKeys": pref_keys.get(name, []),
            }
        )

    if unknown_resolvers:
        raise SystemExit(
            "features reference resolvers that Unobfuscator does not declare: %s"
            % ", ".join(sorted(unknown_resolvers))
        )

    return {
        "module": module_facts(),
        "packages": {
            "whatsapp": {
                "packageName": "com.whatsapp",
                "applicationId": "com.wmods.wppenhacer",
                "declaredVersions": versions["supported_versions_wpp"],
            },
            "business": {
                "packageName": "com.whatsapp.w4b",
                "applicationId": "com.wmods.wppenhacer.w4b",
                "declaredVersions": versions["supported_versions_business"],
            },
        },
        "featureCount": len(features),
        "features": features,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="write the derived facts to this file")
    args = parser.parse_args(argv)

    data = build()
    rendered = json.dumps(data, indent=2, sort_keys=True) + "\n"

    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(rendered)
        print("wrote %s (%d features)" % (args.out, data["featureCount"]))
        return 0

    sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))