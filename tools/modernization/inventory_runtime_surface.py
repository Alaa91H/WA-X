#!/usr/bin/env python3
"""Inventory the runtime surface that M01-M13 have to replace, and prove the inventory moves.

The modernization program replaces several things that are currently invisible. None of them
announce themselves: a reflective static-final resource write fails silently because it is
wrapped in an empty ``catch``, a world-readable preference file works on every device nobody
tests, and the "module enabled" signal is a self-hook that returns a constant. A phase cannot
size its own work from a paragraph, and it cannot tell whether it finished from a paragraph
either.

So the surface is counted. Each category below is one of the things the program has to
eliminate, reduce or make explicit, with the phase that owns it. ``--check`` compares a fresh
inventory against the committed one and fails on any difference, which gives two properties the
program needs:

* the baseline is **reproducible** - same tree, same numbers, on any machine;
* the inventory is **able to fail** - adding a single ``XModuleResources.createInstance`` call
  changes the output, so a later phase can trust a zero here.

What it deliberately does not do is assert a target count. Each category has a different correct
end state and several of them are not zero (direct hooks stay, they are how the module works;
only the *self-hook* activation signal and the world-readable fallback have to go). Targets
belong to the phase that owns them, recorded in ``TARGETS`` below, and asserting them here
would turn a measurement into a gate that fires on work nobody has done yet.

Usage:
    python3 tools/modernization/inventory_runtime_surface.py --write
    python3 tools/modernization/inventory_runtime_surface.py --check
    python3 tools/modernization/inventory_runtime_surface.py --markdown
    python3 tools/modernization/test_inventory_runtime_surface.py
Exit codes: 0 in sync, 1 the inventory drifted, 2 the tree could not be read.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

INVENTORY_PATH = "docs/modernization/baseline/m00-runtime-surface.json"
REPORT_PATH = "docs/modernization/RUNTIME_SURFACE.md"

SOURCE_ROOTS = ("app/src/main/java",)
SOURCE_SUFFIXES = (".kt", ".java")

# The import regex. Group 1 is the fully qualified type, so member imports
# (`XposedBridge.log`) and nested imports (`XC_LoadPackage.LoadPackageParam`) are captured whole
# and normalised by the caller.
LEGACY_IMPORT = re.compile(r"^\s*import\s+(de\.robv\.android\.xposed\.[A-Za-z0-9_.$]+)", re.MULTILINE)

# What "the injected process" means as a set of files. Several categories are only meaningful for
# code that runs inside a hooked app: a singleton in the Manager process is normal, and a
# singleton in the injected process is the thing A02 has to contain. Without this split the
# inventory reported a number that no phase in this program is going to move.
RUNTIME_SCOPE = (
    r"^app/src/main/java/com/wax/module/"
    r"(xposed/|ModuleEntryPoint|TargetRuntime|TargetPackageRegistry|ModuleApplication)"
)


def top_level_type(name: str) -> str:
    """Reduce an imported name to the type that actually exists.

    ``de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam`` is one class, not
    three, and counting the nested segments as separate types would make the inventory read like
    there is more legacy surface than there is.
    """
    parts = name.split(".")
    # package is de.robv.android.xposed, optionally followed by one sub-package segment
    for end in range(len(parts), 3, -1):
        tail = parts[4:end]
        if tail and tail[0][:1].isupper():
            return ".".join(parts[:4] + [tail[0]])
    return ".".join(parts[:5]) if len(parts) > 5 else ".".join(parts)


# Each category is one thing the program owns. `patterns` are matched against each source file's
# text; a category reports the count of matching files and the files themselves, because "three
# files use XSharedPreferences" and "these three files" are different amounts of work.
CATEGORIES: dict[str, dict[str, object]] = {
    "legacy_api_surface": {
        "title": "Legacy Xposed API surface",
        "owner": "#318 M04-M06",
        "why": (
            "The whole legacy loader contract. M06 moves entry to modern libxposed; M13 removes "
            "what is left."
        ),
        "target": "Only the modern API surface remains; these counts reach zero at M13.",
        "patterns": [(LEGACY_IMPORT, "import")],
        "group_by_import": True,
    },
    "runtime_preference_reads": {
        "title": "Preference reads inside the injected process",
        "owner": "#318 M02 / #333 A08",
        "why": (
            "The runtime reading the manager's preference file from inside another app's process. "
            "That is the entire manager/runtime coupling in one line, and it is what the typed "
            "settings snapshot replaces. Scoped to the injected runtime: the Manager process reads "
            "the same file legitimately, and is counted separately below."
        ),
        "target": "Replaced by an immutable SettingsSnapshot delivered over the versioned bridge.",
        "include": RUNTIME_SCOPE,
        "patterns": [
            (re.compile(r"\bXSharedPreferences\b"), "XSharedPreferences"),
            (re.compile(r"\bgetPref\(\)"), "getPref()"),
            (re.compile(r"\bPreferenceManager\b"), "PreferenceManager"),
        ],
    },
    "manager_preference_reads": {
        "title": "Preference reads inside the Manager process",
        "owner": "#333 A09",
        "why": (
            "The Manager reading its own preferences. This is not a defect and it is not this "
            "program's target - it is counted because it is the *other half* of the same file, "
            "and a plan that only looks at the runtime half cannot tell which half a change moved."
        ),
        "target": "Not a target of this program. Listed so the asymmetry stays visible.",
        "exclude": RUNTIME_SCOPE,
        "patterns": [
            (re.compile(r"\bPreferenceManager\b"), "PreferenceManager"),
            (re.compile(r"\bgetSharedPreferences\("), "getSharedPreferences()"),
        ],
    },
    "world_readable_prefs": {
        "title": "World-readable preference compatibility",
        "owner": "#318 M02 / #333 A16",
        "why": (
            "Each of these three is a way the module makes its own preferences readable by "
            "another process. Together they are what lets the hooked process read settings at all, "
            "and together they are the program's most exposed surface: a world-readable file in "
            "the module's data directory can be read by any other app on the device."
        ),
        "target": "No world-readable preference path remains. Everything becomes zero.",
        "patterns": [
            (re.compile(r"MODE_WORLD_READABLE|MODE_WORLD_WRITEABLE"), "MODE_WORLD_*"),
            (re.compile(r"\bmakeWorldReadable\(\)"), "makeWorldReadable()"),
            (re.compile(r'"getDefaultSharedPreferencesMode"'), "getDefaultSharedPreferencesMode hook"),
            (re.compile(r'"checkMode"'), "ContextImpl.checkMode hook"),
        ],
    },
    "resource_injection": {
        "title": "Legacy resource injection",
        "owner": "#318 M07",
        "why": (
            "XModuleResources plus a hand-written walk over R.* declared fields. The whole "
            "mechanism depends on the generated resource classes having writable int fields, "
            "which is exactly what newer Android resource handling stops guaranteeing."
        ),
        "target": "Replaced by ResourceBridge with Android 17-safe resources.",
        "patterns": [
            (re.compile(r"\bXModuleResources\b"), "XModuleResources"),
            (re.compile(r"\bXResources\b"), "XResources"),
            (re.compile(r"\bIXposedHookInitPackageResources\b"), "IXposedHookInitPackageResources"),
            (re.compile(r"\bres\.addResource\("), "res.addResource"),
            (re.compile(r"DrawableLoader"), "XResources.DrawableLoader"),
        ],
    },
    "static_final_mutation": {
        "title": "Reflective mutation of generated/static fields",
        "owner": "#318 M07",
        "why": (
            "Writing resource IDs into `R.string`'s static finals through reflection. This is the "
            "part that breaks first on a new Android release, and it breaks quietly."
        ),
        "target": "No reflective static-final resource mutation remains.",
        "patterns": [
            (re.compile(r"\bsetStaticObjectField\("), "XposedHelpers.setStaticObjectField"),
            (re.compile(r"\bsetStaticIntField\("), "XposedHelpers.setStaticIntField"),
            (re.compile(r"\bsetStaticBooleanField\("), "XposedHelpers.setStaticBooleanField"),
            # The single-field variants are what the resource injection actually uses; leaving
            # them out reported 1 occurrence where the tree has 10.
            (re.compile(r"\bsetObjectField\("), "XposedHelpers.setObjectField"),
            (re.compile(r"\bsetIntField\("), "XposedHelpers.setIntField"),
            (re.compile(r"\bsetBooleanField\("), "XposedHelpers.setBooleanField"),
            (re.compile(r"\bfield\.set\(\s*null\s*,"), "write to a static field"),
            (re.compile(r"\bField\.set\(\s*null\s*,"), "write to a static field (Field)"),
            (re.compile(r"isAccessible\s*=\s*true"), "isAccessible = true"),
        ],
    },
    "dexkit_direct": {
        "title": "Direct DexKit use",
        "owner": "#318 M08 / #333 A04",
        "why": (
            "Every resolver that calls DexKit itself, with no engine and no cache fingerprint "
            "behind it. This is the count M08 has to move behind ResolverEngine, and the count "
            "that decides whether a resolver failure can take the runtime down with it."
        ),
        "target": "Zero calls from features; all DexKit access confined to the resolver engine.",
        "patterns": [
            (re.compile(r"\bdexkit\."), "dexkit.*"),
            (re.compile(r"\bDexKit\("), "DexKit(...)"),
            (re.compile(r"\baddEntry\("), "addEntry()"),
            (re.compile(r"\bDexBackedDexFile\b"), "DexBackedDexFile"),
        ],
    },
    "direct_hooks": {
        "title": "Direct legacy hook installation",
        "owner": "#318 M03 / #333 A05",
        "why": (
            "Hooks installed straight against XposedBridge/XposedHelpers with no owning layer. "
            "The count does not go to zero - hooks are how the module works - but every one of "
            "them currently bypasses the failure isolation M03 introduces."
        ),
        "target": "Stays non-zero; every hook gains an owner that can report its own failure.",
        "patterns": [
            (re.compile(r"XposedBridge\.hookMethod\("), "XposedBridge.hookMethod"),
            (re.compile(r"XposedBridge\.hookAllMethods\("), "XposedBridge.hookAllMethods"),
            (re.compile(r"XposedBridge\.hookAllConstructors\("), "XposedBridge.hookAllConstructors"),
            (re.compile(r"XposedHelpers\.findAndHookMethod\("), "XposedHelpers.findAndHookMethod"),
            (re.compile(r"XposedHelpers\.findAndHookConstructor\("), "XposedHelpers.findAndHookConstructor"),
            (re.compile(r"XposedHelpers\.hookMethod\("), "XposedHelpers.hookMethod"),
        ],
    },
    "self_hook_activation": {
        "title": "Self-hook as the activation signal",
        "owner": "#318 M01-M02 / #333 A01",
        "why": (
            "`isXposedEnabled` is replaced with a constant that returns true inside the module's "
            "own process. That makes the manager report the module as active for the reason that "
            "the module is running, which is circular: the one question the user asks is the one "
            "question the module answers about itself. It is also why a DexKit failure, a resolver "
            "failure or a feature failure can surface as 'Xposed is disabled'."
        ),
        "target": "Zero. Activation is observed from the framework, never from a self-hook.",
        "patterns": [
            (re.compile(r'"isXposedEnabled"'), "hooks isXposedEnabled"),
            (re.compile(r"\bisXposedEnabled\b"), "references isXposedEnabled"),
        ],
    },
    "runtime_global_state": {
        "title": "Global mutable state inside the injected process",
        "owner": "#333 A02",
        "why": (
            "`@JvmStatic var`, `lateinit var` and module-level nullable `var` in code that runs "
            "inside the hooked process. It has no owner, no initialisation order and no way to be "
            "reset between target processes, so it is where cross-account and WhatsApp/Business "
            "settings leakage would come from."
        ),
        "target": "Contained in RuntimeGraph with an explicit lifetime.",
        # Scoped to the injected runtime on purpose. The Manager process has hundreds of
        # singletons and reducing those is a different piece of work under A09/A10; counting
        # them here would put a number here that no phase in this program is going to move.
        "include": r"^app/src/main/java/com/wax/module/(xposed/|ModuleEntryPoint|TargetRuntime|ModuleApplication)",
        "patterns": [
            (re.compile(r"@JvmStatic\s*\n?\s*(?:private\s+|public\s+|internal\s+)?var\b"), "@JvmStatic var"),
            # `val` cannot hold null, so a nullable module-level holder is always a `var`.
            (re.compile(r"^\s*(?:private\s+|public\s+|internal\s+)?var\s+\w+\s*:\s*\w[\w.<>?]*\?\s*=\s*null", re.MULTILINE), "module-level nullable var"),
            (re.compile(r"\blateinit\s+var\b"), "lateinit var"),
            (re.compile(r"\bobject\s+\w+"), "object singleton"),
        ],
    },
    "silent_catch": {
        "title": "Failures swallowed without reporting",
        "owner": "#318 M03 / #333 A05",
        "why": (
            "An empty catch turns a real failure into a missing feature. This is not a style "
            "preference: it is the mechanism by which a broken resolver or a failed resource "
            "injection becomes indistinguishable from a module that is switched off."
        ),
        "target": "Every catch reports through the runtime health store.",
        # One pattern, not two. `\s*` spans newlines, so a separate "multiline" pattern matched
        # every occurrence the single-line one already had and the category reported double.
        "patterns": [
            (re.compile(r"catch\s*\(\s*[^)]*\)\s*\{\s*\}"), "empty catch body"),
            (re.compile(r"catch\s*\(\s*_\s*:\s*\w[\w.]*\s*\)"), "catch with discarded binding"),
        ],
    },
}


def read(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


def source_files(root: str) -> list[str]:
    found: list[str] = []
    for source_root in SOURCE_ROOTS:
        for directory, _dirs, files in os.walk(os.path.join(root, source_root)):
            for name in sorted(files):
                if name.endswith(SOURCE_SUFFIXES):
                    absolute = os.path.join(directory, name)
                    found.append(os.path.relpath(absolute, root).replace("\\", "/"))
    return sorted(found)


def build_inventory(root: str) -> dict[str, object]:
    files = source_files(root)
    if not files:
        print("error: no sources under %s" % ", ".join(SOURCE_ROOTS), file=sys.stderr)
        raise SystemExit(2)

    contents: dict[str, str] = {}
    for relative in files:
        text = read(os.path.join(root, relative))
        if text is None:
            print("error: cannot read %s" % relative, file=sys.stderr)
            raise SystemExit(2)
        contents[relative] = text

    result: dict[str, object] = {}
    for key, spec in CATEGORIES.items():
        matches: dict[str, int] = {}
        hits: dict[str, list[str]] = {}
        imports: set[str] = set()
        scope = re.compile(spec["include"]) if spec.get("include") else None
        outside = re.compile(spec["exclude"]) if spec.get("exclude") else None

        for relative, text in contents.items():
            if scope is not None and scope.search(relative) is None:
                continue
            if outside is not None and outside.search(relative) is not None:
                continue
            for pattern, label in spec["patterns"]:  # type: ignore[union-attr]
                found = len(pattern.findall(text))
                if not found:
                    continue
                matches[label] = matches.get(label, 0) + found
                hits.setdefault(label, []).append(relative)
                if spec.get("group_by_import") and pattern is LEGACY_IMPORT:
                    imports.update(top_level_type(name) for name in pattern.findall(text))

        entry = {
            "title": spec["title"],
            "owner": spec["owner"],
            "why": spec["why"],
            "target": spec["target"],
            "occurrences": sum(matches.values()),
            "files": sorted({item for values in hits.values() for item in values}),
            "by_pattern": dict(sorted(matches.items())),
            "by_file": {label: sorted(set(values)) for label, values in sorted(hits.items())},
        }
        if imports:
            entry["types"] = sorted(imports)
        result[key] = entry

    return {"schema": "wax.m00.runtime-surface/1", "source_files": len(files), "categories": result}


def flatten(value: object, prefix: str = "") -> dict[str, object]:
    if isinstance(value, dict):
        out: dict[str, object] = {}
        for key in sorted(value):
            out.update(flatten(value[key], "%s/%s" % (prefix, key)))
        return out
    return {prefix or "/": value}


def describe_drift(expected: str, actual: str) -> list[str]:
    try:
        before = flatten(json.loads(expected))
        after = flatten(json.loads(actual))
    except json.JSONDecodeError:
        return ["the committed inventory is not valid JSON"]
    lines = []
    for path in sorted(set(before) | set(after)):
        left, right = before.get(path, "<absent>"), after.get(path, "<absent>")
        if left != right:
            lines.append("%s: %s -> %s" % (path, left, right))
    return lines


def render_markdown(inventory: dict[str, object]) -> str:
    lines = [
        "# Runtime surface inventory",
        "",
        "Generated by `tools/modernization/inventory_runtime_surface.py`. Do not edit by hand;",
        "run the tool with `--write` and commit the result in the same change as whatever moved",
        "the numbers.",
        "",
        "| Category | Owner | Occurrences | Files |",
        "| --- | --- | --- | --- |",
    ]
    categories = inventory["categories"]  # type: ignore[index]
    for key, entry in categories.items():  # type: ignore[union-attr]
        lines.append(
            "| [%s](#%s) | %s | %d | %d |"
            % (entry["title"], key, entry["owner"], entry["occurrences"], len(entry["files"]))
        )
    lines.append("")

    for key, entry in categories.items():  # type: ignore[union-attr]
        lines.extend(["## %s" % entry["title"], "", "`%s`" % key, ""])
        lines.append("**Why this is counted:** %s" % entry["why"])
        lines.append("")
        lines.append("**End state:** %s" % entry["target"])
        lines.append("")
        lines.append("**Current:** %d occurrences across %d files."
                     % (entry["occurrences"], len(entry["files"])))
        lines.append("")
        if entry["by_pattern"]:
            lines.append("| Pattern | Occurrences | Files |")
            lines.append("| --- | --- | --- |")
            for label, count in entry["by_pattern"].items():  # type: ignore[union-attr]
                lines.append("| `%s` | %d | %d |" % (label, count, len(entry["by_file"][label])))
            lines.append("")
        if entry.get("types"):
            lines.append("Legacy types in use:")
            lines.append("")
            for name in entry["types"]:  # type: ignore[union-attr]
                lines.append("- `%s`" % name)
            lines.append("")
        if entry["files"]:
            lines.append("<details><summary>Files</summary>")
            lines.append("")
            for path in entry["files"]:  # type: ignore[union-attr]
                lines.append("- `%s`" % path)
            lines.append("")
            lines.append("</details>")
            lines.append("")

    return "\n".join(lines).rstrip() + "\n"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true")
    group.add_argument("--check", action="store_true")
    group.add_argument("--markdown", action="store_true", help="print the report and exit")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args(argv)

    inventory = build_inventory(REPO_ROOT)
    rendered = json.dumps(inventory, indent=2, sort_keys=True) + "\n"

    if args.format == "json":
        print(rendered, end="")
        return 0

    if args.markdown:
        print(render_markdown(inventory), end="")
        return 0

    if args.write:
        json_path = os.path.join(REPO_ROOT, INVENTORY_PATH)
        md_path = os.path.join(REPO_ROOT, REPORT_PATH)
        os.makedirs(os.path.dirname(json_path), exist_ok=True)
        os.makedirs(os.path.dirname(md_path), exist_ok=True)
        with open(json_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(rendered)
        with open(md_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(render_markdown(inventory))
        print("wrote %s and %s" % (INVENTORY_PATH, REPORT_PATH), file=sys.stderr)
        return 0

    path = os.path.join(REPO_ROOT, INVENTORY_PATH)
    existing = read(path)
    if existing is None:
        print("error: %s does not exist; run with --write" % INVENTORY_PATH, file=sys.stderr)
        return 1
    if existing != rendered:
        print(
            "error: %s no longer matches the tree. These counts are what the later phases size "
            "their work against, so a change here is either work that landed or a regression:" % INVENTORY_PATH,
            file=sys.stderr,
        )
        for line in describe_drift(existing, rendered):
            print("  %s" % line, file=sys.stderr)
        return 1

    total = sum(
        entry["occurrences"] for entry in inventory["categories"].values()  # type: ignore[union-attr]
    )
    print("runtime surface inventory is in sync with the tree (%d occurrences tracked)" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))