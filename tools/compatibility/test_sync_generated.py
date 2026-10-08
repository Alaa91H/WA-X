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


if __name__ == "__main__":
    unittest.main()
