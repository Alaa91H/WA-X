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
    # The original application is modern-only; legacy source hooks may remain
    # compile-only during the migration but cannot be an APK entry point.
    assert 'val modernXposedPackage = true' in app_build
    assert 'implementation(project(":modern-runtime"))' in app_build
    assert 'compileOnly(libs.libxposed.modern.api)' in app_build
    assert 'buildConfigField("boolean", "MODERN_XPOSED", modernXposedPackage.toString())' in app_build
    assert "api(project(\":modern-runtime\"))" not in app_build
    assert "compileOnly(libs.libxposed.legacy)" in app_build
    assert 'android:value="93"' not in manifest
    assert not (ROOT / "app/src/main/assets/xposed_init").exists()
    assert (ROOT / "app/src/main/resources/META-INF/xposed/java_init.list").is_file()
    assert not (ROOT / "modern-canary").exists()
    assert "extends XposedModule" in modern_entry
    assert "onPackageLoaded(" in modern_entry
    assert "ModernTargetPolicy.isMainTarget" in modern_entry
    feature = source("modern-runtime/src/main/java/com/wax/module/modern/ModernCustomTimeFeature.kt")
    modern_home = source("app/src/main/java/com/wax/module/ui/fragments/HomeFragment.kt")
    invocation = source("modern-runtime/src/main/java/com/wax/module/modern/ModernInvocationEvidence.java")
    throttle = source("modern-runtime/src/main/java/com/wax/module/modern/ModernInvocationThrottle.java")
    assert "ModernInvocationEvidence.timeKey" in modern_entry
    assert "ModernInvocationEvidence.bootKey" in modern_entry
    assert "invocationThrottle.accept" in modern_entry
    assert "evidenceWorker.execute" in modern_entry
    assert "onFormatted.run()" in feature
    assert "ModernManagerRuntimeStatus.inspect()" in modern_home
    assert "NOT_OBSERVED" in invocation and "INVOKED_FRESH" in invocation
    assert "AtomicLong" in throttle
    assert "ModernCustomTimeFeature.ENABLE_KEY" in modern_entry
    assert "modern.feature.custom_time.enabled" in feature
    assert "ModernHookRegistry.Registration" in feature
    assert "singleOrNull()" in feature
    assert "showModernCustomTimeDialog" in modern_home
    assert "ModernRuntimePreferenceRelay" in modern_home
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

    # Modern Manager settings must explicitly bridge into this module's API102
    # remote preference group; a service handshake alone is insufficient.
    manager_application = source("app/src/main/java/com/wax/module/ModuleApplication.kt")
    relay = source("app/src/main/java/com/wax/module/modern/ModernRuntimePreferenceRelay.kt")
    if 'if (BuildConfig.MODERN_XPOSED)' not in manager_application:
        raise AssertionError("Modern preference relay must be guarded by the API102 build mode")
    assert "ModernRuntimePreferenceRelay.start(this)" in manager_application
    assert "ModernFrameworkServiceBridge.remotePreferences()" in relay
    assert "ModernFrameworkServiceBridge.setOnConnectedListener" in relay
    assert 'setOf(ENABLE_KEY, "segundos", "ampm", "text_in_hour", "removeforwardlimit", "freezelastseen", "dndmode")' in relay
    assert "PreferenceManager.getDefaultSharedPreferences" in relay
    assert 'enabled = source.getBoolean(ENABLE_KEY, false)' in relay
    assert ".clear()" not in relay
    assert "putBoolean(ENABLE_KEY, values.enabled)" in relay
    assert 'putBoolean("removeforwardlimit", source.getBoolean("removeforwardlimit", false))' in relay
    share_limit = source("modern-runtime/src/main/java/com/wax/module/modern/ModernShareLimitFeature.kt")
    assert "ModernShareLimitFeature.ENABLE_KEY" in modern_entry
    assert '"modern.feature.share_limit.state."' in modern_entry
    assert "MultiSelectionLimitInfo" in share_limit
    assert "ModernHookRegistry.Registration" in share_limit
    assert "chain.proceed(" in share_limit
    assert "ModernShareLimitPolicy" in share_limit
    presence = source("modern-runtime/src/main/java/com/wax/module/modern/ModernPresenceFeatures.kt")
    assert 'FREEZE_KEY = "freezelastseen"' in presence
    assert 'DND_KEY = "dndmode"' in presence
    assert "ModernVoidReplacementPolicy.isSafe" in presence
    assert "ModernPresenceFeatures.Pilot.values()" in modern_entry
    assert "modern.feature." in modern_entry
    assert 'putBoolean("freezelastseen", source.getBoolean("freezelastseen", false))' in relay
    assert 'putBoolean("dndmode", source.getBoolean("dndmode", false))' in relay


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

    print("Official API102-only runtime contract OK: main Manager and service wired, single loader enforced.")


if __name__ == "__main__":
    try:
        verify()
    except (AssertionError, OSError, UnicodeError) as exc:
        print(f"Modern runtime contract FAILED: {exc}", file=sys.stderr)
        sys.exit(1)
