#!/usr/bin/env python3
"""Machine-check the contract that keeps this module loadable by LSPosed.

The module is a **legacy Xposed API** module: LSPosed recognises it because the manifest
declares ``xposedmodule`` and ``xposedminversion``, and it finds the entry point by reading
``assets/xposed_init``. Every part of that is easy to break by accident, and one of the ways is
actively silent:

* Removing a meta-data entry makes LSPosed list the module but never call it.
* Adding the modern ``META-INF/xposed`` files (``java_init.list``, ``module.prop``,
  ``scope.list``) does not add anything: LSPosed then ignores ``assets/xposed_init``
  entirely and loads nothing at all. The manifest carries this warning in a comment, and this
  checker is what makes the comment enforced instead of remembered.
* Renaming or moving the entry class leaves ``assets/xposed_init`` pointing at a class that
  does not exist, which fails only on a device, at boot, in the hooked process.
* Moving the compile-time pin off the legacy API artifact compiles against a surface the
  module does not implement at runtime.

Two numbers, two different numbering spaces
--------------------------------------------
The manifest's ``xposedminversion`` and the version catalog's ``xposed-legacy`` look like they
disagree, and an earlier revision of this checker did not check either one. They are not the
same kind of number and they are both correct:

``xposedminversion`` (93)
    A **runtime Xposed API level**, read by LSPosed when it loads the module and compared
    against what ``XposedBridge.getXposedVersion()`` reports. 93 is not a preference: it is the
    level at which LSPosed switches ``XSharedPreferences`` to the new module-owned
    implementation (see the LSPosed "New XSharedPreferences" wiki). This module depends on that
    behaviour, so 93 is the value it must declare.

``xposed-legacy`` (82)
    A **Maven artifact version** for the compile-only stubs ``de.robv.android.xposed:api``.
    That line stopped at 82: ``maven-metadata.xml`` on ``api.xposed.info`` lists exactly
    ``53``, ``81`` and ``82``, last republished in 2016. There is no artifact version 93 and
    never will be.

So "make them equal" is not available, and lowering the manifest to 82 would silently disable
the ``XSharedPreferences`` semantics this module is built on. What *is* a defect is that
nothing tied the two together and that this checker only grepped for the coordinate string, so
it reported "intact" while the pin could have been dropped, mistyped or left dynamic. The
checks below parse the catalog, resolve ``version.ref``, and hold the resolved artifact to the
published set, to the version this module's class surface was verified against, and to the
manifest's runtime level.

Usage:
    python3 tools/quality/check_lsposed_contract.py
    python3 tools/quality/check_lsposed_contract.py --root . --format json
    python3 tools/quality/check_lsposed_contract.py --verify-artifact
Exit codes: 0 contract intact, 1 a violation, 2 the invocation or the tree could not be read.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - fail closed on an interpreter that cannot parse TOML
    print(
        "error: Python 3.11+ is required so gradle/libs.versions.toml is parsed as TOML rather "
        "than pattern-matched, which is what this checker exists to stop trusting",
        file=sys.stderr,
    )
    raise SystemExit(2)

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = "{%s}" % ANDROID_NS

MANIFEST = "app/src/main/AndroidManifest.xml"
ARRAYS = "app/src/main/res/values/arrays.xml"
ENTRY_FILE = "app/src/main/assets/xposed_init"
SOURCE_ROOT = "app/src/main/java"
SOURCE_DIRS = (
    "app/src/main/java",
    "app/src/test/java",
    "app/src/androidTest/java",
)
VERSION_CATALOG = "gradle/libs.versions.toml"
MODULE_BUILD = "app/build.gradle.kts"

# The runtime Xposed API level this module is written against, and the only value it may
# declare. 93 is the level at which LSPosed provides the new XSharedPreferences implementation
# this module relies on. Both directions are wrong: lower understates the requirement and
# changes preference semantics at runtime, higher asks LSPosed for the modern API.
REQUIRED_LEGACY_API_LEVEL = 93

# A sanity ceiling: the highest legacy API level that exists. Anything above it is the modern
# API, which this module deliberately does not use.
MAX_LEGACY_API = 100

# Every package that has to be in the recommended scope, with the reason it is there.
#
# This list was one entry long for a long time, which meant the checker was satisfied by a scope
# array that had quietly stopped containing one of the two apps the module exists to hook. A
# missing scope entry is silent: the module stays enabled, the manager shows no scope problem,
# and the features simply never run on the affected app. Business support in particular is not
# optional, and losing it would look like a WhatsApp-side bug to every Business user.
REQUIRED_SCOPE_ENTRIES = {
    "android": (
        "the settings bridge and the package-visibility bypass both run inside the system "
        "framework, so the module needs hooks that do not belong to either target app"
    ),
    "com.whatsapp": "it is the primary target",
    "com.whatsapp.w4b": (
        "WhatsApp Business is a separately signed application id with its own process and its own "
        "settings. A scope array without it leaves Business unhooked while the module still "
        "reports itself as enabled"
    ),
}

# The compile-time surface has to match the loader that will call it.
LEGACY_API_COORDINATE = "de.robv.android.xposed"
LEGACY_API_ARTIFACT = "api"

# Every version of the legacy API artifact that has ever been published, read from
# https://api.xposed.info/de/robv/android/xposed/api/maven-metadata.xml. A pin outside this set
# is a typo or a coordinate that resolves to nothing, and it would only be discovered when the
# build tried to download it.
LEGACY_API_PUBLISHED_VERSIONS = ("53", "81", "82")
LEGACY_API_PUBLISHED_VERSIONS_SOURCE = (
    "https://api.xposed.info/de/robv/android/xposed/api/maven-metadata.xml"
)

# The one published version whose class surface this module was verified against: 82 carries
# XSharedPreferences, SELinuxHelper and IXposedHookZygoteInit, all of which this module imports.
# 81 predates XSharedPreferences, so pinning it would compile-fail rather than mislead.
EXPECTED_LEGACY_API_ARTIFACT_VERSION = "82"

# The catalog alias the module build has to consume. The pin only means something if the
# module actually compiles against it, so its absence is checked rather than assumed.
LEGACY_API_ALIAS = "libs.libxposed.legacy"
LEGACY_API_CONFIGURATION = "compileOnly"


class Report:
    """Collects violations so every problem is reported in one run."""

    def __init__(self) -> None:
        self.violations: list[tuple[str, str]] = []
        self.notes: list[str] = []

    def fail(self, check: str, message: str) -> None:
        self.violations.append((check, message))

    def note(self, message: str) -> None:
        """Record something a reader should see that is not a violation.

        Used for checks that could not run. A skipped check is reported as skipped rather than
        quietly folded into a pass, because "the checker said nothing" and "the checker had
        nothing to say" are different states and only one of them is a green gate.
        """
        self.notes.append(message)

    def emit(self, fmt: str) -> int:
        if fmt == "json":
            print(
                json.dumps(
                    {
                        "violations": [
                            {"check": check, "message": message} for check, message in self.violations
                        ],
                        "notes": self.notes,
                    },
                    indent=2,
                )
            )
        else:
            for check, message in self.violations:
                print("error: [%s] %s" % (check, message), file=sys.stderr)
            for note in self.notes:
                print("note: %s" % note, file=sys.stderr)
            if not self.violations:
                print(
                    "LSPosed loader contract intact: legacy API level %d declared, single entry "
                    "point, scope declared, %s:%s pinned at %s, no modern-API files shadowing "
                    "the legacy entry point."
                    % (
                        REQUIRED_LEGACY_API_LEVEL,
                        LEGACY_API_COORDINATE,
                        LEGACY_API_ARTIFACT,
                        EXPECTED_LEGACY_API_ARTIFACT_VERSION,
                    )
                )
        return 1 if self.violations else 0


def read(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


def meta_data(root: ET.Element) -> dict[str, list[tuple[str | None, str | None]]]:
    """Map every ``<meta-data>`` name to its (value, resource) pairs."""
    found: dict[str, list[tuple[str | None, str | None]]] = {}
    for element in root.iter("meta-data"):
        name = element.get(ANDROID + "name")
        if name is None:
            continue
        found.setdefault(name, []).append(
            (element.get(ANDROID + "value"), element.get(ANDROID + "resource"))
        )
    return found


def string_array(content: str, name: str) -> list[str] | None:
    """The items of ``<string-array name="...">``, or None when it is absent.

    The tag may carry other attributes (``translatable``, ``tools:ignore``), so the name is
    matched anywhere in the opening tag rather than immediately after the element name.
    """
    pattern = re.compile(
        r'<string-array\b[^>]*\bname="%s"[^>]*>(.*?)</string-array>' % re.escape(name),
        re.DOTALL,
    )
    match = pattern.search(content)
    if match is None:
        return None
    return [
        item.strip()
        for item in re.findall(r"<item>(.*?)</item>", match.group(1), re.DOTALL)
        if item.strip()
    ]


def check_manifest(root: str, report: Report) -> dict[str, list[tuple[str | None, str | None]]]:
    path = os.path.join(root, MANIFEST)
    content = read(path)
    if content is None:
        print("error: cannot read %s" % path, file=sys.stderr)
        raise SystemExit(2)
    try:
        tree = ET.fromstring(content)
    except ET.ParseError as error:
        print("error: %s is not valid XML: %s" % (path, error), file=sys.stderr)
        raise SystemExit(2)

    metadata = meta_data(tree)

    module = metadata.get("xposedmodule")
    if not module:
        report.fail("manifest.xposedmodule", "the module does not declare the xposedmodule flag")
    elif not any((value or "").strip().lower() == "true" for value, _ in module):
        report.fail(
            "manifest.xposedmodule",
            "xposedmodule must be exactly 'true', otherwise LSPosed does not list the module",
        )

    if not metadata.get("xposeddescription"):
        report.fail(
            "manifest.xposeddescription",
            "xposeddescription is missing, so LSPosed shows the module with no description",
        )

    versions = metadata.get("xposedminversion")
    if not versions:
        report.fail(
            "manifest.xposedminversion",
            "xposedminversion is missing, so LSPosed does not treat this as a legacy module "
            "and never reads assets/xposed_init",
        )
    else:
        raw = (versions[0][0] or "").strip()
        try:
            level = int(raw)
        except ValueError:
            report.fail(
                "manifest.xposedminversion",
                "xposedminversion %r is not an integer Xposed API level" % raw,
            )
        else:
            # Exact, not a range. Every level this module could legitimately declare other than
            # 93 is either lower (changes XSharedPreferences semantics at runtime) or higher
            # within the legacy range (claims to need an API level whose behaviour this module
            # has never been run against). A range check would pass all of those.
            if level != REQUIRED_LEGACY_API_LEVEL and level <= MAX_LEGACY_API:
                report.fail(
                    "manifest.xposedminversion",
                    "xposedminversion %d is not %d. %d is the level at which LSPosed switches "
                    "XSharedPreferences to the module-owned implementation this module reads its "
                    "settings through; lower levels change preference semantics at runtime rather "
                    "than failing loudly, and higher levels in the legacy range claim an API this "
                    "module has never been run against"
                    % (level, REQUIRED_LEGACY_API_LEVEL, REQUIRED_LEGACY_API_LEVEL),
                )
            if level > MAX_LEGACY_API:
                report.fail(
                    "manifest.xposedminversion",
                    "xposedminversion %d is above the legacy API range, which would tell LSPosed "
                    "to load this module through the modern API" % level,
                )

    sharedprefs = metadata.get("xposedsharedprefs")
    if not sharedprefs:
        report.fail(
            "manifest.xposedsharedprefs",
            "xposedsharedprefs is missing, so the hooked process cannot read the module's "
            "preferences through XSharedPreferences",
        )
    elif not any((value or "").strip().lower() == "true" for value, _ in sharedprefs):
        report.fail(
            "manifest.xposedsharedprefs",
            "xposedsharedprefs must be exactly 'true'; LSPosed reads it as a boolean and "
            "anything else leaves the hooked process without the module's preferences",
        )

    arrays = read(os.path.join(root, ARRAYS))
    scopes = metadata.get("xposedscope")
    if not scopes:
        report.fail(
            "manifest.xposedscope",
            "xposedscope is missing, so LSPosed cannot pre-select the scope the module needs",
        )
    elif arrays is None:
        report.fail("manifest.xposedscope", "xposedscope points at an array but arrays.xml is unreadable")
    else:
        resource = scopes[0][1] or ""
        match = re.fullmatch(r"@array/(\w+)", resource.strip())
        if match is None:
            report.fail(
                "manifest.xposedscope",
                "xposedscope resource %r is not an @array reference" % resource,
            )
        else:
            items = string_array(arrays, match.group(1))
            if items is None:
                report.fail(
                    "manifest.xposedscope",
                    "xposedscope references @array/%s, which does not exist in arrays.xml"
                    % match.group(1),
                )
            elif not items:
                report.fail("manifest.xposedscope", "the scope array is empty")
            else:
                for entry, reason in REQUIRED_SCOPE_ENTRIES.items():
                    if entry not in items:
                        report.fail(
                            "manifest.xposedscope",
                            "the scope array is missing %r, because %s" % (entry, reason),
                        )

    return metadata


def check_entry_point(root: str, report: Report) -> None:
    path = os.path.join(root, ENTRY_FILE)
    content = read(path)
    if content is None:
        report.fail(
            "entry.file",
            "%s is missing, so LSPosed has no class to load" % ENTRY_FILE,
        )
        return

    lines = [line.strip() for line in content.splitlines() if line.strip() and not line.strip().startswith("#")]
    if not lines:
        report.fail("entry.single", "%s names no class" % ENTRY_FILE)
        return
    if len(lines) > 1:
        report.fail(
            "entry.single",
            "%s names %d classes (%s); the legacy loader expects exactly one entry point"
            % (ENTRY_FILE, len(lines), ", ".join(lines)),
        )
        return

    entry = lines[0]
    if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+", entry):
        report.fail("entry.class", "%r is not a fully qualified class name" % entry)
        return

    package, _, simple_name = entry.rpartition(".")
    relative = package.replace(".", os.sep)
    for extension in (".kt", ".java"):
        candidate = os.path.join(root, SOURCE_ROOT, relative, simple_name + extension)
        source = read(candidate)
        if source is None:
            continue
        if not re.search(r"\b(object|class)\s+%s\b" % re.escape(simple_name), source):
            report.fail(
                "entry.class",
                "%s does not declare a class or object named %s" % (candidate, simple_name),
            )
        return

    report.fail(
        "entry.class",
        "%s names %s, but no %s.kt or %s.java exists under %s"
        % (ENTRY_FILE, entry, simple_name, simple_name, SOURCE_ROOT),
    )


def check_modern_api_files(root: str, report: Report) -> None:
    """Refuse the files that would silently replace the legacy entry point."""
    main = os.path.join(root, "app", "src", "main")
    for directory, _dirs, files in os.walk(main):
        parts = os.path.relpath(directory, main).replace("\\", "/").split("/")
        if "META-INF" not in parts:
            continue
        if "xposed" not in parts:
            continue
        relative = os.path.relpath(directory, root).replace("\\", "/")
        report.fail(
            "modern.meta",
            "%s exists: LSPosed loads the modern API when these files are present and then "
            "ignores assets/xposed_init, so the module would load nothing" % relative,
        )
        for name in files:
            report.fail("modern.meta", "found %s/%s" % (relative, name))


def legacy_imports(root: str) -> set[str]:
    """Every ``de.robv.android.xposed`` type the module sources import.

    Import lines are matched textually rather than resolved, because the point is to compare the
    surface the module *asks for* against the artifact that has to supply it. Nested types
    (``XposedBridge.log``, ``XC_LoadPackage.LoadPackageParam``) are returned as written; the
    caller reduces them to whichever prefix the artifact actually publishes.
    """
    found: set[str] = set()
    pattern = re.compile(r"^\s*import\s+(de\.robv\.android\.xposed\.[A-Za-z0-9_.$]+)", re.MULTILINE)
    for source_dir in SOURCE_DIRS:
        for directory, _dirs, files in os.walk(os.path.join(root, source_dir)):
            for name in files:
                if not name.endswith((".kt", ".java")):
                    continue
                content = read(os.path.join(directory, name))
                if content is None:
                    continue
                found.update(pattern.findall(content))
    return found


def class_entry(type_name: str) -> str:
    """The jar entry path that publishes ``type_name`` as a class."""
    return type_name.replace(".", "/") + ".class"


def resolve_to_published(imported: str, published: set[str]) -> str | None:
    """Reduce an imported name to the type the artifact publishes, or None if it publishes none.

    Longest prefix first, so a top-level type inside a sub-package (``services.BaseService``) is
    matched before a bare package name would be.
    """
    parts = imported.split(".")
    for end in range(len(parts), 4, -1):
        candidate = ".".join(parts[:end])
        if class_entry(candidate) in published:
            return candidate
    return None


def locate_artifact(root: str, version: str, explicit: str | None) -> str | None:
    """Find the resolved legacy API jar without assuming a Gradle cache layout.

    ``--artifact`` is how CI points at the exact file it resolved; the cache search is the local
    convenience. Returning ``None`` is a real answer, not a fallback: the caller reports it as a
    skipped check instead of a pass.
    """
    if explicit:
        return explicit if os.path.isfile(explicit) else None

    candidates = sorted(
        glob.glob(os.path.join(root, "app", "libs", "*%s*.jar" % re.escape(version)))
    )
    candidates = [item for item in candidates if "sources" not in os.path.basename(item)]
    if candidates:
        return candidates[0]

    for cache in (
        os.environ.get("GRADLE_USER_HOME"),
        os.path.join(os.path.expanduser("~"), ".gradle"),
    ):
        if not cache:
            continue
        base = os.path.join(
            cache,
            "caches",
            "modules-2",
            "files-2.1",
            # files-2.1 is laid out as <group>/<artifact>/<version>, and the group component
            # is the literal group id with its dots, not one directory per segment.
            LEGACY_API_COORDINATE,
            LEGACY_API_ARTIFACT,
            version,
        )
        if not os.path.isdir(base):
            continue
        found = [
            item
            for item in sorted(glob.glob(os.path.join(base, "*", "*.jar")))
            if "sources" not in os.path.basename(item)
        ]
        if found:
            return found[0]
    return None


def check_api_dependency(
    root: str, report: Report, verify_artifact: bool = False, artifact: str | None = None
) -> None:
    """Validate the compile-time pin, not merely that a coordinate string exists.

    The previous revision matched ``group = "de.robv.android.xposed", name = "api"`` anywhere in
    the catalog, so it passed while the version could be missing, dynamic, mistyped or pointed
    at an unpublished artifact. This parses the catalog, resolves ``version.ref``, and holds the
    result against the published set, the verified version, and the module's actual usage.
    """
    path = os.path.join(root, VERSION_CATALOG)
    content = read(path)
    if content is None:
        report.fail("api.pinned", "%s is unreadable, so the loader API cannot be confirmed" % VERSION_CATALOG)
        return
    try:
        catalog = tomllib.loads(content)
    except tomllib.TOMLDecodeError as error:
        report.fail("api.pinned", "%s is not valid TOML: %s" % (VERSION_CATALOG, error))
        return

    libraries = catalog.get("libraries", {})
    versions = catalog.get("versions", {})

    # Locate the alias by coordinate. An alias is the addressable key in the libraries table, and
    # the module build refers to it, so the alias name matters as well as the coordinate.
    alias = None
    entry = None
    for name, value in sorted(libraries.items()):
        if not isinstance(value, dict):
            continue
        if value.get("module") == "%s:%s" % (LEGACY_API_COORDINATE, LEGACY_API_ARTIFACT) or (
            value.get("group") == LEGACY_API_COORDINATE and value.get("name") == LEGACY_API_ARTIFACT
        ):
            alias, entry = name, value
            break

    if entry is None:
        report.fail(
            "api.pinned",
            "no %s:%s dependency is declared, so the module does not compile against the "
            "loader API it is written for"
            % (LEGACY_API_COORDINATE, LEGACY_API_ARTIFACT),
        )
        return

    # Resolve version.ref -> [versions], or take a literal version. Anything that is neither is
    # a pin that can move underneath a release build.
    #
    # `version.ref = "x"` and `{ version = { ref = "x" } }` are the same TOML and both arrive as
    # a nested table, so `version` has to be unwrapped before it can be told apart from the
    # literal `version = "1.2.3"` form. Reading it as a plain string is what made an earlier
    # revision of this checker report the ref itself as the version.
    declared = entry.get("version")
    ref = None
    if isinstance(declared, dict):
        ref = declared.get("ref")
    elif isinstance(declared, str):
        version = declared
    elif declared is None and "version.ref" in entry:  # non-canonical but legal TOML
        ref = entry["version.ref"]

    if ref is not None:
        if ref not in versions:
            report.fail(
                "api.pinned",
                "%s pins %s through version.ref %r, which is not defined in [versions], so the "
                "legacy API version is unresolvable" % (alias, LEGACY_API_ARTIFACT, ref),
            )
            return
        version = versions[ref]
    elif declared is None:
        report.fail(
            "api.pinned",
            "%s declares %s:%s with no version and no version.ref, so the loader API is not "
            "pinned and a resolution could pick any version"
            % (alias, LEGACY_API_COORDINATE, LEGACY_API_ARTIFACT),
        )
        return

    version = str(version).strip()
    # A snapshot is a moving target even though it is syntactically a version, so it is rejected
    # here as dynamic rather than later as unpublished: the two have different fixes, and
    # reporting a snapshot as "does not exist" sends the reader looking in the wrong place.
    if re.search(r"(?i)snapshot|latest\.|\+", version):
        report.fail(
            "api.pinned",
            "%s resolves the legacy API to %r, which is not a fixed version. A moving version "
            "means a release can compile against a different loader API than the one this gate "
            "verified" % (alias, version),
        )
        return

    if not re.fullmatch(r"\d+(?:\.\d+)*(?:-[0-9A-Za-z.\-]+)?", version):
        report.fail(
            "api.pinned",
            "%s resolves the legacy API to %r, which is not a fixed version. A dynamic version "
            "means a release can compile against a different loader API than the one this gate "
            "verified" % (alias, version),
        )
        return

    if version not in LEGACY_API_PUBLISHED_VERSIONS:
        report.fail(
            "api.published",
            "%s:%s:%s does not exist. The published versions are %s (%s), so this pin would fail "
            "at dependency resolution rather than at compile time"
            % (
                LEGACY_API_COORDINATE,
                LEGACY_API_ARTIFACT,
                version,
                ", ".join(LEGACY_API_PUBLISHED_VERSIONS),
                LEGACY_API_PUBLISHED_VERSIONS_SOURCE,
            ),
        )
        return

    if version != EXPECTED_LEGACY_API_ARTIFACT_VERSION:
        report.fail(
            "api.expected",
            "%s:%s is pinned at %s, but this module's class surface was verified against %s. "
            "%s predates XSharedPreferences and SELinuxHelper, both of which the module imports, "
            "so it would compile-fail instead of failing here"
            % (
                LEGACY_API_COORDINATE,
                LEGACY_API_ARTIFACT,
                version,
                EXPECTED_LEGACY_API_ARTIFACT_VERSION,
                ", ".join(
                    item for item in LEGACY_API_PUBLISHED_VERSIONS if item != "82"
                ),
            ),
        )
        return

    # The pin is only meaningful if the module build actually consumes it. A catalog entry
    # nothing reads is the same as no pin at all, and it reads as if it were one.
    build_path = os.path.join(root, MODULE_BUILD)
    build = read(build_path) or ""
    # `compileOnly(libs.libxposed.legacy)` and `compileOnly(libs.libxposed.legacy.get())` are both
    # valid, so the call is matched on its prefix and its arguments, not on an exact spelling.
    wired = re.search(
        r"\b%s\s*\(\s*%s\b" % (LEGACY_API_CONFIGURATION, re.escape(LEGACY_API_ALIAS)),
        build,
        re.MULTILINE,
    )
    if re.search(r"\b%s\b" % re.escape(LEGACY_API_ALIAS), build) is None:
        report.fail(
            "api.wired",
            "%s is pinned but %s is never consumed in %s, so the pin does not reach the module's "
            "compile classpath" % (LEGACY_API_ALIAS, LEGACY_API_ALIAS, MODULE_BUILD),
        )
    elif wired is None:
        report.fail(
            "api.wired",
            "%s must be on the %s configuration, and no %s call in %s does that. The legacy API "
            "stubs must not be packaged into the APK, because the framework supplies those "
            "classes inside the hooked process"
            % (
                LEGACY_API_ALIAS,
                LEGACY_API_CONFIGURATION,
                LEGACY_API_CONFIGURATION,
                MODULE_BUILD,
            ),
        )

    if not verify_artifact:
        return

    # Prove the pinned artifact actually publishes every legacy type the module imports. This is
    # the only check that reads the artifact rather than the declaration, so it is opt-in: it
    # needs the jar to have been resolved, which is true after a build and false on a cold
    # checkout. In --verify-artifact mode an unresolvable artifact is a failure, not a skip,
    # because that mode is only ever requested where the artifact is known to exist.
    artifact_path = locate_artifact(root, version, artifact)
    if artifact_path is None:
        report.fail(
            "api.surface",
            "--verify-artifact was requested but %s:%s:%s could not be located, so the class "
            "surface it publishes could not be checked. Point --artifact at the resolved jar, or "
            "run this without --verify-artifact."
            % (LEGACY_API_COORDINATE, LEGACY_API_ARTIFACT, version),
        )
        return

    import zipfile

    with zipfile.ZipFile(artifact_path) as archive:
        published = set(archive.namelist())

    required: set[str] = set()
    missing: list[str] = []
    for imported in sorted(legacy_imports(root)):
        resolved = resolve_to_published(imported, published)
        if resolved is None:
            missing.append(imported)
        else:
            required.add(resolved)

    if missing:
        report.fail(
            "api.surface",
            "%s does not publish %s, which the module imports. The pinned artifact and the code "
            "that has to run against it disagree."
            % (artifact_path, ", ".join(sorted(missing))),
        )
    else:
        report.note(
            "%s publishes all %d legacy types the module imports (%s)"
            % (artifact_path, len(required), ", ".join(sorted(required)))
        )


CHECKS = (
    "manifest.xposedmodule",
    "manifest.xposeddescription",
    "manifest.xposedminversion",
    "manifest.xposedsharedprefs",
    "manifest.xposedscope",
    "entry.file",
    "entry.single",
    "entry.class",
    "modern.meta",
    "api.pinned",
    "api.published",
    "api.expected",
    "api.wired",
    "api.surface",
)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=REPO_ROOT, help="repository root to check")
    parser.add_argument(
        "--format",
        choices=("text", "json"),
        default="text",
        help="report format (default: text)",
    )
    parser.add_argument(
        "--verify-artifact",
        action="store_true",
        help=(
            "also open the pinned legacy API artifact and assert it publishes every legacy type "
            "the module imports. Requires the jar to be resolved, so run it after a build; in "
            "this mode a missing artifact fails rather than being skipped"
        ),
    )
    parser.add_argument(
        "--artifact",
        default=None,
        help="explicit path to the resolved legacy API jar, for --verify-artifact",
    )
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)

    report = Report()
    check_manifest(root, report)
    check_entry_point(root, report)
    check_modern_api_files(root, report)
    check_api_dependency(root, report, verify_artifact=args.verify_artifact, artifact=args.artifact)
    return report.emit(args.format)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
