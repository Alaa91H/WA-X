package com.wmods.wppenhacer.resolver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverCacheKeyTest {
    private val version = "2.26.40.21"
    private val code = 2_600_000L
    private val hash = "a1b2c3d4"

    private fun key(
        v: String? = version,
        c: Long = code,
        h: String? = hash,
        schema: Int = ResolverCacheKey.SCHEMA_VERSION,
    ) = ResolverCacheKey.build(v, c, h, schema)

    // --- shape ------------------------------------------------------------------------

    @Test
    fun aKeyCarriesAllFourInputs() {
        val built = key()
        assertTrue(built.contains(version))
        assertTrue(built.contains(code.toString()))
        assertTrue(built.contains(hash))
        assertTrue(built.contains(ResolverCacheKey.SCHEMA_VERSION.toString()))
    }

    @Test
    fun theDefaultSchemaVersionIsUsed() {
        assertEquals(ResolverCacheKey.SCHEMA_VERSION, ResolverCacheKey.schemaVersionOf(key()))
    }

    @Test
    fun theSameInputsProduceTheSameKey() {
        assertEquals(key(), key())
    }

    @Test
    fun inputsAreTrimmed() {
        assertEquals(key(), key(v = "  2.26.40.21  "))
    }

    // --- invalidation -----------------------------------------------------------------

    @Test
    fun aDifferentVersionInvalidates() {
        assertFalse(ResolverCacheKey.isReusable(key(), key(v = "2.26.41.1")))
    }

    @Test
    fun aDifferentVersionCodeInvalidates() {
        assertFalse(ResolverCacheKey.isReusable(key(), key(c = code + 1)))
    }

    @Test
    fun aDifferentApkHashInvalidates() {
        assertFalse(ResolverCacheKey.isReusable(key(), key(h = "ffff0000")))
    }

    @Test
    fun aDifferentSchemaVersionInvalidates() {
        assertFalse(ResolverCacheKey.isReusable(key(), key(schema = 2)))
    }

    @Test
    fun anIdenticalTargetIsReusable() {
        assertTrue(ResolverCacheKey.isReusable(key(), key()))
    }

    @Test
    fun aMissingStoredKeyIsNeverReusable() {
        assertFalse(ResolverCacheKey.isReusable(null, key()))
        assertFalse(ResolverCacheKey.isReusable("", key()))
        assertFalse(ResolverCacheKey.isReusable("   ", key()))
    }

    @Test
    fun aDifferentStoredKeyIsNotReusable() {
        assertFalse(ResolverCacheKey.isReusable("something-else", key()))
    }

    // --- a WhatsApp update invalidates, which is the whole point ----------------------

    @Test
    fun aWhatsAppUpdateInvalidatesTheCache() {
        val before = key()
        // Same versionCode is possible in the wild, so the hash has to carry this.
        val after = key(v = "2.26.40.22", h = "deadbeef")
        assertFalse("a WhatsApp update must invalidate", ResolverCacheKey.isReusable(before, after))
    }

    @Test
    fun aResolverSchemaBumpInvalidatesAnOtherwiseIdenticalTarget() {
        val before = key()
        val after = key(schema = ResolverCacheKey.SCHEMA_VERSION + 1)
        assertFalse(ResolverCacheKey.isReusable(before, after))
    }

    @Test
    fun aRebuildOfTheSameSchemaReusesTheCache() {
        // Module version is deliberately NOT part of the key: a module rebuild that does
        // not change resolution logic should still benefit from the cache.
        assertTrue(ResolverCacheKey.isReusable(key(), key()))
    }

    // --- missing hash -----------------------------------------------------------------

    @Test
    fun aNullHashProducesAVisibleMarker() {
        assertTrue(key(h = null).contains(ResolverCacheKey.HASH_UNAVAILABLE))
    }

    @Test
    fun anEmptyHashProducesAVisibleMarker() {
        assertTrue(key(h = "   ").contains(ResolverCacheKey.HASH_UNAVAILABLE))
    }

    @Test
    fun aKeyWithoutAHashIsNeverReusable() {
        // Accepting it would reintroduce the stale-cache bug with less visibility.
        assertFalse(ResolverCacheKey.isReusable(key(h = null), key(h = null)))
    }

    @Test
    fun aNullHashIsReportedAsAbsent() {
        assertNull(ResolverCacheKey.apkHashOf(key(h = null)))
    }

    @Test
    fun aRealHashIsReadBack() {
        assertEquals(hash, ResolverCacheKey.apkHashOf(key()))
    }

    @Test
    fun aNullVersionStillProducesAUsableKeyShape() {
        val built = key(v = null)
        assertEquals(ResolverCacheKey.SCHEMA_VERSION, ResolverCacheKey.schemaVersionOf(built))
    }

    // --- reading parts back -----------------------------------------------------------

    @Test
    fun theSchemaVersionIsReadBack() {
        assertEquals(7, ResolverCacheKey.schemaVersionOf(key(schema = 7)))
    }

    @Test
    fun theSchemaVersionOfAMalformedKeyIsNull() {
        assertNull(ResolverCacheKey.schemaVersionOf("garbage"))
        assertNull(ResolverCacheKey.schemaVersionOf(""))
        assertNull(ResolverCacheKey.schemaVersionOf(null))
        assertNull(ResolverCacheKey.schemaVersionOf("a|b|c"))
    }

    @Test
    fun theSchemaVersionOfANonNumericTailIsNull() {
        assertNull(ResolverCacheKey.schemaVersionOf("a|b|c|notanumber"))
    }

    @Test
    fun theHashOfAMalformedKeyIsNull() {
        assertNull(ResolverCacheKey.apkHashOf("garbage"))
        assertNull(ResolverCacheKey.apkHashOf(null))
    }

    // --- collisions -------------------------------------------------------------------

    @Test
    fun partsCannotBeConfusedWithEachOther() {
        // Without a separator an APK hash containing the separator could forge another
        // target's key.
        assertFalse(key(h = "x|y") == key(h = "x", v = "2.26.40.21|y"))
    }

    @Test
    fun aVersionContainingTheSeparatorDoesNotForgeAKey() {
        val forged = "2.26.40.21|999|deadbeef|1"
        assertFalse(ResolverCacheKey.isReusable(forged, key(v = "2.26.40.21|999", h = "deadbeef")))
    }

    @Test
    fun anEmptyVersionIsDistinctFromAnyRealVersion() {
        assertFalse(ResolverCacheKey.isReusable(key(v = ""), key(v = "2.26.40.21")))
    }
}
