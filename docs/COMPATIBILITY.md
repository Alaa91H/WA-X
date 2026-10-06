# WA X compatibility matrix

<!-- GENERATED FILE - do not edit by hand. -->
<!-- Source: tools/compatibility/compatibility.json -->
<!-- Regenerate: python3 tools/compatibility/sync_generated.py -->

This document is the human-readable view of the WA X compatibility matrix. It is the single place to look before adding support for a new WhatsApp version.

WA X is a fork/continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer), maintained by [Alaa](https://github.com/Alaa91H). Fork provenance does not change the evidence standard used by this matrix.
Developer Telegram: [@Alaa91h](https://t.me/Alaa91h) · Community: [@WAXposed](https://t.me/WAXposed) · Email: [alahus2591@gmail.com](mailto:alahus2591@gmail.com) · Voluntary support: [Ko-fi](https://ko-fi.com/alaa91h)

## Current state

No cell in this matrix is resolver-verified yet. Declared versions below are a maintainer declaration that the runtime version gate enforces; they are **not** evidence. Per the plan's governing rule, a version or feature is not declared supported until its resolvers actually resolve on that target.

| Package | Declared versions | Resolver-verified |
|---|---|---|
| WhatsApp | 8 | 0 / 512 cells |
| WhatsApp Business | 7 | 0 / 448 cells |

## Status vocabulary

| Status | Meaning |
|---|---|
| `supported` | Every resolver this feature depends on was observed to resolve on this exact target, at runtime, with evidence recorded under evidence. |
| `degraded` | The feature loads, but at least one required resolver failed and the feature fell back to a legacy or partial path. |
| `unsupported` | A required resolver is known not to resolve on this target. The feature must not be enabled. |
| `unknown` | No resolver evidence has been collected for this cell yet. This is the only status permitted without evidence. |

## Resolution tiers

How a feature reaches its hook targets. This is derived from the source tree, so it is always accurate, and it tells us which features can actually break on a WhatsApp update.

| Tier | Meaning | Features |
|---|---|---|
| `none` | The feature references no internal resolution layer (Unobfuscator, ReflectionUtils, WppCore). It is driven purely by preferences or its own UI. | 5 |
| `indirect` | The feature resolves hook targets through ReflectionUtils or WppCore but calls no Unobfuscator resolver directly. | 12 |
| `dexkit` | The feature calls one or more Unobfuscator DexKit resolvers directly and therefore needs resolver evidence. | 47 |

### Tier `none` cannot break through resolution

5 of 64 features reference no internal resolution layer at all, so no WhatsApp update can break them via DexKit:

- `CopySelectionMessage`
- `DebugFeature`
- `FloatingBottomBar`
- `HideSeenView`
- `MinorFixes`

## Target dimensions

| Dimension | Value |
|---|---|
| minSdk | 28 |
| targetSdk | 34 |
| compileSdk | 37 |
| ABIs | `arm64-v8a`, `armeabi-v7a` |

## Feature inventory

All 64 registered features. `W` and `B` are the worst status across the declared versions of that package.

| # | Feature | Category | Tier | Resolvers | Sources | W | B |
|---|---|---|---|---|---|---|---|
| 1 | `BubbleColors` | customization | dexkit | 3 | Unobfuscator | _unknown_ | _unknown_ |
| 2 | `ContactVerify` | customization | dexkit | 3 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 3 | `CustomThemeV2` | customization | dexkit | 2 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 4 | `CustomTime` | customization | dexkit | 1 | Unobfuscator | _unknown_ | _unknown_ |
| 5 | `CustomToolbar` | customization | dexkit | 2 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 6 | `CustomView` | customization | indirect | 0 | ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 7 | `DefaultEmoji` | customization | dexkit | 2 | Unobfuscator | _unknown_ | _unknown_ |
| 8 | `FilterGroups` | customization | dexkit | 3 | Unobfuscator, UnobfuscatorCache | _unknown_ | _unknown_ |
| 9 | `FloatingBottomBar` | customization | none | 0 | - | _unknown_ | _unknown_ |
| 10 | `HideSeenView` | customization | none | 0 | - | _unknown_ | _unknown_ |
| 11 | `HideTabs` | customization | dexkit | 4 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 12 | `IGStatus` | customization | dexkit | 6 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 13 | `SeparateGroup` | customization | dexkit | 15 | Unobfuscator, UnobfuscatorCache, ReflectionUtils | _unknown_ | _unknown_ |
| 14 | `ShowOnline` | customization | dexkit | 4 | Unobfuscator, UnobfuscatorCache, ReflectionUtils | _unknown_ | _unknown_ |
| 15 | `AboutContactPicker` | general | indirect | 0 | ModuleRuntime | _unknown_ | _unknown_ |
| 16 | `AntiRevoke` | general | dexkit | 4 | Unobfuscator, UnobfuscatorCache, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 17 | `CallType` | general | dexkit | 1 | Unobfuscator, UnobfuscatorCache | _unknown_ | _unknown_ |
| 18 | `CaptureDevice` | general | dexkit | 1 | Unobfuscator | _unknown_ | _unknown_ |
| 19 | `ChatLimit` | general | dexkit | 5 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 20 | `DeleteStatus` | general | indirect | 0 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 21 | `NewChat` | general | indirect | 0 | ModuleRuntime | _unknown_ | _unknown_ |
| 22 | `Others` | general | dexkit | 28 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 23 | `PinnedLimit` | general | dexkit | 4 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 24 | `SeenTick` | general | dexkit | 7 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 25 | `ShareLimit` | general | dexkit | 1 | Unobfuscator | _unknown_ | _unknown_ |
| 26 | `ShowEditMessage` | general | dexkit | 3 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 27 | `Tasker` | general | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 28 | `ContactItemListener` | listeners | dexkit | 3 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 29 | `ConversationItemListener` | listeners | indirect | 0 | ModuleRuntime | _unknown_ | _unknown_ |
| 30 | `CallRecording` | media | indirect | 0 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 31 | `DownloadProfile` | media | indirect | 0 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 32 | `DownloadViewOnce` | media | dexkit | 1 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 33 | `MediaPreview` | media | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 34 | `MediaQuality` | media | dexkit | 10 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 35 | `StatusDownload` | media | indirect | 0 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 36 | `ActivityController` | others | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 37 | `AudioTranscript` | others | dexkit | 2 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 38 | `BackupRestore` | others | indirect | 0 | Unobfuscator | _unknown_ | _unknown_ |
| 39 | `Channels` | others | dexkit | 4 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 40 | `ChatFilters` | others | dexkit | 1 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 41 | `CopySelectionMessage` | others | none | 0 | - | _unknown_ | _unknown_ |
| 42 | `CopyStatus` | others | dexkit | 2 | Unobfuscator | _unknown_ | _unknown_ |
| 43 | `DebugFeature` | others | none | 0 | - | _unknown_ | _unknown_ |
| 44 | `GoogleTranslate` | others | indirect | 0 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 45 | `GroupAdmin` | others | dexkit | 2 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 46 | `JumpFirstMessage` | others | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 47 | `MenuHome` | others | indirect | 0 | ModuleRuntime | _unknown_ | _unknown_ |
| 48 | `MinorFixes` | others | none | 0 | - | _unknown_ | _unknown_ |
| 49 | `Stickers` | others | dexkit | 1 | Unobfuscator | _unknown_ | _unknown_ |
| 50 | `TextStatusComposer` | others | dexkit | 3 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 51 | `ToastViewer` | others | dexkit | 2 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 52 | `AntiWa` | privacy | dexkit | 3 | Unobfuscator | _unknown_ | _unknown_ |
| 53 | `CallPrivacy` | privacy | dexkit | 2 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 54 | `CustomPrivacy` | privacy | indirect | 0 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 55 | `DndMode` | privacy | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 56 | `FreezeLastSeen` | privacy | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 57 | `HideChat` | privacy | dexkit | 1 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 58 | `HideSeen` | privacy | dexkit | 7 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 59 | `LockedChatsEnhancer` | privacy | dexkit | 4 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 60 | `TagMessage` | privacy | dexkit | 2 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |
| 61 | `TypingPrivacy` | privacy | dexkit | 1 | Unobfuscator, ReflectionUtils, ModuleRuntime | _unknown_ | _unknown_ |
| 62 | `ViewOnce` | privacy | dexkit | 1 | Unobfuscator | _unknown_ | _unknown_ |
| 63 | `ContextMenuActionProvider` | providers | dexkit | 1 | Unobfuscator, ModuleRuntime | _unknown_ | _unknown_ |
| 64 | `MenuStatusProvider` | providers | dexkit | 3 | Unobfuscator, ReflectionUtils | _unknown_ | _unknown_ |

## Features requiring resolver evidence

These features call DexKit resolvers directly. Each one needs a recorded `verifiedAt` and a `resolved` result for every listed resolver before any cell may claim `supported`.

### WhatsApp

| Feature | Required resolvers |
|---|---|
| `Others` | `loadAbsViewHolder`, `loadAdVerifyMethod`, `loadAddOptionSearchBarMethod`, `loadChatFilterViewMethod`, `loadCheckOnlineMethod`, `loadConversationRowClass`, `loadConversationsHeightMethod`, `loadCopiedMessageMethod`, `loadFilterDimenId`, `loadForwardAudioTypeMethod`, `loadGetCurrentPageInHomeField`, `loadMediaTypeMethod`, `loadMySearchBarMethod`, `loadNextStatusRunMethod`, `loadOnChangeStatus`, `loadOnPlaybackFinished`, `loadOriginFMessageField`, `loadPlaybackSpeed`, `loadPropsBooleanMethod`, `loadPropsIntegerMethod`, `loadProximitySensorListenerClasses`, `loadStateChangeMethod`, `loadStatusDataClass`, `loadStatusProfileMethod`, `loadStatusStyleMethod`, `loadSwipeUpInGroupMethod`, `loadViewAddSearchBarMethod`, `loadViewHolderField1` |
| `SeparateGroup` | `loadAddMenuAndroidX`, `loadEnableCountTabBadgeItem`, `loadEnableCountTabBadgeWrapper`, `loadEnableCountTabEmptyBadgeClass`, `loadEnableCountTabMethod`, `loadFabMethod`, `loadFragmentClass`, `loadGetFiltersMethod`, `loadGetTabMethod`, `loadIconTabMethod`, `loadRecreateFragmentConstructor`, `loadTabCountMethod`, `loadTabFragmentMethod`, `loadTabListMethod`, `loadTabNameMethod` |
| `MediaQuality` | `loadBottomBarConfigClass`, `loadMediaDataVideoConfigurationClass`, `loadMediaQualityOriginalVideoFields`, `loadMediaQualitySelectionMethod`, `loadMediaQualityVideoFields`, `loadMediaQualityVideoMethod2`, `loadMediaTranscoderStart`, `loadProcessImageQualityClass`, `loadProcessVideoQualityClass`, `loadVideoTranscoderStartMethod` |
| `SeenTick` | `loadBlueOnReplayMessageJobMethod`, `loadBlueOnReplayViewButtonMethod`, `loadBlueOnReplayWaJobManagerMethod`, `loadOnCreatedMenuConversation`, `loadStatusPlaybackReplyContainer`, `loadUnknownStatusPlaybackMethod`, `loadViewOnceDownloadMenuMethod` |
| `HideSeen` | `loadHideViewSendReadJob`, `loadOndispatchMessage`, `loadReadReceiptMethod`, `loadReceiptMessageInfoClass`, `loadReceiptMethod`, `loadSenderPlayedBusiness`, `loadSenderPlayedMethod` |
| `IGStatus` | `loadFabMethod`, `loadGetInvokeField`, `loadGetViewConversationMethod`, `loadOnUpdateStatusChanged`, `loadStatusInfoClass`, `loadStatusListUpdatesClass` |
| `ChatLimit` | `loadChatLimitDelete2Method`, `loadChatLimitDeleteMethod`, `loadEphemeralInsertdb`, `loadFmessageTimestampField`, `loadSeeMoreConstructor` |
| `AntiRevoke` | `loadAntiRevokeFStatusMethod`, `loadAntiRevokeMessageMethod`, `loadStatusPlaybackViewClass`, `loadUnknownStatusPlaybackMethod` |
| `ShowOnline` | `loadSendPresenceMethod`, `loadStatusUserMethod`, `loadTcTokenMethod`, `loadViewHolder` |
| `HideTabs` | `loadOnMenuItemSelected`, `loadOnTabItemAddMethod`, `loadTabFrameClass`, `loadTabListMethod` |
| `PinnedLimit` | `loadPinnedFilterMethod`, `loadPinnedHashSetMethod`, `loadPinnedInChatMethod`, `loadSetPinnedLimitMethod` |
| `Channels` | `loadHeaderChannelItemClass`, `loadListChannelItemClass`, `loadListUpdateItems`, `loadRemoveChannelRecClass` |
| `LockedChatsEnhancer` | `loadChatCacheClass`, `loadLoadedContactsMethod`, `loadLockedChatsMethod`, `loadNotificationMethod` |
| `ContactItemListener` | `loadAbsViewHolder`, `loadOnChangeStatus`, `loadViewHolderField1` |
| `MenuStatusProvider` | `loadMenuManagerClass`, `loadMenuStatusMethod`, `loadStatusPlaybackCurrentIndexField` |
| `ShowEditMessage` | `loadCallerMessageEditMethod`, `loadGetEditMessageMethod`, `loadMessageEditMethod` |
| `BubbleColors` | `loadBallonBorderDrawable`, `loadBallonDateDrawable`, `loadBubbleDrawableMethod` |
| `FilterGroups` | `loadGetFiltersMethod`, `loadOnConversationsListChangedMethod`, `loadTabFragmentMethod` |
| `TextStatusComposer` | `loadTextStatusComposerOnCreate`, `loadTextStatusData`, `loadTextStatusDataFStatus` |
| `AntiWa` | `loadCheckCustomRom`, `loadCheckEmulator`, `loadRootDetector` |
| `ContactVerify` | `loadDialerProfilePictureLoader`, `loadGetProfilePhoto`, `loadProfilePhotoProtocolHelperClass` |
| `CustomToolbar` | `loadExpirationClass`, `loadOnMenuItemSelected` |
| `CallPrivacy` | `loadAntiRevokeOnCallReceivedMethod`, `loadVoipManager` |
| `CustomThemeV2` | `loadFragmentViewMethod`, `loadTabFrameClass` |
| `TagMessage` | `loadForwardClassMethod`, `loadForwardTagMethod` |
| `GroupAdmin` | `loadGroupCheckAdminMethod`, `loadJidFactory` |
| `CopyStatus` | `loadBlueOnReplayStatusViewMethod`, `loadBlueOnReplayViewButtonMethod` |
| `ToastViewer` | `loadOnInsertReceipt`, `loadSeenReceiptForStatus` |
| `AudioTranscript` | `loadTranscribeMethod`, `loadTranscriptSegment` |
| `DefaultEmoji` | `loadDrawSpanMethods`, `loadGetSizeSpanMethods` |
| `ActivityController` | `loadLockedAuthCheckMethod` |
| `DndMode` | `loadDndModeMethod` |
| `FreezeLastSeen` | `loadFreezeSeenMethod` |
| `TypingPrivacy` | `loadGhostModeMethod` |
| `HideChat` | `loadArchiveChatClass` |
| `CustomTime` | `loadTimeToSecondsMethod` |
| `ShareLimit` | `loadMultiSelectionLimitInfoClass` |
| `ViewOnce` | `loadViewOnceMethod` |
| `CallType` | `loadStartOutgoingCallMethod` |
| `MediaPreview` | `loadLayoutClass` |
| `Tasker` | `loadReceiptMethod` |
| `DownloadViewOnce` | `loadViewOnceDownloadMenuMethod` |
| `ChatFilters` | `loadFilterAdaperClass` |
| `Stickers` | `loadStickerColoredOutline` |
| `JumpFirstMessage` | `loadOnCreatedMenuConversation` |
| `CaptureDevice` | `loadSharedMessageProcessorHandlePlaintextMethod` |
| `ContextMenuActionProvider` | `loadPopupWindowMessageClass` |

### WhatsApp Business

| Feature | Required resolvers |
|---|---|
| `Others` | `loadAbsViewHolder`, `loadAdVerifyMethod`, `loadAddOptionSearchBarMethod`, `loadChatFilterViewMethod`, `loadCheckOnlineMethod`, `loadConversationRowClass`, `loadConversationsHeightMethod`, `loadCopiedMessageMethod`, `loadFilterDimenId`, `loadForwardAudioTypeMethod`, `loadGetCurrentPageInHomeField`, `loadMediaTypeMethod`, `loadMySearchBarMethod`, `loadNextStatusRunMethod`, `loadOnChangeStatus`, `loadOnPlaybackFinished`, `loadOriginFMessageField`, `loadPlaybackSpeed`, `loadPropsBooleanMethod`, `loadPropsIntegerMethod`, `loadProximitySensorListenerClasses`, `loadStateChangeMethod`, `loadStatusDataClass`, `loadStatusProfileMethod`, `loadStatusStyleMethod`, `loadSwipeUpInGroupMethod`, `loadViewAddSearchBarMethod`, `loadViewHolderField1` |
| `SeparateGroup` | `loadAddMenuAndroidX`, `loadEnableCountTabBadgeItem`, `loadEnableCountTabBadgeWrapper`, `loadEnableCountTabEmptyBadgeClass`, `loadEnableCountTabMethod`, `loadFabMethod`, `loadFragmentClass`, `loadGetFiltersMethod`, `loadGetTabMethod`, `loadIconTabMethod`, `loadRecreateFragmentConstructor`, `loadTabCountMethod`, `loadTabFragmentMethod`, `loadTabListMethod`, `loadTabNameMethod` |
| `MediaQuality` | `loadBottomBarConfigClass`, `loadMediaDataVideoConfigurationClass`, `loadMediaQualityOriginalVideoFields`, `loadMediaQualitySelectionMethod`, `loadMediaQualityVideoFields`, `loadMediaQualityVideoMethod2`, `loadMediaTranscoderStart`, `loadProcessImageQualityClass`, `loadProcessVideoQualityClass`, `loadVideoTranscoderStartMethod` |
| `SeenTick` | `loadBlueOnReplayMessageJobMethod`, `loadBlueOnReplayViewButtonMethod`, `loadBlueOnReplayWaJobManagerMethod`, `loadOnCreatedMenuConversation`, `loadStatusPlaybackReplyContainer`, `loadUnknownStatusPlaybackMethod`, `loadViewOnceDownloadMenuMethod` |
| `HideSeen` | `loadHideViewSendReadJob`, `loadOndispatchMessage`, `loadReadReceiptMethod`, `loadReceiptMessageInfoClass`, `loadReceiptMethod`, `loadSenderPlayedBusiness`, `loadSenderPlayedMethod` |
| `IGStatus` | `loadFabMethod`, `loadGetInvokeField`, `loadGetViewConversationMethod`, `loadOnUpdateStatusChanged`, `loadStatusInfoClass`, `loadStatusListUpdatesClass` |
| `ChatLimit` | `loadChatLimitDelete2Method`, `loadChatLimitDeleteMethod`, `loadEphemeralInsertdb`, `loadFmessageTimestampField`, `loadSeeMoreConstructor` |
| `AntiRevoke` | `loadAntiRevokeFStatusMethod`, `loadAntiRevokeMessageMethod`, `loadStatusPlaybackViewClass`, `loadUnknownStatusPlaybackMethod` |
| `ShowOnline` | `loadSendPresenceMethod`, `loadStatusUserMethod`, `loadTcTokenMethod`, `loadViewHolder` |
| `HideTabs` | `loadOnMenuItemSelected`, `loadOnTabItemAddMethod`, `loadTabFrameClass`, `loadTabListMethod` |
| `PinnedLimit` | `loadPinnedFilterMethod`, `loadPinnedHashSetMethod`, `loadPinnedInChatMethod`, `loadSetPinnedLimitMethod` |
| `Channels` | `loadHeaderChannelItemClass`, `loadListChannelItemClass`, `loadListUpdateItems`, `loadRemoveChannelRecClass` |
| `LockedChatsEnhancer` | `loadChatCacheClass`, `loadLoadedContactsMethod`, `loadLockedChatsMethod`, `loadNotificationMethod` |
| `ContactItemListener` | `loadAbsViewHolder`, `loadOnChangeStatus`, `loadViewHolderField1` |
| `MenuStatusProvider` | `loadMenuManagerClass`, `loadMenuStatusMethod`, `loadStatusPlaybackCurrentIndexField` |
| `ShowEditMessage` | `loadCallerMessageEditMethod`, `loadGetEditMessageMethod`, `loadMessageEditMethod` |
| `BubbleColors` | `loadBallonBorderDrawable`, `loadBallonDateDrawable`, `loadBubbleDrawableMethod` |
| `FilterGroups` | `loadGetFiltersMethod`, `loadOnConversationsListChangedMethod`, `loadTabFragmentMethod` |
| `TextStatusComposer` | `loadTextStatusComposerOnCreate`, `loadTextStatusData`, `loadTextStatusDataFStatus` |
| `AntiWa` | `loadCheckCustomRom`, `loadCheckEmulator`, `loadRootDetector` |
| `ContactVerify` | `loadDialerProfilePictureLoader`, `loadGetProfilePhoto`, `loadProfilePhotoProtocolHelperClass` |
| `CustomToolbar` | `loadExpirationClass`, `loadOnMenuItemSelected` |
| `CallPrivacy` | `loadAntiRevokeOnCallReceivedMethod`, `loadVoipManager` |
| `CustomThemeV2` | `loadFragmentViewMethod`, `loadTabFrameClass` |
| `TagMessage` | `loadForwardClassMethod`, `loadForwardTagMethod` |
| `GroupAdmin` | `loadGroupCheckAdminMethod`, `loadJidFactory` |
| `CopyStatus` | `loadBlueOnReplayStatusViewMethod`, `loadBlueOnReplayViewButtonMethod` |
| `ToastViewer` | `loadOnInsertReceipt`, `loadSeenReceiptForStatus` |
| `AudioTranscript` | `loadTranscribeMethod`, `loadTranscriptSegment` |
| `DefaultEmoji` | `loadDrawSpanMethods`, `loadGetSizeSpanMethods` |
| `ActivityController` | `loadLockedAuthCheckMethod` |
| `DndMode` | `loadDndModeMethod` |
| `FreezeLastSeen` | `loadFreezeSeenMethod` |
| `TypingPrivacy` | `loadGhostModeMethod` |
| `HideChat` | `loadArchiveChatClass` |
| `CustomTime` | `loadTimeToSecondsMethod` |
| `ShareLimit` | `loadMultiSelectionLimitInfoClass` |
| `ViewOnce` | `loadViewOnceMethod` |
| `CallType` | `loadStartOutgoingCallMethod` |
| `MediaPreview` | `loadLayoutClass` |
| `Tasker` | `loadReceiptMethod` |
| `DownloadViewOnce` | `loadViewOnceDownloadMenuMethod` |
| `ChatFilters` | `loadFilterAdaperClass` |
| `Stickers` | `loadStickerColoredOutline` |
| `JumpFirstMessage` | `loadOnCreatedMenuConversation` |
| `CaptureDevice` | `loadSharedMessageProcessorHandlePlaintextMethod` |
| `ContextMenuActionProvider` | `loadPopupWindowMessageClass` |

## Updating this matrix

When adding a new WhatsApp version:

1. Add the version prefix to `packages.<target>.declaredVersions` in `compatibility.json`.
2. Run `python3 tools/compatibility/sync_generated.py` to regenerate this document and `arrays.xml`.
3. Run `python3 tools/compatibility/validate_compatibility.py` and `sync_generated.py --check`.
4. Record real runtime resolver evidence under `evidence.<FeatureId>.resolvers`.
5. Only then set cells to `supported`.
6. Re-run validation and commit the source-of-truth and generated artifacts together.

The validator refuses any `supported` cell whose resolver evidence is missing,
partial, or lacking a `verifiedAt` timestamp.

Step 2 rewrites `app/src/main/res/values/arrays.xml` from this matrix, so the runtime version gate and this document can never disagree.

