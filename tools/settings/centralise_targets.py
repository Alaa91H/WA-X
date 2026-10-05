#!/usr/bin/env python3
"""Route every duplicated target package reference through TargetPackageRegistry.

The supported package names used to be spelled out in seven files. They are now
owned by `TargetApp` / `TargetPackageRegistry`, and the call sites read from there.

Only the definitions are redirected. The existing public constants on
`FeatureLoader` and `BridgeAccessPolicy` are kept as delegating aliases so no caller
outside this change has to move, which keeps the blast radius to the definitions.
"""
from __future__ import annotations

import io
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

# (relative path, old, new) applied only when `old` is present.
EDITS = [
    (
        "app/src/main/java/com/wax/module/xposed/core/FeatureLoader.kt",
        "const val PACKAGE_WPP = SupportedPackages.WHATSAPP\n"
        "        const val PACKAGE_BUSINESS = SupportedPackages.WHATSAPP_BUSINESS",
        "const val PACKAGE_WPP = TargetPackageRegistry.WHATSAPP\n"
        "        const val PACKAGE_BUSINESS = TargetPackageRegistry.WHATSAPP_BUSINESS",
    ),
    (
        "app/src/main/java/com/wax/module/platform/CompatibilityCanary.kt",
        'const val BUSINESS_PACKAGE: String = SupportedPackages.WHATSAPP_BUSINESS',
        'const val BUSINESS_PACKAGE: String = TargetPackageRegistry.WHATSAPP_BUSINESS',
    ),
    (
        "app/src/main/java/com/wax/module/multipackage/MultiPackage.kt",
        "    WHATSAPP(SupportedPackages.WHATSAPP, SupportedPackages.DISPLAY_NAMES.getValue(SupportedPackages.WHATSAPP)),\n"
        "    BUSINESS(\n"
        "        SupportedPackages.WHATSAPP_BUSINESS,\n"
        "        SupportedPackages.DISPLAY_NAMES.getValue(SupportedPackages.WHATSAPP_BUSINESS),\n"
        "    ),",
        "    WHATSAPP(TargetApp.WHATSAPP.packageName, TargetApp.WHATSAPP.displayName),\n"
        "    BUSINESS(TargetApp.WHATSAPP_BUSINESS.packageName, TargetApp.WHATSAPP_BUSINESS.displayName),",
    ),
    (
        "app/src/main/java/com/wax/module/utils/WhatsAppContactPickerLauncher.kt",
        "private val whatsappPackages = SupportedPackages.ALL.toList()",
        "private val whatsappPackages = TargetPackageRegistry.packageNames.toList()",
    ),
    (
        "app/src/main/java/com/wax/module/utils/WhatsAppContactPickerLauncher.kt",
        'if (packageName == SupportedPackages.WHATSAPP_BUSINESS) "WA X Business" else "WhatsApp"',
        "if (packageName == TargetPackageRegistry.WHATSAPP_BUSINESS) \"WA X Business\" else \"WhatsApp\"",
    ),
    (
        "app/src/main/java/com/wax/module/utils/RootDiagnostics.kt",
        "private val WHATSAPP_PACKAGES = SupportedPackages.ALL.toList()",
        "private val WHATSAPP_PACKAGES = TargetPackageRegistry.packageNames.toList()",
    ),
    (
        "app/src/main/java/com/wax/module/xposed/bridge/BridgeAccessPolicy.kt",
        "val TARGET_PACKAGES: Set<String> = SupportedPackages.ALL",
        "val TARGET_PACKAGES: Set<String> = TargetPackageRegistry.packageNames",
    ),
]


def main() -> int:
    for rel, old, new in EDITS:
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path):
            print("skip (missing) %s" % rel)
            continue
        text = io.open(path, encoding="utf-8", newline="").read()
        if old not in text:
            print("skip (pattern absent) %s" % rel)
            continue
        io.open(path, "w", encoding="utf-8", newline="").write(text.replace(old, new))
        print("updated %s" % rel)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
