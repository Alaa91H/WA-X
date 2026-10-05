package com.wax.module.multipackage

import com.wax.module.platform.FeatureKillSwitch
import com.wax.module.platform.FeatureSwitchState
import com.wax.module.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MultiPackageTest {
    private lateinit var store: InMemoryKeyValueStore

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
    }

    // --- T152: package profiles -------------------------------------------------------

    @Test
    fun theKnownTargetsAreResolvable() {
        assertEquals(TargetPackage.WHATSAPP, TargetPackage.byPackageName("com.whatsapp"))
        assertEquals(TargetPackage.BUSINESS, TargetPackage.byPackageName("com.whatsapp.w4b"))
        assertNull(TargetPackage.byPackageName("com.example.other"))
    }

    // --- T153: per-package preferences ------------------------------------------------

    @Test
    fun scopedValuesAreIndependentAndSharedValuesAreShared() {
        val preferences = PerPackagePreferences(store)
        preferences.putString("com.whatsapp", "privacy.profile", "ghost")
        preferences.putString("com.whatsapp.w4b", "privacy.profile", "work")
        assertEquals("ghost", preferences.getString("com.whatsapp", "privacy.profile"))
        assertEquals("work", preferences.getString("com.whatsapp.w4b", "privacy.profile"))

        preferences.putString("com.whatsapp", "theme", "midnight", useSharedDefaults = true)
        assertEquals("midnight", preferences.getString("com.whatsapp.w4b", "theme", useSharedDefaults = true))
    }

    @Test
    fun clearingOnePackageLeavesTheOtherAndSharedValuesAlone() {
        val preferences = PerPackagePreferences(store)
        preferences.putString("com.whatsapp", "a", "1")
        preferences.putString("com.whatsapp.w4b", "a", "2")
        preferences.putString("com.whatsapp", "shared", "x", useSharedDefaults = true)
        assertEquals(1, preferences.clearPackage("com.whatsapp"))
        assertNull(preferences.getString("com.whatsapp", "a"))
        assertEquals("2", preferences.getString("com.whatsapp.w4b", "a"))
        assertEquals("x", preferences.getString("com.whatsapp.w4b", "shared", useSharedDefaults = true))
    }

    // --- T154: per-package compatibility ----------------------------------------------

    @Test
    fun compatibilityStateRoundTripsPerPackage() {
        val states = PackageCompatibilityStore(store)
        states.record(
            PackageCompatibilityState(
                packageName = "com.whatsapp",
                versionName = "2.26.40.21",
                versionCode = 2_264_021,
                apkFingerprint = "apk-a",
                resolverCacheKey = "key-a",
            ),
        )
        states.record(
            PackageCompatibilityState(
                packageName = "com.whatsapp.w4b",
                versionName = "2.26.39.11",
                versionCode = 2_263_911,
                apkFingerprint = "apk-b",
                resolverCacheKey = "key-b",
            ),
        )
        assertEquals("2.26.40.21", states.stateOf("com.whatsapp")!!.versionName)
        assertEquals("apk-b", states.stateOf("com.whatsapp.w4b")!!.apkFingerprint)
    }

    @Test
    fun featureStatesAreRecordedPerPackage() {
        val states = PackageCompatibilityStore(store)
        states.setFeatureState("com.whatsapp", "privacy.profiles", "ENABLED")
        states.setFeatureState("com.whatsapp.w4b", "privacy.profiles", "INCOMPATIBLE")
        assertEquals("ENABLED", states.stateOf("com.whatsapp")!!.featureStates["privacy.profiles"])
        assertEquals("INCOMPATIBLE", states.stateOf("com.whatsapp.w4b")!!.featureStates["privacy.profiles"])
    }

    @Test
    fun recordedStatesCanBeAppliedToTheKillSwitch() {
        val states = PackageCompatibilityStore(store)
        states.setFeatureState("com.whatsapp", "a", "INCOMPATIBLE")
        states.setFeatureState("com.whatsapp", "b", "MANUALLY_DISABLED")
        val killSwitch = FeatureKillSwitch(store) { 0L }
        states.applyTo(killSwitch, "com.whatsapp")
        assertEquals(FeatureSwitchState.INCOMPATIBLE, killSwitch.stateOf("a"))
        assertEquals(FeatureSwitchState.MANUALLY_DISABLED, killSwitch.stateOf("b"))
    }

    // --- T155/T156: accounts ----------------------------------------------------------

    @Test
    fun accountHashesAreStableAndDoNotExposeTheRawIdentifier() {
        val first = AccountContext("com.whatsapp", "4915112345678@s.whatsapp.net")
        val second = AccountContext("com.whatsapp", "4915112345678@s.whatsapp.net")
        assertEquals(first.accountHash, second.accountHash)
        assertEquals(16, first.accountHash.length)
        assertFalse(first.describe().contains("4915"))
        assertTrue(first.toString().contains("account"))
    }

    @Test
    fun accountContextsDoNotCollideAcrossPackages() {
        val personal = AccountContext("com.whatsapp", "default")
        val business = AccountContext("com.whatsapp.w4b", "default")
        assertNotEquals(personal.scopedKey("k"), business.scopedKey("k"))
    }

    @Test
    fun theRegistryTracksAccountsPerPackage() {
        val registry = AccountRegistry(store)
        registry.register(AccountContext("com.whatsapp", "personal"))
        registry.register(AccountContext("com.whatsapp.w4b", "business"))
        assertEquals(2, registry.all().size)
        assertEquals(1, registry.forPackage("com.whatsapp").size)
        assertTrue(registry.remove(AccountContext("com.whatsapp", "personal")))
        assertEquals(1, registry.all().size)
    }

    // --- T157: per-account automation -------------------------------------------------

    @Test
    fun eachAccountKeepsItsOwnProfileAssignment() {
        val assignments = AccountProfileAssignments(store)
        val business = AccountContext("com.whatsapp.w4b", "business")
        val personal = AccountContext("com.whatsapp", "personal")
        assertTrue(assignments.assign(business, "work"))
        assertTrue(assignments.assign(personal, "ghost"))
        assertEquals("work", assignments.profileFor(business))
        assertEquals("ghost", assignments.profileFor(personal))
        assertTrue(assignments.clearAssignment(business))
        assertNull(assignments.profileFor(business))
        assertEquals("ghost", assignments.profileFor(personal))
    }

    @Test
    fun clearingAssignmentsLeavesOtherAccountSettingsAlone() {
        val assignments = AccountProfileAssignments(store)
        val context = AccountContext("com.whatsapp", "personal")
        assignments.assign(context, "ghost")
        store.putString(context.scopedKey("history.retention"), "30")
        assignments.clear()
        assertNull(assignments.profileFor(context))
        assertEquals("30", store.getString(context.scopedKey("history.retention")))
    }

    // --- T158: migration --------------------------------------------------------------

    @Test
    fun theSingleProfileMigrationRunsOnceAndCopiesToEveryPackage() {
        store.putString(MultiAccountMigration.LEGACY_PROFILE_KEY, "ghost")
        val migration = MultiAccountMigration(store)
        val report = migration.migrate(listOf("com.whatsapp", "com.whatsapp.w4b"))
        assertFalse(report.alreadyDone)
        assertEquals(2, report.migratedAccounts)
        val personal = AccountContext("com.whatsapp", MultiAccountMigration.DEFAULT_ACCOUNT_ID)
        assertEquals(
            "ghost",
            store.getString(personal.scopedKey(MultiAccountMigration.LEGACY_PROFILE_KEY)),
        )

        val second = migration.migrate()
        assertTrue(second.alreadyDone)
        assertEquals(0, second.migratedAccounts)
    }

    @Test
    fun migratingWithoutALegacyValueIsARecordedNoOp() {
        val migration = MultiAccountMigration(store)
        val report = migration.migrate(listOf("com.whatsapp"))
        assertFalse(report.alreadyDone)
        assertEquals(0, report.migratedAccounts)
        assertTrue(migration.isDone())
    }

    // --- T159: test matrix ------------------------------------------------------------

    @Test
    fun theValidationMatrixNamesEveryRequiredScenario() {
        assertEquals(
            listOf(
                "WhatsApp only",
                "Business only",
                "Both installed",
                "Multiple supported accounts",
                "Independent compatibility failures",
            ),
            MultiAccountTestMatrix.scenarios,
        )
        assertTrue(MultiAccountTestMatrix.covers(listOf("com.whatsapp"), 1))
        assertFalse(MultiAccountTestMatrix.covers(emptyList(), 1))
    }
}
