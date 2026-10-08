"""Negative cases for check_feature_registry.py.

Each case builds a tree the checker must reject and asserts the specific finding, because a gate that
reports the wrong defect is as useless as one that reports none. The last case runs the real tree,
which is what stops the checker from being "correct" on fixtures and wrong on the repository.
"""

from __future__ import annotations

import importlib.util
import io
import json
import os
import shutil
import tempfile
import unittest

CHECKER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_feature_registry.py")

REGISTRY_BODY = """package com.wax.module.xposed.registry

object RuntimeFeatureRegistry {
    val entries: List<FeatureFactory> =
        listOf(
            FeatureFactory.Contract("DebugFeature") { DebugFeature() },
            FeatureFactory.Legacy("MinorFixes") { loader, preferences -> MinorFixes(loader, preferences) },
        )
}
"""

FEATURE_BODY = """package com.wax.module.xposed.features.others

import com.wax.module.xposed.core.Feature

class MinorFixes(
    classLoader: ClassLoader,
    prefs: android.content.SharedPreferences,
) : Feature(classLoader, prefs) {
    override fun doHook() = Unit

    override fun getPluginName(): String = "Minor Fixes"
}
"""


def load_checker():
    spec = importlib.util.spec_from_file_location("check_feature_registry", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FeatureRegistryCases(unittest.TestCase):
    def setUp(self) -> None:
        self.checker = load_checker()
        self.root = tempfile.mkdtemp(prefix="wax-registry-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        for name in (
            "REPO",
            "MAIN",
            "FEATURES_DIR",
            "REGISTRY",
            "LOADER",
            "EXTRACTOR",
            "COMPATIBILITY",
        ):
            self.addCleanup(setattr, self.checker, name, getattr(self.checker, name))
        self.features = os.path.join(self.root, "app", "src", "main", "java", "com", "wax", "module", "xposed", "features", "others")
        self.registry_dir = os.path.join(self.root, "app", "src", "main", "java", "com", "wax", "module", "xposed", "registry")
        self.core_dir = os.path.join(self.root, "app", "src", "main", "java", "com", "wax", "module", "xposed", "core")
        self.tools = os.path.join(self.root, "tools", "compatibility")

    def write(self, path: str, body: str) -> None:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(body)

    def point_checker_at(self) -> None:
        self.checker.REPO = self.root
        self.checker.MAIN = os.path.join(self.root, "app", "src", "main", "java", "com", "wax", "module")
        self.checker.FEATURES_DIR = os.path.join(self.checker.MAIN, "xposed", "features")
        self.checker.REGISTRY = os.path.join(self.registry_dir, "RuntimeFeatureRegistry.kt")
        self.checker.LOADER = os.path.join(self.core_dir, "FeatureLoader.kt")
        self.checker.EXTRACTOR = os.path.join(self.tools, "extract_features.py")
        self.checker.COMPATIBILITY = os.path.join(self.tools, "compatibility.json")

    def types(self) -> list[str]:
        self.point_checker_at()
        return [finding["type"] for finding in self.checker.check()]

    def write_consistent_tree(self) -> None:
        self.write(os.path.join(self.registry_dir, "RuntimeFeatureRegistry.kt"), REGISTRY_BODY)
        self.write(os.path.join(self.features, "MinorFixes.kt"), FEATURE_BODY)
        self.write(os.path.join(self.features, "DebugFeature.kt"), "package com.wax.module.xposed.features.others\n\nclass DebugFeature : com.wax.module.contract.WaFeature {\n    override val featureId = \"DebugFeature\"\n\n    override fun start(context: com.wax.module.contract.FeatureContext) = error(\"not run in a test\")\n}\n")
        self.write(os.path.join(self.core_dir, "FeatureLoader.kt"), "package com.wax.module.xposed.core\n\nobject FeatureLoader {\n    fun plugins() = RuntimeFeatureRegistry.entries\n}\n")
        self.write(
            os.path.join(self.tools, "extract_features.py"),
            'FEATURE_REGISTRY = "RuntimeFeatureRegistry.kt"\n',
        )
        self.write(
            os.path.join(self.tools, "compatibility.json"),
            json.dumps({"derived": {"features": [{"id": "DebugFeature"}, {"id": "MinorFixes"}]}}),
        )

    def test_a_consistent_tree_passes(self) -> None:
        self.write_consistent_tree()
        self.assertEqual([], self.types())

    def test_a_missing_registry_is_rejected(self) -> None:
        self.write_consistent_tree()
        os.remove(os.path.join(self.registry_dir, "RuntimeFeatureRegistry.kt"))
        self.assertIn("missing-registry", self.types())

    def test_a_feature_on_disk_that_is_not_registered_is_rejected(self) -> None:
        self.write_consistent_tree()
        self.write(
            os.path.join(self.features, "GhostFeature.kt"),
            FEATURE_BODY.replace("MinorFixes", "GhostFeature").replace("Minor Fixes", "Ghost"),
        )
        self.assertIn("unregistered-feature", self.types())

    def test_a_duplicate_id_is_rejected(self) -> None:
        self.write_consistent_tree()
        body = REGISTRY_BODY.replace(
            'FeatureFactory.Legacy("MinorFixes") { loader, preferences -> MinorFixes(loader, preferences) },',
            'FeatureFactory.Legacy("MinorFixes") { loader, preferences -> MinorFixes(loader, preferences) },\n'
            '            FeatureFactory.Legacy("MinorFixes") { loader, preferences -> MinorFixes(loader, preferences) },',
        )
        self.write(os.path.join(self.registry_dir, "RuntimeFeatureRegistry.kt"), body)
        self.assertIn("duplicate-id", self.types())

    def test_derived_drift_is_rejected(self) -> None:
        self.write_consistent_tree()
        # The matrix promises a feature the module never installs.
        self.write(
            os.path.join(self.tools, "compatibility.json"),
            json.dumps(
                {
                    "derived": {
                        "features": [{"id": "DebugFeature"}, {"id": "MinorFixes"}, {"id": "NeverInstalled"}]
                    }
                }
            ),
        )
        self.point_checker_at()
        findings = self.checker.check()
        self.assertIn("derived-drift", [finding["type"] for finding in findings])
        drift = [finding for finding in findings if finding["type"] == "derived-drift"][0]
        self.assertIn("NeverInstalled", drift["message"])

    def test_reflective_construction_is_rejected(self) -> None:
        self.write_consistent_tree()
        self.write(
            os.path.join(self.core_dir, "FeatureLoader.kt"),
            "package com.wax.module.xposed.core\n\nobject FeatureLoader {\n"
            "    fun build(clazz: Class<*>) = clazz.getConstructor(ClassLoader::class.java).newInstance(null)\n}\n",
        )
        self.assertIn("reflective-construction", self.types())

    def test_an_extractor_that_ignores_the_registry_is_rejected(self) -> None:
        self.write_consistent_tree()
        self.write(
            os.path.join(self.tools, "extract_features.py"),
            'FEATURE_REGISTRY = "xposed/core/FeatureLoader.kt"\n',
        )
        self.assertIn("extractor-not-unified", self.types())

    def test_a_class_merely_mentioning_feature_is_not_a_feature(self) -> None:
        self.write_consistent_tree()
        # A file whose body talks about Feature without extending it must not be demanded in the
        # registry, or the gate would push people to wrap helpers in a registration.
        self.write(
            os.path.join(self.features, "FeatureHelper.kt"),
            "package com.wax.module.xposed.features.others\n\nobject FeatureHelper {\n"
            "    fun describe(): String = \"Feature\"\n}\n",
        )
        self.assertEqual([], self.types())

    def test_the_real_tree_is_consistent(self) -> None:
        # The real registry, the real features and the real compatibility matrix.
        self.assertEqual([], self.checker.check())
        self.assertEqual(64, len(self.checker.registry_entries()))


if __name__ == "__main__":
    unittest.main(verbosity=2)