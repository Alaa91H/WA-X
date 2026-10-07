#!/usr/bin/env python3
"""Self-test for tools/architecture/check_architecture.py.

The architecture law is the one gate in this repository whose failure mode is invisible: a rule
that matches nothing reports zero violations and looks exactly like a codebase that is clean. That
is not hypothetical. During A00 the two most important rules - a feature must not reach
Unobfuscator, a feature must not touch SharedPreferences - both reported **0** against a tree with
58 and 64 violating files, because their scopes named `xposed.features` while the real layers are
`xposed.features.general`, `.privacy` and so on, and an exact layer match resolves to nothing.

So the cases below cover three things:

1. Each rule fires on a tree built to violate it, and stops firing when the violation is removed.
2. The scopes actually resolve to files - asserted directly, because a scope that resolves to
   nothing is the bug that shipped.
3. The limits are read from the measurement, so a hand-typed limit cannot make a violation pass.

Usage:
    python3 tools/architecture/test_check_architecture.py
Exit codes: 0 every case behaved as expected, 1 a case did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load():
    spec = importlib.util.spec_from_file_location(
        "check_architecture", os.path.join(HERE, "check_architecture.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ARCH = load()


def write(path: str, content: str) -> None:
    """Write a fixture file *relative to the sandbox root*, never to the current directory.

    The first version of this test called `os.makedirs(os.path.dirname(path))` on a relative path,
    which meant the fixture tree was created inside the real repository: eight stray `Probe.kt`
    files appeared under `app/src/main/java/com/wax/module/`, in layers that do not exist, and the
    checker then measured the wrong tree entirely. A test that writes outside its sandbox does not
    merely risk a dirty working tree - it silently stops testing what it claims to test.
    """
    absolute = os.path.join(ROOT_SANDBOX[0], path)
    os.makedirs(os.path.dirname(absolute), exist_ok=True)
    with open(absolute, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


# Set once, immediately below, so `write` has a target without threading it through every call.
ROOT_SANDBOX: list[str] = [os.getcwd()]


def feature_file(pkg: str, body: str) -> tuple[str, str]:
    rel = "%s/com/wax/module/%s/Probe.kt" % ("app/src/main/java", pkg)
    text = "package com.wax.module.%s\n\n%s" % (pkg, body)
    return rel, text


# A minimal tree that violates every rule. Small enough to reason about, specific enough that a
# rule which silently matches nothing still reads as clean - which is the point.
def build_violating_tree(root: str) -> None:
    write(
        *feature_file(
            "xposed.features.general",
            "import com.wax.module.xposed.core.devkit.Unobfuscator\n"
            "import android.content.SharedPreferences\n"
            "object Probe { fun f(p: SharedPreferences) { Unobfuscator.findFirstMethodUsingStrings() } }\n",
        )
    )
    write(
        *feature_file(
            "xposed.features.privacy",
            "import com.wax.module.xposed.core.devkit.Unobfuscator\n"
            "object Probe2 { fun g() { Unobfuscator.findAllMethodsUsingStrings() } }\n",
        )
    )
    # Transitive only: this feature never names Unobfuscator, it reaches it through xposed.core.
    write(
        *feature_file(
            "xposed.core",
            "import com.wax.module.xposed.core.devkit.Unobfuscator\n"
            "object Core { fun h() { Unobfuscator.findFirstMethodUsingStrings() } }\n",
        )
    )
    write(
        *feature_file(
            "xposed.features.media",
            "import com.wax.module.xposed.core.Core\n"
            "object Probe3 { fun i() { Core.h() } }\n",
        )
    )
    # DexKit outside the resolver layers.
    write(
        *feature_file(
            "xposed.features.others",
            "import dexkit.DexKitBridge\n"
            "object Probe4 { val b = DexKitBridge.create(\"/x\") }\n",
        )
    )
    # A shared layer depending on the injected runtime.
    write(
        *feature_file(
            "utils",
            "import com.wax.module.xposed.core.Core\n"
            "object SharedHelper { fun j() { Core.h() } }\n",
        )
    )
    # Manager-side import of the framework API.
    write(
        *feature_file(
            "activities",
            "import de.robv.android.xposed.XposedBridge\n"
            "object ManagerThing { fun k() { XposedBridge.log(\"\") } }\n",
        )
    )
    write(
        *feature_file(
            "",
            "import com.wax.module.ModuleApplication\n"
            "object Root { fun l() { ModuleApplication.isXposedEnabled() } }\n",
        )
    )


def measure(root: str) -> dict[str, int]:
    return ARCH.measure_all(ARCH.build(root))


def run_check(root: str, law: dict) -> tuple[int, list[str], list[str]]:
    """Run the checker's decision logic against a tree and a law, without touching CI files."""
    values = measure(root)
    failures: list[str] = []
    notes: list[str] = []
    for key, rule in sorted(law["edges"].items()):
        measure_name = rule.get("measure")
        if measure_name not in values:
            failures.append("%s names an unknown measurement %r" % (key, measure_name))
            continue
        actual = values[measure_name]
        limit = rule.get("limit")
        if limit is None:
            failures.append("%s has no limit, so it cannot fail" % key)
        elif actual > limit:
            failures.append("%s regressed: %s is %d, limit %d" % (key, measure_name, actual, limit))
        else:
            notes.append("%s holds at %d" % (key, actual))
    return (1 if failures else 0), failures, notes


