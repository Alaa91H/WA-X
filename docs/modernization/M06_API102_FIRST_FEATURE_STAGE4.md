# M06 stage 4 — first real modern feature (CustomTime)

The legacy WA X production APK is not changed. This experiment extends only the separately installable, opt-in `modern-canary` package.

## Scope and toggle

`CustomTime` is a user-visible cosmetic feature already present in the legacy WA X registry. This is its first implementation using `libxposed API 102`. The canary's modern remote preference flag `modern.feature.custom_time.enabled` is **false by default**; the Canary Manager has a separate explicit switch. Updating the flag requires restarting the WhatsApp target process to reinstall the hook safely. This does not migrate the complete old preference schema.

The new `ModernCustomTimeFeature`:
- Uses a DexKit matcher with the same numeric and method-shape signature used by the legacy `Unobfuscator.loadTimeToSecondsMethod` resolver (223 and 224, static String method, two parameters, Calendar second).
- Accepts a method only when **exactly one** candidate matches and its reflected shape is valid. Ambiguous/missing candidates report `RESOLVER_MISSING` rather than guessing a method or modifying arbitrary WhatsApp classes.
- Installs with `ModernHookRegistry.installFeature` and API 102 `Hooker`. Calls `chain.proceed()` first, then formats returned timestamp text from Calendar, retaining legacy [TIME] template and AM/PM/seconds behavior.
- Runs resolver and remote settings on an asynchronous reporter thread after `Application.attach`; no Dex scan or remote IPC on the target UI thread.
- Reports `DISABLED`, `INSTALLED`, `RESOLVER_MISSING` or a generic exception class to the canary-only preference group for each target.
- Uses the existing local `app/libs/dexkit-android.aar` in **canary only**; binary version remains unverified, see #327. The production WA X APK and native libraries are unchanged.

## Do not overstate readiness

`INSTALLED` means only that an API 102 hook handle was registered, not that WhatsApp calls the method or produces correct visible timestamps. A version change may invalidate the DexKit resolver. Neither CI nor JVM formatting tests prove rooted-device behavior.

## Acceptance path

1. Green strict Android CI, CodeQL, unit/coverage, source inventory, APK metadata and signature checks; independent canary debug artifact only.
2. Test with Vector 2.2 on rooted Android: turn on canary in Vector for one target, start it, observe fresh target heartbeat and `CustomTime: INSTALLED`, then visually check displayed time changes with seconds/AM-PM and default format.
3. Disable feature and restart the target; verify no custom timestamp behavior and that legacy WA X remains unaffected. Test Business separately.
4. On resolver error, show incompatibility and do not claim supported feature status.

**No automatic APK installation, flashing, dual-loader packaging, or silent production-release replacement.**
