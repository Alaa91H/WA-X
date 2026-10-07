#!/usr/bin/env python3
"""Regenerate every artifact derived from tools/compatibility/compatibility.json (T01).

Two artifacts are generated, never hand edited:

  docs/COMPATIBILITY.md          the human readable matrix
  app/src/main/res/values/arrays.xml   the runtime supported-version lists

The second one is what makes compatibility.json the single source of truth: the
runtime version gate still reads an Android string-array, but that array is now a
mirror of the matrix rather than a second place to remember to update.

Usage:
    python3 tools/compatibility/sync_generated.py           # write both artifacts
    python3 tools/compatibility/sync_generated.py --check   # fail if either is stale
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MATRIX = os.path.join(HERE, "compatibility.json")
DOC = os.path.join(REPO_ROOT, "docs", "COMPATIBILITY.md")
ARRAYS = os.path.join(REPO_ROOT, "app", "src", "main", "res", "values", "arrays.xml")

# Maps a matrix package key to its string-array name in arrays.xml.
ARRAY_NAMES = {
    "whatsapp": "supported_versions_wpp",
    "business": "supported_versions_business",
}

PACKAGE_LABELS = {
    "whatsapp": "WhatsApp",
    "business": "WhatsApp Business",
}
STATUS_MARK = {
    "supported": "supported",
    "degraded": "degraded",
    "unsupported": "**unsupported**",
    "unknown": "_unknown_",
}


def effective_status(matrix: dict, feature_id: str, package: str, version: str) -> str:
    """Resolve one cell: an explicit override, else the package default."""
    override = (
        matrix.get("matrix", {})
        .get(feature_id, {})
        .get(package, {})
        .get("versions", {})
    )
    if version in override:
        return override[version]
    return matrix.get("packages", {}).get(package, {}).get("defaultStatus", "unknown")


def worst_status(statuses: list[str]) -> str:
    for candidate in ("unsupported", "degraded", "unknown", "supported"):
        if candidate in statuses:
            return candidate
    return "unknown"


def render(matrix: dict) -> str:
    module = matrix["module"]
    features = matrix["derived"]["features"]
    out: list[str] = []
    add = out.append

    add("# WA X compatibility matrix")
    add("")
    add("<!-- GENERATED FILE - do not edit by hand. -->")
    add("<!-- Source: tools/compatibility/compatibility.json -->")
    add("<!-- Regenerate: python3 tools/compatibility/sync_generated.py -->")
    add("")
    add(
        "This document is the human-readable view of the WA X compatibility matrix. It is the"
        " single place to look before adding support for a new WhatsApp version."
    )
    add("")
    add("WA X is a fork/continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer), maintained by [Alaa](https://github.com/Alaa91H). Fork provenance does not change the evidence standard used by this matrix.")
    add("Developer Telegram: [@Alaa91h](https://t.me/Alaa91h) · Community: [@WAXposed](https://t.me/WAXposed) · Email: [alahus2591@gmail.com](mailto:alahus2591@gmail.com) · Voluntary support: [Ko-fi](https://ko-fi.com/alaa91h)")
    add("")

    add("## Current state")
    add("")
    add(
        "No cell in this matrix is resolver-verified yet. Declared versions below are a"
        " maintainer declaration that the runtime version gate enforces; they are **not**"
        " evidence. Per the plan's governing rule, a version or feature is not declared"
        " supported until its resolvers actually resolve on that target."
    )
    add("")
    add("| Package | Declared versions | Resolver-verified |")
    add("|---|---|---|")
    for key in ("whatsapp", "business"):
        entry = matrix["packages"][key]
        verified = sum(
            1
            for feature in features
            for version in entry["declaredVersions"]
            if effective_status(matrix, feature["id"], key, version) == "supported"
        )
        add(
            "| %s | %d | %d / %d cells |"
            % (
                PACKAGE_LABELS[key],
                len(entry["declaredVersions"]),
                verified,
                len(entry["declaredVersions"]) * len(features),
            )
        )
    add("")

    add("## Status vocabulary")
    add("")
    add("| Status | Meaning |")
    add("|---|---|")
    for status in ("supported", "degraded", "unsupported", "unknown"):
        add("| `%s` | %s |" % (status, matrix["statusVocabulary"][status]))
    add("")

    add("## Resolution tiers")
    add("")
    add(
        "How a feature reaches its hook targets. This is derived from the source tree, so"
        " it is always accurate, and it tells us which features can actually break on a"
        " WhatsApp update."
    )
    add("")
    add("| Tier | Meaning | Features |")
    add("|---|---|---|")
    for tier in ("none", "indirect", "dexkit"):
        members = [f["id"] for f in features if f["resolutionTier"] == tier]
        add(
            "| `%s` | %s | %d |"
            % (tier, matrix["resolutionTiers"][tier]["meaning"], len(members))
        )
    add("")
    add("### Tier `none` cannot break through resolution")
    add("")
    independent = [f["id"] for f in features if f["resolutionTier"] == "none"]
    add(
        "%d of %d features reference no internal resolution layer at all, so no WhatsApp"
        " update can break them via DexKit:"
        % (len(independent), len(features))
    )
    add("")
    for feature_id in sorted(independent):
        add("- `%s`" % feature_id)
    add("")

    add("## Target dimensions")
    add("")
    add("| Dimension | Value |")
    add("|---|---|")
    add("| minSdk | %s |" % module["minSdk"])
    add("| targetSdk | %s |" % module["targetSdk"])
    add("| compileSdk | %s |" % module["compileSdk"])
    add("| ABIs | %s |" % ", ".join("`%s`" % abi for abi in module["abis"]))
    add("")

    add("## Feature inventory")
    add("")
    add(
        "All %d registered features. `W` and `B` are the worst status across the declared"
        " versions of that package." % len(features)
    )
    add("")
    add("| # | Feature | Category | Tier | Resolvers | Sources | W | B |")
    add("|---|---|---|---|---|---|---|---|")

    order = {feature["id"]: index for index, feature in enumerate(features)}
    rows = sorted(
        features,
        key=lambda f: (f["category"], f["id"]),
    )
    for row_number, feature in enumerate(rows, start=1):
        worst = {}
        for package in ("whatsapp", "business"):
            statuses = [
                effective_status(matrix, feature["id"], package, version)
                for version in matrix["packages"][package]["declaredVersions"]
            ]
            worst[package] = STATUS_MARK[worst_status(statuses)]
        add(
            "| %d | `%s` | %s | %s | %d | %s | %s | %s |"
            % (
                row_number,
                feature["id"],
                feature["category"],
                feature["resolutionTier"],
                len(feature["resolverDependencies"]),
                ", ".join(feature["resolutionSources"]) or "-",
                worst["whatsapp"],
                worst["business"],
            )
        )
    add("")

    add("## Features requiring resolver evidence")
    add("")
    add(
        "These features call DexKit resolvers directly. Each one needs a recorded"
        " `verifiedAt` and a `resolved` result for every listed resolver before any cell"
        " may claim `supported`."
    )
    add("")
    for package in ("whatsapp", "business"):
        add("### %s" % PACKAGE_LABELS[package])
        add("")
        add("| Feature | Required resolvers |")
        add("|---|---|")
        for feature in sorted(
            (f for f in features if f["resolutionTier"] == "dexkit"),
            key=lambda f: -len(f["resolverDependencies"]),
        ):
            add(
                "| `%s` | %s |"
                % (feature["id"], ", ".join("`%s`" % r for r in feature["resolverDependencies"]))
            )
        add("")

    add("## Updating this matrix")
    add("")
    add("When adding a new WhatsApp version:")
    add("")
    add("1. Add the version prefix to `packages.<target>.declaredVersions` in `compatibility.json`.")
    add("2. Run `python3 tools/compatibility/sync_generated.py` to regenerate this document and `arrays.xml`.")
    add("3. Run `python3 tools/compatibility/validate_compatibility.py` and `sync_generated.py --check`.")
    add("4. Record real runtime resolver evidence under `evidence.<FeatureId>.resolvers`.")
    add("5. Only then set cells to `supported`.")
    add("6. Re-run validation and commit the source-of-truth and generated artifacts together.")
    add("")
    add("The validator refuses any `supported` cell whose resolver evidence is missing,")
    add("partial, or lacking a `verifiedAt` timestamp.")
    add("")
    add(
        "Step 2 rewrites `app/src/main/res/values/arrays.xml` from this matrix, so the"
        " runtime version gate and this document can never disagree."
    )

    # The join below already terminates the last line, so the document must not carry a
    # trailing empty element as well: that is what put a blank line at the end of the
    # generated file, which then showed up as a spurious diff on every regeneration.
    return "\n".join(out) + "\n"


def read_text(path: str) -> tuple[str, str]:
    """Return ``(content, newline)`` for a text file.

    ``content`` is always LF normalised: the text read uses universal newlines, so
    CRLF, lone CR and LF all collapse to ``\\n``. That guarantee is what lets
    :func:`write_text` convert back without ever doubling a carriage return.

    ``newline`` is the file's own convention, detected from the raw bytes. Preserving
    it keeps a regenerate step from showing up as a diff that changes nothing but line
    endings.
    """
    with open(path, "r", encoding="utf-8") as handle:
        content = handle.read()
    with open(path, "rb") as handle:
        raw = handle.read()
    newline = "\r\n" if b"\r\n" in raw else "\n"
    return content, newline


def write_text(path: str, content: str, newline: str) -> None:
    """Write LF normalised ``content`` using the target newline convention.

    ``newline=""`` is essential: any other value makes Python translate the newlines
    itself, which would double the carriage returns we already expanded.
    """
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(content.replace("\r\n", "\n").replace("\n", newline))


def render_arrays(matrix: dict, current: str) -> str:
    """Rewrite only the supported-version string-arrays, leaving the rest untouched."""
    rendered = current
    for package, array_name in ARRAY_NAMES.items():
        versions = matrix["packages"][package]["declaredVersions"]
        body = "\n".join("        <item>%s</item>" % version for version in versions)
        replacement = (
            '<string-array name="%s">\n%s\n    </string-array>' % (array_name, body)
        )
        pattern = re.compile(
            r'<string-array name="%s">.*?</string-array>' % re.escape(array_name),
            re.DOTALL,
        )
        if not pattern.search(rendered):
            raise SystemExit("could not find string-array %s in arrays.xml" % array_name)
        rendered = pattern.sub(lambda _match: replacement, rendered, count=1)
    return rendered


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit 1 when any generated artifact is stale instead of writing it",
    )
    args = parser.parse_args(argv)

    if not os.path.exists(MATRIX):
        print("missing %s" % MATRIX, file=sys.stderr)
        return 2
    with open(MATRIX, "r", encoding="utf-8") as handle:
        matrix = json.load(handle)

    if not os.path.exists(ARRAYS):
        print("missing %s" % ARRAYS, file=sys.stderr)
        return 2
    arrays_current, arrays_newline = read_text(ARRAYS)

    targets = [
        (DOC, render(matrix), "\n"),
        (ARRAYS, render_arrays(matrix, arrays_current), arrays_newline),
    ]

    stale = []
    for path, expected, _newline in targets:
        if not os.path.exists(path):
            stale.append(path)
            continue
        current, _newline = read_text(path)
        if current != expected:
            stale.append(path)

    if args.check:
        if stale:
            for path in stale:
                print(
                    "%s is stale, re-run sync_generated.py"
                    % os.path.relpath(path, REPO_ROOT),
                    file=sys.stderr,
                )
            return 1
        for path, _expected, _newline in targets:
            print("%s is up to date" % os.path.relpath(path, REPO_ROOT))
        return 0

    for path, expected, newline in targets:
        write_text(path, expected, newline)
        print("wrote %s" % os.path.relpath(path, REPO_ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
