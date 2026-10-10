"""Regression tests for compatibility report status summaries (#392)."""

from __future__ import annotations

import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sync_generated  # noqa: E402


def fixture() -> dict:
    return {
        "module": {
            "minSdk": 28,
            "targetSdk": 34,
            "compileSdk": 37,
            "abis": ["arm64-v8a"],
        },
        "packages": {
            "whatsapp": {
                "declaredVersions": ["w1", "w2", "w3"],
                "defaultStatus": "unknown",
            },
            "business": {
                "declaredVersions": ["b1", "b2"],
                "defaultStatus": "unknown",
            },
        },
        "statusVocabulary": {
            status: status
            for status in ("supported", "degraded", "unsupported", "unknown")
        },
        "resolutionTiers": {
            tier: {"meaning": tier}
            for tier in ("none", "indirect", "dexkit")
        },
        "derived": {
            "features": [{
                "id": "ExampleFeature",
                "category": "general",
                "resolutionTier": "none",
                "resolutionSources": [],
                "resolverDependencies": [],
                "preferenceKeys": [],
            }],
        },
        "matrix": {},
        "evidence": {},
    }


class CompatibilityReportTests(unittest.TestCase):
    def test_all_unknown_preserves_zero_evidence_message(self) -> None:
        report = sync_generated.render(fixture())
        self.assertIn("No cell in this matrix is resolver-verified yet.", report)
        self.assertIn("they are **not** evidence", report)
        self.assertIn("| Package | Declared versions | Resolver-verified |", report)
        self.assertIn("| WhatsApp | 3 | 0 / 3 cells |", report)
        self.assertIn("| WhatsApp Business | 2 | 0 / 2 cells |", report)

    def test_mixed_statuses_are_counted_without_certification(self) -> None:
        matrix = fixture()
        matrix["matrix"] = {
            "ExampleFeature": {
                "whatsapp": {"versions": {"w1": "supported", "w2": "degraded"}},
                "business": {"versions": {"b1": "unsupported"}},
            }
        }
        report = sync_generated.render(matrix)
        self.assertIn("Across 5 feature/version cells:", report)
        self.assertIn("1 `supported` status claim(s)", report)
        self.assertIn("1 `degraded`, 1 `unsupported`, and 2 `unknown`", report)
        self.assertIn("not independent resolver verification", report)
        self.assertNotIn("No cell in this matrix is resolver-verified yet.", report)
        self.assertIn("| WhatsApp | 3 | 1 / 3 cells | 1 | 0 | 1 |", report)
        self.assertIn("| WhatsApp Business | 2 | 0 / 2 cells | 0 | 1 | 1 |", report)

    def test_degraded_without_supported_is_not_reported_as_all_unknown(self) -> None:
        matrix = fixture()
        matrix["packages"]["whatsapp"]["defaultStatus"] = "degraded"
        report = sync_generated.render(matrix)
        self.assertIn("0 `supported` status claim(s)", report)
        self.assertIn("3 `degraded`, 0 `unsupported`, and 2 `unknown`", report)
        self.assertNotIn("No cell in this matrix is resolver-verified yet.", report)


class InheritedCertificationGateTests(unittest.TestCase):
    """The generator half of the #396 gate.

    The validator already refuses a package-wide ``supported`` default. These
    tests cover the other half: the generator must refuse to *emit* a cell it
    cannot justify, because it can be run on its own.
    """

    def test_a_supported_default_is_listed_for_both_packages(self) -> None:
        for package in ("whatsapp", "business"):
            with self.subTest(package=package):
                matrix = fixture()
                matrix["packages"][package]["defaultStatus"] = "supported"
                inherited = sync_generated.inherited_supported_cells(matrix)
                self.assertTrue(inherited, "%s inherited cells were not caught" % package)
                for cell in inherited:
                    self.assertIn(package, cell)

    def test_a_supported_default_lists_every_declared_version(self) -> None:
        matrix = fixture()
        matrix["packages"]["whatsapp"]["defaultStatus"] = "supported"
        inherited = sync_generated.inherited_supported_cells(matrix)
        for version in matrix["packages"]["whatsapp"]["declaredVersions"]:
            self.assertTrue(
                any(cell.endswith("/whatsapp/%s" % version) for cell in inherited),
                "version %s was not listed" % version,
            )

    def test_an_explicit_cell_is_never_listed(self) -> None:
        matrix = fixture()
        matrix["packages"]["whatsapp"]["defaultStatus"] = "supported"
        matrix["matrix"] = {
            "ExampleFeature": {
                "whatsapp": {"versions": {"w1": "supported"}},
            }
        }
        inherited = sync_generated.inherited_supported_cells(matrix)
        self.assertFalse(
            any(cell == "ExampleFeature/whatsapp/w1" for cell in inherited),
            "an evidence-backed cell must not be treated as inherited",
        )
        self.assertTrue(any(cell.endswith("/whatsapp/w2") for cell in inherited))

    def test_the_gate_raises_rather_than_emitting(self) -> None:
        matrix = fixture()
        matrix["packages"]["business"]["defaultStatus"] = "supported"
        with self.assertRaises(SystemExit) as raised:
            sync_generated.refuse_inherited_certification(matrix)
        self.assertIn("refusing to generate", str(raised.exception))

    def test_other_defaults_pass_the_gate(self) -> None:
        for status in ("unknown", "degraded", "unsupported"):
            with self.subTest(status=status):
                matrix = fixture()
                matrix["packages"]["whatsapp"]["defaultStatus"] = status
                matrix["packages"]["business"]["defaultStatus"] = status
                self.assertEqual(
                    [],
                    sync_generated.inherited_supported_cells(matrix),
                )
                sync_generated.refuse_inherited_certification(matrix)

    def test_the_real_generation_still_runs(self) -> None:
        # The gate must not break the zero-evidence matrix this project has.
        matrix = fixture()
        report = sync_generated.render(matrix)
        self.assertIn("No cell in this matrix is resolver-verified yet.", report)


if __name__ == "__main__":
    unittest.main()
