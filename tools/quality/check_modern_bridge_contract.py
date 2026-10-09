#!/usr/bin/env python3
"""Enforce that stage-1 API 102 compiles without shadowing the shipped legacy loader."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]


def source(path: str) -> str:
    file = ROOT / path
    if not file.exists():
        raise AssertionError(f"missing required modern contract file: {path}")
    return file.read_text(encoding="utf-8")


def verify() -> None:
    app_build = source("app/build.gradle.kts")
    modern_build = source("modern-runtime/build.gradle.kts")
    modern_entry = source(
        "modern-runtime/src/main/java/com/wax/module/modern/ModernXposedEntry.java"
    )
    facade = source(
        "modern-runtime/src/main/java/com/wax/module/modern/ModernHookBridge.java"
    )
    scope = source(
        "modern-runtime/src/main/java/com/wax/module/modern/ModernTargetPolicy.java"
    )
    manager = source(
        "app/src/main/java/com/wax/module/modern/ModernFrameworkServiceBridge.kt"
    )
    application = source("app/src/main/java/com/wax/module/ModuleApplication.kt")
    manifest = source("app/src/main/AndroidManifest.xml")

    assert 'include(":modern-runtime")' in source("settings.gradle.kts")
    assert "compileOnly(libs.libxposed.modern.api)" in modern_build
    assert "implementation(libs.libxposed.modern.service)" in app_build
    assert "implementation(project(\":modern-runtime\"))" not in app_build
    assert "api(project(\":modern-runtime\"))" not in app_build
    assert "compileOnly(libs.libxposed.legacy)" in app_build
    assert 'android:value="93"' in manifest
    assert (ROOT / "app/src/main/assets/xposed_init").is_file()

    # No modern metadata can enter the same APK as the old assets/xposed_init loader.
    assert not list((ROOT / "app/src").glob("**/META-INF/xposed/java_init.list"))
    assert "extends XposedModule" in modern_entry
    assert "onPackageLoaded(" in modern_entry
    assert "ModernTargetPolicy.isMainTarget" in modern_entry
    feature = source("modern-runtime/src/main/java/com/wax/module/modern/ModernCustomTimeFeature.kt")
    canary_ui = source("modern-canary/src/main/java/com/wax/module/modern/canary/CanaryActivity.java")
    invocation = source("modern-runtime/src/main/java/com/wax/module/modern/ModernInvocationEvidence.java")
    throttle = source("modern-runtime/src/main/java/com/wax/module/modern/ModernInvocationThrottle.java")
    assert "ModernInvocationEvidence.timeKey" in modern_entry
    assert "ModernInvocationEvidence.bootKey" in modern_entry
    assert "invocationThrottle.accept" in modern_entry
    assert "evidenceWorker.execute" in modern_entry
    assert "onFormatted.run()" in feature
    assert "ModernInvocationEvidence.classify" in source("modern-canary/src/main/java/com/wax/module/modern/canary/CanaryApplication.java")
    assert "NOT_OBSERVED" in invocation and "INVOKED_FRESH" in invocation
    assert "AtomicLong" in throttle
    assert "ModernCustomTimeFeature.ENABLE_KEY" in modern_entry
    assert "modern.feature.custom_time.enabled" in feature
    assert "ModernHookRegistry.Registration" in feature
    assert "singleOrNull()" in feature
    assert "setCustomTimeEnabled" in canary_ui
    assert "CustomTime" in canary_ui
    assert "PROTECTIVE" in facade
    lifecycle = source("modern-runtime/src/main/java/com/wax/module/modern/ModernHookRegistry.java")
    assert "hookRegistry.installOnce" in modern_entry
    assert "handle::unhook" in modern_entry
    assert "installFeature(" in lifecycle and "removeFeature(" in lifecycle
    assert "requireUnclaimedId" in lifecycle and "failure.addSuppressed" in lifecycle
    # minSdk 28: java.lang.String.isBlank() was not in older Android core libraries.
    assert ".isBlank()" not in lifecycle
    assert "com.whatsapp.w4b" in scope
    assert "com.whatsapp" in scope
    assert "XposedServiceHelper.registerListener" in manager
    assert "ModernFrameworkServiceBridge.register()" in application
    assert '"wax.runtime.v1"' in manager
    assert '"wax.runtime.v1"' in modern_entry

    # The modern module does not reference APIs rejected by modern Vector/LSPosed.
    modern_java = ROOT / "modern-runtime/src/main/java"
    for file in modern_java.rglob("*.java"):
        content = file.read_text(encoding="utf-8")
        for forbidden in ("de.robv.android.xposed", "XSharedPreferences", "FeatureLoader"):
            if forbidden in content and not file.name == "ModernXposedEntry.java":
                raise AssertionError(f"forbidden legacy runtime API in {file}: {forbidden}")
        for forbidden in ("import de.robv.android.xposed", "FeatureLoader.doHook"):
            if forbidden in content:
                raise AssertionError(f"forbidden modern runtime dependency in {file}: {forbidden}")

    # Framework handshake is not proof that any feature works in WhatsApp.
    assert "NOT_CONNECTED" in manager
    assert "SERVICE_DISCONNECTED" in manager
    assert "connected: Boolean" in manager
    assert "READY" not in manager

    print("Modern API102 staging contract OK: service attached, canary isolated, legacy APK preserved.")


if __name__ == "__main__":
    try:
        verify()
    except (AssertionError, OSError, UnicodeError) as exc:
        print(f"Modern runtime contract FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
