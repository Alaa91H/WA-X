# M06 stage 6 — real WA X Manager settings to API 102 RemotePreferences

This change extends the gated, same-package modern build from PR #413. It is a
**pilot**, not a completed migration of the legacy feature registry.

## Requirements and safety

- Only `BuildConfig.MODERN_XPOSED` builds initialize the bridge. The published
  Legacy 93 release and its preference lifecycle are unchanged.
- The modern Manager uses the original WA X app identity `com.wax.module`,
  and its ordinary Manager `PreferenceManager.getDefaultSharedPreferences`.
- A narrowly scoped `ModernRuntimePreferenceRelay` mirrors only the four
  CustomTime keys: `modern.feature.custom_time.enabled`, `segundos`, `ampm`,
  and `text_in_hour`. It uses the official libxposed API 102 service-backed
  `getRemotePreferences("wax.runtime.v1")`, not XSharedPreferences.
- The opt-in flag defaults **false** regardless of the values of the other
  formatting preferences. Changing time formatting never silently enables the
  experimental hook.
- A user must explicitly tap the modern status card and enable/disable
  CustomTime. The action saves their opt-in in the original Manager's
  local settings. Closing/reopening WhatsApp is necessary to update hooks.
- A retained SharedPreferences listener sends updates asynchronously to a
  single daemon worker. The service's on-bind notification schedules a retry.
  The hook thread and the Android UI thread never perform remote preference IPC.
- No preference file is cleared, no user identity is changed, and no chat,
  contact, image, credential or network data is mirrored to the hook service.
  The remote group may contain other per-target keys that are left untouched.
- The pilot is deliberately absent from the default Legacy build UI, and
  the modern page explicitly warns that the other legacy features are not
  available with API 102 yet.

## Quality gates and remaining work

The new pure `ModernCustomTimeSettingPolicyTest` exercises explicit opt-in,
formatting preservation, and safe default template behavior. Source and runtime
inventories must be regenerated alongside the new Kotlin production/test files.
Full CI/CodeQL, APK loader validation and strict localized lint are required
before merging. No rooted-device proof is claimed.

To make WA X **fully** modern, every other feature adapter must be migrated,
the target compatibility matrix must be filled with real Vector 2.2 rooted
Android evidence, and reversible updates must preserve each user's settings.
The existence of this synchronization bridge or a green build does not prove
that older features have migrated or that WhatsApp rendered the changed time.
