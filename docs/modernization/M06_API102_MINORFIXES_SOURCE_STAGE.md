# M06 API 102 MinorFixes source-stage adapter

This changeset adds a guarded, *unwired* libxposed API 102 adapter for the
legacy DocumentPicker / ML Kit initialization repair. It is included for source
review and regression tests, not as evidence that the fix works on a device.

- Default is disabled by the explicit preference 'modern.feature.minor_fixes.enabled'.
- The ModernXposedEntry runtime does not register this adapter. It therefore
  has no user-visible effect in released builds.
- The adapter checks an exact DocumentPicker class name and never replaces
  Activity.onCreate. Optional ML Kit initialization failures are logged and
  the Activity's original callback is retained.
- Hook ownership is limited to 'minor_fixes' through ModernHookRegistry.
- Source tests cover exact-class selection and already-initialized exception
  handling. They do **not** prove invocation or device compatibility.

Before activation: verify both target package/class variants from actual
rooted Vector Logcat, add authenticated Manager preference relay and
per-feature target-side diagnostics, instrument hook invocation and rollback,
run on-device regression checks, and only then consider wiring the adapter.
Never report this source-stage adapter as an installed or verified feature.
