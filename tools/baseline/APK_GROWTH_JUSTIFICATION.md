APK size ratchet: deliberate reset

This file is the audit trail for a change that moves the APK size baseline. The CI gate
compares against the baseline recorded in the **base revision**, so a change cannot raise
its own limits by editing `baseline.json` in the same commit. That is deliberate and it
is correct. The consequence is that a legitimate step change needs a way through, and this
file is that way: while it exists, the gate is run with the limit stated below and the
text below is echoed into the job summary. Deleting the file restores the normal limit.

accepted-growth-pct: 60

reason:
Compose is now in the module for the first time. The runtime, the Material3 library and
the Compose compiler output are in every APK, and in an unminified debug build that is
dex.

Measured, not estimated:

- Debug APK: 37.03 MiB -> 57.81 MiB (+56.13%). The increase is dex; resources, assets and
  native libraries are unchanged to within a rounding error.
- Release APK, which is what a user downloads: 8.99 MiB -> 13.38 MiB.
- Before accepting the step, `androidx.compose.material:material-icons-extended` was
  removed. It had been pulled in for two icons and added 30 MiB of vector drawables to the
  debug build on its own, taking that build from 57.8 MiB to 87.6 MiB. The remaining
  growth is the framework, not a packaging mistake.

The ratchet goes back to its default when this file is removed. The next deliberate reset
should carry the same measurements rather than a single percentage.