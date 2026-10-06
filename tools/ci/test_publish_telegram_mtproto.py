#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).with_name("publish_telegram_mtproto.py")
spec = importlib.util.spec_from_file_location("publish_telegram_mtproto", MODULE_PATH)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)


class TelegramPublisherTests(unittest.TestCase):
    def make_args(self, changelog: str = "✨ Added\n• Feature") -> module.PublishArgs:
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        apk = root / "WA-X-dev-deadbeef.apk"
        apk.write_bytes(b"apk")
        changelog_file = root / "changelog.txt"
        changelog_file.write_text(changelog, encoding="utf-8")
        return module.PublishArgs(
            file=apk,
            publish_type="development",
            version="1.0.0",
            commit="deadbeefcafebabe",
            branch="test",
            sha256="a" * 64,
            size="3 B",
            changelog_file=changelog_file,
            release_url="",
            workflow_url="https://github.com/Alaa91H/WA-X/actions/runs/1",
            chat_id="@WAXposed",
            thread_id=4,
        )

    def test_split_text_respects_limit(self) -> None:
        text = "\n".join(["x" * 500 for _ in range(20)])
        chunks = module.split_text(text, limit=1200)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(len(chunk) <= 1200 for chunk in chunks))

    def test_split_text_preserves_unicode(self) -> None:
        text = "إضافة حماية 🔒\n" * 100
        chunks = module.split_text(text, limit=200)
        self.assertIn("إضافة حماية 🔒", "".join(chunks))

    def test_stable_title(self) -> None:
        args = self.make_args()
        args = module.PublishArgs(**{**args.__dict__, "publish_type": "stable", "version": "2.0.0"})
        self.assertEqual(module.title_for(args), "🚀 WA X v2.0.0")

    def test_caption_contains_integrity_metadata(self) -> None:
        args = self.make_args()
        caption = module.build_caption(args)
        self.assertIn(args.file.name, caption)
        self.assertIn(args.sha256, caption)
        self.assertIn(args.commit[:8], caption)
        self.assertLessEqual(len(caption), 1024)

    def test_validate_rejects_empty_apk(self) -> None:
        args = self.make_args()
        args.file.write_bytes(b"")
        with self.assertRaises(ValueError):
            module.validate_local_inputs(args)

    def test_validate_accepts_small_apk(self) -> None:
        args = self.make_args()
        self.assertEqual(module.validate_local_inputs(args), 3)

    def test_max_limit_matches_2000_mib(self) -> None:
        self.assertEqual(module.MAX_TELEGRAM_BOT_BYTES, 2000 * 1024 * 1024)


if __name__ == "__main__":
    unittest.main()
