# Correct API102 target liveness reporting, without self-hook false positives

## Reproduction

Poco F5 / Android 17 / Vector / WA X dev+6DEA6851:
WhatsApp's real target-UID provider had authenticated BOOTSTRAP,
ATTACH_OBSERVED and MENU_HOME ITEM_ADDED evidence. The Manager showed
NO_TARGET_LIFECYCLE_SIGNAL after 90 seconds anyway, as the only
freshness condition was a short-lived bootstrap timestamp. This is
a status-model bug, not an indication the modern hooks had failed.

## Contract

- Framework service connection alone does not establish target injection.
- BOOTSTRAP marks when Application.attach was observed, not continuous health.
- Only actual injected main-process code schedules a 45-second, daemon,
  no-wakelock process heartbeat after its authenticated bootstrap is accepted.
- Provider validates the actual WhatsApp UID, exact known event and state,
  and stores the latest SystemClock.elapsedRealtime plus boot epoch.
- Manager regards the target as recently alive for 150 seconds after a
  valid heartbeat with a matching boot. Suspended or dead processes
  naturally expire; reboot/old/future counters cannot be considered live.
- Detailed lifecycle milestones retain their 120-second expiry, but
  expiration is no longer mislabeled as no lifecycle signal when an
  authenticated older bootstrap exists.
- Manager displays the most recent actual MENU_HOME state separately.
- This is a process liveness indication, not a promise that
  FreezeLastSeen, CustomTime or any legacy-only feature works.
- Does not access chats/contacts, request network access, create
  foreground services, change settings, or alter WhatsApp databases.

## Verification gates

1. Android CI, unit tests, CodeQL, format and signed APK metadata must pass.
2. Install production-signed update over existing Manager without data reset.
3. Restart the target through the user's UI to load the updated module;
   no automatic force-stop/clear data is performed by diagnostics.
4. Read only modern_runtime_target_reports.xml, filtered Logcat and
   installed APK metadata. Wait >90 seconds with WhatsApp running,
   verify that manager reports LIVE_HEARTBEAT; after target process stops
   and 150s expires verify that the state does not remain live.
5. WhatsApp Business remains untested if not installed.
