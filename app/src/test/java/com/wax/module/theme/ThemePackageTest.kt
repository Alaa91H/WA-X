package com.wax.module.theme

import com.wax.module.platform.InMemoryKeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThemePackageTest {
    private lateinit var store: InMemoryKeyValueStore

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
    }

    private fun theme() = WaTheme.default.copy(name = "Midnight")

    @Test
    fun aPackageRoundTripsWithItsManifest() {
        val encoded = ThemePackageCodec.encode(theme(), author = "User", minimumWaeVersion = "2.4.0")
        val imported = ThemePackageCodec.decode(encoded)
        assertTrue(imported is ThemePackageResult.Imported)
        val packageResult = imported as ThemePackageResult.Imported
        assertEquals("Midnight", packageResult.themePackage.theme.name)
        assertEquals("User", packageResult.themePackage.manifest.author)
        assertEquals("2.4.0", packageResult.themePackage.manifest.minimumWaeVersion)
        assertEquals(ThemePackageCodec.SCHEMA_VERSION, packageResult.themePackage.manifest.schema)
        assertEquals("waetheme", ThemePackageCodec.FILE_EXTENSION)
    }

    @Test
    fun aNewerSchemaIsRejectedWithAnActionableMessage() {
        val encoded = ThemePackageCodec.encode(theme(), "User", "3.0.0")
        val bumped = encoded.replace("\"schema\":1", "\"schema\":2")
        val result = ThemePackageCodec.decode(bumped) as ThemePackageResult.Rejected
        assertTrue(result.problems.single().code == "schema_unsupported")
        assertTrue(
            result.problems
                .single()
                .message
                .contains("schema 2"),
        )
    }

    @Test
    fun malformedAndIncompleteDocumentsAreRejectedNotCrashed() {
        assertTrue(ThemePackageCodec.decode("{") is ThemePackageResult.Rejected)
        assertTrue(ThemePackageCodec.decode(null) is ThemePackageResult.Rejected)
        val noManifest = MiniJson.write(jsonObject("theme" to jsonObject()))
        assertTrue(ThemePackageCodec.decode(noManifest) is ThemePackageResult.Rejected)
        val noBody =
            MiniJson.write(
                jsonObject(
                    "manifest" to
                        jsonObject(
                            "schema" to
                                com.wax.module.platform
                                    .jsonNumber(1L),
                            "name" to jsonString("X"),
                        ),
                ),
            )
        assertTrue(ThemePackageCodec.decode(noBody) is ThemePackageResult.Rejected)
    }

    @Test
    fun anUnsafeFontInAPackageIsRejectedByValidation() {
        val unsafe = theme().copy(typography = theme().typography.copy(fontFamily = "../fonts/evil"))
        val encoded = ThemePackageCodec.encode(unsafe, "Attacker", "2.4.0")
        val result = ThemePackageCodec.decode(encoded)
        assertTrue(result is ThemePackageResult.Rejected)
        assertTrue((result as ThemePackageResult.Rejected).problems.any { it.code == "font_unsafe" || it.code == "font_path" })
    }

    @Test
    fun anUnsafeAuthorIsRejected() {
        val encoded = ThemePackageCodec.encode(theme(), "A".repeat(ThemePackageCodec.MAX_AUTHOR_LENGTH + 1), "2.4.0")
        val result = ThemePackageCodec.decode(encoded) as ThemePackageResult.Rejected
        assertEquals("author_unsafe", result.problems.single().code)
    }

    @Test
    fun theStoredListCodecSkipsMalformedEntriesWithoutLosingTheRest() {
        val first = ThemePackageCodec.encodeTheme(theme())
        val encodedList =
            com.wax.module.platform.MiniJson.write(
                com.wax.module.platform.JsonValue.Arr(
                    listOf(
                        first,
                        com.wax.module.platform.JsonValue
                            .Str("garbage"),
                    ),
                ),
            )
        val decoded = ThemePackageCodec.decodeList(encodedList)
        assertEquals(1, decoded.size)
        assertEquals("Midnight", decoded.single().name)
    }

    @Test
    fun theLocalGalleryOffersBuiltInSamplesAndInstallsThem() {
        val repository = ThemeRepository(store)
        val gallery = ThemeGallery(repository)
        val samples = gallery.entries().filter { it.builtIn }
        assertTrue(samples.any { it.theme.name == "Midnight" })
        assertTrue(samples.any { it.theme.amoled })

        val midnight = samples.first { it.theme.name == "Midnight" }
        assertTrue(gallery.install(midnight.theme).isEmpty())
        assertTrue(gallery.entries().any { it.theme.name == "Midnight" && it.installed })
    }

    @Test
    fun theGalleryPolicyStatesThemesAreDataOnly() {
        val policy = ThemeGallery(ThemeRepository(store)).distributionPolicy()
        assertTrue(policy.contains("data only"))
        assertFalse(policy.contains("code execution"))
    }
}
