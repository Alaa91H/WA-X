#!/usr/bin/env python3
"""Keep docs/FEATURE_EXPANSION_MATRIX.md and the platform catalog in step.

The matrix is a plan and a status report; the catalog is what the loader actually obeys. Left
alone the two drift in the direction that hurts: a declared feature nobody documented, or a
documented feature that no longer exists. Neither is a compile error, and both make the matrix
worthless as a status view.

The check is deliberately two-sided:

  * every id the catalog registers appears in the matrix's coverage table, and
  * every id in that table is registered, with the same category.

The declarations are read from every source in the platform package, so a table row that moves
into a support file still counts as declared.

Usage:
    python3 tools/quality/check_matrix_ids.py
Exit codes: 0 in step, 1 drift, 2 the invocation or a source file was unreadable.
"""

from __future__ import annotations

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
PLATFORM_PACKAGE = os.path.join(REPO_ROOT, "app/src/main/java/com/wax/module/platform")
FEATURE_IDS = os.path.join(PLATFORM_PACKAGE, "PlatformFeatures.kt")
MATRIX = os.path.join(REPO_ROOT, "docs/FEATURE_EXPANSION_MATRIX.md")

# A coverage-table row: exactly three cells, the first being a code span. The milestone tables
# in Part 1 have twelve columns and therefore cannot match.
COVERAGE_ROW = re.compile(r"^\|\s*`([a-z0-9_.]+)`\s*\|\s*([A-Z_]+)\s*\|\s*([A-Z_]+)\s*\|\s*$")


def read(path: str) -> str:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError as error:
        print("error: cannot read %s: %s" % (path, error), file=sys.stderr)
        raise SystemExit(2)


def catalog_source() -> str:
    """Every Kotlin source in the platform package, concatenated.

    The declaration table is one list, but it is not required to be one file: the class-size
    budget makes moving a wave of rows into a support file the expected response to a growing
    catalog, and the rows are no less registered for living there. Reading the package rather
    than one filename is what keeps this check measuring the catalog instead of its file layout.
    """
    try:
        names = sorted(name for name in os.listdir(PLATFORM_PACKAGE) if name.endswith(".kt"))
    except OSError as error:
        print("error: cannot list %s: %s" % (PLATFORM_PACKAGE, error), file=sys.stderr)
        raise SystemExit(2)
    if not names:
        print("error: no Kotlin sources in %s" % PLATFORM_PACKAGE, file=sys.stderr)
        raise SystemExit(2)
    return "\n".join(read(os.path.join(PLATFORM_PACKAGE, name)) for name in names)


def catalog_features() -> dict[str, str]:
    """Registered id -> category, read from the catalog's declaration table."""
    source = catalog_source()
    features: dict[str, str] = {}
    for chunk in source.split("feature(")[1:]:
        identifier = re.search(r"PlatformFeatures\.(\w+)", chunk)
        if identifier is None:
            continue
        category = re.search(r"FeatureCategory\.(\w+)", chunk)
        if category is None:
            continue
        features.setdefault(identifier.group(1), category.group(1))
    if not features:
        print("error: no feature declarations found in %s" % PLATFORM_PACKAGE, file=sys.stderr)
        raise SystemExit(2)
    return features


def documented_features() -> dict[str, tuple[str, str]]:
    """Documented id -> (category, registration), read from the coverage table."""
    documented: dict[str, tuple[str, str]] = {}
    for line in read(MATRIX).splitlines():
        match = COVERAGE_ROW.match(line.strip())
        if match is None:
            continue
        documented[match.group(1)] = (match.group(2), match.group(3))
    if not documented:
        print("error: no coverage rows found in %s" % MATRIX, file=sys.stderr)
        raise SystemExit(2)
    return documented


def main() -> int:
    catalog = catalog_features()
    documented = documented_features()

    # The catalog names a feature with a constant; the matrix names it with the id that
    # constant holds. Resolve the constants so the comparison is between ids.
    constants = dict(re.findall(r'const val (\w+)\s*=\s*"([a-z0-9_.]+)"', read(FEATURE_IDS)))
    resolved = {constants.get(name, name): category for name, category in catalog.items()}

    problems: list[str] = []
    missing = sorted(set(resolved) - set(documented))
    stale = sorted(set(documented) - set(resolved))
    if missing:
        problems.append(
            "declared in the catalog but not documented in docs/FEATURE_EXPANSION_MATRIX.md: %s" % missing
        )
    if stale:
        problems.append(
            "documented in docs/FEATURE_EXPANSION_MATRIX.md but not declared in the catalog: %s" % stale
        )
    for identifier in sorted(set(resolved) & set(documented)):
        expected = resolved[identifier]
        actual = documented[identifier][0]
        if expected != actual:
            problems.append(
                "%s is documented as category %s but declared as %s" % (identifier, actual, expected)
            )

    if problems:
        for problem in problems:
            print("error: %s" % problem, file=sys.stderr)
        return 1

    print(
        "%d catalog features are documented with matching categories."
        % len(resolved)
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
