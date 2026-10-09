#!/usr/bin/env python3
"""Derive compatibility facts for T01 from the module source.

This script does not invent compatibility data. It only *derives facts* that are
provable by reading the source tree:

  * the registered feature list, taken from ``RuntimeFeatureRegistry``
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

FEATURE_REGISTRY = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wax/module/xposed/registry/RuntimeFeatureRegistry.kt"
)
FEATURES_DIR = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wax/module/xposed/features"
)
ARRAYS_XML = os.path.join(REPO_ROOT, "app/src/main/res/values/arrays.xml")
UNOBFUSCATOR = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wax/module/xposed/core/devkit/Unobfuscator.kt"
)
APP_BUILD_GRADLE = os.path.join(REPO_ROOT, "app/build.gradle.kts")

PACKAGE_IMPORT = re.compile(r"^import\s+([\w.]+)\s*$")
RESOLVER_CALL = re.compile(r"\bUnobfuscator\s*\.\s*(load\w+)")
RESOLVER_DECL = re.compile(r"\bfun\s+(load\w+)\s*\(")
ARRAY_ITEM = re.compile(r"<item>([^<]+)</item>")
# Only known preference/settings receivers are treated as evidence of a read.
# Generic getString/getInt calls on JSONObject, Bundle, etc. are not preferences.
PREF_READ = re.compile(
    r'\b(?:prefs|preferences|sharedPreferences|settingsStore|settings|store)'
    r'\s*\.\s*(?:getBoolean|getString|getInt|getLong|getFloat|getStringSet|contains|get|read)'
    r'\s*\(\s*(?:"([^"]+)"|(PREF_[A-Z0-9_]+))'
)
PREF_CONST = re.compile(
    r'\bconst\s+val\s+(PREF_[A-Z0-9_]+)\s*=\s*"([^"]+)"'
)
KOTLIN_PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.MULTILINE)
KOTLIN_IMPORT = re.compile(r"^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$", re.MULTILINE)
KOTLIN_DECL = re.compile(
    r"\b(?:class|object|interface)\s+([A-Z][A-Za-z0-9_]*)\b"
)

# A type token naming another Kotlin file in the feature tree.
TYPE_TOKEN = re.compile(r"\b([A-Z][A-Za-z0-9_]{2,})\b")
# A helper class is *owned* by a feature when the feature constructs it. A bare type
# mention is not enough: a comment, a KDoc link or a static access such as
# ``Others.propsInteger`` would otherwise drag a whole unrelated feature in.
CONSTRUCTS = re.compile(r"\b([A-Z][A-Za-z0-9_]{2,})\s*\(")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")

# A feature reaches its hook targets through one or more of these internal layers.
# Recording which ones a feature touches is what makes its compatibility auditable:
# a feature that only uses ReflectionUtils still depends on resolution succeeding.
RESOLUTION_SOURCES = (
    "Unobfuscator",
    "UnobfuscatorCache",
    "ReflectionUtils",
    # Formerly WppCore; renamed to ModuleRuntime in the WA X identity migration.
    "ModuleRuntime",
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
    for line in read(FEATURE_REGISTRY).splitlines():
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
    """Return feature ids in the exact order the runtime installs them.

    Read from ``RuntimeFeatureRegistry``, which is the one registration source. Before #337 this
    parsed an ``arrayOf(...)`` inside ``FeatureLoader.plugins()``, which meant the compatibility
    matrix was derived from a hand-maintained list that duplicated the runtime list and that
    nothing checked against it: a feature added to one and not the other would have produced a
    module that installs a different set of features than the matrix claims to describe.

    The id is the ``featureId`` argument rather than the class reference, because that is the name
    the runtime logs, the diagnostics dialog shows and the failure reports carry. A rename in the
    registry therefore fails here rather than producing a matrix whose cells describe features that
    no longer exist.
    """
    source = read(FEATURE_REGISTRY)
    body = source[source.index("val entries:") :]
    ids = re.findall(r'FeatureFactory\.(?:Contract|Legacy)\("([A-Za-z0-9_]+)"\)', body)
    if not ids:
        raise SystemExit(
            "could not locate the feature registry entries in %s; a compatibility matrix derived "
            "from an empty list would report every cell unsupported for the wrong reason"
            % os.path.relpath(FEATURE_REGISTRY, REPO_ROOT)
        )
    return ids


def resolvers_declared() -> set[str]:
    return set(RESOLVER_DECL.findall(read(UNOBFUSCATOR)))


def feature_files() -> dict[str, str]:
    """Index source by file stem; never silently drop a colliding Kotlin file."""
    files: dict[str, str] = {}
    paths: dict[str, str] = {}
    for dirpath, dirnames, filenames in os.walk(FEATURES_DIR):
        dirnames.sort()
        for filename in sorted(filenames):
            if not filename.endswith(".kt"):
                continue
            stem = filename[:-3]
            path = os.path.join(dirpath, filename)
            if stem in files:
                raise ValueError(
                    "duplicate Kotlin file stem %r: %s and %s"
                    % (stem, paths[stem], path)
                )
            files[stem] = read(path)
            paths[stem] = path
    return files


def source_package(body: str) -> str | None:
    match = KOTLIN_PACKAGE.search(body)
    return match.group(1) if match else None


def source_imports(body: str) -> dict[str, str]:
    """Resolve explicitly imported Kotlin classes, including aliases."""
    return {
        alias or qualified.rsplit(".", 1)[-1]: qualified
        for qualified, alias in KOTLIN_IMPORT.findall(body)
    }


def top_level_classes(code: str) -> set[str]:
    """Resolve only top-level Kotlin declarations, never nested helper interfaces.

    Replace string and character literals with equal-length whitespace first so
    braces or fake declarations inside them do not alter source nesting.
    """
    literals = re.compile(
        r"""\"\"\"[\s\S]*?\"\"\"|\"(?:\\.|[^\"\\])*\"|'(?:\\.|[^'\\])*'"""
    )
    sanitized = literals.sub(lambda match: " " * len(match.group()), code)
    names: set[str] = set()
    depth = 0
    cursor = 0
    for match in KOTLIN_DECL.finditer(sanitized):
        for char in sanitized[cursor:match.start()]:
            if char == "{":
                depth += 1
            elif char == "}":
                depth = max(0, depth - 1)
        if depth == 0:
            names.add(match.group(1))
        cursor = match.start()
    return names


