package com.wax.module.xposed.registry

import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.features.customization.BubbleColors
import com.wax.module.xposed.features.customization.ContactVerify
import com.wax.module.xposed.features.customization.CustomThemeV2
import com.wax.module.xposed.features.customization.CustomTime
import com.wax.module.xposed.features.customization.CustomToolbar
import com.wax.module.xposed.features.customization.CustomView
import com.wax.module.xposed.features.customization.DefaultEmoji
import com.wax.module.xposed.features.customization.FilterGroups
import com.wax.module.xposed.features.customization.FloatingBottomBar
import com.wax.module.xposed.features.customization.HideSeenView
import com.wax.module.xposed.features.customization.HideTabs
import com.wax.module.xposed.features.customization.IGStatus
import com.wax.module.xposed.features.customization.SeparateGroup
import com.wax.module.xposed.features.customization.ShowOnline
import com.wax.module.xposed.features.general.AboutContactPicker
import com.wax.module.xposed.features.general.AntiRevoke
import com.wax.module.xposed.features.general.CallType
import com.wax.module.xposed.features.general.CaptureDevice
import com.wax.module.xposed.features.general.ChatLimit
import com.wax.module.xposed.features.general.DeleteStatus
import com.wax.module.xposed.features.general.NewChat
import com.wax.module.xposed.features.general.Others
import com.wax.module.xposed.features.general.PinnedLimit
import com.wax.module.xposed.features.general.SeenTick
import com.wax.module.xposed.features.general.ShareLimit
import com.wax.module.xposed.features.general.ShowEditMessage
import com.wax.module.xposed.features.general.Tasker
import com.wax.module.xposed.features.listeners.ContactItemListener
import com.wax.module.xposed.features.listeners.ConversationItemListener
import com.wax.module.xposed.features.media.CallRecording
import com.wax.module.xposed.features.media.DownloadProfile
import com.wax.module.xposed.features.media.DownloadViewOnce
import com.wax.module.xposed.features.media.MediaPreview
import com.wax.module.xposed.features.media.MediaQuality
import com.wax.module.xposed.features.media.StatusDownload
import com.wax.module.xposed.features.others.ActivityController
import com.wax.module.xposed.features.others.AudioTranscript
import com.wax.module.xposed.features.others.BackupRestore
import com.wax.module.xposed.features.others.Channels
import com.wax.module.xposed.features.others.ChatFilters
import com.wax.module.xposed.features.others.CopySelectionMessage
import com.wax.module.xposed.features.others.CopyStatus
import com.wax.module.xposed.features.others.DebugFeature
import com.wax.module.xposed.features.others.GoogleTranslate
import com.wax.module.xposed.features.others.GroupAdmin
import com.wax.module.xposed.features.others.JumpFirstMessage
import com.wax.module.xposed.features.others.MenuHome
import com.wax.module.xposed.features.others.MinorFixes
import com.wax.module.xposed.features.others.Stickers
import com.wax.module.xposed.features.others.TextStatusComposer
import com.wax.module.xposed.features.others.ToastViewer
import com.wax.module.xposed.features.privacy.AntiWa
import com.wax.module.xposed.features.privacy.CallPrivacy
import com.wax.module.xposed.features.privacy.CustomPrivacy
import com.wax.module.xposed.features.privacy.DndMode
import com.wax.module.xposed.features.privacy.FreezeLastSeen
import com.wax.module.xposed.features.privacy.HideChat
import com.wax.module.xposed.features.privacy.HideSeen
import com.wax.module.xposed.features.privacy.LockedChatsEnhancer
import com.wax.module.xposed.features.privacy.TagMessage
import com.wax.module.xposed.features.privacy.TypingPrivacy
import com.wax.module.xposed.features.privacy.ViewOnce
import com.wax.module.xposed.features.providers.ContextMenuActionProvider
import com.wax.module.xposed.features.providers.MenuStatusProvider

