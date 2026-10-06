# WA X — Xposed / LSPosed Scope

WA X currently uses the **legacy Xposed API** (`assets/xposed_init` + `xposedminversion`).

## Required scope

The module only installs hooks in these processes:

| Package | Role |
|---|---|
| `com.whatsapp` | WhatsApp target |
| `com.whatsapp.w4b` | WhatsApp Business target |
| `android` / System Framework | package-visibility and installer infrastructure |
| `com.android.providers.settings` | settings-provider bridge infrastructure |

The Android manifest publishes the same set through legacy `xposedscope` metadata, so LSPosed can mark/select the recommended scope automatically.

The module entry point performs a second runtime check and immediately returns for every package outside this set. Accidentally ticking another application in LSPosed therefore does not make WA X install its feature hooks there.

## Why unrelated apps can still appear in the LSPosed selector

This is a limitation of the current **legacy** Xposed module format, not a missing WA X filter.

In LSPosed, legacy modules are always treated as `staticScope = false`. The `xposedscope` manifest metadata is a **recommended scope**, not a hard/static scope, so the manager may continue to display other installed applications in the selection screen.

A true manager-enforced static scope requires migrating the whole module to the modern libxposed module format (`META-INF/xposed/java_init.list`, `scope.list`, `module.prop` with `staticScope=true`). That migration changes the Xposed API and callback model and must not be faked by adding modern metadata to a legacy module: doing so can make LSPosed treat the APK as a modern module and stop loading the existing legacy entry point.

Until a full modern-API migration is completed, the safe behavior is:

1. Declare only the four required packages as recommended.
2. Let LSPosed preselect/recommend them.
3. Reject every unrelated package again in `ModuleEntryPoint`.

This keeps WA X from hooking unrelated apps without risking a partial modern-API migration.
