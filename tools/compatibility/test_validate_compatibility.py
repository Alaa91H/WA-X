"""Fail-closed regression tests for target-scoped compatibility evidence (#391, #396)."""
from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import validate_compatibility as validator  # noqa: E402

FEATURE = {
    "id": "Example",
    "resolutionTier": "dexkit",
    "resolverDependencies": ["resolveExample"],
}
VERSION = "2.26.32.123"
STAMP = "2026-10-08T12:00:00Z"
BUILD = "com.whatsapp/release/arm64:stable-build-123"


def fixture() -> dict:
    return {
        "schemaVersion": 1,
        "statusVocabulary": {name: name for name in validator.VALID_STATUSES},
        "resolutionTiers": {},
        "module": {"minSdk": 28, "abis": ["arm64-v8a"]},
        "packages": {
            "whatsapp": {
                "packageName": "com.whatsapp",
                "applicationId": "com.wax.module",
                "declaredVersions": [VERSION],
                "certifiedBuildFingerprints": {VERSION: BUILD},
                "defaultStatus": "unknown",
            },
            "business": {
                "packageName": "com.whatsapp.w4b",
                "applicationId": "com.wax.module",
                "declaredVersions": [VERSION],
                "certifiedBuildFingerprints": {VERSION: BUILD},
                "defaultStatus": "unknown",
            },
        },
        "derived": {"features": [FEATURE]},
        "matrix": {},
        "evidence": {},
    }


def observation(package="whatsapp", version=VERSION):
    return {
        "package": package,
        "packageName": "com.whatsapp" if package == "whatsapp" else "com.whatsapp.w4b",
        "version": version,
        "buildFingerprint": BUILD,
        "sdk": 35,
        "abi": "arm64-v8a",
        "verifiedAt": STAMP,
        "result": "resolved",
        "resolvers": {"resolveExample": {"verifiedAt": STAMP, "result": "resolved"}},
    }


def supported(matrix, package="whatsapp", version=VERSION):
    matrix["matrix"] = {"Example": {package: {"versions": {version: "supported"}}}}
    return matrix


def validate(matrix):
    report = validator.Report()
    validator.check_schema(matrix, report)
    validator.check_evidence(matrix, {"features": [FEATURE]}, report)
    return report.failures


