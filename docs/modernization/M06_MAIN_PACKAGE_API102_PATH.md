# M06 — same-package API 102 build path, gated and experimental

**Status: build infrastructure only. NOT a completed migration or a supported release.**

The default WA X Android build remains the legacy Xposed 93 module with the existing package
`com.wax.module` and its normal manager UI and settings. Modern libxposed 102 entry points
were previously restricted to the separately installable `modern-canary` APK.

This stage introduces an opt-in `-PmodernXposed=true` lane **using the real WA X Manager,
its current applicationId `com.wax.module`, and the modern Runtime AAR**. It must
never run during signed release publishing or overwrite the normal release artifact.

## Loader transition, verified on the APK itself

- Gradle builds a transformed **modern manifest** from the real AndroidManifest.xml,
  asserting and stripping the five legacy Xposed metadata tags including
  `xposedminversion=93`; the original source manifest is not modified.
- Copies all Manager assets into a generated directory and removes **only**
  `xposed_init`; editor/prism/etc. assets remain available.
- Adds the modern-runtime library and published API 102 `compileOnly` dependency
  **only with the opt-in build property**.
- Copies the already-reviewed `META-INF/xposed/{java_init.list,module.prop,scope.list}`
  from the modern canary: loader entry `ModernXposedEntry`, min/target API 102,
  limited WhatsApp/Business target scope, hot reload disabled.
- Restricts the R8 modern entry keep rule to the opt-in lane.
- Gives this APK a visible `-api102-experimental` version suffix, prohibits a
  releaseTag for modern mode, and prevents a DexKit cache reset on installation.
- The default build and its strict loader contract remain unchanged. The CI
  verifies the real Debug and minified Release APKs have **exactly one loader,
  correct module entry in DEX, the full Manager class and editor assets**.
- The debug APK is debug-signed; the minified Release without private signing
  credentials is **not** presented as installable.

## Essential limitations (do not misrepresent)

**This is not yet the full Runtime migration.** Most existing feature classes
depend directly on legacy `XposedBridge` and related APIs. API 102 already has
the independent registry, remote preferences, target bootstrap, and an opt-in
CustomTime pilot; **other legacy features are not automatically available** in
this modern-lane APK. The legacy hook APIs may still be compiled into its DEX
as ordinary application classes, but they are not registered by the API 102 entry.
This build path must remain experimental until these hooks, settings and per-target
health reports are migrated and verified.

The current Home screen's legacy-self-hook and legacy heartbeat diagnostic are
also still designed for the old Runtime and must be modernized before a usable
API 102 production Manager can be released.

## Testing and installation safety

```sh
./gradlew -PmodernXposed=true :app:assembleDebug :app:assembleRelease
python3 tools/quality/check_modern_main_apk.py --source-only
python3 tools/quality/check_modern_main_apk.py --generated-only
python3 tools/quality/check_modern_main_apk.py --apk app/build/outputs/apk/debug/app-debug.apk
```

**Do not install over a production WA X APK with a different signing key.**
The same applicationId requires the same signing identity for in-place upgrade;
uninstalling to get around this destroys app data unless backed up. Do not
auto-install or flash this preview. Only test on an explicitly authorized rooted
Vector v2.2 device, with a known rollback and a WhatsApp / Business compatibility
matrix. Test actual user-visible features and persistent settings before switching
the default release configuration.
