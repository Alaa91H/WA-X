#!/usr/bin/env python3
"""Fail when the module's features stop having exactly one registration source.

Before #337 there were two. `FeatureLoader.plugins()` held a hand-maintained `arrayOf(...)` that the
runtime installed from, and `tools/compatibility/extract_features.py` produced the feature list the
compatibility matrix was computed from. They held the same 64 ids in the same order, and nothing
checked it: a feature added to one list and not the other would have produced a module that installs
a different set of features than the matrix claims to describe - a compatibility claim about code
that is never loaded.

So this checker is not about style. It is the difference between the compatibility matrix being a
description of the module and being a coincidence that has not been tested yet.

Five things are checked:

1. **Every feature class is registered.** A `Feature` or `WaFeature` subclass on disk with no entry
   would be dead code that a test still counts.
2. **Every id is unique**, and every entry has a factory. The id keys the log line, the failure
   report and the compatibility cell, so two features sharing one are indistinguishable everywhere.
3. **The registry and the derived facts describe the same features, in the same order.** This is the
   duplication itself: the matrix is derived from the registry, so a disagreement means the derived
   file is stale or the registry was edited without regenerating it.
4. **Nothing constructs a feature reflectively.** `getConstructor(ClassLoader, SharedPreferences)`
   fails at runtime with a `NoSuchMethodException` that names neither the feature nor the reason.
5. **The extractor reads the registry.** If it went back to parsing a list inside the loader, the
   two views would start drifting again with nothing to notice.

Usage:
    python3 tools/quality/check_feature_registry.py
    python3 tools/quality/check_feature_registry.py --format json

Exit codes:
    0 the registry is authoritative, complete and in sync with the derived facts
    1 a feature is unregistered, an id is duplicated, the two views disagree, or something reflects
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MAIN = os.path.join(REPO, "app", "src", "main", "java", "com", "wax", "module")
FEATURES_DIR = os.path.join(MAIN, "xposed", "features")
REGISTRY = os.path.join(MAIN, "xposed", "registry", "RuntimeFeatureRegistry.kt")
LOADER = os.path.join(MAIN, "xposed", "core", "FeatureLoader.kt")
EXTRACTOR = os.path.join(REPO, "tools", "compatibility", "extract_features.py")
COMPATIBILITY = os.path.join(REPO, "tools", "compatibility", "compatibility.json")

ENTRY = re.compile(r'FeatureFactory\.(Contract|Legacy)\("([A-Za-z0-9_]+)"\)')


def read(path: str) -> str:
    with open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//.*$", "", text, flags=re.MULTILINE)


def registry_entries() -> list[tuple[str, str]]:
    """(kind, id) for every entry, in the order the registry declares them."""
    if not os.path.exists(REGISTRY):
        return []
    body = read(REGISTRY)
    if "val entries:" in body:
        body = body[body.index("val entries:") :]
    return [(match.group(1), match.group(2)) for match in ENTRY.finditer(body)]


def feature_classes_on_disk() -> list[str]:
    """Simple names of every class under xposed/features that extends Feature or WaFeature."""
    found = []
    for directory, _, names in os.walk(FEATURES_DIR):
        for name in sorted(names):
            if not name.endswith(".kt"):
                continue
            text = strip_comments(read(os.path.join(directory, name)))
            stem = name[:-3]
            if not re.search(r"\b(class|object)\s+%s\b" % re.escape(stem), text):
                continue
            if re.search(r":\s*(?:[\w.]*\.)?(?:Feature|WaFeature)\b", text):
                found.append(stem)
    return found


def derived_ids() -> list[str]:
    if not os.path.exists(COMPATIBILITY):
        return []
    with open(COMPATIBILITY, encoding="utf-8") as handle:
        data = json.load(handle)
    return [feature["id"] for feature in data.get("derived", {}).get("features", [])]


def check() -> list[dict]:
    findings: list[dict] = []
    entries = registry_entries()
    ids = [feature_id for _, feature_id in entries]

    if not entries:
        return [
            {
                "type": "missing-registry",
                "file": os.path.relpath(REGISTRY, REPO),
                "message": "No feature registry was found. Without one there is no authoritative "
                "registration source, and every check below would pass for the wrong reason.",
            }
        ]

    duplicates = sorted({feature_id for feature_id in ids if ids.count(feature_id) > 1})
    for feature_id in duplicates:
        findings.append(
            {
                "type": "duplicate-id",
                "file": os.path.relpath(REGISTRY, REPO),
                "message": "The feature id '%s' is registered more than once. The id keys the log "
                "line, the failure report and the compatibility cell, so two features sharing one are "
                "indistinguishable in every place that reads it." % feature_id,
            }
        )

    registered = set(ids)
    for stem in feature_classes_on_disk():
        if stem not in registered:
            findings.append(
                {
                    "type": "unregistered-feature",
                    "file": os.path.relpath(FEATURES_DIR, REPO),
                    "message": "%s extends Feature or WaFeature but is not in RuntimeFeatureRegistry, "
                    "so it would never be installed. A feature that exists but never runs is a test "
                    "that still counts it." % stem,
                }
            )

    derived = derived_ids()
    if derived and derived != ids:
        only_registry = sorted(set(ids) - set(derived))
        only_derived = sorted(set(derived) - set(ids))
        findings.append(
            {
                "type": "derived-drift",
                "file": os.path.relpath(COMPATIBILITY, REPO),
                "message": "The compatibility matrix's derived features do not match the registry. "
                "Registry-only: %s. Matrix-only: %s. Order differs: %s. Regenerate with "
                "`python3 tools/compatibility/extract_features.py` and the matrix sync tool, or fix "
                "the registry if it is the one that is wrong."
                % (
                    ", ".join(only_registry) or "none",
                    ", ".join(only_derived) or "none",
                    "yes" if sorted(derived) == sorted(ids) else "no",
                ),
            }
        )

    for path, label in ((LOADER, "FeatureLoader.kt"), (REGISTRY, "RuntimeFeatureRegistry.kt")):
        if not os.path.exists(path):
            continue
        text = strip_comments(read(path))
        if "getConstructor(" in text or "getDeclaredConstructor()" in text:
            findings.append(
                {
                    "type": "reflective-construction",
                    "file": os.path.relpath(path, REPO),
                    "message": "%s constructs a feature reflectively. A constructor looked up by "
                    "parameter type fails at runtime with a message naming neither the feature nor "
                    "the reason; a factory lambda that names the constructor fails to compile "
                    "instead." % label,
                }
            )

    if os.path.exists(EXTRACTOR):
        extractor = read(EXTRACTOR)
        if "RuntimeFeatureRegistry.kt" not in extractor:
            findings.append(
                {
                    "type": "extractor-not-unified",
                    "file": os.path.relpath(EXTRACTOR, REPO),
                    "message": "extract_features.py does not read RuntimeFeatureRegistry.kt, so the "
                    "compatibility matrix is derived from something other than the authoritative "
                    "registration source. That is the duplication #337 removed.",
                }
            )

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args()

    findings = check()
    if args.format == "json":
        print(json.dumps({"findingCount": len(findings), "findings": findings}, indent=2, sort_keys=True))
    else:
        for finding in findings:
            print("%s: %s" % (finding["type"], finding["message"]))
        if findings:
            print("\n%d finding(s)." % len(findings))
        else:
            entries = registry_entries()
            kinds = {}
            for kind, _ in entries:
                kinds[kind] = kinds.get(kind, 0) + 1
            print(
                "feature registry is authoritative: %d feature(s) (%s), one registration source, "
                "no reflective construction"
                % (
                    len(entries),
                    ", ".join("%d %s" % (count, kind.lower()) for kind, count in sorted(kinds.items())),
                )
            )
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())