def declared_classes(files: dict[str, str]) -> dict[str, str]:
    """Map fully qualified top-level classes to their defining Kotlin file stem."""
    owners: dict[str, str] = {}
    for stem, body in files.items():
        code = strip_comments(body)
        package = source_package(code)
        # Bare source snippets in test fixtures retain the old stem fallback.
        names = top_level_classes(code) or {stem}
        for name in names:
            qualified = (package + "." if package else "") + name
            previous = owners.get(qualified)
            if previous is not None and previous != stem:
                raise ValueError(
                    "ambiguous Kotlin class ownership %s: %s and %s"
                    % (qualified, previous, stem)
                )
            owners[qualified] = stem
    return owners

def strip_comments(body: str) -> str:
    """Remove comments so a KDoc mention is not mistaken for a real reference."""
    without_block = BLOCK_COMMENT.sub(" ", body)
    return LINE_COMMENT.sub(" ", without_block)


def feature_closure(
    stem: str, files: dict[str, str], unresolved: set[str] | None = None,
    owners: dict[str, str] | None = None
) -> set[str]:
    """Follow constructed helpers by declaration, package and explicit imports.

    Unqualified names in other packages are not evidence of ownership.
    Unresolved/ambiguous names can be collected for audit without inventing
    dependencies or failing on ordinary external constructors.
    """
    if stem not in files:
        return set()
    if owners is None:
        owners = declared_classes(files)
    closure: set[str] = set()
    pending = [stem]
    while pending:
        current = pending.pop()
        if current in closure or current not in files:
            continue
        closure.add(current)
        code = strip_comments(files[current])
        package = source_package(code)
        imports = source_imports(code)
        for token in set(CONSTRUCTS.findall(code)):
            qualified = imports.get(token) or (
                (package + "." if package else "") + token
            )
            target = owners.get(qualified)
            if target is None and unresolved is not None:
                # Only report an unresolved *feature-tree* candidate.
                candidates = [
                    owner for name, owner in owners.items()
                    if name.rsplit(".", 1)[-1] == token
                ]
                if candidates:
                    unresolved.add("%s: %s" % (current, token))
            if target is not None and target not in closure:
                pending.append(target)
    return closure

