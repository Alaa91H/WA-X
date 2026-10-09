# M06 Stage 5 — distinguish installed hooks from executed hooks

The independent API 102 canary now has two separate evidence layers. It does **not** replace the production WA X application and no feature is enabled by default.

## Evidence and process safety

1. **Target-loader heartbeat:** recorded by the modern entry after the WhatsApp Application.attach callback.
2. **Hook installed:** the DexKit resolver found a unique method and libxposed returned a HookHandle. This does not establish invocation.
3. **Hook actually invoked:** only after the CustomTime Hooker receives a valid Calendar, calls the original chain, and successfully computes a formatted timestamp. A lock-free rate limiter makes at most one report per 30 seconds. The hook thread only increments a counter and queues background work; remote preferences writes run in a dedicated daemon worker.
4. **Rendered outcome:** not remotely provable from a Hooker counter. Still needs a real, user-visible test on a compatible rooted device.

The invocation report records **no chat contents, contact IDs, message body, times, or user-provided text**: only the feature/target identifier, approximate invocation count, timestamp of recent invocation and boot epoch. Each target has a separate key.

## Truthful canary display

The diagnostic UI reports registration separately from the actual callback status:
- `NOT_OBSERVED`: no callback proof even if an installation was reported.
- `INVOKED_FRESH`: recorded callback occurred in the current boot within the permitted freshness window.
- `INVOKED_STALE`: callback record aged out.
- `DIFFERENT_BOOT`: an old callback record cannot be reused after reboot.
- `CLOCK_MISMATCH`: future timestamp is not proof.

If the pilot is disabled, only `DISABLED` is presented. The UI explicitly warns that seeing a fresh callback does not prove visible WhatsApp output.

## Verification

```shell
./gradlew -PenableModernCanary=true :modern-runtime:testDebugUnitTest :modern-canary:assembleDebug
python tools/quality/check_modern_bridge_contract.py
python tools/quality/check_modern_canary_apk.py --apk modern-canary/build/outputs/apk/debug/modern-canary-debug.apk
```

Unit tests cover evidence freshness, previous boot, unknown targets, and simultaneous/throttled reporting. Android CI and CodeQL must be green before merge. A rooted Vector 2.2 device must still verify actual WhatsApp and Business behavior before a production release.

## Rollback

Disable the CustomTime switch in the canary Manager and restart the target process. The existing WA X Legacy APK and WhatsApp data are never modified by the canary's build, identity, or preferences.