def case(name: str, ok: bool) -> bool:
    print(("[pass] " if ok else "[fail] ") + name)
    return ok


def main() -> int:
    failures = 0
    tree = tempfile.mkdtemp(prefix="a00-architecture-")
    ROOT_SANDBOX[0] = tree
    try:
        build_violating_tree(tree)
        values = measure(tree)

        # --- the regression that shipped: a scope that resolves to nothing -------------

        if not case(
            "the feature scope resolves to files rather than to nothing",
            len(ARCH.build(tree).files_in(ARCH.FEATURE_LAYERS)) > 0,
        ):
            failures += 1
        if not case(
            "a scope naming a parent layer matches its sub-layers",
            "xposed.features.general" in ARCH.build(tree).layers_matching(ARCH.FEATURE_LAYERS),
        ):
            failures += 1
        if not case(
            "a scope naming no real layer resolves to nothing, and that is detectable",
            ARCH.build(tree).files_in(("xposed.doesnotexist",)) == set(),
        ):
            failures += 1

        # --- each rule fires on a tree built to violate it ---------------------------

        expectations = {
            # Two features name Unobfuscator directly, one reaches it through xposed.core.
            # Transitive is what makes the third count.
            "feature_unobfuscator": 3,
            "feature_shared_preferences": 1,
            "ui_xposed_direct": 1,
            "dexkit_outside_resolver": 1,
            "shared_layer_imports_runtime": 1,
            "self_hook_references": 1,
        }
        for name, expected in expectations.items():
            if not case(
                "%s measures %d on the violating tree" % (name, expected), values.get(name) == expected
            ):
                print("  got %s" % values.get(name))
                failures += 1

        # --- and the check fails on it -------------------------------------------------

        law = {
            "edges": {
                key: {"measure": rule["measure"], "limit": 0, "owner": "x", "removalPhase": "y"}
                for key, rule in ARCH.LAW.items()
            }
        }
        code, reported, _notes = run_check(tree, law)
        if not case(
            "every rule fails when its limit is 0 and the tree violates it",
            code == 1 and len(reported) == len(ARCH.LAW),
        ):
            print("  %s" % reported)
            failures += 1

        # --- and stops failing when the violation is removed --------------------------
        #
        # A second sandbox rather than deleting files out of the first. Mutating the tree in
        # place to reach the "clean" state is how the first version of this test ended up
        # leaving rules measuring non-zero on a tree it believed was empty.

        clean_root = tempfile.mkdtemp(prefix="a00-clean-")
        ROOT_SANDBOX[0] = clean_root
        try:
            write(*feature_file("model", "data class Clean(val name: String)\n"))
            write(*feature_file("xposed.core.devkit", "object Unobfuscator\n"))
            clean = measure(clean_root)

            if not case(
                "a clean tree measures zero for every rule",
                all(clean.get(name) == 0 for name in expectations),
            ):
                print("  %s" % {k: clean.get(k) for k in expectations})
                failures += 1

            code, reported, _notes = run_check(clean_root, law)
            if not case(
                "the check passes on a clean tree with zero limits",
                code == 0 and not reported,
            ):
                print("  %s" % reported)
                failures += 1
        finally:
            ROOT_SANDBOX[0] = tree
            shutil.rmtree(clean_root, ignore_errors=True)
    finally:
        shutil.rmtree(tree, ignore_errors=True)

    # --- the real law, against the real tree -------------------------------------------

    try:
        with open(os.path.join(ROOT, ARCH.LAW_PATH), "r", encoding="utf-8") as handle:
            real_law = json.load(handle)
    except OSError as error:
        print("[fail] cannot read %s: %s" % (ARCH.LAW_PATH, error))
        return 1

    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(io.StringIO()):
        code = ARCH.main(["--check"])
    if code == 0:
        print("[pass] the real repository is within the architecture law")
    else:
        print("[fail] the real repository violates the architecture law:")
        print(buffer.getvalue())
        failures += 1

    # Every recorded limit has to have been measured from the tree, not typed. If a limit is
    # larger than today's measurement the rule is stricter than reality, which is safe; if it is
    # smaller, the gate is lying and must not be trusted.
    real_values = measure(ROOT)
    stale = [
        key
        for key, rule in real_law["edges"].items()
        if rule.get("measure") in real_values and rule.get("limit") != real_values[rule["measure"]]
    ]
    if not case(
        "every limit in the law file equals the current measurement",
        not stale,
    ):
        print("  stale: %s" % stale)
        print("  measured: %s" % {k: real_values.get(k) for k in stale})
        failures += 1

    missing_owner = [
        key
        for key, rule in real_law["edges"].items()
        if not rule.get("owner") or not rule.get("removalPhase")
    ]
    if not case("every rule names an owner and a removal phase", not missing_owner):
        print("  %s" % missing_owner)
        failures += 1

    if failures:
        print("%d architecture-law cases failed" % failures, file=sys.stderr)
        return 1
    print("all architecture-law cases behaved correctly")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())