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

Checks are listed in ``CHECKS``; each violation names one, so a CI failure says which part of
the loader contract was broken rather than "the module does not load".

Usage:
    python3 tools/quality/check_lsposed_contract.py
    python3 tools/quality/check_lsposed_contract.py --root . --format json
Exit codes: 0 contract intact, 1 a violation, 2 the invocation or the tree could not be read.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

ANDROID_NS = "http://schemas.android.com/apk/res/android"
ANDROID = "{%s}" % ANDROID_NS

MANIFEST = "app/src/main/AndroidManifest.xml"
ARRAYS = "app/src/main/res/values/arrays.xml"
ENTRY_FILE = "app/src/main/assets/xposed_init"
SOURCE_ROOT = "app/src/main/java"
VERSION_CATALOG = "gradle/libs.versions.toml"

# The Xposed API level the module is written against. 93 is the level whose semantics this
# module relies on (it is also the level that introduced the scope meta-data LSPosed reads).
MIN_LEGACY_API = 93

# A sanity ceiling: the highest legacy API level that exists. Anything above it is the modern
# API, which this module deliberately does not use.
MAX_LEGACY_API = 100

# The package LSPosed shows as "System Framework". It is required by the package-visibility
# bypass and the settings bridge; see the comment on the `scope` array in arrays.xml.
FRAMEWORK_SCOPE_ENTRY = "android"

# The compile-time surface has to match the loader that will call it.
LEGACY_API_COORDINATE = "de.robv.android.xposed"
LEGACY_API_ARTIFACT = "api"


class Report:
    """Collects violations so every problem is reported in one run."""

    def __init__(self) -> None:
        self.violations: list[tuple[str, str]] = []

    def fail(self, check: str, message: str) -> None:
        self.violations.append((check, message))

    def emit(self, fmt: str) -> int:
        if fmt == "json":
            print(
                json.dumps(
                    {
                        "violations": [
                            {"check": check, "message": message} for check, message in self.violations
                        ]
                    },
                    indent=2,
                )
            )
        elif self.violations:
            for check, message in self.violations:
                print("error: [%s] %s" % (check, message), file=sys.stderr)
        else:
            print(
                "LSPosed loader contract intact: legacy API >= %d, single entry point, "
                "scope declared, no modern-API files shadowing it." % MIN_LEGACY_API
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
            if level < MIN_LEGACY_API:
                report.fail(
                    "manifest.xposedminversion",
                    "xposedminversion %d is below the %d this module is written against"
                    % (level, MIN_LEGACY_API),
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
            elif FRAMEWORK_SCOPE_ENTRY not in items:
                report.fail(
                    "manifest.xposedscope",
                    "the scope array is missing %r, which the settings bridge and the "
                    "package-visibility bypass run inside" % FRAMEWORK_SCOPE_ENTRY,
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


def check_api_dependency(root: str, report: Report) -> None:
    path = os.path.join(root, VERSION_CATALOG)
    content = read(path)
    if content is None:
        report.fail("api.pinned", "%s is unreadable, so the loader API cannot be confirmed" % VERSION_CATALOG)
        return
    pattern = re.compile(
        r'"%s"\s*,\s*name\s*=\s*"%s"' % (re.escape(LEGACY_API_COORDINATE), re.escape(LEGACY_API_ARTIFACT))
    )
    if not pattern.search(content):
        report.fail(
            "api.pinned",
            "no %s:%s dependency is declared, so the module does not compile against the "
            "loader API it is written for"
            % (LEGACY_API_COORDINATE, LEGACY_API_ARTIFACT),
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
    args = parser.parse_args(argv)

    root = os.path.abspath(args.root)

    report = Report()
    check_manifest(root, report)
    check_entry_point(root, report)
    check_modern_api_files(root, report)
    check_api_dependency(root, report)
    return report.emit(args.format)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