def aggregate(files: dict[str, str], stems: set[str]) -> str:
    return "\n".join(files[stem] for stem in sorted(stems) if stem in files)


def feature_resolver_usage(files: dict[str, str], owners: dict[str, str] | None = None) -> dict[str, list[str]]:
    """Map feature simple name -> sorted ``Unobfuscator.load*`` calls in its closure."""
    if owners is None:
        owners = declared_classes(files)
    usage: dict[str, list[str]] = {}
    for stem in files:
        calls = sorted(set(RESOLVER_CALL.findall(aggregate(files, feature_closure(stem, files, owners=owners)))))
        if calls:
            usage[stem] = calls
    return usage


def feature_preference_keys(files: dict[str, str], owners: dict[str, str] | None = None) -> dict[str, list[str]]:
    """Inventory statically provable reads; writes and dynamic keys stay unknown."""
    if owners is None:
        owners = declared_classes(files)
    keys: dict[str, list[str]] = {}
    for stem in files:
        body = strip_comments(aggregate(files, feature_closure(stem, files, owners=owners)))
        constants = dict(PREF_CONST.findall(body))
        found: set[str] = set()
        for literal, constant in PREF_READ.findall(body):
            if literal:
                found.add(literal)
            elif constant in constants:
                found.add(constants[constant])
        if found:
            keys[stem] = sorted(found)
    return keys

def feature_resolution_sources(files: dict[str, str], owners: dict[str, str] | None = None) -> dict[str, list[str]]:
    """Map feature simple name -> internal resolution layers its closure references."""
    if owners is None:
        owners = declared_classes(files)
    sources: dict[str, list[str]] = {}
    for stem in files:
        body = aggregate(files, feature_closure(stem, files, owners=owners))
        found = [name for name in RESOLUTION_SOURCES if re.search(r"\b%s\b" % name, body)]
        if found:
            sources[stem] = found
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
    files = feature_files()
    owners = declared_classes(files)
    usage = feature_resolver_usage(files, owners)
    sources = feature_resolution_sources(files, owners)
    pref_keys = feature_preference_keys(files, owners)
    versions = supported_versions()

    missing = [name for name in order if name not in imported]
    if missing:
        raise SystemExit(
            "features registered in the runtime registry but not present as a source file: %s"
            % ", ".join(missing)
        )

    unknown_resolvers: set[str] = set()
    features: list[dict[str, Any]] = []
    for name in order:
        package = imported[name]
        owner = owners.get(package + "." + name)
        if owner is None:
            raise SystemExit(
                "registered feature %s has no unique Kotlin declaration in %s"
                % (name, package)
            )
        used = usage.get(owner, [])
        for resolver in used:
            if resolver not in declared:
                unknown_resolvers.add("%s -> %s" % (name, resolver))
        features.append(
            {
                "id": name,
                "category": package_for(package) or "unknown",
                "sourcePackage": package,
                "resolverDependencies": used,
                "resolutionSources": sources.get(owner, []),
                "preferenceKeys": pref_keys.get(owner, []),
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
                "applicationId": "com.wax.module",
                "declaredVersions": versions["supported_versions_wpp"],
            },
            "business": {
                "packageName": "com.whatsapp.w4b",
                "applicationId": "com.wax.module",
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
