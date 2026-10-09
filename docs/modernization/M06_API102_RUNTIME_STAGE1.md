# M06 — libxposed API 102 Runtime integration (stage 1)

**State:** experimental, not release-ready. This change is built on `main` after PR #406 and is independent of the blocked Gradle 9.8.1/AGP preview PR #407.

## Components with actual wiring

- `app/.../modern/ModernFrameworkServiceBridge.kt`: installed during `ModuleApplication.onCreate`; uses `XposedServiceHelper.registerListener` and reads `XposedService.getRemotePreferences("wax.runtime.v1")`. Service connection, API version, framework identity, and disconnection are recorded separately from per-target module readiness. Unavailable services never fall back to legacy self-hook evidence.
- `:modern-runtime`: separately compilable modern-API 102 library. Its `ModernXposedEntry` extends `XposedModule`, processes only main `com.whatsapp` and `com.whatsapp.w4b` processes, installs an API102 `Application.attach(Context)` intercept in protective mode, and obtains the same remote preference group through `XposedModule.getRemotePreferences`. The intercept observes startup and does not mutate WhatsApp data or enable legacy features.
- `ModernHookBridge`: encapsulates API 102 `hook(Executable).intercept(Hooker)`. Future feature implementations should depend on this adapter, **not** on `de.robv.android.xposed`.
- `ModernTargetPolicy`: pure, unit-tested main-process scope policy. Rejects background and framework processes and duplicate package callbacks.

## Packaging boundary (deliberate)

The existing user APK still uses `assets/xposed_init`, `ModuleEntryPoint` and legacy API 93. The modern entry lives in a **separate Android library** with no dependency from `:app`. It is **NOT** packaged in the shipped legacy APK. The *service* AAR is included in the Manager only, so a modern framework can connect without switching the target process to a half-migrated loader.

Do not move `ModernXposedEntry` to `app/src/main` or add `META-INF/xposed/java_init.list` to the same artifact as `assets/xposed_init`. That would silently choose the modern loader and disable most features, all of which still call legacy Xposed APIs.

## Required stages to complete M06

1. **Stage 1 (this branch):** Framework service, remote preference contract, modern entry and hook facade compile; unit scope policy tests, evidence contract and single-loader packaging unchanged.
2. **Stage 2:** Separate opt-in modern APK channel with `META-INF/xposed/{module.prop,scope.list,java_init.list}`, manifest, correct R8 keep/adapt rules, and signed APK metadata verification; no ambiguous mixed loader. Make this independent of legacy distribution.
3. **Stage 3:** Migrate feature registry, remote preferences, ModuleRuntime lifecycle and canary, then simple features; classify unsupported features explicitly.
4. **Stage 4:** Migrate remaining feature groups (64 entries), reflection helpers, callback and resource hooks (removed in modern API). Substitute documented resource bridge where needed, migrate WhatsApp and Business variants, and remove all legacy API references from modern APK.
5. **Stage 5:** Vector 2.2 stable + canary device verification (Android 14–17); per-target heartbeat, hooks, preferences, restart, reboot, downgrade/rollback; release only after final user-visible behavior is verified.

## Verification

Run with a JDK supported by AGP:
```shell
./gradlew :modern-runtime:assembleDebug :modern-runtime:testDebugUnitTest :app:assembleDebug
python tools/quality/check_modern_bridge_contract.py
python tools/modernization/collect_m00_baseline.py --check
python tools/modernization/inventory_runtime_surface.py --check
```

## Explicit nonclaims

Successful Gradle tests do not establish API 102 on-device injection or migration of the 64 features. The old **Legacy self-hook** signal remains insufficient proof of target readiness. A service handshake only proves that the Manager can speak to the framework.
