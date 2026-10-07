#!/usr/bin/env python3
"""Record the M00 evidence lock: what this repository is, built from, and claims.

The modernization program is a long sequence of phases, and every later phase decides whether a
change is safe by comparing it to "the state before". A hand-written list of versions cannot be
that reference: it drifts from the build the moment anything is upgraded, and once it has
drifted nobody can tell whether the code moved or the document did.

So the baseline is *derived*, not written. Everything here is read out of the tree: the version
catalog, the module build file, the manifest, the compatibility matrix, the source files
themselves. ``--check`` re-derives it and fails when the committed copy no longer matches,
which turns "the baseline is current" into something CI can prove instead of something a
reviewer has to remember.

What is deliberately *not* here: anything that depends on a machine. SDK and NDK locations, JDK
paths and Gradle caches are recorded as the versions the build asks for, not as paths on one
developer's disk, so the file means the same thing on every machine and in every CI run.

Usage:
    python3 tools/modernization/collect_m00_baseline.py --write
    python3 tools/modernization/collect_m00_baseline.py --check
    python3 tools/modernization/collect_m00_baseline.py --format json
Exit codes: 0 in sync (or written), 1 the committed baseline has drifted, 2 the tree is unreadable.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys

try:
    import tomllib
except ModuleNotFoundError:
    print(
        "error: Python 3.11+ is required so gradle/libs.versions.toml is parsed as TOML",
        file=sys.stderr,
    )
    raise SystemExit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

BASELINE_PATH = "docs/modernization/baseline/m00-baseline.json"
CATALOG = "gradle/libs.versions.toml"
MODULE_BUILD = "app/build.gradle.kts"
GRADLE_PROPERTIES = "gradle.properties"
MANIFEST = "app/src/main/AndroidManifest.xml"
COMPATIBILITY = "docs/COMPATIBILITY.md"
VERSION_CATALOG_SOURCE = "https://api.xposed.info/de/robv/android/xposed/api/maven-metadata.xml"


def read(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


def git(*args: str) -> str | None:
    try:
        result = subprocess.run(
            ("git",) + args,
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return None
    if result.returncode != 0:
        return None
    return result.stdout.strip()


def sha256_of(path: str) -> str | None:
    content = read(path)
    if content is None:
        return None
    return hashlib.sha256(content.encode("utf-8")).hexdigest()


def gradle_property(content: str, name: str) -> str | None:
    match = re.search(r"^%s=(.*)$" % re.escape(name), content, re.MULTILINE)
    return match.group(1).strip() if match else None


def first_int(content: str, pattern: str) -> int | None:
    match = re.search(pattern, content, re.MULTILINE)
    return int(match.group(1)) if match else None


def source_counts() -> dict[str, int]:
    counts = {"kotlin": 0, "java": 0, "cpp": 0, "unit_tests": 0, "android_tests": 0}
    for directory, _dirs, files in os.walk(os.path.join(REPO_ROOT, "app", "src")):
        parts = directory.replace("\\", "/").split("/")
        is_native = "cpp" in parts
        for name in files:
            if name.endswith((".kt", ".java")):
                counts["kotlin" if name.endswith(".kt") else "java"] += 1
            elif is_native and name.endswith((".cpp", ".c", ".h", ".hpp")):
                counts["cpp"] += 1
    for key, path in (
        ("unit_tests", os.path.join("app", "src", "test")),
        ("android_tests", os.path.join("app", "src", "androidTest")),
    ):
        base = os.path.join(REPO_ROOT, path)
        for _directory, _dirs, files in os.walk(base):
            for name in files:
                if name.endswith((".kt", ".java")):
                    counts[key] += 1
    return counts


def loader_contract() -> dict[str, object]:
    """The legacy loader facts, read from the tree rather than restated from constants.

    The values are duplicated into this baseline on purpose. That duplication is the hazard the
    LSPosed contract checker exists for: `docs/modernization/execution-manifest.json` recorded
    "AGP=9.3.1 and Kotlin=2.4.10" while the catalog said 9.4.1 and 2.4.20, and nothing noticed,
    because the document was prose and prose is not compared to anything.
    """
    catalog_text = read(os.path.join(REPO_ROOT, CATALOG)) or ""
    catalog = tomllib.loads(catalog_text) if catalog_text else {}
    versions = catalog.get("versions", {})
    libraries = catalog.get("libraries", {})

    pinned = None
    alias = None
    for name, value in sorted(libraries.items()):
        if not isinstance(value, dict):
            continue
        if value.get("group") == "de.robv.android.xposed" and value.get("name") == "api":
            alias = name
            declared = value.get("version")
            if isinstance(declared, dict):
                pinned = versions.get(declared.get("ref"))
            else:
                pinned = declared
            break

    manifest = read(os.path.join(REPO_ROOT, MANIFEST)) or ""
    level = re.search(
        r'android:name="xposedminversion"\s*\n?\s*android:value="(\d+)"', manifest
    )
    entry = read(os.path.join(REPO_ROOT, "app", "src", "main", "assets", "xposed_init")) or ""

    modern = []
    for directory, _dirs, _files in os.walk(os.path.join(REPO_ROOT, "app", "src", "main")):
        parts = os.path.relpath(directory, REPO_ROOT).replace("\\", "/").split("/")
        if "META-INF" in parts and "xposed" in parts:
            modern.append("/".join(parts))

    return {
        "legacy_api_coordinate": "de.robv.android.xposed:api",
        "catalog_alias": alias,
        "pinned_artifact_version": pinned,
        "published_artifact_versions": ["53", "81", "82"],
        "published_artifact_versions_source": VERSION_CATALOG_SOURCE,
        "manifest_xposedminversion": int(level.group(1)) if level else None,
        "entry_point": entry.splitlines()[0].strip() if entry.strip() else None,
        "modern_metadata_directories": sorted(modern),
        "note": (
            "xposedminversion is a runtime Xposed API level; xposed-legacy is a Maven artifact "
            "version. They are different numbering spaces, 93 and 82 are both correct, and the "
            "two must never be made equal. See docs/modernization/M00_EVIDENCE_LOCK.md."
        ),
    }


def detekt_block(build: str) -> str:
    """The ``detekt { }`` block from the module build, and nothing else.

    Matching ``ignoreFailures`` against the whole file is how this baseline would end up
    *claiming* the detekt gate ignores failures when it does not: the build file also sets
    ``ignoreFailures = true`` on the ``Test`` tasks under ``strictCollectAll``, which is a
    collect-everything-for-one-report behaviour and the opposite of a hidden failure. A
    baseline that misreports its own gates is worse than no baseline, so the read is scoped to
    the block it is talking about.
    """
    match = re.search(r"^\s*detekt\s*\{", build, re.MULTILINE)
    if match is None:
        return ""
    depth = 0
    for index in range(match.end() - 1, len(build)):
        if build[index] == "{":
            depth += 1
        elif build[index] == "}":
            depth -= 1
            if depth == 0:
                return build[match.end() : index]
    return ""


def gradle_block(build: str, name: str) -> str:
    """The body of a named top-level Gradle configuration block."""
    match = re.search(r"^\s*%s\s*\{" % re.escape(name), build, re.MULTILINE)
    if match is None:
        return ""
    depth = 0
    for index in range(match.end() - 1, len(build)):
        if build[index] == "{":
            depth += 1
        elif build[index] == "}":
            depth -= 1
            if depth == 0:
                return build[match.end() : index]
    return ""


def collect() -> dict[str, object]:
    catalog_text = read(os.path.join(REPO_ROOT, CATALOG)) or ""
    build = read(os.path.join(REPO_ROOT, MODULE_BUILD)) or ""
    properties = read(os.path.join(REPO_ROOT, GRADLE_PROPERTIES)) or ""
    if not catalog_text or not build:
        print(
            "error: %s or %s could not be read" % (CATALOG, MODULE_BUILD),
            file=sys.stderr,
        )
        raise SystemExit(2)

    catalog = tomllib.loads(catalog_text)
    versions = catalog.get("versions", {})
    plugins = catalog.get("plugins", {})

    # Only the versions that describe the shape of the build. Every dependency version is
    # recorded too, because "what does this module actually link against" is the question the
    # DexKit and libxposed phases need answered without re-deriving it.
    toolchain_keys = (
        "agp",
        "kotlin",
        "composeBom",
        "composeMaterial3",
        "detekt",
        "spotless",
        "xposed-legacy",
    )

    lint = gradle_block(build, "lint")
    detekt = detekt_block(build)

    workflows_dir = os.path.join(REPO_ROOT, ".github", "workflows")
    workflows = {}
    if os.path.isdir(workflows_dir):
        for name in sorted(os.listdir(workflows_dir)):
            if name.endswith((".yml", ".yaml")):
                workflows[name] = sha256_of(os.path.join(".github", "workflows", name))

    arrays = read(os.path.join(REPO_ROOT, "app", "src", "main", "res", "values", "arrays.xml")) or ""
    targets = {}
    for match in re.finditer(
        r'<string-array name="(supported_versions_[a-z]+)"\s*>(.*?)</string-array>', arrays, re.DOTALL
    ):
        targets[match.group(1)] = re.findall(r"<item>(.*?)</item>", match.group(2))

    ci_text = read(os.path.join(REPO_ROOT, ".github", "workflows", "ci.yml")) or ""
    jvm = re.search(r"JAVA_VERSION:\s*'?(\d+)", ci_text)

    wrapper = read(os.path.join(REPO_ROOT, "gradle", "wrapper", "gradle-wrapper.properties")) or ""
    wrapper_version = re.search(r"distributionUrl=.*gradle-([0-9.]+)-(bin|all)\.zip", wrapper)

    return {
        "schema": "wax.m00.baseline/1",
        "repository": git("config", "--get", "remote.origin.url") or "unknown",
        # Revision identity is deliberately absent. It changes on every commit and on every
        # machine's clone layout, so putting it in a file that `--check` compares would make the
        # gate fail on every single run and train everyone to ignore it. The commit under test is
        # already known to whoever is running the gate.
        "module": {
            "version_name": gradle_property(properties, "waxVersionName"),
            "version_code": gradle_property(properties, "waxVersionCode"),
            "application_id": (
                re.search(r'applicationId\s*=\s*"([^"]+)"', build).group(1)
                if re.search(r'applicationId\s*=\s*"([^"]+)"', build)
                else None
            ),
            "min_sdk": first_int(build, r"minSdk\s*=\s*(\d+)"),
            "target_sdk": first_int(build, r"targetSdk\s*=\s*(\d+)"),
            "compile_sdk": first_int(build, r"compileSdk\s*=\s*(\d+)"),
            "ndk_version": (
                re.search(r'ndkVersion\s*=\s*"([^"]+)"', build).group(1)
                if re.search(r'ndkVersion\s*=\s*"([^"]+)"', build)
                else None
            ),
            "abi_filters": sorted(set(re.findall(r'abiFilters\.add\("([^"]+)"\)', build))),
            "packages_excluded_from_packaging": sorted(
                set(re.findall(r'excludes\s*\+=\s*"([^"]+)"', build))
            ),
            "lint": {
                "warnings_as_errors": "warningsAsErrors = true" in lint,
                "abort_on_error": "abortOnError = true" in lint,
                "disabled_rules": sorted(set(re.findall(r'disable\s*\+=\s*"([^"]+)"', lint))),
                "baseline": (
                    re.search(r'baseline\s*=\s*file\("([^"]+)"\)', lint).group(1)
                    if re.search(r'baseline\s*=\s*file\("([^"]+)"\)', lint)
                    else None
                ),
            },
            "detekt": {
                "ignore_failures": "ignoreFailures = true" in detekt,
                "fail_on_severity": (
                    re.search(r"failOnSeverity\s*=\s*([\w.]+)", detekt).group(1)
                    if re.search(r"failOnSeverity\s*=\s*([\w.]+)", detekt)
                    else None
                ),
                "build_upon_default_config": "buildUponDefaultConfig = true" in detekt,
            },
            "jvm_target": (
                re.search(r"JvmTarget\.([A-Z_0-9]+)", build).group(1)
                if re.search(r"JvmTarget\.([A-Z_0-9]+)", build)
                else None
            ),
            "kotlin_all_warnings_as_errors": "allWarningsAsErrors.set(true)" in build,
        },
        "toolchain": dict(
            sorted(
                {key: versions[key] for key in toolchain_keys if key in versions}.items()
            ),
            **{
                "ksp": plugins.get("kspPlugin", {}).get("version")
                if isinstance(plugins.get("kspPlugin"), dict)
                else None
            },
        ),
        "dependencies": {
            name: value for name, value in sorted(catalog.get("libraries", {}).items())
        },
        "loader_contract": loader_contract(),
        "compatibility": {
            "matrix": COMPATIBILITY,
            "matrix_sha256": sha256_of(COMPATIBILITY),
            "declared_version_targets": {name: items for name, items in sorted(targets.items())},
        },
        "ci": {
            "workflows_sha256": workflows,
            "jvm_version": jvm.group(1) if jvm else None,
            "gradle_wrapper": wrapper_version.group(1) if wrapper_version else None,
            "branch_filters": sorted(
                set(re.findall(r"^\s*-?\s*\"?([a-z][a-z0-9/-]*)\"?\s*$", ci_text, re.MULTILINE))
                & {"main", "master", "ci/telegram-release-publisher-test"}
            ),
        },
        "source_counts": source_counts(),
        "static_analysis": {
            "lint_baseline_sha256": sha256_of("app/lint-baseline.xml"),
            "detekt_config_sha256": sha256_of("config/detekt/detekt.yml"),
            "strict_policy_sha256": sha256_of("quality/strict-policy.json"),
        },
    }


def render(payload: dict[str, object]) -> str:
    return json.dumps(payload, indent=2, sort_keys=True) + "\n"


def flatten(value: object, prefix: str = "") -> dict[str, object]:
    """Flatten to JSON-pointer-ish paths so a drift report names fields, not a wall of JSON."""
    if isinstance(value, dict):
        out: dict[str, object] = {}
        for key in sorted(value):
            out.update(flatten(value[key], "%s/%s" % (prefix, key)))
        return out
    return {prefix or "/": value}


def describe_drift(expected: str, actual: str) -> list[str]:
    """The fields that differ, as ``path: committed -> derived`` lines."""
    try:
        before = flatten(json.loads(expected))
        after = flatten(json.loads(actual))
    except json.JSONDecodeError:
        return ["the committed baseline is not valid JSON"]

    lines = []
    for path in sorted(set(before) | set(after)):
        left, right = before.get(path, "<absent>"), after.get(path, "<absent>")
        if left == right:
            continue
        if path.endswith("sha256") and left != "<absent>" and right != "<absent>":
            lines.append("%s: %s -> %s (file content changed)" % (path, left[:12], right[:12]))
        else:
            lines.append("%s: %s -> %s" % (path, left, right))
    return lines


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true", help="write the baseline into the tree")
    group.add_argument("--check", action="store_true", help="fail when the committed baseline drifted")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args(argv)

    payload = collect()
    rendered = render(payload)

    if args.format == "json":
        print(rendered, end="")
        return 0

    if args.write:
        path = os.path.join(REPO_ROOT, BASELINE_PATH)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(rendered)
        print("wrote %s" % BASELINE_PATH, file=sys.stderr)
        return 0

    path = os.path.join(REPO_ROOT, BASELINE_PATH)
    existing = read(path)
    if existing is None:
        print("error: %s does not exist; run with --write" % BASELINE_PATH, file=sys.stderr)
        return 1
    if existing != rendered:
        print(
            "error: %s no longer describes this tree. Every field is derived, so a difference "
            "here means a version, a workflow or a source count actually moved:" % BASELINE_PATH,
            file=sys.stderr,
        )
        for line in describe_drift(existing, rendered):
            print("  %s" % line, file=sys.stderr)
        print(
            "Read the list before accepting it. If the change is intended, run with --write and "
            "commit the baseline in the same change as whatever moved it, so the two cannot "
            "disagree afterwards.",
            file=sys.stderr,
        )
        return 1
    print("M00 baseline is in sync with the tree")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))