/**
 * The one place a runtime feature is registered.
 *
 * Before this there were two: the `arrayOf(...)` in `FeatureLoader.plugins()` that the runtime
 * actually installed from, and the derived list in `tools/compatibility/compatibility.json` that the
 * compatibility matrix was computed from. They happened to agree - same 64 ids, same order - and
 * nothing checked it, so the day a feature was added to one of them and not the other, the module
 * would have installed a different set of features than the compatibility matrix claimed to
 * describe. That is the failure this file exists to make impossible.
 *
 * Order is the install order and is therefore meaningful: features earlier in this list have their
 * hooks in place before features later in it. It is also the order the derived compatibility facts
 * are sorted into, so the two views of a feature agree rather than merely matching.
 *
 * ## How to add a feature
 *
 * 1. Write it in `com.wax.module.xposed.features`.
 * 2. Add one entry here, in the position its install order requires.
 * 3. Nothing else. `check_feature_registry.py` fails if a feature class exists that is not
 * registered here, if an id is duplicated, if an entry has no factory, or if this list and the
 * derived compatibility facts disagree - so a feature that is only half-registered cannot reach a
 * build.
 *
 * ## What a factory buys
 *
 * Construction is a lambda, so there is no `getConstructor` to go stale: change a feature's
 * constructor and the build stops compiling at the entry that has to change with it. The entry
 * itself is the metadata - the id is the class's simple name by construction, so a rename cannot
 * leave a log line, a failure report and a compatibility cell describing three different features.
 *
 * `preferenceKeys`, `resolutionTier` and `resolverDependencies` are deliberately **not** here.
 * They are facts about a feature's body, and they are derived from source by
 * `tools/compatibility/extract_features.py`. Writing them here by hand would create a second
 * list of the same facts that could disagree with the first - which is the problem this package
 * exists to close, not to recreate one layer down.
 */
