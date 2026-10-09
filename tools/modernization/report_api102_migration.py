#!/usr/bin/env python3
"""Source-derived API102 migration ledger. Never infer device compatibility from code or CI."""
import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "app/src/main/java/com/wax/module/xposed/registry/RuntimeFeatureRegistry.kt"
MODERN = ROOT / "modern-runtime/src/main/java/com/wax/module/modern"
ENTRY = MODERN / "ModernXposedEntry.java"
FEATURE = re.compile(r'FeatureFactory\.(?:Legacy|Contract)\s*\(\s*"([A-Za-z][A-Za-z0-9]*)"')


def registry_names(content):
    names = FEATURE.findall(content)
    if not names:
        raise ValueError("RuntimeFeatureRegistry has no parsed feature registrations")
    if len(names) != len(set(names)):
        raise ValueError("Duplicate feature ID in RuntimeFeatureRegistry")
    return names


def grouped_wired_ids(entry, sources):
    """Recognize an enum-based modern feature family only if the entry actually installs it."""
    wired = set()
    for source in sources.values():
        for group in re.findall(r'\bobject\s+(Modern\w+Features)\b', source):
            if (group + ".Pilot.values()" not in entry or group + ".INSTANCE" not in entry:
                continue
            for _, identifier in re.findall(
                r'\b([A-Z][A-Z0-9_]+)\s*\(\s*"([a-z][a-z0-9_]*)"',
                source,
            ):
                wired.add(identifier.replace("_", "").lower())
    return wired


def inspect(registry, entry, sources):
    """'Wired' is a source-level fact, never a claim that WhatsApp executes a hook."""
    names = registry_names(registry)
    grouped = grouped_wired_ids(entry, sources)
    features = []
    for name in names:
        symbol = "Modern" + name + "Feature"
        has_definition = any(
            re.search(r'\b(?:object|class)\s+' + re.escape(symbol) + r'\b', source)
            for source in sources.values()
        )
        referenced_by_entry = re.search(r'\b' + re.escape(symbol) + r'\b', entry) is not None
        if (has_definition and referenced_by_entry) or name.lower() in grouped:
            status = "API102_SOURCE_WIRED_DEVICE_UNVERIFIED"
        elif has_definition:
            status = "API102_SOURCE_NOT_WIRED"
        else:
            status = "LEGACY_ONLY"
        features.append({
            "id": name,
            "source_state": status,
            "target_runtime_verified": False,
            "user_visible_behavior_verified": False,
        })
    counts = {
        "total": len(features),
        "api102_wired_in_source": sum(f["source_state"] == "API102_SOURCE_WIRED_DEVICE_UNVERIFIED" for f in features),
        "adapter_present_not_wired": sum(f["source_state"] == "API102_SOURCE_NOT_WIRED" for f in features),
        "legacy_only": sum(f["source_state"] == "LEGACY_ONLY" for f in features),
        "device_behavior_verified": 0,
    }
    if sum([counts["api102_wired_in_source"], counts["adapter_present_not_wired"], counts["legacy_only"]]) != counts["total"]:
        raise AssertionError("Feature ledger totals do not match the canonical registry")
    return {
        "schema": 1,
        "meaning": "Derived source-wiring inventory only. Neither CI nor a hooked class proves runtime compatibility.",
        "counts": counts,
        "features": features,
    }


def generate():
    sources = {
        str(path.relative_to(ROOT)): path.read_text(encoding="utf-8")
        for path in list(MODERN.glob("*.java")) + list(MODERN.glob("*.kt"))
    }
    if not ENTRY.is_file():
        raise ValueError("ModernXposedEntry.java is missing")
    return inspect(
        REGISTRY.read_text(encoding="utf-8"),
        ENTRY.read_text(encoding="utf-8"),
        sources,
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Print the full source-only feature matrix")
    parser.add_argument("--check", action="store_true", help="Validate registry, source wiring and evidence semantics")
    args = parser.parse_args()
    try:
        matrix = generate()
    except (OSError, UnicodeError, ValueError, AssertionError) as exc:
        print(f"API102 migration ledger FAILED: {exc}", file=sys.stderr)
        return 1
    if args.json:
        print(json.dumps(matrix, ensure_ascii=False, indent=2))
    else:
        counts = matrix["counts"]
        print(
            "API102 source-derived feature ledger: "
            f"{counts['api102_wired_in_source']} wired, "
            f"{counts['adapter_present_not_wired']} present but not wired, "
            f"{counts['legacy_only']} legacy-only, "
            f"{counts['total']} total; on-device verification NOT inferred."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
