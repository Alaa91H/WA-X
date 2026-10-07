#!/usr/bin/env python3
"""Self-test for tools/modernization/inventory_runtime_surface.py.

An inventory is trusted for one reason: later phases read a number off it and decide whether
their work finished. That only means anything if the number moves when the code moves. So this
test does what the loader-contract test does, in the other direction: it takes a tree the tool
already measured, changes exactly one thing, and asserts the number changed.

The cases that matter here are the ones where a category could quietly become decorative - a
regex that matches nothing, a scope filter that excludes everything, two patterns that report the
same occurrences twice. Each of those produces an inventory that reads plausible and measures
nothing, and none of them would ever show up as a failing gate.

Usage:
    python3 tools/modernization/test_inventory_runtime_surface.py
Exit codes: 0 every case behaved as expected, 1 a case did not.
"""

from __future__ import annotations

import importlib.util
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load():
    spec = importlib.util.spec_from_file_location(
        "inventory_runtime_surface", os.path.join(HERE, "inventory_runtime_surface.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


INVENTORY = load()

RUNTIME_FILE = "app/src/main/java/com/wax/module/xposed/Probe.kt"
MANAGER_FILE = "app/src/main/java/com/wax/module/ui/Probe.kt"


def write(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def occurrence(build, category: str, root: str) -> int:
    return build(root)["categories"][category]["occurrences"]  # type: ignore[index,return-value]


def case(name: str, ok: bool) -> bool:
    if ok:
        print("[pass] %s" % name)
        return True
    print("[fail] %s" % name)
    return False


def main() -> int:
    build = INVENTORY.build_inventory
    failures = 0

    root = tempfile.mkdtemp(prefix="runtime-surface-")
    try:
        # An empty runtime file is the baseline for the "does the category fire at all" cases.
        write(os.path.join(root, RUNTIME_FILE), "package com.wax.module.xposed\n\nobject Probe\n")

        def with_body(body: str, path: str = RUNTIME_FILE) -> int:
            write(os.path.join(root, path), "package com.wax.module.xposed\n\nobject Probe\n" + body)
            return occurrence(build, "resource_injection", root)

        baseline = with_body("")
        added = with_body(
            "\nfun install() {\n"
            "    val modRes = XModuleResources.createInstance(modulePath, res)\n"
            "}\n"
        )
        if not case("adding XModuleResources moves the resource-injection count", added == baseline + 1):
            failures += 1
        if not case("an empty body reports zero", baseline == 0):
            failures += 1

        # Duplicate-pattern detection. If two patterns in one category match the same text the
        # category reports every occurrence twice and the number reads like a trend that never
        # happened.
        write(
            os.path.join(root, RUNTIME_FILE),
            "package com.wax.module.xposed\n\nobject Probe\n\nfun f() {\n"
            "    try { g() } catch (_: Exception) {}\n}\n\nfun g() {}\n",
        )
        swallowed = occurrence(build, "silent_catch", root)
        if not case(
            "one empty catch is counted once, not once per matching pattern",
            swallowed == 2,  # the empty body plus the discarded binding, and no more
        ):
            failures += 1

        # The runtime/Manager scope split is the other thing that can quietly rot. If the filter
        # stopped matching, the injected-process count would silently absorb Manager code.
        write(
            os.path.join(root, MANAGER_FILE),
            "package com.wax.module.ui\n\nobject Probe\n\n"
            "fun f() {\n    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)\n}\n",
        )
        runtime_side = occurrence(build, "runtime_preference_reads", root)
        manager_side = occurrence(build, "manager_preference_reads", root)
        if not case(
            "a Manager preference read is counted on the Manager side, not the runtime side",
            manager_side >= 1 and runtime_side == 0,
        ):
            failures += 1

        write(
            os.path.join(root, RUNTIME_FILE),
            "package com.wax.module.xposed\n\nobject Probe\n\n"
            "fun f() {\n    val prefs = XSharedPreferences(\"a\", \"b\")\n}\n",
        )
        runtime_side = occurrence(build, "runtime_preference_reads", root)
        if not case(
            "an XSharedPreferences read is counted on the runtime side",
            runtime_side >= 1,
        ):
            failures += 1

        # Every category must fire on something the tree already contains. A category that
        # reports zero everywhere is either dead weight or a broken regex, and both look the same
        # from the outside.
        real = build(REPO_ROOT)
        silent = [
            name
            for name, entry in real["categories"].items()  # type: ignore[union-attr]
            if entry["occurrences"] == 0
        ]
        if not case("no category in the real tree is dead", not silent):
            failures += 1

        # And every category must name the phase that owns it, or the number has nobody to move it.
        unowned = [
            name
            for name, entry in real["categories"].items()  # type: ignore[union-attr]
            if not entry["owner"] or not entry["target"]
        ]
        if not case("every category names an owner and a target", not unowned):
            failures += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)

    # The committed inventory has to match the tree, or the numbers everyone reads are fiction.
    committed_path = os.path.join(REPO_ROOT, INVENTORY.INVENTORY_PATH)
    try:
        with open(committed_path, "r", encoding="utf-8") as handle:
            committed = handle.read()
    except OSError:
        print("[fail] %s is missing; run with --write" % INVENTORY.INVENTORY_PATH)
        return 1
    if committed != json.dumps(build(REPO_ROOT), indent=2, sort_keys=True) + "\n":
        drift = INVENTORY.describe_drift(
            committed, json.dumps(build(REPO_ROOT), indent=2, sort_keys=True) + "\n"
        )
        print("[fail] the committed inventory no longer matches the tree:")
        for line in drift[:20]:
            print("  %s" % line)
        failures += 1
    else:
        print("[pass] the committed inventory matches the tree")

    if failures:
        print("%d runtime-surface inventory cases failed" % failures, file=sys.stderr)
        return 1
    print("all runtime-surface inventory cases behaved correctly")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())