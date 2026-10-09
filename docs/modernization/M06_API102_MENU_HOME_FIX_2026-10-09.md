# Restore the WA X entry inside WhatsApp (API 102)

## Rooted-device evidence collected read-only, 2026-10-09

Poco F5, Android 17 (SDK 37), WhatsApp 2.26.39.74, and
WA X 1.2.0-beta.9-dev+2DEBBFD8 were inspected by ADB without
modifying the device. The Vector log reports loading the WA X module
successfully. Authenticated target telemetry contains a recent
BOOTSTRAP/ATTACH_OBSERVED and feature pilot reports. The home screen
observed in the Android activity manager is
com.whatsapp.home.ui.HomeActivity.

The legacy MenuHome class hooks that screen's onCreateOptionsMenu(Menu)
callback. The modern API 102 entry had not ported that menu integration,
which explains why the WA X option was missing even though an API 102
pilot could install.

## Patch

- ModernMenuHomeFeature registers one reversible libxposed 102 hook on
  the target HomeActivity's declared onCreateOptionsMenu(Menu) method.
- The original method executes first and its boolean result is
  preserved. An existing native overflow menu gets one deduplicated
  WA X item (never an extra toolbar button or overlay).
- Clicking the item starts the existing exported
  com.wax.module.activities.MainActivity through an explicit Intent.
- The menu is always available when WA X is enabled for the target;
  other feature settings are not silently changed or activated.
- Only exact WhatsApp or WhatsApp Business main-process HomeActivity
  class names are eligible. The Business class is not verified on this
  phone (Business is not installed).
- ModernTargetTelemetryProvider accepts only fixed MENU_HOME status
  strings from the UID-authenticated WhatsApp sender.
- ITEM_ADDED evidence is queued off the UI thread, not sent to the
  Manager synchronously from the hook.

## Verification boundary

Build success, hook registration (INSTALLED) and real menu visibility
(ITEM_ADDED) are separate claims. The final acceptance test is to
install the CI-signed build with the user's approval, restart WhatsApp
via the device UI, open its overflow menu, check that WA X appears,
tap it, and confirm the installed WA X Manager opens. Read the status
from modern_runtime_target_reports.xml or filtered Vector logs.
Do not upload raw logs containing personal content.

This fixes access to the Manager settings. It DOES NOT enable the
remaining legacy-only features; those require individual migrations
and device testing. No device install, force-stop, data reset, or
Vector setting changes were authorized during the source patch.
