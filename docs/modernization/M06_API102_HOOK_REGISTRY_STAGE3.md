# M06 Stage 3 — modern hook lifecycle and feature isolation

**Scope:** make the API 102 runtime resilient enough for gradual feature migration. This is a runtime infrastructure milestone, not an assertion that the 63 legacy hook feature classes already work on modern libxposed.

## Production safety

- The default WA X APK remains on the legacy loader. The modern implementation exists only in the opt-in canary application. No `META-INF/xposed/java_init.list` is added to the legacy APK.
- Each target process constructs its own `ModernHookRegistry`. No static mutable global registry.
- The bootstrap `Application.attach` hook now registers through `installOnce("runtime.bootstrap", ...)`, and its returned API 102 `HookHandle.unhook()` is retained.
- Hook IDs are globally unique within the process. A feature owns its handles and cannot accidentally overwrite or unhook another feature.
- Future features use `installFeature(featureId, registrations)`: all hook IDs are validated *before* installation; installation is atomic with reverse-order rollback on failure. If an unhook itself fails, its handle remains tracked for later cleanup.
- `removeFeature(featureId)` removes only that feature's handles, in reverse install order; a failed removal remains tracked.
- Existing API 102 framework exception protection remains enabled. Never catch VM failures to keep a potentially corrupted WhatsApp process running.

## Tests and evidence

The new `ModernHookRegistryTest` validates repeated callbacks, owner isolation, collisions, atomic rollback, rollback exception preservation, reverse ordering, retryable failed unhooks, and invalid IDs. Existing three target process policy cases and modern heartbeat/boot evidence tests remain active.

```shell
./gradlew -PenableModernCanary=true :modern-runtime:testDebugUnitTest :modern-canary:assembleDebug
python3 tools/quality/check_modern_bridge_contract.py
python3 tools/quality/check_modern_canary_apk.py --apk modern-canary/build/outputs/apk/debug/modern-canary-debug.apk
```

**Next real gate:** migrate a simple *real* feature through the independent modern hook layer and prove its behavior in the target process on Vector 2.2, with per-feature ready/fail diagnosis. The `DebugFeature` in the legacy registry is a no-op and must not be misrepresented as proof of a user-visible hook. Remaining features, Settings/Proto DataStore, and production Manager sharing require separate milestones.

**Device limitations:** CI cannot provide rooted WhatsApp injection proof. No ADB-connected phone was visible through the authorized PC at this stage; the canary must be tested with an explicitly connected rooted device.
