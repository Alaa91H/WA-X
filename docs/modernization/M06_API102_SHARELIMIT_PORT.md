# M06 — ShareLimit migrated through libxposed API 102

This stage replaces the old `ShareLimit` constructor hook with a native API102
Hooker, using the original WA X app identity (`com.wax.module`). It does not
change WhatsApp, Vector, the installed APK, or a user's account.

## Resolver and behavior

- The migrated feature uses the exact legacy DexKit string anchor
  `MultiSelectionLimitInfo`, and will never pick a class if the resolver
  finds zero or multiple candidates.
- Constructor hooks are allowed only when the first parameter is a primitive
  `int`. Unsupported class signatures return `UNSAFE_SIGNATURE`.
- The API102 Hooker forwards a defensive copy of the original constructor
  arguments through `Chain.proceed(args)`, replacing **only the first integer**
  with `Int.MAX_VALUE`, consistent with the old feature.
- `ModernHookRegistry.installFeature` owns every hook handle, rejects repeated
  identifiers, and rolls back partially installed sets.
- The original local Manager preference `removeforwardlimit` is mirrored via
  framework-owned RemotePreferences. Its default is **false**, and neither
  importing old settings nor enabling other features turns it on.
- The target reports `DISABLED`, `INSTALLED`, `RESOLVER_MISSING`,
  `RESOLVER_AMBIGUOUS`, `UNSAFE_SIGNATURE` or `ERROR_*` separately for
  WhatsApp and Business. The Manager displays the result without claiming that
  the user-visible feature has executed.

## Acceptance tests

1. `modern-runtime:testDebugUnitTest`: signature and copy safety, including
   unchanged original inputs and disabled no-op behavior.
2. `app:assembleDebug`, strict Kotlin formatting and Android CI/CodeQL.
3. Confirm each APK has just the libxposed API102 loader and valid app identity.
4. On a rooted Vector/LSPosed device, enable the existing forwarding-limit
   toggle, restart WhatsApp and demonstrate actual forwarding beyond the stock
   limit; test disabling restores stock behavior. Repeat for Business if
   installed; run process-death/reboot checks.
5. When the resolver cannot find the unique matching class, preserve the
   original app behavior and report compatibility failure.

**Not verified on a rooted phone in CI.** A registered HookHandle is not
evidence that WhatsApp invoked it, so neither READY nor complete compatibility
is claimed before the device checks.
