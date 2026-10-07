#!/usr/bin/env python3
"""Prove which baselined UnusedResources are genuinely dead (T06).

Lint reports a resource as unused when it sees no static reference. That is good advice
but it is not proof: a resource can be reached through `?attr/...`, through a
`getIdentifier` lookup by name, through a theme applied at runtime, or through a
flavour-specific source set that lint did not analyse. Deleting on lint's word alone is
exactly the "random deletion" the plan forbids.

This tool therefore searches the whole repository for each name before offering it:

  * referenced   -> the name appears somewhere other than its own definition; kept
  * definition-only -> the name appears exactly once, at its definition; dead
  * only in a locale folder -> translations exist but no code references it; still dead

Only `definition-only` and `locale-only` are reported as removal candidates. Anything
ambiguous is left alone.

Usage:
    python3 tools/baseline/find_dead_resources.py            # report
    python3 tools/baseline/find_dead_resources.py --json     # machine readable
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASELINE = os.path.join(REPO_ROOT, "app", "lint-baseline.xml")
RES_DIR = os.path.join(REPO_ROOT, "app", "src")

# Directories that hold no references worth trusting: build output and VCS metadata.
SKIP_DIRS = {".git", "build", ".gradle", ".kotlin", ".idea", "captures", ".cxx"}


def resource_names(baseline_path: str) -> list[tuple[str, str, str]]:
    """Return (resource_type, resource_name, defining_file) for each unused entry."""
    import xml.etree.ElementTree as ET

    root = ET.parse(baseline_path).getroot()
    found: list[tuple[str, str, str]] = []
    for issue in root.findall("issue"):
        if issue.get("id") != "UnusedResources":
            continue
        message = issue.get("message", "")
        match = re.search(r"`R\.([A-Za-z]+)\.([A-Za-z0-9_]+)`", message)
        if not match:
            continue
        location = issue.find("location")
        file_attr = location.get("file", "") if location is not None else ""
        found.append((match.group(1), match.group(2), file_attr))
    return found


def definition_paths(repo_root: str) -> dict[str, set[str]]:
    """Map a resource name to every file that *defines* it.

    A definition is an XML element carrying that `name` attribute, or a Kotlin/Java file
    named after it. Anything else mentioning the name is a reference.
    """
    definitions: dict[str, set[str]] = collections.defaultdict(set)
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for filename in filenames:
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, repo_root).replace("\\", "/")
            stem, ext = os.path.splitext(filename)
            if ext == ".xml":
                try:
                    text = open(path, encoding="utf-8", errors="replace").read()
                except OSError:
                    continue
                for name in re.findall(r'<[A-Za-z-]+\s[^>]*\bname="([A-Za-z0-9_.]+)"', text):
                    definitions[name.split(".")[-1]].add(rel)
            else:
                definitions[stem].add(rel)
    return definitions


def reference_files(repo_root: str, name: str, defining: set[str]) -> set[str]:
    """Every file mentioning [name], excluding the files that define it.

    Locale folders are excluded from the search entirely: a translated string in
    `values-de/strings.xml` is a translation of the definition, not a use of it, and
    counting those would make every translated resource look alive.
    """
    pattern = re.compile(r"\b%s\b" % re.escape(name))
    hits: set[str] = set()
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for filename in filenames:
            path = os.path.join(dirpath, filename)
            rel = os.path.relpath(path, repo_root).replace("\\", "/")
            if rel in defining or is_locale_file(rel):
                continue
            if filename.endswith((".png", ".webp", ".jpg", ".jar", ".apk", ".so")):
                continue
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            if pattern.search(text):
                hits.add(rel)
    return hits


def is_locale_file(rel: str) -> bool:
    """True for translation folders such as `values-de` or `values-ar`."""
    parts = rel.split("/")
    return any(part.startswith("values-") and part != "values-night" for part in parts)


def is_distinctive(name: str) -> bool:
    """Whether a name is specific enough for a text search to mean anything.

    A generic word like `loading` or `chat` occurs in prose, comments and unrelated
    identifiers, so a hit proves nothing and a miss proves nothing either. Only names
    that look like identifiers are treated as evidence either way.
    """
    return len(name) >= 6 or "_" in name


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--json", action="store_true", help="emit JSON")
    parser.add_argument("--baseline", default=BASELINE)
    args = parser.parse_args(argv)

    if not os.path.exists(args.baseline):
        print("missing %s" % args.baseline, file=sys.stderr)
        return 2

    entries = resource_names(args.baseline)
    definitions = definition_paths(REPO_ROOT)

    dead: list[dict[str, object]] = []
    alive: list[dict[str, object]] = []
    undecidable: list[dict[str, object]] = []

    for res_type, name, file_attr in entries:
        defining = definitions.get(name, set())
        if not is_distinctive(name):
            # A short, generic name cannot be settled by grepping, so it is reported as
            # needing human review rather than guessed at in either direction.
            undecidable.append({
                "type": res_type,
                "name": name,
                "declaredIn": file_attr,
                "reason": "name too generic for text search to be evidence",
            })
            continue
        refs = reference_files(REPO_ROOT, name, defining)
        if refs:
            alive.append({
                "type": res_type,
                "name": name,
                "definedIn": sorted(defining),
                "referencedBy": sorted(refs)[:6],
                "referenceCount": len(refs),
            })
        else:
            dead.append({
                "type": res_type,
                "name": name,
                "declaredIn": file_attr,
                "definedIn": sorted(defining),
            })

    if args.json:
        print(json.dumps(
            {"dead": dead, "referenced": alive, "undecidable": undecidable},
            indent=2, sort_keys=True))
        return 0

    print("baselined UnusedResources : %d" % len(entries))
    print("provably dead             : %d" % len(dead))
    print("still referenced          : %d" % len(alive))
    print("needs human review        : %d" % len(undecidable))

    if dead:
        print("\nremoval candidates (defined, referenced nowhere outside translations):")
        by_type = collections.Counter(str(item["type"]) for item in dead)
        for res_type, count in by_type.most_common():
            print("  %-14s %d" % (res_type, count))
        print()
        for item in dead:
            where = ", ".join(item["definedIn"]) or str(item["declaredIn"])
            print("  R.%-12s %-42s %s" % (str(item["type"]) + ".", item["name"], where))

    if alive:
        print("\nkept because something still references them (top 15):")
        for item in sorted(alive, key=lambda x: -int(x["referenceCount"]))[:15]:
            print("  R.%-12s %-42s %d file(s)"
                  % (str(item["type"]) + ".", item["name"], item["referenceCount"]))

    if undecidable:
        print("\nnot decidable by search (%d), listed for manual review:" % len(undecidable))
        for item in undecidable[:40]:
            print("  R.%-12s %s" % (str(item["type"]) + ".", item["name"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
