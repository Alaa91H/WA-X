#!/usr/bin/env python3
"""Validate tools/compatibility/compatibility.json (T01).

Checks, in order:

  1. schema      the document has the required sections and only known status values
  2. freshness   the ``derived`` section matches what the source tree currently says
  3. inventory   every feature registered in FeatureLoader.plugins() is represented
  4. evidence    no cell claims ``supported`` without resolver evidence
  5. sync        declared versions and module facts match the build and resource files

Exit codes:
  0  valid
  1  at least one check failed
  2  bad input (missing file, unparsable JSON)

Usage:
    python3 tools/compatibility/validate_compatibility.py
    python3 tools/compatibility/validate_compatibility.py --sync
    python3 tools/compatibility/validate_compatibility.py --matrix OTHER.json
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import extract_features  # noqa: E402

MATRIX_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "compatibility.json"
)

VALID_STATUSES = ("supported", "degraded", "unsupported", "unknown")
PACKAGE_KEYS = ("whatsapp", "business")

# Dimension keys allowed inside a per-feature package override.
DIMENSION_KEYS = ("versions", "sdk", "abi")


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.notes: list[str] = []

    def fail(self, message: str) -> None:
        self.failures.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)

    def emit(self) -> int:
        for message in self.notes:
            print("note: %s" % message)
        for message in self.failures:
            print("FAIL: %s" % message, file=sys.stderr)
        if self.failures:
            print("\n%d check(s) failed" % len(self.failures), file=sys.stderr)
            return 1
        print("compatibility.json: all checks passed")
        return 0


def load_matrix(path: str) -> dict:
    if not os.path.exists(path):
        print("missing %s" % path, file=sys.stderr)
        raise SystemExit(2)
    with open(path, "r", encoding="utf-8") as handle:
        try:
            return json.load(handle)
        except json.JSONDecodeError as error:
            print("%s is not valid JSON: %s" % (path, error), file=sys.stderr)
            raise SystemExit(2)


def check_schema(matrix: dict, report: Report) -> None:
    for section in ("schemaVersion", "statusVocabulary", "resolutionTiers", "module",
                    "packages", "matrix", "evidence", "derived"):
        if section not in matrix:
            report.fail("missing required section %r" % section)

    if matrix.get("schemaVersion") != 1:
        report.fail("unsupported schemaVersion %r, expected 1" % matrix.get("schemaVersion"))

    vocabulary = matrix.get("statusVocabulary", {})
    for status in VALID_STATUSES:
        if status not in vocabulary:
            report.fail("statusVocabulary is missing the %r status" % status)

    packages = matrix.get("packages", {})
    for key in PACKAGE_KEYS:
        if key not in packages:
            report.fail("packages is missing %r" % key)
            continue
        entry = packages[key]
        for field in ("packageName", "applicationId", "declaredVersions", "defaultStatus"):
            if field not in entry:
                report.fail("packages.%s is missing %r" % (key, field))
        if entry.get("defaultStatus") not in VALID_STATUSES:
            report.fail(
                "packages.%s.defaultStatus %r is not one of %s"
                % (key, entry.get("defaultStatus"), ", ".join(VALID_STATUSES))
            )
        # Explicitly pin the single expected build per exact version.
        # These are curator-declared identities, separate from observations:
        # without one, a lone changed binary could certify the wrong build.
        certified = entry.get("certifiedBuildFingerprints", {})
        if not isinstance(certified, dict):
            report.fail("packages.%s.certifiedBuildFingerprints must be an object" % key)
        else:
            for version, fingerprint in certified.items():
                if not _exact_version(version) or version not in entry.get("declaredVersions", []):
                    report.fail("packages.%s has non-declared exact build %r" % (key, version))
                if (not isinstance(fingerprint, str) or not fingerprint.strip()
                        or len(fingerprint) > 512):
                    report.fail("packages.%s has invalid expected fingerprint for %r" % (key, version))
        # A package-wide default cannot certify all features and versions.
        if entry.get("defaultStatus") == "supported":
            report.fail(
                "packages.%s.defaultStatus=supported is forbidden without "
                "per-feature, exact-target resolver evidence" % key
            )

    for feature_id, per_package in matrix.get("matrix", {}).items():
        for package_key, override in per_package.items():
            if package_key not in PACKAGE_KEYS:
                report.fail(
                    "matrix.%s uses unknown package %r, expected one of %s"
                    % (feature_id, package_key, ", ".join(PACKAGE_KEYS))
                )
                continue
            for dimension, cells in override.items():
                if dimension not in DIMENSION_KEYS:
                    report.fail(
                        "matrix.%s.%s uses unknown dimension %r, expected one of %s"
                        % (feature_id, package_key, dimension, ", ".join(DIMENSION_KEYS))
                    )
                    continue
                if not isinstance(cells, dict):
                    report.fail("matrix.%s.%s.%s must be an object" % (feature_id, package_key, dimension))
                    continue
                for cell, status in cells.items():
                    if status not in VALID_STATUSES:
                        report.fail(
                            "matrix.%s.%s.%s.%s has invalid status %r, expected one of %s"
                            % (feature_id, package_key, dimension, cell, status,
                               ", ".join(VALID_STATUSES))
                        )


def build_derived_section() -> dict:
    facts = extract_features.build()
    features = []
    for feature in facts["features"]:
        sources = feature["resolutionSources"]
        if feature["resolverDependencies"]:
            tier = "dexkit"
        elif sources:
            tier = "indirect"
        else:
            tier = "none"
        features.append(
            {
                "id": feature["id"],
                "category": feature["category"],
                "resolutionTier": tier,
                "resolutionSources": sources,
                "resolverDependencies": feature["resolverDependencies"],
                "preferenceKeys": feature["preferenceKeys"],
            }
        )
    return {
        "generator": "tools/compatibility/extract_features.py",
        "regenerateWith": (
            "python3 tools/compatibility/extract_features.py "
            "--out tools/compatibility/derived_facts.json"
        ),
        "featureCount": len(features),
        "features": features,
    }


def normalise_derived(section: dict) -> dict:
    """Compare only the meaningful fields, ignoring key order."""
    return {
        "featureCount": section.get("featureCount"),
        "features": [
            {
                "id": item.get("id"),
                "category": item.get("category"),
                "resolutionTier": item.get("resolutionTier"),
                "resolutionSources": item.get("resolutionSources"),
                "resolverDependencies": item.get("resolverDependencies"),
                "preferenceKeys": item.get("preferenceKeys"),
            }
            for item in section.get("features", [])
        ],
    }


def check_freshness(matrix: dict, derived: dict, report: Report, sync: bool, path: str) -> None:
    stored = normalise_derived(matrix.get("derived", {}))
    fresh = normalise_derived(derived)

    if sync:
        matrix["derived"] = derived
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(matrix, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        report.note("synced the derived section (%d features)" % derived["featureCount"])
        return

    if stored == fresh:
        return

    stored_ids = [item.get("id") for item in stored["features"]]
    fresh_ids = [item.get("id") for item in fresh["features"]]
    added = sorted(set(fresh_ids) - set(stored_ids))
    removed = sorted(set(stored_ids) - set(fresh_ids))
    if added or removed:
        report.fail(
            "derived section is stale: features added=%s removed=%s. "
            "Run: python3 tools/compatibility/validate_compatibility.py --sync"
            % (added or "none", removed or "none")
        )
        return

    drifted = []
    stored_by_id = {item.get("id"): item for item in stored["features"]}
    for item in fresh["features"]:
        previous = stored_by_id.get(item["id"], {})
        for field in ("category", "resolutionTier", "resolutionSources",
                      "resolverDependencies", "preferenceKeys"):
            if previous.get(field) != item[field]:
                drifted.append("%s.%s" % (item["id"], field))
    if drifted:
        report.fail(
            "derived section is stale for: %s. "
            "Run: python3 tools/compatibility/validate_compatibility.py --sync"
            % ", ".join(sorted(drifted))
        )
    else:
        report.fail("derived section differs from a fresh extraction; re-run with --sync")


def check_inventory(matrix: dict, derived: dict, report: Report) -> None:
    stored_ids = {item.get("id") for item in matrix.get("derived", {}).get("features", [])}
    fresh_ids = {item["id"] for item in derived["features"]}
    missing = sorted(fresh_ids - stored_ids)
    if missing:
        report.fail("features missing from the matrix: %s" % ", ".join(missing))

    known = fresh_ids
    for feature_id in matrix.get("matrix", {}):
        if feature_id not in known:
            report.fail("matrix references unknown feature %r" % feature_id)
    for feature_id in matrix.get("evidence", {}):
        if feature_id not in known:
            report.fail("evidence references unknown feature %r" % feature_id)


def _utc_timestamp(value: object) -> datetime | None:
    """Parse an explicitly UTC/offset-qualified ISO-8601 observation timestamp."""
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)


def _exact_version(value: object) -> bool:
    """Do not certify declared wildcard version families as tested builds."""
    return isinstance(value, str) and re.fullmatch(r"\d+\.\d+\.\d+\.\d+", value) is not None


def _target_verified(
    target: object, package_key: str, version: str, package_name: str,
    required: list[str], module: dict,
) -> bool:
    if not isinstance(target, dict):
        return False
    if (target.get("package") != package_key
            or target.get("packageName") != package_name
            or target.get("version") != version):
        return False
    if not isinstance(target.get("buildFingerprint"), str) or not target["buildFingerprint"].strip():
        return False
    sdk = target.get("sdk")
    if type(sdk) is not int or sdk < module.get("minSdk", 1):
        return False
    if target.get("abi") not in module.get("abis", []):
        return False
    now = datetime.now(timezone.utc)
    verified = _utc_timestamp(target.get("verifiedAt"))
    if verified is None or verified > now:
        return False
    if "expiresAt" in target:
        expires = _utc_timestamp(target["expiresAt"])
        if expires is None or expires <= now or expires <= verified:
            return False
    if target.get("result") != "resolved":
        return False
    resolvers = target.get("resolvers")
    if not isinstance(resolvers, dict):
        return False
    for resolver in required:
        record = resolvers.get(resolver)
        if not isinstance(record, dict) or record.get("result") != "resolved":
            return False
        observed_at = _utc_timestamp(record.get("verifiedAt"))
        if observed_at is None or observed_at > now or observed_at > verified:
            return False
    return True


def check_evidence(matrix: dict, derived: dict, report: Report) -> None:
    """Fail closed: a supported claim needs evidence for that exact target.

    Legacy feature-wide resolver records cannot certify a package/version cell.
    Evidence targets use feature -> targets[] -> package, packageName, version,
    buildFingerprint, sdk, abi, verifiedAt, result and resolver observations.
    """
    by_id = {item["id"]: item for item in derived["features"]}
    entries = matrix.get("evidence", {})
    packages = matrix.get("packages", {})
    module = matrix.get("module", {})

    for feature_id, per_package in matrix.get("matrix", {}).items():
        if not isinstance(per_package, dict):
            report.fail("matrix.%s must be an object" % feature_id)
            continue
        for package_key, override in per_package.items():
            if package_key not in PACKAGE_KEYS or not isinstance(override, dict):
                continue
            for dimension, cells in override.items():
                if not isinstance(cells, dict):
                    continue
                for cell, status in cells.items():
                    if status != "supported":
                        continue
                    claim = "%s/%s/%s/%s" % (feature_id, package_key, dimension, cell)
                    # A version is a precise runtime identity. SDK/ABI-only
                    # overrides would certify all versions, so reject them.
                    if dimension != "versions" or not _exact_version(cell):
                        report.fail("%s claims supported without an exact target version" % claim)
                        continue
                    package = packages.get(package_key, {})
                    feature = by_id.get(feature_id)
                    if feature is None or cell not in package.get("declaredVersions", []):
                        report.fail("%s claims supported for an unknown feature/version" % claim)
                        continue
                    certified = package.get("certifiedBuildFingerprints", {})
                    expected_fingerprint = certified.get(cell) if isinstance(certified, dict) else None
                    if not isinstance(expected_fingerprint, str) or not expected_fingerprint.strip():
                        report.fail(
                            "%s claims supported without a separately declared exact build fingerprint"
                            % claim
                        )
                        continue
                    record = entries.get(feature_id, {})
                    targets = record.get("targets") if isinstance(record, dict) else None
                    observed = [
                        target
                        for target in (targets or [])
                        if _target_verified(target, package_key, cell,
                                            package.get("packageName"),
                                            feature["resolverDependencies"], module)
                    ] if isinstance(targets, list) else []
                    matching = [
                        target for target in observed
                        if target.get("buildFingerprint") == expected_fingerprint
                    ]
                    if not matching:
                        report.fail(
                            "%s claims supported without complete resolver evidence "
                            "for the exact package/version/build/SDK/ABI target" % claim
                        )
                        continue
                    # One cell, one build. A beta and a release observation of the
                    # same version are different signers and different runtimes,
                    # so accepting whichever one happens to be listed would let a
                    # green cell rest on evidence from a build it never described.
                    fingerprints = {
                        target.get("buildFingerprint") for target in observed
                        if isinstance(target, dict)
                    }
                    if len(fingerprints) > 1:
                        report.fail(
                            "%s is claimed supported by %d conflicting build "
                            "fingerprints; a cell names exactly one target"
                            % (claim, len(fingerprints))
                        )

    # Feature-wide supported status cannot be scoped to any runtime target.
    for feature_id, record in entries.items():
        if isinstance(record, dict) and record.get("status") == "supported":
            report.fail(
                "evidence.%s.status=supported is not an exact-target claim" % feature_id
            )

    # 'none' means no direct resolver dependency, not a runtime guarantee.
    independent = sorted(
        item["id"] for item in derived["features"] if item["resolutionTier"] == "none"
    )
    report.note(
        "%d/%d features have no direct resolver dependencies "
        "(resolutionTier=none; runtime compatibility is not proven): %s"
        % (len(independent), len(derived["features"]), ", ".join(independent))
    )


def check_sync(matrix: dict, report: Report) -> None:
    facts = extract_features.build()

    for key, expected in (
        ("whatsapp", "supported_versions_wpp"),
        ("business", "supported_versions_business"),
    ):
        declared = matrix.get("packages", {}).get(key, {}).get("declaredVersions")
        actual = facts["packages"][key]["declaredVersions"]
        if declared != actual:
            report.fail(
                "packages.%s.declaredVersions drifted from %s.\n  matrix:  %s\n  arrays.xml: %s"
                % (key, expected, declared, actual)
            )

    module = matrix.get("module", {})
    for field in ("minSdk", "targetSdk", "compileSdk"):
        if module.get(field) != facts["module"][field]:
            report.fail(
                "module.%s is %r but app/build.gradle.kts says %r"
                % (field, module.get(field), facts["module"][field])
            )
    if sorted(module.get("abis", [])) != sorted(facts["module"]["abis"]):
        report.fail(
            "module.abis is %r but app/build.gradle.kts declares %r"
            % (module.get("abis"), facts["module"]["abis"])
        )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--matrix",
        default=MATRIX_PATH,
        help="matrix document to validate (default: tools/compatibility/compatibility.json)",
    )
    parser.add_argument(
        "--sync",
        action="store_true",
        help="rewrite the derived section from the source tree instead of checking it",
    )
    args = parser.parse_args(argv)

    matrix = load_matrix(args.matrix)
    report = Report()

    check_schema(matrix, report)

    try:
        derived = build_derived_section()
    except SystemExit as error:
        report.fail("extraction failed: %s" % error)
        return report.emit()

    if args.sync:
        check_freshness(matrix, derived, report, sync=True, path=args.matrix)
        return report.emit()

    check_freshness(matrix, derived, report, sync=False, path=args.matrix)
    check_inventory(matrix, derived, report)
    check_evidence(matrix, derived, report)
    check_sync(matrix, report)
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