object RuntimeFeatureRegistry {
    /** Every runtime feature, in install order. */
    val entries: List<FeatureFactory> =
        listOf(
            FeatureFactory.Contract("DebugFeature") { DebugFeature() },
            FeatureFactory.Legacy("MinorFixes") { loader, preferences ->
                MinorFixes(loader, preferences)
            },
            FeatureFactory.Legacy("ContactItemListener") { loader, preferences ->
                ContactItemListener(loader, preferences)
            },
            FeatureFactory.Legacy("ConversationItemListener") { loader, preferences ->
                ConversationItemListener(loader, preferences)
            },
            FeatureFactory.Legacy("MenuStatusProvider") { loader, preferences ->
                MenuStatusProvider(loader, preferences)
            },
            FeatureFactory.Legacy("ShowEditMessage") { loader, preferences ->
                ShowEditMessage(loader, preferences)
            },
            FeatureFactory.Legacy("AntiRevoke") { loader, preferences ->
                AntiRevoke(loader, preferences)
            },
            FeatureFactory.Legacy("CustomToolbar") { loader, preferences ->
                CustomToolbar(loader, preferences)
            },
            FeatureFactory.Legacy("CustomView") { loader, preferences ->
                CustomView(loader, preferences)
            },
            FeatureFactory.Legacy("SeenTick") { loader, preferences ->
                SeenTick(loader, preferences)
            },
            FeatureFactory.Legacy("BubbleColors") { loader, preferences ->
                BubbleColors(loader, preferences)
            },
            FeatureFactory.Legacy("CallPrivacy") { loader, preferences ->
                CallPrivacy(loader, preferences)
            },
            FeatureFactory.Legacy("ActivityController") { loader, preferences ->
                ActivityController(loader, preferences)
            },
            FeatureFactory.Legacy("CustomThemeV2") { loader, preferences ->
                CustomThemeV2(loader, preferences)
            },
            FeatureFactory.Legacy("FloatingBottomBar") { loader, preferences ->
                FloatingBottomBar(loader, preferences)
            },
            FeatureFactory.Legacy("ChatLimit") { loader, preferences ->
                ChatLimit(loader, preferences)
            },
            FeatureFactory.Legacy("SeparateGroup") { loader, preferences ->
                SeparateGroup(loader, preferences)
            },
            FeatureFactory.Legacy("ShowOnline") { loader, preferences ->
                ShowOnline(loader, preferences)
            },
            FeatureFactory.Legacy("DndMode") { loader, preferences ->
                DndMode(loader, preferences)
            },
            FeatureFactory.Legacy("FreezeLastSeen") { loader, preferences ->
                FreezeLastSeen(loader, preferences)
            },
            FeatureFactory.Legacy("TypingPrivacy") { loader, preferences ->
                TypingPrivacy(loader, preferences)
            },
            FeatureFactory.Legacy("HideChat") { loader, preferences ->
                HideChat(loader, preferences)
            },
            FeatureFactory.Legacy("HideSeen") { loader, preferences ->
                HideSeen(loader, preferences)
            },
            FeatureFactory.Legacy("HideSeenView") { loader, preferences ->
                HideSeenView(loader, preferences)
            },
            FeatureFactory.Legacy("TagMessage") { loader, preferences ->
                TagMessage(loader, preferences)
            },
            FeatureFactory.Legacy("HideTabs") { loader, preferences ->
                HideTabs(loader, preferences)
            },
            FeatureFactory.Legacy("IGStatus") { loader, preferences ->
                IGStatus(loader, preferences)
            },
            FeatureFactory.Legacy("MediaQuality") { loader, preferences ->
                MediaQuality(loader, preferences)
            },
            FeatureFactory.Legacy("NewChat") { loader, preferences ->
                NewChat(loader, preferences)
            },
            FeatureFactory.Legacy("Others") { loader, preferences ->
                Others(loader, preferences)
            },
            FeatureFactory.Legacy("PinnedLimit") { loader, preferences ->
                PinnedLimit(loader, preferences)
            },
            FeatureFactory.Legacy("CustomTime") { loader, preferences ->
                CustomTime(loader, preferences)
            },
            FeatureFactory.Legacy("ShareLimit") { loader, preferences ->
                ShareLimit(loader, preferences)
            },
            FeatureFactory.Legacy("StatusDownload") { loader, preferences ->
                StatusDownload(loader, preferences)
            },
            FeatureFactory.Legacy("ViewOnce") { loader, preferences ->
                ViewOnce(loader, preferences)
            },
            FeatureFactory.Legacy("CallType") { loader, preferences ->
                CallType(loader, preferences)
            },
            FeatureFactory.Legacy("MediaPreview") { loader, preferences ->
                MediaPreview(loader, preferences)
            },
            FeatureFactory.Legacy("FilterGroups") { loader, preferences ->
                FilterGroups(loader, preferences)
            },
            FeatureFactory.Legacy("Tasker") { loader, preferences ->
                Tasker(loader, preferences)
            },
            FeatureFactory.Legacy("DeleteStatus") { loader, preferences ->
                DeleteStatus(loader, preferences)
            },
            FeatureFactory.Legacy("DownloadViewOnce") { loader, preferences ->
                DownloadViewOnce(loader, preferences)
            },
            FeatureFactory.Legacy("Channels") { loader, preferences ->
                Channels(loader, preferences)
            },
            FeatureFactory.Legacy("DownloadProfile") { loader, preferences ->
                DownloadProfile(loader, preferences)
            },
            FeatureFactory.Legacy("ChatFilters") { loader, preferences ->
                ChatFilters(loader, preferences)
            },
            FeatureFactory.Legacy("GroupAdmin") { loader, preferences ->
                GroupAdmin(loader, preferences)
            },
            FeatureFactory.Legacy("Stickers") { loader, preferences ->
                Stickers(loader, preferences)
            },
            FeatureFactory.Legacy("CopyStatus") { loader, preferences ->
                CopyStatus(loader, preferences)
            },
            FeatureFactory.Legacy("CopySelectionMessage") { loader, preferences ->
                CopySelectionMessage(loader, preferences)
            },
            FeatureFactory.Legacy("TextStatusComposer") { loader, preferences ->
                TextStatusComposer(loader, preferences)
            },
            FeatureFactory.Legacy("ToastViewer") { loader, preferences ->
                ToastViewer(loader, preferences)
            },
            FeatureFactory.Legacy("MenuHome") { loader, preferences ->
                MenuHome(loader, preferences)
            },
            FeatureFactory.Legacy("AntiWa") { loader, preferences ->
                AntiWa(loader, preferences)
            },
            FeatureFactory.Legacy("CustomPrivacy") { loader, preferences ->
                CustomPrivacy(loader, preferences)
            },
            FeatureFactory.Legacy("AudioTranscript") { loader, preferences ->
                AudioTranscript(loader, preferences)
            },
            FeatureFactory.Legacy("GoogleTranslate") { loader, preferences ->
                GoogleTranslate(loader, preferences)
            },
            FeatureFactory.Legacy("ContactVerify") { loader, preferences ->
                ContactVerify(loader, preferences)
            },
            FeatureFactory.Legacy("LockedChatsEnhancer") { loader, preferences ->
                LockedChatsEnhancer(loader, preferences)
            },
            FeatureFactory.Legacy("CallRecording") { loader, preferences ->
                CallRecording(loader, preferences)
            },
            FeatureFactory.Legacy("BackupRestore") { loader, preferences ->
                BackupRestore(loader, preferences)
            },
            FeatureFactory.Legacy("JumpFirstMessage") { loader, preferences ->
                JumpFirstMessage(loader, preferences)
            },
            FeatureFactory.Legacy("AboutContactPicker") { loader, preferences ->
                AboutContactPicker(loader, preferences)
            },
            FeatureFactory.Legacy("DefaultEmoji") { loader, preferences ->
                DefaultEmoji(loader, preferences)
            },
            FeatureFactory.Legacy("CaptureDevice") { loader, preferences ->
                CaptureDevice(loader, preferences)
            },
            FeatureFactory.Legacy("ContextMenuActionProvider") { loader, preferences ->
                ContextMenuActionProvider(loader, preferences)
            },
        )
}
