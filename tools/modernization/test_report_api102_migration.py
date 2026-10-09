#!/usr/bin/env python3
"""No device readiness claims may be manufactured by the source migration ledger."""
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("report_api102_migration.py")
SPEC = importlib.util.spec_from_file_location("api102_migration_report", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

REGISTRY = '''
FeatureFactory.Contract("DebugFeature") { DebugFeature() },
FeatureFactory.Legacy("CustomTime") { loader, prefs -> CustomTime(loader, prefs) },
FeatureFactory.Legacy("ShareLimit") { loader, prefs -> ShareLimit(loader, prefs) },
FeatureFactory.Legacy("Others") { loader, prefs -> Others(loader, prefs) },
'''


class MigrationLedgerTests(unittest.TestCase):
    def test_counts_only_explicitly_wired_modern_adapter(self):
        entry = "ModernCustomTimeFeature.INSTANCE.install()"
        sources = {
            "ModernCustomTimeFeature.kt": "object ModernCustomTimeFeature {}",
            "ModernShareLimitFeature.kt": "object ModernShareLimitFeature {}",
        }
        report = MODULE.inspect(REGISTRY, entry, sources)
        self.assertEqual(4, report["counts"]["total"])
        self.assertEqual(1, report["counts"]["api102_wired_in_source"])
        self.assertEqual(1, report["counts"]["adapter_present_not_wired"])
        self.assertEqual(2, report["counts"]["legacy_only"])
        self.assertEqual(0, report["counts"]["device_behavior_verified"])
        states = {x["id"]: x["source_state"] for x in report["features"]}
        self.assertEqual("API102_SOURCE_WIRED_DEVICE_UNVERIFIED", states["CustomTime"])
        self.assertEqual("API102_SOURCE_NOT_WIRED", states["ShareLimit"])
        for feature in report["features"]:
            self.assertFalse(feature["target_runtime_verified"])
            self.assertFalse(feature["user_visible_behavior_verified"])

    def test_enum_grouped_presence_adapters_are_source_wired_when_installed(self):
        registry = ('FeatureFactory.Legacy("FreezeLastSeen") {} '
                    'FeatureFactory.Legacy("DndMode") {}')
        grouped = '''
object ModernPresenceFeatures {
    enum class Pilot(val id: String) {
        FREEZE_LAST_SEEN("freeze_last_seen"),
        DND_MODE("dnd_mode"),
    }
}
'''
        entry = 'for (ModernPresenceFeatures.Pilot pilot : ModernPresenceFeatures.Pilot.values()) { ModernPresenceFeatures.INSTANCE.install(pilot); }'
        matrix = MODULE.inspect(registry, entry, {"ModernPresenceFeatures.kt": grouped})
        self.assertEqual(2, matrix["counts"]["api102_wired_in_source"])
        self.assertEqual(0, matrix["counts"]["device_behavior_verified"])
        disconnected = MODULE.inspect(registry, "// no installed presence group", {"ModernPresenceFeatures.kt": grouped})
        self.assertEqual(2, disconnected["counts"]["legacy_only"])

    def test_class_name_in_comment_is_not_enough_without_definition(self):
        report = MODULE.inspect(
            REGISTRY,
            "// ModernShareLimitFeature is planned, but no implementation exists",
            {},
        )
        self.assertEqual("LEGACY_ONLY", report["features"][2]["source_state"])

    def test_duplicates_and_empty_registry_fail_closed(self):
        with self.assertRaises(ValueError):
            MODULE.registry_names('FeatureFactory.Legacy("Same") {} FeatureFactory.Legacy("Same") {}')
        with self.assertRaises(ValueError):
            MODULE.registry_names('val entries = emptyList()')

    def test_order_is_canonical_registry_order(self):
        self.assertEqual(["DebugFeature", "CustomTime", "ShareLimit", "Others"],
                         [x["id"] for x in MODULE.inspect(REGISTRY, "", {})["features"]])


if __name__ == "__main__":
    unittest.main()
