package com.wax.module.xposed.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeAccessPolicyTest {
    private val wpp = "com.whatsapp"
    private val wppb = "com.whatsapp.w4b"
    private val module = "com.wax.module"
    private val settingsProvider = "com.android.providers.settings"

    /** The policy reports '/' separators regardless of the host running the test. */
    private fun normalised(file: File): String = file.path.replace('\\', '/')

    // --- obtaining the binder handle -------------------------------------------------

    @Test
    fun targetPackageMayObtainTheHandle() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                arrayOf(wpp),
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun businessPackageMayObtainTheHandle() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                arrayOf(wppb),
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun settingsProviderMayObtainTheHandle() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                arrayOf(settingsProvider),
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun ownPackageMayObtainTheHandleByName() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                arrayOf(module),
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun ownUidAlwaysMayObtainTheHandle() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                emptyArray(),
                isSelfUid = true,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun systemUidAlwaysMayObtainTheHandle() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                emptyArray(),
                isSelfUid = false,
                isSystemUid = true,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun unrelatedPackageMayNotObtainTheHandle() {
        assertFalse(
            BridgeAccessPolicy.isAllowedBinderRequester(
                arrayOf("com.example.evil"),
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun nullPackageListIsRejected() {
        assertFalse(
            BridgeAccessPolicy.isAllowedBinderRequester(
                null,
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    @Test
    fun oneAllowedPackageAmongManyIsEnough() {
        assertTrue(
            BridgeAccessPolicy.isAllowedBinderRequester(
                arrayOf("com.example.evil", wpp),
                isSelfUid = false,
                isSystemUid = false,
                modulePackage = module,
            ),
        )
    }

    // --- using the file tunnel ------------------------------------------------------

    @Test
    fun targetPackageMayUseTheTunnel() {
        assertTrue(BridgeAccessPolicy.isAllowedTunnelCaller(arrayOf(wpp), isSelfUid = false))
    }

    @Test
    fun businessPackageMayUseTheTunnel() {
        assertTrue(BridgeAccessPolicy.isAllowedTunnelCaller(arrayOf(wppb), isSelfUid = false))
    }

    @Test
    fun ownUidMayUseTheTunnel() {
        assertTrue(BridgeAccessPolicy.isAllowedTunnelCaller(emptyArray(), isSelfUid = true))
    }

    @Test
    fun settingsProviderMayNotUseTheTunnelEvenThoughItGetsTheHandle() {
        // This is the defence-in-depth split: holding the binder is not enough.
        assertFalse(BridgeAccessPolicy.isAllowedTunnelCaller(arrayOf(settingsProvider), isSelfUid = false))
    }

    @Test
    fun systemUidIsNotSpecialCasedForTheTunnel() {
        // The tunnel caller is a uid, not a policy, so SYSTEM_UID gets no bypass here.
        assertFalse(BridgeAccessPolicy.isAllowedTunnelCaller(arrayOf("android"), isSelfUid = false))
    }

    @Test
    fun unrelatedPackageMayNotUseTheTunnel() {
        assertFalse(
            BridgeAccessPolicy.isAllowedTunnelCaller(arrayOf("com.example.evil"), isSelfUid = false),
        )
    }

    @Test
    fun nullPackageListIsRejectedForTheTunnel() {
        assertFalse(BridgeAccessPolicy.isAllowedTunnelCaller(null, isSelfUid = false))
    }

    @Test
    fun targetPackageNamesAreTheOnesTheModuleActuallyHooks() {
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), BridgeAccessPolicy.TARGET_PACKAGES)
    }

    // --- path containment ------------------------------------------------------------

    @Test
    fun theRootItselfIsAllowed() {
        assertTrue(BridgeAccessPolicy.isUnderRoot("/storage/emulated/0", "/storage/emulated/0"))
    }

    @Test
    fun aDescendantIsAllowed() {
        assertTrue(BridgeAccessPolicy.isUnderRoot("/storage/emulated/0/DCIM/a.jpg", "/storage/emulated/0"))
    }

    @Test
    fun aSiblingWithASharedPrefixIsRejected() {
        // Without the separator check "/storage/ABCdef" would pass as being under
        // "/storage/ABC".
        assertFalse(BridgeAccessPolicy.isUnderRoot("/storage/ABCdef", "/storage/ABC"))
    }

    @Test
    fun aParentIsRejected() {
        assertFalse(BridgeAccessPolicy.isUnderRoot("/storage", "/storage/emulated/0"))
    }

    @Test
    fun anUnrelatedPathIsRejected() {
        assertFalse(BridgeAccessPolicy.isUnderRoot("/data/data/com.whatsapp", "/storage/emulated/0"))
    }

    // --- shared storage roots --------------------------------------------------------

    // --- volume root extraction ---------------------------------------------------------

    @Test
    fun volumeRootIsTheSegmentAboveAndroidData() {
        assertEquals(
            "/storage/1234-5678",
            BridgeAccessPolicy.volumeRootOf("/storage/1234-5678/Android/data/pkg/files"),
        )
    }

    @Test
    fun aPathWithoutTheMarkerHasNoVolumeRoot() {
        assertNull(BridgeAccessPolicy.volumeRootOf("/tmp/somewhere/else"))
    }

    @Test
    fun aPathThatStartsWithTheMarkerHasNoVolumeRoot() {
        // Returning "" here would make the caller treat the filesystem root as an
        // allowed bridge root, so the match at index zero must be rejected.
        assertNull(BridgeAccessPolicy.volumeRootOf("/Android/data/pkg/files"))
    }

    @Test
    fun windowsStyleSeparatorsAreNormalisedBeforeExtraction() {
        assertEquals(
            "/storage/ABCD",
            BridgeAccessPolicy.volumeRootOf("\\storage\\ABCD\\Android\\data\\pkg"),
        )
    }

    @Test
    fun sharedRootsAlwaysIncludePrimaryExternalStorage() {
        val roots =
            BridgeAccessPolicy.sharedStorageRoots(
                externalStorageDir = File("/storage/emulated/0"),
                externalFilesDirs = emptyList(),
            )
        assertEquals(1, roots.size)
        assertTrue(normalised(roots.first()).endsWith("/storage/emulated/0"))
    }

    @Test
    fun sharedRootsAddTheVolumeOfAnAppSpecificDirectory() {
        val roots =
            BridgeAccessPolicy.sharedStorageRoots(
                externalStorageDir = File("/storage/emulated/0"),
                externalFilesDirs = listOf(File("/storage/1234-5678/Android/data/pkg/files")),
            )
        assertEquals(2, roots.size)
        assertTrue(roots.map { normalised(it) }.any { it.endsWith("/storage/1234-5678") })
    }

    @Test
    fun nullAndMarkerlessDirectoriesAreSkipped() {
        val roots =
            BridgeAccessPolicy.sharedStorageRoots(
                externalStorageDir = File("/storage/emulated/0"),
                externalFilesDirs = listOf(null, File("/tmp/elsewhere"), null),
            )
        assertEquals(1, roots.size)
    }

    @Test
    fun duplicateVolumeRootsAreCollapsed() {
        val roots =
            BridgeAccessPolicy.sharedStorageRoots(
                externalStorageDir = File("/storage/emulated/0"),
                externalFilesDirs =
                    listOf(
                        File("/storage/ABCD/Android/data/pkg/files"),
                        File("/storage/ABCD/Android/data/other/files"),
                    ),
            )
        assertEquals(2, roots.size)
    }

    @Test
    fun windowsStyleSeparatorsAreNormalised() {
        // The rule is defined against the '/' convention Android storage uses, so a
        // host-native separator must not change the verdict.
        assertTrue(BridgeAccessPolicy.isUnderRoot("\\storage\\emulated\\0\\a.jpg", "/storage/emulated/0"))
        assertFalse(BridgeAccessPolicy.isUnderRoot("\\storage\\ABCdef", "/storage/ABC"))
    }

    @Test
    fun aTrailingSeparatorOnTheRootIsIgnored() {
        assertTrue(BridgeAccessPolicy.isUnderRoot("/storage/emulated/0/a.jpg", "/storage/emulated/0/"))
    }

    // --- PackageSettings scraping ---------------------------------------------------

    @Test
    fun packageNameIsReadFromThePkgFieldOfARealDump() {
        // Regression test. The previous implementation scraped between the last space and
        // the last '/', which on a real dump returned "codePath=/data/user/0" and so could
        // never match a package name.
        val dump =
            "PackageSettings{38a1b2c userId=10123 pkg=com.wax.module " +
                "codePath=/data/user/0/com.wax.module enabled=0 hidden=0}"
        assertEquals(
            "com.wax.module",
            BridgeAccessPolicy.packageNameFromPackageSettings(dump),
        )
    }

    @Test
    fun packageNameIsReadFromADumpWithoutTrailingFields() {
        val dump = "PackageSettings{38a1b2c userId=10123 pkg=com.whatsapp}"
        assertEquals("com.whatsapp", BridgeAccessPolicy.packageNameFromPackageSettings(dump))
    }

    @Test
    fun aDumpWithoutThePkgFieldYieldsNull() {
        assertNull(BridgeAccessPolicy.packageNameFromPackageSettings("PackageSettings{userId=10123}"))
    }

    @Test
    fun anEmptyDumpYieldsNull() {
        assertNull(BridgeAccessPolicy.packageNameFromPackageSettings(""))
        assertNull(BridgeAccessPolicy.packageNameFromPackageSettings(null))
    }

    @Test
    fun aTruncatedPkgFieldYieldsNull() {
        assertNull(BridgeAccessPolicy.packageNameFromPackageSettings("PackageSettings{38a1b2c pkg="))
    }

    @Test
    fun aPkgFieldWithNoValueYieldsNull() {
        assertNull(BridgeAccessPolicy.packageNameFromPackageSettings("PackageSettings{ pkg=}"))
    }
}
