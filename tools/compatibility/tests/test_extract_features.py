"""Focused regression tests for Issues #394 and #395.

Run: python3 -m unittest discover -s tools/compatibility/tests -p 'test_extract_features.py'
"""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

EXTRACTOR_PATH = Path(__file__).resolve().parents[1] / "extract_features.py"
SPEC = importlib.util.spec_from_file_location("wa_x_feature_extractor", EXTRACTOR_PATH)
extractor = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(extractor)


class PreferenceReadTests(unittest.TestCase):
    def test_typed_shared_preferences_and_settings_reads(self):
        files = {
            "Demo": """
                package com.wax.module.xposed.features.general
                const val PREF_COUNT = "count_pref"
                fun install() {
                    prefs.getBoolean("bool_key", false)
                    prefs.getString("string_key", null)
                    prefs.getInt(PREF_COUNT, 0)
                    prefs.getLong("long_key", 0)
                    prefs.getFloat("float_key", 0f)
                    prefs.getStringSet("set_key", null)
                    settingsStore.read("typed_key")
                    prefs.contains("contains_key")
                    prefs.edit().putBoolean("write_only", true)
                    // prefs.getString("comment_key", null)
                    /* prefs.getLong("block_comment_key", 0) */
                }
            """
        }
        keys = extractor.feature_preference_keys(files)["Demo"]
        self.assertEqual(
            keys,
            sorted([
                "bool_key", "string_key", "count_pref", "long_key",
                "float_key", "set_key", "typed_key", "contains_key",
            ]),
        )

    def test_no_read_does_not_claim_preference(self):
        files = {
            "WriteOnly": """
                const val PREF_UNUSED = "unused"
                fun install() {
                    prefs.edit().putString("written", "value")
                    // prefs.getBoolean("comment", false)
                }
            """
        }
        self.assertNotIn("WriteOnly", extractor.feature_preference_keys(files))


class OwnershipTests(unittest.TestCase):
    def test_duplicate_file_stems_fail_loudly(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for subdir in ("general", "privacy"):
                folder = root / subdir
                folder.mkdir()
                (folder / "Helper.kt").write_text(
                    "package com.wax.module.xposed.features." + subdir + "\n",
                    encoding="utf-8",
                )
            with patch.object(extractor, "FEATURES_DIR", str(root)):
                with self.assertRaisesRegex(ValueError, "duplicate Kotlin file stem"):
                    extractor.feature_files()

    def test_same_package_helper_and_explicit_cross_package_import(self):
        files = {
            "Feature": """
                package com.wax.module.xposed.features.media
                import com.wax.module.xposed.features.general.CrossHelper as AliasHelper
                fun install() { LocalHelper(); AliasHelper(); Unrelated() }
            """,
            "LocalHelper": """
                package com.wax.module.xposed.features.media
                class LocalHelper { fun apply() { Unobfuscator.loadMedia() } }
            """,
            "CrossHelper": """
                package com.wax.module.xposed.features.general
                class CrossHelper { fun apply() { Unobfuscator.loadGeneral() } }
            """,
            "Unrelated": """
                package com.wax.module.xposed.features.others
                class Unrelated { fun apply() { Unobfuscator.loadUnrelated() } }
            """,
        }
        self.assertEqual(
            extractor.feature_closure("Feature", files),
            {"Feature", "LocalHelper", "CrossHelper"},
        )
        self.assertEqual(
            extractor.feature_resolver_usage(files)["Feature"],
            ["loadGeneral", "loadMedia"],
        )


if __name__ == "__main__":
    unittest.main()
