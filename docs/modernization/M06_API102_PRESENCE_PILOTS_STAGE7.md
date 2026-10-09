# M06 stage 7: two further real API 102 pilot adapters (opt-in only)

The pre-existing WA X runtime has 64 feature registrations, and the approved
API 102 branch initially migrated only **CustomTime**. This stage migrates
the initial Xposed callback behavior of two additional Legacy features into
**real libxposed API 102 Hookers**, without claiming device compatibility:

| Feature | Exact legacy DexKit anchor | Original behavior | Modern guard |
| --- | --- | --- | --- |
| FreezeLastSeen | `presencestatemanager/setAvailable/new-state` (contains) | `XC_MethodReplacement.DO_NOTHING` | `modern.feature.freeze_last_seen.enabled` |
| DndMode | `MessageHandler/start` (equals) | `XC_MethodReplacement.DO_NOTHING` | `modern.feature.dnd_mode.enabled` |

Both feature flags are **false by default** and independent of Legacy flags
(including ghostmode). The modern WA X Manager exposes an explicit three-item
experimental feature dialog. The preference relay mirrors only the three
experimental enable flags and CustomTime settings using API102
`getRemotePreferences("wax.runtime.v1")` and never clears any user data.

The modern resolver uses the same anchor and match style as the old
`Unobfuscator`, but requires **exactly one** method and a concrete,
non-native, `void` return before installing a callback that deliberately
does not call `chain.proceed()`. Any absent, ambiguous, or non-void result
is reported as a failure instead of guessing. The hooks are registered by
the process-scoped `ModernHookRegistry` and can be reverted safely.

Separate diagnostic values are stored for WhatsApp and WhatsApp Business:
`modern.feature.{freeze_last_seen,dnd_mode}.state.<package>`. An `INSTALLED`
value means only that the API102 HookHandle was registered; it is **not**
proof that the intended method actually executed or the feature behaved
correctly on a specific WhatsApp version.

## Verification and release gates

- Pure Java test `ModernVoidReplacementPolicyTest` proves invalid, native,
  abstract and non-void methods are rejected.
- Source contract confirms both original DexKit anchors, modern registry
  installation, and explicit Manager settings transport.
- Modern Library and main opt-in APK must pass debug, R8 Release, loader,
  signature, Android Lint, Detekt/Spotless, test coverage and CodeQL.
- Rooted Vector/LSPosed API102 must demonstrate each feature individually
  on actual WhatsApp builds. DND can temporarily interrupt WhatsApp network
  activity; testers must explicitly opt in and be able to disable it and
  restart the target to roll back.
- This stage does not port any other API93 callbacks. No claims of full
  parity, 64/64 compatibility, or production release readiness are permitted.

Never flash another framework or install an unsigned APK automatically.
