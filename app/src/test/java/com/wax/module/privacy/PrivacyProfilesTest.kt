package com.wax.module.privacy

import com.wax.module.platform.ChatKind
import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.KeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyProfilesTest {
    private lateinit var store: InMemoryKeyValueStore
    private var now = 5_000L

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
        now = 5_000L
    }

    private fun profiles(store: KeyValueStore = this.store) = PrivacyProfileStore(store) { now }

    // --- T76: profile engine ----------------------------------------------------------

    @Test
    fun theBuiltInProfilesCoverTheRequiredSet() {
        val names = BuiltInPrivacyProfiles.all.map { it.name }
        assertTrue(names.containsAll(listOf("Normal", "Ghost", "Work", "Night", "Maximum Privacy")))
        assertEquals(5, names.size)
        assertTrue(BuiltInPrivacyProfiles.all.all { it.builtIn })
    }

    @Test
    fun aFreshStoreStartsOnTheDefaultProfile() {
        assertEquals(BuiltInPrivacyProfiles.NORMAL, profiles().activeId())
        assertEquals("Normal", profiles().active().name)
    }

    @Test
    fun aCustomProfileCanBeCreatedDuplicatedRenamedAndDeleted() {
        val store = profiles()
        val created =
            store.create(
                name = "Focus",
                visibility = mapOf(PrivacyField.TYPING to Visibility.HIDE),
                callBehavior = CallBehavior.SILENCE,
                notificationPrivacy = NotificationPrivacy.HIDE_CONTENT,
            )
        assertTrue(created.isSuccess)
        val custom = (created as PrivacyOpResult.Success).value
        // Creating a profile does not switch to it: switching is always an explicit action.
        assertEquals("Normal", store.active().name)

        val duplicated = store.duplicate(custom.id, "Focus copy")
        assertTrue(duplicated.isSuccess)
        assertEquals(2, store.profiles().count { !it.builtIn })

        val renamed = store.rename(custom.id, "Deep focus")
        assertTrue(renamed.isSuccess)
        assertEquals("Deep focus", (renamed as PrivacyOpResult.Success).value.name)

        assertTrue(store.delete(custom.id).isSuccess)
        assertEquals(1, store.profiles().count { !it.builtIn })
        assertTrue(store.delete(custom.id) is PrivacyOpResult.NotFound)
    }

    @Test
    fun builtInProfilesCannotBeRenamedOrDeleted() {
        val store = profiles()
        assertTrue(store.rename(BuiltInPrivacyProfiles.GHOST, "Mine") is PrivacyOpResult.Rejected)
        assertTrue(store.delete(BuiltInPrivacyProfiles.GHOST) is PrivacyOpResult.Rejected)
        assertEquals(5, store.profiles().size)
    }

    @Test
    fun duplicateNamesAreRejectedCaseInsensitively() {
        val store = profiles()
        assertTrue(store.create("ghost", emptyMap()) is PrivacyOpResult.Rejected)
    }

    @Test
    fun blankAndOverlongNamesAreRejected() {
        val store = profiles()
        assertTrue(store.create("   ", emptyMap()) is PrivacyOpResult.Rejected)
        assertTrue(store.create("x".repeat(PrivacyProfileValidation.MAX_NAME_LENGTH + 1), emptyMap()) is PrivacyOpResult.Rejected)
    }

    @Test
    fun aContradictoryProfileIsRejectedWithAnExplanation() {
        val store = profiles()
        val result =
            store.create(
                name = "Contradiction",
                visibility =
                    mapOf(
                        PrivacyField.ONLINE to Visibility.HIDE,
                        PrivacyField.LAST_SEEN to Visibility.SHOW,
                    ),
            )
        assertTrue(result is PrivacyOpResult.Rejected)
        val problem = (result as PrivacyOpResult.Rejected).problems.single()
        assertEquals("contradiction_last_seen", problem.code)
        assertTrue(problem.message.contains("reveal"))
    }

    @Test
    fun switchingPersistsTheActiveProfile() {
        val store = profiles()
        assertTrue(store.switchTo(BuiltInPrivacyProfiles.GHOST).isSuccess)
        assertEquals(BuiltInPrivacyProfiles.GHOST, store.activeId())
        assertEquals("Ghost", store.active().name)
    }

    @Test
    fun aFailedApplierLeavesThePreviousProfileActive() {
        val store = profiles()
        val applier = PrivacyProfileApplier { throw IllegalStateException("preference write failed") }
        val result = store.switchTo(BuiltInPrivacyProfiles.NIGHT, applier)
        assertTrue(result is PrivacyOpResult.Failed)
        assertEquals(BuiltInPrivacyProfiles.NORMAL, store.activeId())
    }

    @Test
    fun anApplierThatSucceedsSwitchesTheProfile() {
        val store = profiles()
        var applied: PrivacyProfile? = null
        val result = store.switchTo(BuiltInPrivacyProfiles.WORK) { applied = it }
        assertTrue(result.isSuccess)
        assertEquals("Work", applied?.name)
        assertEquals(BuiltInPrivacyProfiles.WORK, store.activeId())
    }

    @Test
    fun deletingTheActiveProfileFallsBackToNormal() {
        val store = profiles()
        val created = (store.create("Temp", emptyMap()) as PrivacyOpResult.Success).value
        store.switchTo(created.id)
        store.delete(created.id)
        assertEquals(BuiltInPrivacyProfiles.NORMAL, store.activeId())
    }

    @Test
    fun customProfilesSurviveAStoreRecreation() {
        val first = profiles()
        val created = (first.create("Persisted", mapOf(PrivacyField.TYPING to Visibility.HIDE)) as PrivacyOpResult.Success).value
        first.switchTo(created.id)

        val second = profiles(store)
        assertTrue(second.profiles().any { it.id == created.id })
        assertEquals(created.id, second.activeId())
        assertEquals(Visibility.HIDE, second.active().choiceFor(PrivacyField.TYPING))
    }

    @Test
    fun builtInsAreImmutableButDuplicable() {
        val store = profiles()
        val copy = store.duplicate(BuiltInPrivacyProfiles.MAXIMUM_PRIVACY, "My maximum")
        assertTrue(copy.isSuccess)
        val duplicated = (copy as PrivacyOpResult.Success).value
        assertFalse(duplicated.builtIn)
        assertEquals(BuiltInPrivacyProfiles.maximumPrivacy.hiddenFields, duplicated.hiddenFields)
    }

    // --- T77/T78: overrides -----------------------------------------------------------

    @Test
    fun anOverrideChangesOnlyTheConfiguredField() {
        val profiles = profiles()
        val overrides = PrivacyOverrideStore(store)
        val resolver = PrivacyResolver(profiles, overrides)
        profiles.switchTo(BuiltInPrivacyProfiles.NORMAL)
        overrides.setOverride("contact-a", ChatKind.CONTACT, PrivacyField.READ_RECEIPTS, Visibility.HIDE)

        val effective = resolver.effective("contact-a", ChatKind.CONTACT)
        assertEquals(Visibility.HIDE, effective.choiceFor(PrivacyField.READ_RECEIPTS))
        assertEquals(Visibility.SHOW, effective.choiceFor(PrivacyField.TYPING))
        assertEquals(setOf(PrivacyField.READ_RECEIPTS), effective.overriddenFields)
        assertTrue(effective.isOverridden(PrivacyField.READ_RECEIPTS))
        assertFalse(effective.isOverridden(PrivacyField.TYPING))
        assertEquals("Normal", effective.profileName)
    }

    @Test
    fun clearingAnOverrideReturnsTheFieldToTheProfile() {
        val profiles = profiles()
        val overrides = PrivacyOverrideStore(store)
        overrides.setOverride("contact-a", ChatKind.CONTACT, PrivacyField.TYPING, Visibility.HIDE)
        assertTrue(overrides.clearOverride("contact-a", ChatKind.CONTACT, PrivacyField.TYPING))
        assertFalse(overrides.hasOverrides("contact-a", ChatKind.CONTACT))
        val effective = PrivacyResolver(profiles, overrides).effective("contact-a", ChatKind.CONTACT)
        assertEquals(Visibility.SHOW, effective.choiceFor(PrivacyField.TYPING))
    }

    @Test
    fun groupRulesAreIsolatedFromContactRulesWithTheSameId() {
        val profiles = profiles()
        val overrides = PrivacyOverrideStore(store)
        overrides.setOverride("same-id", ChatKind.CONTACT, PrivacyField.LAST_SEEN, Visibility.HIDE)
        overrides.setOverride("same-id", ChatKind.GROUP, PrivacyField.LAST_SEEN, Visibility.HIDE)
        overrides.setOverride("same-id", ChatKind.GROUP, PrivacyField.TYPING, Visibility.HIDE)
        overrides.clearOverride("same-id", ChatKind.CONTACT, PrivacyField.LAST_SEEN)

        val resolver = PrivacyResolver(profiles, overrides)
        val contact = resolver.effective("same-id", ChatKind.CONTACT)
        val group = resolver.effective("same-id", ChatKind.GROUP)
        assertEquals(Visibility.SHOW, contact.choiceFor(PrivacyField.LAST_SEEN))
        assertEquals(Visibility.HIDE, group.choiceFor(PrivacyField.LAST_SEEN))
        assertEquals(Visibility.HIDE, group.choiceFor(PrivacyField.TYPING))
        assertNotEquals(contact.overriddenFields, group.overriddenFields)
    }

    @Test
    fun overridesSurviveBackupStyleStorageRecreation() {
        val overrides = PrivacyOverrideStore(store)
        overrides.setOverride("group-1", ChatKind.GROUP, PrivacyField.RECORDING, Visibility.HIDE)

        val reloaded = PrivacyOverrideStore(store)
        assertEquals(
            mapOf(PrivacyField.RECORDING to Visibility.HIDE),
            reloaded.overrides("group-1", ChatKind.GROUP),
        )
        assertEquals(1, reloaded.count())
        assertEquals(ChatKind.GROUP, reloaded.all().single().kind)
    }

    @Test
    fun aBlankChatIdIsRefused() {
        val overrides = PrivacyOverrideStore(store)
        assertFalse(overrides.setOverride("  ", ChatKind.CONTACT, PrivacyField.TYPING, Visibility.HIDE))
        assertFalse(overrides.hasOverrides("  ", ChatKind.CONTACT))
    }

    @Test
    fun theProfileStillProvidesCallAndNotificationBehaviour() {
        val profiles = profiles()
        val overrides = PrivacyOverrideStore(store)
        profiles.switchTo(BuiltInPrivacyProfiles.NIGHT)
        val effective = PrivacyResolver(profiles, overrides).effective("nobody", ChatKind.CONTACT)
        assertEquals(CallBehavior.SILENCE, effective.callBehavior)
        assertEquals(NotificationPrivacy.HIDE_ALL, effective.notificationPrivacy)
    }

    @Test
    fun everyBuiltInProfilePassesValidation() {
        val problems = BuiltInPrivacyProfiles.all.flatMap { PrivacyProfileValidation.validate(it) }
        assertTrue("built-in profiles must all be valid: $problems", problems.isEmpty())
    }

    @Test
    fun maximumPrivacyHidesEveryField() {
        assertEquals(PrivacyField.entries.toSet(), BuiltInPrivacyProfiles.maximumPrivacy.hiddenFields)
        assertEquals(CallBehavior.REJECT, BuiltInPrivacyProfiles.maximumPrivacy.callBehavior)
    }

    @Test
    fun ghostKeepsNotificationsVisibleSoThePhoneStillWorks() {
        assertEquals(NotificationPrivacy.FULL, BuiltInPrivacyProfiles.ghost.notificationPrivacy)
        assertEquals(Visibility.HIDE, BuiltInPrivacyProfiles.ghost.choiceFor(PrivacyField.READ_RECEIPTS))
    }
}
