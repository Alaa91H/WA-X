#!/usr/bin/env python3
"""Self-test for tools/quality/check_lsposed_contract.py.

The checker's value is that it fails when the loader contract breaks, so the test is a
mutation suite: build a tree that satisfies the contract, break exactly one thing, and assert
the checker reports that check and exits 1. A checker that only ever passes would be worse than
no checker, and this is what stops it from silently grading itself.

Every mutation below corresponds to a way the module has actually broken, or can break, in a way
that is invisible until it ships. The version ones exist because the previous revision of the
checker grepped for the coordinate string and therefore passed while the pin could be dropped,
left dynamic, or pointed at an artifact that does not exist.

Usage:
    python3 tools/quality/test_check_lsposed_contract.py
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
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load_checker():
    spec = importlib.util.spec_from_file_location(
        "check_lsposed_contract", os.path.join(HERE, "check_lsposed_contract.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECKER = load_checker()

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <meta-data android:name="xposedmodule" android:value="__MODULE__" />
        <meta-data android:name="xposeddescription" android:value="fixture" />
        <meta-data android:name="xposedminversion" android:value="__MINVERSION__" />
        <meta-data android:name="xposedsharedprefs" android:value="__SHAREDPREFS__" />
        <meta-data android:name="xposedscope" android:resource="__SCOPE__" />
    </application>
</manifest>
"""

ARRAYS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="scope">
__ITEMS__    </string-array>
</resources>
"""

ENTRY = "com.example.hook.EntryPoint\n"

SOURCE = "package com.example.hook\n\nobject EntryPoint\n"

# The keep rule without which a release build removes the entry point, and with it the whole
# injected runtime: R8 does not read assets/xposed_init, so this rule is the only thing that
# holds the class against the shrinker.
PROGUARD = """# fixture rules
-keep class com.example.hook.EntryPoint { *; }
-keep class com.example.R { *; }
-keepclassmembers class com.example.R$* { public static <fields>; }
"""

# A version catalog that satisfies the contract: the coordinate is declared, the version is
# resolved through a `version.ref` rather than pattern-matched, and the value is the one the
# published artifact set actually contains.
CATALOG = """[versions]
__REFKEY__ = "__REFVALUE__"

[libraries]
__COORD__
"""

BUILD = """plugins { id("com.android.application") }

