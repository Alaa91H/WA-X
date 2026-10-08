"""Negative cases for check_legacy_global_writes.py.

A gate that has only ever passed is not evidence. Each case here builds a tree that the checker must
reject, and the one positive case builds a tree that must pass, so a rule that silently stops
matching reads as a clean repository instead of as a broken gate.
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile
import unittest

CHECKER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_legacy_global_writes.py")


def load_checker():
    spec = importlib.util.spec_from_file_location("check_legacy_global_writes", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class LegacyGlobalWriteCases(unittest.TestCase):
    def setUp(self) -> None:
        self.checker = load_checker()
        self.root = tempfile.mkdtemp(prefix="wax-global-writes-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.addCleanup(setattr, self.checker, "REPO", self.checker.REPO)
        self.addCleanup(setattr, self.checker, "SOURCE_ROOT", self.checker.SOURCE_ROOT)
        self.addCleanup(setattr, self.checker, "LEDGER", self.checker.LEDGER)
        self.source = os.path.join(self.root, "app", "src", "main", "java", "com", "wax", "module")
        os.makedirs(os.path.join(self.source, "xposed", "core"))
        os.makedirs(os.path.join(self.source, "graph"))
        os.makedirs(os.path.join(self.source, "xposed", "graph"))
        os.makedirs(os.path.join(self.root, "tools", "quality"))

    def write(self, relative: str, body: str) -> None:
        path = os.path.join(self.root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)

    def ledger(self, symbols: dict) -> None:
        self.write(
            "tools/quality/legacy_global_writes.json",
            json.dumps({"schema": "wax.legacy_global_writes/1", "symbols": symbols}, indent=2),
        )

    def point_checker_at(self) -> None:
        self.checker.REPO = self.root
        self.checker.SOURCE_ROOT = os.path.join(self.root, "app", "src", "main", "java")
        self.checker.LEDGER = os.path.join(self.root, "tools", "quality", "legacy_global_writes.json")

    def types(self) -> list[str]:
        self.point_checker_at()
        return [finding["type"] for finding in self.checker.check()]

    # A tree that satisfies every rule: the recorded owner writes, the declaration is where it
    # belongs, and the graph is held in one place.
    def write_permitted_tree(self) -> None:
        self.ledger(
            {
                "FeatureLoader.mApp": {"writers": ["app/src/main/java/com/wax/module/xposed/core/FeatureLoader.kt"]},
                "declare:mApp": {"writers": ["app/src/main/java/com/wax/module/xposed/core/FeatureLoader.kt"]},
                "mCurrentActivity": {"writers": ["app/src/main/java/com/wax/module/xposed/core/WaCallback.kt"]},
                "declare:mCurrentActivity": {
                    "writers": ["app/src/main/java/com/wax/module/xposed/core/ModuleRuntime.kt"]
                },
            }
        )
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/FeatureLoader.kt",
            "package com.wax.module.xposed.core\n\nclass FeatureLoader {\n"
            "    @JvmField var mApp: Application? = null\n\n"
            "    fun attach(application: Application) {\n        mApp = application\n    }\n}\n",
        )
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/WaCallback.kt",
            "package com.wax.module.xposed.core\n\nobject WaCallback {\n"
            "    fun created(activity: Activity?) {\n        ModuleRuntime.mCurrentActivity = activity\n    }\n}\n",
        )
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/ModuleRuntime.kt",
            "package com.wax.module.xposed.core\n\nobject ModuleRuntime {\n"
            "    @JvmField var mCurrentActivity: Activity? = null\n}\n",
        )
        self.write(
            "app/src/main/java/com/wax/module/xposed/graph/RuntimeGraphs.kt",
            "package com.wax.module.xposed.graph\n\nimport com.wax.module.graph.RuntimeGraph\n\n"
            "object RuntimeGraphs {\n    @Volatile\n    private var graph: RuntimeGraph? = null\n}\n",
        )

    def test_a_clean_tree_passes(self) -> None:
        self.write_permitted_tree()
        self.assertEqual([], self.types())

    def test_a_new_writer_is_rejected(self) -> None:
        self.write_permitted_tree()
        # A second file writing the same global. The state has not grown, so a count would not see
        # it; the write site rule does.
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/Extra.kt",
            "package com.wax.module.xposed.core\n\nobject Extra {\n"
            "    fun reset() {\n        FeatureLoader.mApp = null\n    }\n}\n",
        )
        self.assertIn("new-writer", self.types())

    def test_a_new_declaration_is_rejected(self) -> None:
        self.write_permitted_tree()
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/SecondOwner.kt",
            "package com.wax.module.xposed.core\n\nobject SecondOwner {\n"
            "    @JvmField var mApp: Application? = null\n}\n",
        )
        self.assertIn("new-owner", self.types())

    def test_a_second_graph_holder_is_rejected(self) -> None:
        self.write_permitted_tree()
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/MyGraphs.kt",
            "package com.wax.module.xposed.core\n\nimport com.wax.module.graph.RuntimeGraph\n\n"
            "object MyGraphs {\n    private var other: RuntimeGraph? = null\n}\n",
        )
        self.assertIn("second-graph-holder", self.types())

    def test_an_unexpected_graph_builder_is_rejected(self) -> None:
        self.write_permitted_tree()
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/BuildsItsOwn.kt",
            "package com.wax.module.xposed.core\n\nimport com.wax.module.graph.RuntimeGraph\n"
            "import com.wax.module.graph.TargetIdentity\n\nobject BuildsItsOwn {\n"
            "    fun build(): RuntimeGraph = RuntimeGraph(TargetIdentity(\"p\", null, 34, javaClass.classLoader!!))\n}\n",
        )
        self.assertIn("unexpected-graph-builder", self.types())

    def test_a_local_declaration_is_not_a_write(self) -> None:
        self.write_permitted_tree()
        # `val pref = ...` is a local. Without this rule the checker would fail on ordinary code
        # that happens to use the same name as a global, which is how gates get switched off.
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/Locals.kt",
            "package com.wax.module.xposed.core\n\nobject Locals {\n"
            "    fun read(): Any {\n        val pref = null\n        return pref\n    }\n\n"
            "    fun make(): Context {\n        val moduleContext = null\n        return moduleContext\n    }\n}\n",
        )
        self.assertEqual([], self.types())

    def test_a_comment_mentioning_a_write_is_not_a_write(self) -> None:
        self.write_permitted_tree()
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/Commented.kt",
            "package com.wax.module.xposed.core\n\nobject Commented {\n"
            "    // mApp = null is what this used to do\n"
            "    /* Utils.xprefs = pref */\n"
            "    fun nothing() = Unit\n}\n",
        )
        self.assertEqual([], self.types())

    def test_a_comparison_is_not_a_write(self) -> None:
        self.write_permitted_tree()
        self.write(
            "app/src/main/java/com/wax/module/xposed/core/Compares.kt",
            "package com.wax.module.xposed.core\n\nobject Compares {\n"
            "    fun attached(): Boolean = FeatureLoader.mApp == null\n}\n",
        )
        self.assertEqual([], self.types())

    def test_a_disappearing_writer_is_not_a_failure(self) -> None:
        self.write_permitted_tree()
        # The ratchet only fails in one direction on purpose: legacy state is meant to go away, and a
        # gate that failed when it did would make progress look like breakage.
        os.remove(os.path.join(self.source, "xposed", "core", "WaCallback.kt"))
        self.assertEqual([], self.types())

    def test_the_real_tree_has_no_unowned_writer(self) -> None:
        # The real ledger, over the real sources. If this fails, either a new writer appeared without
        # being recorded, or the checker's own patterns stopped matching the tree.
        self.assertEqual([], self.checker.check())


if __name__ == "__main__":
    unittest.main(verbosity=2)