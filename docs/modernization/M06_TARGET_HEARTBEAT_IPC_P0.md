# M06 P0 — why the Manager connected but the WhatsApp runtime stayed NOT_REPORTED

**Observed**: WA X 1.2.0-beta.9 on Android 17, Vector API 102 framework Service handshake succeeds, the official Xposed scope shows the installed `com.whatsapp` package, but WhatsApp reports NOT_REPORTED.

## Root-cause correction

The libxposed API 102 `XposedModule.getRemotePreferences()` in injected target processes is **read-only**. Prior code tried `preferences.edit().putLong(...).apply()` from WhatsApp, while the Manager queried the framework's preferences for these target-authored values. A Service connection does not establish a successful target-to-Manager telemetry channel, and this implementation could never be a reliable target heartbeat.

A second problem: the only Application.attach interception was registered in `onPackageLoaded` behind `param.isFirstPackage()`. A framework could load the module in WhatsApp's main process without satisfying that hint, causing no attempted attach interception at all.

## Fix in this branch

- Install the bootstrap hook in `onModuleLoaded()` as soon as the process name is **exactly** `com.whatsapp` or `com.whatsapp.w4b`. The `onPackageLoaded` callback offers an idempotent fallback and logs `firstPackage`, but it does not gate installation. Never install in system_server, module own process, remote push process or another package.
- Install a Manager-side exported `ContentProvider` at `content://com.wax.module.runtime.telemetry`. Its only useful method is `call("report-target-event-v1", ...)`. The provider checks **the actual Binder calling UID** and its package ownership, allowing only an exact target package matching that UID. Every other caller is rejected. Query, insert, update and delete are unavailable.
- The target sends `BOOTSTRAP=ATTACHED` to that provider off WhatsApp's UI thread. The receiving Manager records timestamp and boot epoch from its **own clock** in **private Manager SharedPreferences**, never trusting a caller-provided clock.
- Feature installation status and throttled real callback evidence return through the same verified transport. The target's `RemotePreferences` path is restricted to **reading** Settings toggles supplied by the Manager. No user messages, contacts, media, personal data or credentials cross the provider.
- The Home screen reads the Manager-owned heartbeat/status and does **not** convert a framework handshake, static module scope or hook installation into READY. Vector's process log records MODULE_LOADED, PACKAGE_LOADED, ATTACH_HOOK_INSTALLED/FAILED, ATTACH_OBSERVED, and target-to-Manager delivery success/failure. Before Application.attach no target Context exists, so early lifecycle stages remain Vector logs, not invented Manager heartbeat entries.

## Testing and limitations

Unit tests enforce precise process/UID checks, rejection of non-target package and arbitrary event methods, and distinguish stage evidence from real bootstrap. Existing CI gates for libxposed 102 loader metadata, signed APK, R8, Android lint, CodeQL, and feature tests still apply. Do not install or claim this fixes the user's phone until a verified debug APK and rooted Vector/WhatsApp experiment exist.

**Device test**: install signed matching-update APK (do not uninstall the production package), confirm WA X connected to Vector 102, force-stop WhatsApp (do not delete WhatsApp data), launch it, open WA X within 90 seconds, and inspect the target card. If still NOT_REPORTED export Vector's **Module Logs and Framework Logs** around the restart, searching for `WA-X Modern` and `Target-to-Manager bootstrap delivery`. The last successful lifecycle event identifies the failing stage. Avoid flashing or updating Vector before collecting logs.
