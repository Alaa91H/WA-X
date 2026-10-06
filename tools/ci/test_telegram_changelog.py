#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).with_name("generate_telegram_changelog.py")
spec = importlib.util.spec_from_file_location("generate_telegram_changelog", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


class TelegramChangelogTests(unittest.TestCase):
    def test_conventional_categories(self) -> None:
        commits = [
            ("1", "feat(privacy): add privacy mode", ""),
            ("2", "fix: repair crash", ""),
            ("3", "perf: speed up resolver", ""),
            ("4", "security: harden caller validation", ""),
            ("5", "refactor: simplify registry", ""),
        ]
        with mock.patch.object(module, "read_commits", return_value=commits):
            output = module.generate("base", "HEAD", 100, 30)
        self.assertIn("✨ Added", output)
        self.assertIn("• add privacy mode", output)
        self.assertIn("🐛 Fixed", output)
        self.assertIn("🔒 Security", output)
        self.assertIn("⚡ Performance", output)
        self.assertIn("🛠 Improved", output)

    def test_breaking_change_is_prominent(self) -> None:
        commits = [("1", "feat!: replace storage format", "BREAKING CHANGE: migration required")]
        with mock.patch.object(module, "read_commits", return_value=commits):
            output = module.generate("base", "HEAD", 100, 30)
        self.assertIn("⚠️ Important", output)
        self.assertIn("replace storage format", output)

    def test_unicode_and_arabic_are_preserved(self) -> None:
        commits = [("1", "feat: إضافة حماية 🔒", "")]
        with mock.patch.object(module, "read_commits", return_value=commits):
            output = module.generate("base", "HEAD", 100, 30)
        self.assertIn("إضافة حماية 🔒", output)

    def test_duplicates_are_removed_case_insensitively(self) -> None:
        commits = [
            ("1", "fix: Repair crash", ""),
            ("2", "fix: repair crash", ""),
        ]
        with mock.patch.object(module, "read_commits", return_value=commits):
            output = module.generate("base", "HEAD", 100, 30)
        self.assertEqual(output.lower().count("repair crash"), 1)

    def test_empty_history_has_fallback(self) -> None:
        with mock.patch.object(module, "read_commits", return_value=[]):
            output = module.generate("base", "HEAD", 100, 30)
        self.assertIn("Development build", output)


if __name__ == "__main__":
    unittest.main()