class EvidenceGateTests(unittest.TestCase):
    def test_default_supported_is_forbidden(self):
        # Both targets, and every declared version, not just one of them: the
        # bypass was never specific to a package or to a version list.
        for package in validator.PACKAGE_KEYS:
            for version in (VERSION, "2.26.39.74", "2.25.10.3"):
                with self.subTest(package=package, version=version):
                    matrix = fixture()
                    matrix["packages"][package]["declaredVersions"] = [version]
                    matrix["packages"][package]["defaultStatus"] = "supported"
                    self.assertTrue(validate(matrix))

    def test_default_supported_is_forbidden_with_an_empty_matrix(self):
        # The exact bypass shape: a green default, no explicit cell anywhere.
        for package in validator.PACKAGE_KEYS:
            with self.subTest(package=package):
                matrix = fixture()
                matrix["matrix"] = {}
                matrix["evidence"] = {}
                matrix["packages"][package]["defaultStatus"] = "supported"
                self.assertTrue(validate(matrix))

    def test_other_defaults_are_allowed(self):
        for status in ("unknown", "degraded", "unsupported"):
            with self.subTest(status=status):
                matrix = fixture()
                matrix["packages"]["whatsapp"]["defaultStatus"] = status
                self.assertFalse(validate(matrix))

    def test_supported_requires_target_evidence(self):
        self.assertTrue(validate(supported(fixture())))

    def test_exact_version_regex_rejects_wildcards_and_partial_versions(self):
        self.assertTrue(validator._exact_version(VERSION))
        for value in ("2.26.32", "2.26.32.xx", "2.26.32.123-extra", ""):
            with self.subTest(version=value):
                self.assertFalse(validator._exact_version(value))

    def test_same_target_complete_evidence_passes(self):
        matrix = supported(fixture())
        matrix["evidence"] = {"Example": {"targets": [observation()]}}
        self.assertFalse(validate(matrix))

    def test_conflicting_build_fingerprints_do_not_certify_one_cell(self):
        # A beta and a release observation of the same version are different
        # signers. A cell names one target, so accepting whichever happens to
        # be listed would let a green cell rest on evidence it never described.
        matrix = supported(fixture())
        release = observation()
        beta = observation()
        beta["buildFingerprint"] = "com.whatsapp/beta/arm64:stable-build-999"
        matrix["evidence"] = {"Example": {"targets": [release, beta]}}
        self.assertTrue(validate(matrix))

    def test_two_targets_sharing_one_fingerprint_are_fine(self):
        matrix = supported(fixture())
        first = observation()
        second = observation()
        second["verifiedAt"] = STAMP
        matrix["evidence"] = {"Example": {"targets": [first, second]}}
        self.assertFalse(validate(matrix))

    def test_single_changed_fingerprint_cannot_certify_a_supported_cell(self):
        # The previous fix rejected conflicting pairs, but one drifted build
        # remained enough to make the same version appear supported.
        matrix = supported(fixture())
        changed = observation()
        changed["buildFingerprint"] = "com.whatsapp/release/arm64:changed-without-version-bump"
        matrix["evidence"] = {"Example": {"targets": [changed]}}
        self.assertTrue(validate(matrix))

    def test_unpinned_build_cannot_be_supported_even_with_valid_observation(self):
        matrix = supported(fixture())
        del matrix["packages"]["whatsapp"]["certifiedBuildFingerprints"]
        matrix["evidence"] = {"Example": {"targets": [observation()]}}
        self.assertTrue(validate(matrix))

    def test_expected_fingerprint_is_required_only_for_supported_cells(self):
        matrix = fixture()
        matrix["packages"]["whatsapp"]["certifiedBuildFingerprints"] = {}
        matrix["matrix"] = {"Example": {"whatsapp": {"versions": {VERSION: "unknown"}}}}
        self.assertFalse(validate(matrix))

    def test_certified_builds_must_be_exact_declared_versions(self):
        for version, fingerprint in (
            ("2.26.32.xx", BUILD),
            ("2.26.32.999", BUILD),
            (VERSION, ""),
        ):
            with self.subTest(version=version, fingerprint=fingerprint):
                matrix = fixture()
                matrix["packages"]["whatsapp"]["certifiedBuildFingerprints"] = {version: fingerprint}
                self.assertTrue(validate(matrix))

    def test_cross_package_evidence_is_rejected(self):
        matrix = supported(fixture(), package="business")
        matrix["evidence"] = {"Example": {"targets": [observation("whatsapp")]}}
        self.assertTrue(validate(matrix))

    def test_cross_version_evidence_is_rejected(self):
        matrix = supported(fixture())
        matrix["evidence"] = {"Example": {"targets": [observation(version="2.26.32.124")]}}
        self.assertTrue(validate(matrix))

    def test_missing_resolver_result_is_rejected(self):
        matrix = supported(fixture())
        record = observation()
        record["resolvers"]["resolveExample"]["result"] = "missing"
        matrix["evidence"] = {"Example": {"targets": [record]}}
        self.assertTrue(validate(matrix))

    def test_expired_evidence_is_rejected(self):
        matrix = supported(fixture())
        record = observation()
        record["expiresAt"] = "2020-01-01T00:00:00Z"
        matrix["evidence"] = {"Example": {"targets": [record]}}
        self.assertTrue(validate(matrix))

    def test_version_wildcard_cannot_be_certified(self):
        matrix = supported(fixture(), version="2.26.32.xx")
        matrix["evidence"] = {"Example": {"targets": [observation(version="2.26.32.xx")]}}
        self.assertTrue(validate(matrix))

    def test_legacy_global_evidence_cannot_certify_a_cell(self):
        matrix = supported(fixture())
        matrix["evidence"] = {
            "Example": {"resolvers": {"resolveExample": {"verifiedAt": STAMP, "result": "resolved"}}}
        }
        self.assertTrue(validate(matrix))


if __name__ == "__main__":
    unittest.main()