dependencies {
    __CONFIG__(libs.__ALIAS__)
}
"""

# The legacy types the fixture sources import. The artifact check compares these against the
# real jar, so a fixture that claims a type the artifact does not publish must fail on
# `api.surface` and not be quietly accepted because the checker only looked at the version.
LEGACY_IMPORTS = (
    "de.robv.android.xposed.XposedBridge",
    "de.robv.android.xposed.XposedHelpers",
    "de.robv.android.xposed.XSharedPreferences",
)

# Every type api-82 publishes, used to build an artifact-shaped stand-in for the negative
# fixtures that need to prove a *missing* class is caught.
FULL_SURFACE = (
    "de/robv/android/xposed/XposedBridge.class",
    "de/robv/android/xposed/XposedHelpers.class",
    "de/robv/android/xposed/XSharedPreferences.class",
)


def write(path: str, content: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(content)


def catalog_with(
    coordinate: str | None = 'libxposed-legacy = { group = "de.robv.android.xposed", name = "api", version.ref = "xposed-legacy" }',
    refkey: str = "xposed-legacy",
    refvalue: str = "82",
) -> str:
    return CATALOG.replace("__COORD__", coordinate or "").replace(
        "__REFKEY__", refkey
    ).replace("__REFVALUE__", refvalue)


def build_with(configuration: str = "compileOnly", alias: str = "libxposed.legacy") -> str:
    return BUILD.replace("__CONFIG__", configuration).replace("__ALIAS__", alias)


def fixture(root: str, **overrides: object) -> None:
    """Write a tree that satisfies the contract, with named parts replaced when asked."""
    manifest = MANIFEST
    manifest = manifest.replace("__MODULE__", str(overrides.get("module", "true")))
    manifest = manifest.replace("__MINVERSION__", str(overrides.get("minversion", "93")))
    manifest = manifest.replace("__SHAREDPREFS__", str(overrides.get("sharedprefs", "true")))
    manifest = manifest.replace("__SCOPE__", str(overrides.get("scope", "@array/scope")))
    write(os.path.join(root, "app/src/main/AndroidManifest.xml"), manifest)

    items = overrides.get("scope_items", list(CHECKER.REQUIRED_SCOPE_ENTRIES) + ["com.android.providers.settings"])
    body = "".join("        <item>%s</item>\n" % item for item in items)  # type: ignore[union-attr]
    arrays = ARRAYS.replace("__ITEMS__", body)
    if overrides.get("scope_array_name"):
        arrays = arrays.replace('name="scope"', 'name="%s"' % overrides["scope_array_name"])
    write(os.path.join(root, "app/src/main/res/values/arrays.xml"), arrays)

    if overrides.get("entry") is not False:
        write(os.path.join(root, "app/src/main/assets/xposed_init"), str(overrides.get("entry", ENTRY)))

    if overrides.get("proguard") is not False:
        write(os.path.join(root, CHECKER.PROGUARD_RULES), str(overrides.get("proguard", PROGUARD)))
    if overrides.get("source") is not False:
        source = str(overrides.get("source", SOURCE))
        if overrides.get("legacy_imports") is not False:
            imports = overrides.get("legacy_imports", LEGACY_IMPORTS)
            source = source.replace(
                "object EntryPoint",
                "\n".join("import %s" % name for name in imports) + "\n\nobject EntryPoint",  # type: ignore[union-attr]
            )
        write(os.path.join(root, "app/src/main/java/com/example/hook/EntryPoint.kt"), source)

    if overrides.get("catalog") is not False:
        write(
            os.path.join(root, "gradle/libs.versions.toml"),
            str(overrides.get("catalog", catalog_with())),
        )

    if overrides.get("build") is not False:
        write(
            os.path.join(root, "app/build.gradle.kts"),
            str(overrides.get("build", build_with())),
        )

    for relative in overrides.get("extra_files", []):  # type: ignore[union-attr]
        write(os.path.join(root, str(relative)), "x\n")


def fake_jar(root: str, entries: tuple[str, ...] = FULL_SURFACE) -> str:
    """A stand-in jar with the same entry layout the real artifact has.

    Building it rather than shipping a fixture jar keeps the surface the checker compares against
    visible in this file, so a real API change shows up as a diff here instead of as a binary
    that has to be re-cut by hand.
    """
    import zipfile

    path = os.path.join(root, "api-fixture.jar")
    with zipfile.ZipFile(path, "w") as archive:
        for entry in entries:
            archive.writestr(entry, b"\xca\xfe\xba\xbe")
    return path


def run(root: str, *extra: str) -> tuple[int, list[dict], list[str]]:
    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(io.StringIO()):
        code = CHECKER.main(["--root", root, "--format", "json", *extra])
    output = buffer.getvalue().strip()
    parsed = json.loads(output) if output else {}
    return code, parsed.get("violations", []), parsed.get("notes", [])


def case(name: str, expected_check: str | None, **overrides: object) -> bool:
    root = tempfile.mkdtemp(prefix="lsposed-contract-")
    try:
        fixture(root, **overrides)
        extra = overrides.pop("_argv", ())
        code, violations, _notes = run(root, *extra)  # type: ignore[arg-type]
        checks = {violation["check"] for violation in violations}
        if expected_check is None:
            if code != 0 or violations:
                print("[fail] %s: expected a clean run, got exit %d and %s" % (name, code, violations))
                return False
            return True
        if code != 1:
            print("[fail] %s: expected exit 1, got %d (%s)" % (name, code, violations))
            return False
        if expected_check not in checks:
            print("[fail] %s: expected check %r, got %s" % (name, expected_check, sorted(checks)))
            return False
        return True
    finally:
        shutil.rmtree(root, ignore_errors=True)


def artifact_case(name: str, expect_pass: bool, entries: tuple[str, ...], **overrides: object) -> bool:
    """Run with ``--verify-artifact`` against a stand-in jar."""
    root = tempfile.mkdtemp(prefix="lsposed-contract-artifact-")
    try:
        fixture(root, **overrides)
        jar = fake_jar(root, entries)
        code, violations, _notes = run(root, "--verify-artifact", "--artifact", jar)
        checks = {violation["check"] for violation in violations}
        if expect_pass:
            if code != 0:
                print("[fail] %s: expected the surface check to pass, got %s" % (name, violations))
                return False
            return True
        if code != 1:
            print("[fail] %s: expected exit 1, got %d" % (name, code))
            return False
        if "api.surface" not in checks:
            print("[fail] %s: expected check 'api.surface', got %s" % (name, sorted(checks)))
            return False
        return True
    finally:
        shutil.rmtree(root, ignore_errors=True)


def mixed_loader_case() -> bool:
    """A tree carrying both loader contracts at once.

    This is the failure that has no loud symptom: LSPosed prefers the modern API the moment the
    ``META-INF/xposed`` files are present, so ``assets/xposed_init`` is never read and the module
    loads nothing at all while still appearing enabled in the manager.
    """
    root = tempfile.mkdtemp(prefix="lsposed-contract-mixed-")
    try:
        fixture(root, extra_files=[
            "app/src/main/resources/META-INF/xposed/java_init.list",
            "app/src/main/resources/META-INF/xposed/module.prop",
            "app/src/main/resources/META-INF/xposed/scope.list",
        ])
        code, violations, _notes = run(root)
        checks = {violation["check"] for violation in violations}
        # Both halves of the diagnosis have to be reported: the shadowing files *and* the fact
        # that the legacy entry point is still declared, because the pair together is the bug.
        messages = " ".join(violation["message"] for violation in violations)
        if code != 1:
            print("[fail] mixed loader contract: expected exit 1, got %d" % code)
            return False
        if "modern.meta" not in checks:
            print("[fail] mixed loader contract: expected 'modern.meta', got %s" % sorted(checks))
            return False
        if "entry.file" in checks:
            print("[fail] mixed loader contract: the legacy entry still exists, so entry.file should be clean")
            return False
        if "java_init.list" not in messages:
            print("[fail] mixed loader contract: the shadowing file was not named: %s" % messages)
            return False
        return True
    finally:
        shutil.rmtree(root, ignore_errors=True)


def main() -> int:
    cases = [
        ("a contract-satisfying tree is clean", None, {}),
        ("a missing xposedmodule flag is caught", "manifest.xposedmodule", {"module": "false"}),
        ("xposedmodule must be exactly true", "manifest.xposedmodule", {"module": "TRUEish"}),
        ("a missing xposedminversion is caught", "manifest.xposedminversion", {"minversion": ""}),
        # 82 is the newest published *artifact*, but it is not a valid runtime API level for this
        # module, and treating the two numbering spaces as one is the mistake this gate exists to
        # stop. Lowering the manifest would silently change XSharedPreferences semantics.
        ("the artifact version used as an API level is caught", "manifest.xposedminversion", {"minversion": "82"}),
        ("a lower runtime API level is caught", "manifest.xposedminversion", {"minversion": "91"}),
        ("a higher legacy API level is caught", "manifest.xposedminversion", {"minversion": "94"}),
        ("a non-numeric API level is caught", "manifest.xposedminversion", {"minversion": "latest"}),
        ("a modern-API level is caught", "manifest.xposedminversion", {"minversion": "102"}),
        ("a missing xposedsharedprefs is caught", "manifest.xposedsharedprefs", {"sharedprefs": ""}),
        ("a missing xposedscope is caught", "manifest.xposedscope", {"scope": ""}),
        ("a scope resource that is not an array is caught", "manifest.xposedscope", {"scope": "@string/scope"}),
        (
            "a scope pointing at a missing array is caught",
            "manifest.xposedscope",
            {"scope": "@array/not_there"},
        ),
        ("an empty scope array is caught", "manifest.xposedscope", {"scope_items": []}),
        (
            "a scope without the framework entry is caught",
            "manifest.xposedscope",
            {"scope_items": ["com.whatsapp", "com.whatsapp.w4b"]},
        ),
        # The two target apps are required, not optional. Business is a separate application id
        # with its own process and its own settings, so dropping it from the scope leaves it
        # unhooked while the module still reads as enabled, which is indistinguishable from a
        # WhatsApp-side bug to the person hitting it.
        ("a scope that drops WhatsApp Business is caught", "manifest.xposedscope", {"scope_items": ["android", "com.whatsapp"]}),
        ("a scope that drops WhatsApp is caught", "manifest.xposedscope", {"scope_items": ["android", "com.whatsapp.w4b"]}),
        ("a scope with only the framework is caught", "manifest.xposedscope", {"scope_items": ["android"]}),
        ("a missing entry point file is caught", "entry.file", {"entry": False}),
        ("an empty entry point file is caught", "entry.single", {"entry": "\n# comment\n"}),
        ("two entry points are caught", "entry.single", {"entry": "a.B\nc.D\n"}),
        ("a missing entry class is caught", "entry.class", {"source": False}),
        (
            "a source file without the entry class is caught",
            "entry.class",
            {"source": "package com.example.hook\n\nobject SomethingElse\n"},
        ),
        (
            "an entry class renamed out from under xposed_init is caught",
            "entry.class",
            {"entry": "com.example.hook.Renamed\n"},
        ),
        (
            "a modern-API META-INF/xposed directory is caught",
            "modern.meta",
            {"extra_files": ["app/src/main/resources/META-INF/xposed/module.prop"]},
        ),
        (
            "a modern-API scope.list alone is caught",
            "modern.meta",
            {"extra_files": ["app/src/main/resources/META-INF/xposed/scope.list"]},
        ),
        ("a missing legacy API dependency is caught", "api.pinned", {"catalog": catalog_with(coordinate='something = { group = "x", name = "y", version.ref = "xposed-legacy" }')}),
        ("a missing version catalog is caught", "api.pinned", {"catalog": False}),
        # The four cases the old checker could not see. It matched the coordinate string and
        # never read the version, so all of these reported "contract intact".
        (
            "a dependency with no version at all is caught",
            "api.pinned",
            {"catalog": catalog_with(coordinate='libxposed-legacy = { group = "de.robv.android.xposed", name = "api" }')},
        ),
        (
            "a version.ref with no matching key is caught",
            "api.pinned",
            {"catalog": catalog_with(coordinate='libxposed-legacy = { group = "de.robv.android.xposed", name = "api", version.ref = "renamed" }')},
        ),
        (
            "a dynamic version is caught",
            "api.pinned",
            {"catalog": catalog_with(refvalue="82.+", refkey="xposed-legacy")},
        ),
        (
            "a snapshot version is caught",
            "api.pinned",
            {"catalog": catalog_with(refvalue="82-SNAPSHOT", refkey="xposed-legacy")},
        ),
        (
            "an unpublished artifact version is caught",
            "api.published",
            {"catalog": catalog_with(refvalue="83")},
        ),
        (
            "a mistyped artifact version is caught",
            "api.published",
            {"catalog": catalog_with(refvalue="8.2")},
        ),
        (
            "an artifact version that predates the required surface is caught",
            "api.expected",
            {"catalog": catalog_with(refvalue="81")},
        ),
        (
            "a pin nothing consumes is caught",
            "api.wired",
            {"build": build_with(alias="libxposed.somethingElse")},
        ),
        (
            "the legacy stubs on a packaging configuration are caught",
            "api.wired",
            {"build": build_with(configuration="implementation")},
        ),
        (
            "a missing module build file is caught",
            "api.wired",
            {"build": False},
        ),
    ]

    failed = 0
    for name, expected_check, overrides in cases:
        if case(name, expected_check, **overrides):
            print("[pass] %s" % name)
        else:
            failed += 1

    # A literal `version = "82"` is as valid as a `version.ref`, and it has to be accepted for
    # the ref-resolution branch to mean anything.
    if case(
        "a literal version is accepted",
        None,
        catalog=catalog_with(coordinate='libxposed-legacy = { group = "de.robv.android.xposed", name = "api", version = "82" }'),
    ):
        print("[pass] a literal version is accepted")
    else:
        failed += 1

    if artifact_case(
        "the artifact publishing every imported type passes",
        True,
        FULL_SURFACE,
    ):
        print("[pass] the artifact publishing every imported type passes")
    else:
        failed += 1

    if artifact_case(
        "an artifact missing an imported type is caught",
        False,
        tuple(entry for entry in FULL_SURFACE if "XSharedPreferences" not in entry),
    ):
        print("[pass] an artifact missing an imported type is caught")
    else:
        failed += 1

    if mixed_loader_case():
        print("[pass] a tree carrying both loader contracts at once is caught")
    else:
        failed += 1

    # The keep rule is the only thing that holds the entry point against R8, which does not read
    # assets/xposed_init. Every case here is a way to lose it while the source, the manifest, the
    # declared API level and the scope array all still look perfect - which is exactly the state
    # every release APK shipped in until it was measured.
    keep_cases = [
        (
            "a keep rule covering the entry point is required",
            "entry.keep",
            {"proguard": "# no rules at all\n-keep class com.example.other.Thing { *; }\n"},
        ),
        (
            "a rule that keeps a different class does not cover the entry point",
            "entry.keep",
            {"proguard": "-keep class com.example.hook.OtherEntry { *; }\n"},
        ),
        (
            "a keepclassmembers rule does not hold the class itself",
            "entry.keep",
            {"proguard": "-keepclassmembers class com.example.hook.EntryPoint { *; }\n"},
        ),
        (
            "a keepnames rule does not stop the shrinker removing the class",
            "entry.keep",
            {"proguard": "-keepnames class com.example.hook.EntryPoint\n"},
        ),
        (
            "a keep rule that allows shrinking does not keep the class",
            "entry.keep",
            {"proguard": "-keep,allowshrinking class com.example.hook.EntryPoint { *; }\n"},
        ),
        (
            "the keep rule has to be a directive, not a comment",
            "entry.keep",
            {"proguard": "# -keep class com.example.hook.EntryPoint { *; }\n-keep class com.example.R { *; }\n"},
        ),
        (
            "a missing ProGuard file is a violation, not a skip",
            "entry.keep",
            {"proguard": False},
        ),
        (
            "a single-star wildcard does not cross a package separator",
            "entry.keep",
            {"proguard": "-keep class com.example.* { *; }\n"},
        ),
    ]
    for name, expected_check, overrides in keep_cases:
        if case(name, expected_check, **overrides):
            print("[pass] %s" % name)
        else:
            failed += 1

    # And the rule that does hold it must be accepted, or the check above proves nothing.
    if case(
        "a keep rule naming the entry point directly is accepted",
        None,
        proguard="-keep class com.example.hook.EntryPoint { *; }\n",
    ):
        print("[pass] a keep rule naming the entry point directly is accepted")
    else:
        failed += 1

    # A keep rule with an unrelated modifier still keeps: allowobfuscation does not let the
    # shrinker remove the class, and refusing it would make the check fire on correct rules.
    if case(
        "a keep rule with an unrelated modifier is accepted",
        None,
        proguard="-keep,allowobfuscation class com.example.hook.EntryPoint { *; }\n",
    ):
        print("[pass] a keep rule with an unrelated modifier is accepted")
    else:
        failed += 1

    # A double star does cross separators, so a package-level rule covers the entry point. This is
    # the positive counterpart of the single-star case above: together they prove the wildcard
    # handling is ProGuard's, not a substring match that would accept anything.
    if case(
        "a double-star wildcard covers the entry point",
        None,
        proguard="-keep class com.example.** { *; }\n",
    ):
        print("[pass] a double-star wildcard covers the entry point")
    else:
        failed += 1

    # --verify-artifact must fail, not skip, when there is nothing to inspect. A gate that
    # degrades to "nothing to say" is how the version blind spot got here in the first place.
    root = tempfile.mkdtemp(prefix="lsposed-contract-absent-")
    try:
        fixture(root)
        code, violations, _notes = run(root, "--verify-artifact", "--artifact", os.path.join(root, "absent.jar"))
        if code == 1 and "api.surface" in {violation["check"] for violation in violations}:
            print("[pass] --verify-artifact fails when the named artifact is absent")
        else:
            print("[fail] --verify-artifact must fail on an absent artifact, got %d %s" % (code, violations))
            failed += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)

    # The real repository has to satisfy the contract this checker enforces.
    code, violations, _notes = run(REPO_ROOT)
    if code == 0 and not violations:
        print("[pass] the real repository satisfies the LSPosed loader contract")
    else:
        print("[fail] the real repository violates the contract: %s" % violations)
        failed += 1

    total = len(cases) + len(keep_cases) + 11
    if failed:
        print("%d of %d contract cases failed" % (failed, total), file=sys.stderr)
        return 1
    print("all %d contract cases behaved correctly" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())