# M06 stage 2 — isolated signed API 102 canary APK

**Status:** independent diagnostic application, never a replacement for WA X. This stage does not migrate the existing 64 features.

## Build and installation boundaries

The shipping application remains `com.wax.module`, the only package built by the default Gradle graph. The modern test package is `com.wax.module.modern.canary` and is included **only** when Gradle receives `-PenableModernCanary=true`. It can be installed and removed without touching existing WA X / WhatsApp data.

```shell
./gradlew -PenableModernCanary=true :modern-canary:assembleDebug :modern-canary:assembleRelease :modern-runtime:testDebugUnitTest
python tools/quality/check_modern_canary_apk.py --source-only
python tools/quality/check_modern_canary_apk.py --apk modern-canary/build/outputs/apk/debug/modern-canary-debug.apk
```

The modern diagnostic APK uses:
- `META-INF/xposed/java_init.list` containing exactly `com.wax.module.modern.ModernXposedEntry`.
- `module.prop`: min/target API 102, static scope, protective exceptions and hot reload **disabled**.
- `scope.list`: **only** `com.whatsapp` and `com.whatsapp.w4b`.
- No `assets/xposed_init`, legacy framework package, or support for system_server.
- `ModernXposedEntry` compiled from `:modern-runtime` and packaged in the APK; the `io.github.libxposed.api` classes remain compile-only, furnished by compatible Vector/LSPosed.

The debug APK is signed using Android debug signing; the unsigned/minified release APK is compiled **only** for R8 and metadata verification. Never publish the unsigned release artifact. Signing keys must remain private and never committed.

## Target-origin proof, not a fabricated readiness state

The modern entry point installs a protected `Application.attach(Context)` observational hook only for the main target processes. After `chain.proceed()`, a daemon worker writes a timestamp and its package-specific process name to the module's `wax.runtime.v1` remote preferences. Binder calls are off WhatsApp's UI thread.

The separate canary Manager uses its **own** `XposedService` binding, shows framework API/version, and checks the corresponding timestamp and running-target list for both WhatsApp packages. The UI displays UNREPORTED, FRESH_TARGET_REPORT, STALE_TARGET_REPORT or CLOCK_MISMATCH. None are proof that any WA X feature works. The optional switch writes a diagnostic preference, not a WhatsApp change.

**Important:** Modern RemotePreferences are scoped to the module application package. Existing WA X Manager (`com.wax.module`) and the canary package (`com.wax.module.modern.canary`) do **not** share the same provider or settings, even though their preference group has the same name. Do not implement a fallback between them. Full migration must move the official Manager and target Runtime to the same modern module identity.

## Deployment and acceptance

1. Verify the real APK ZIP entries and DEX descriptor (not just source files) using `check_modern_canary_apk.py`.
2. Verify the debug APK's cryptographic signature with the Android SDK `apksigner verify --verbose`. Run the APK contract again after release minification to prove the entry survived R8.
3. Install the canary **alongside** legacy WA X; scope only its two targets in Vector 2.2. Keep rollback: uninstall canary, restart target, legacy WA X remains untouched.
4. With canary Activity open, verify framework API 102 connection. Open WhatsApp then WhatsApp Business separately, and check a target-origin report with current timestamp and matching running target. Record build SHA, Vector build, Android version, WhatsApp builds and test evidence.
5. If a compatibility failure occurs, do not forcibly replace or disable the old WA X module. Diagnose before moving on.

## Remaining M06

- Move real FeatureRegistry, RuntimeGraph and all 64 features from legacy callbacks to `ModernHookBridge`; add feature-level diagnostic criticality and rollback.
- Migrate the production Manager and target preferences under a single modern module package, with user-data-safe migration and rollback.
- Remove legacy loader and resource-hook APIs from the modern production APK before switching release mode.
- Validate on rooted Android 14–17 with Vector/LSPosed, including cold start, process death, multiple accounts, WhatsApp Business and reboot.
