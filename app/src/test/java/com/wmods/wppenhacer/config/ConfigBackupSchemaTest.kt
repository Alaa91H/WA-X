package com.wmods.wppenhacer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupSchemaTest {
    // --- export side ------------------------------------------------------------------

    @Test
    fun booleansAreTaggedBoolean() {
        assertEquals("Boolean", ConfigBackupSchema.typeNameOf(true))
    }

    @Test
    fun intsAreTaggedInteger() {
        assertEquals("Integer", ConfigBackupSchema.typeNameOf(3))
    }

    @Test
    fun longsAreTaggedLong() {
        assertEquals("Long", ConfigBackupSchema.typeNameOf(3L))
    }

    @Test
    fun floatsAreTaggedFloat() {
        assertEquals("Float", ConfigBackupSchema.typeNameOf(1.5f))
    }

    @Test
    fun doublesAreTaggedDouble() {
        assertEquals("Double", ConfigBackupSchema.typeNameOf(1.5))
    }

    @Test
    fun stringsAreTaggedString() {
        assertEquals("String", ConfigBackupSchema.typeNameOf("dark"))
    }

    @Test
    fun setsAreTaggedAsTheArrayType() {
        assertEquals("JSONArray", ConfigBackupSchema.typeNameOf(setOf("a", "b")))
    }

    @Test
    fun nullIsNotBackedUp() {
        assertNull(ConfigBackupSchema.typeNameOf(null))
    }

    @Test
    fun unsupportedValuesAreNotBackedUp() {
        assertNull(ConfigBackupSchema.typeNameOf(Thread.currentThread()))
    }

    // --- import side ------------------------------------------------------------------

    @Test
    fun stringsDecode() {
        assertEquals(ConfigValue.Text("dark"), ConfigBackupSchema.decode("String", "dark"))
    }

    @Test
    fun booleansDecode() {
        assertEquals(ConfigValue.Flag(true), ConfigBackupSchema.decode("Boolean", true))
    }

    @Test
    fun theLowerCaseBooleanAliasStillDecodes() {
        assertEquals(ConfigValue.Flag(false), ConfigBackupSchema.decode("boolean", false))
    }

    @Test
    fun intsDecode() {
        assertEquals(ConfigValue.Whole(7), ConfigBackupSchema.decode("Integer", 7))
    }

    @Test
    fun theIntAliasStillDecodes() {
        assertEquals(ConfigValue.Whole(7), ConfigBackupSchema.decode("int", 7))
    }

    @Test
    fun aJsonIntegerArrivingAsLongIsAccepted() {
        // JSON has one number type, so a value written as 7 can come back as Long.
        // The old parser cast to Int and threw.
        assertEquals(ConfigValue.Whole(7), ConfigBackupSchema.decode("Integer", 7L))
    }

    @Test
    fun longsDecode() {
        assertEquals(ConfigValue.Wide(9_000_000_000L), ConfigBackupSchema.decode("Long", 9_000_000_000L))
    }

    @Test
    fun theLongAliasStillDecodes() {
        assertEquals(ConfigValue.Wide(5L), ConfigBackupSchema.decode("long", 5L))
    }

    @Test
    fun floatsAndDoublesBothDecodeToDecimal() {
        assertEquals(ConfigValue.Decimal(1.5f), ConfigBackupSchema.decode("Float", 1.5f))
        assertEquals(ConfigValue.Decimal(1.5f), ConfigBackupSchema.decode("Double", 1.5))
    }

    @Test
    fun theFloatAndDoubleAliasesStillDecode() {
        assertEquals(ConfigValue.Decimal(2.0f), ConfigBackupSchema.decode("float", 2))
        assertEquals(ConfigValue.Decimal(2.0f), ConfigBackupSchema.decode("double", 2.0))
    }

    @Test
    fun arraysDecodeToAStringSet() {
        val decoded = ConfigBackupSchema.decode("JSONArray", listOf("a", "b"))
        assertEquals(ConfigValue.Texts(setOf("a", "b")), decoded)
    }

    @Test
    fun arrayEntriesAreStringified() {
        assertEquals(
            ConfigValue.Texts(setOf("1", "true")),
            ConfigBackupSchema.decode("JSONArray", listOf(1, true)),
        )
    }

    @Test
    fun nullArrayEntriesBecomeEmptyStrings() {
        assertEquals(ConfigValue.Texts(setOf("")), ConfigBackupSchema.decode("JSONArray", listOf(null)))
    }

    @Test
    fun anUnknownTypeIsRejectedRatherThanThrowing() {
        assertNull(ConfigBackupSchema.decode("Widget", "x"))
    }

    @Test
    fun aMissingTypeIsRejected() {
        assertNull(ConfigBackupSchema.decode(null, "x"))
    }

    @Test
    fun aMismatchedValueIsRejectedRatherThanThrowing() {
        // type says Boolean, the document carries text. The old parser threw a
        // ClassCastException here, aborting the whole import.
        assertNull(ConfigBackupSchema.decode("Boolean", "not-a-boolean"))
        assertNull(ConfigBackupSchema.decode("Integer", "seven"))
        assertNull(ConfigBackupSchema.decode("JSONArray", "not-a-list"))
    }

    @Test
    fun aNullValueIsRejectedRatherThanThrowing() {
        assertNull(ConfigBackupSchema.decode("String", null))
        assertNull(ConfigBackupSchema.decode("Boolean", null))
    }

    // --- whole document ---------------------------------------------------------------

    @Test
    fun aFullyValidDocumentDecodes() {
        val entries =
            listOf(
                BackupEntry("antirevoke", "Boolean", true),
                BackupEntry("thememode", "String", "1"),
                BackupEntry("tags", "JSONArray", listOf("a")),
            )
        val decoded = ConfigBackupSchema.decodeAll(entries)
        assertEquals(3, decoded?.size)
        assertEquals("antirevoke" to ConfigValue.Flag(true), decoded?.first())
    }

    @Test
    fun oneBadEntryRejectsTheWholeDocument() {
        // This is the guarantee that lets a restore wipe first without risking data loss:
        // the caller learns the document is unusable before touching preferences.
        val entries =
            listOf(
                BackupEntry("good", "Boolean", true),
                BackupEntry("bad", "Widget", "x"),
            )
        assertNull(ConfigBackupSchema.decodeAll(entries))
    }

    @Test
    fun anEmptyDocumentDecodesToNothing() {
        assertEquals(emptyList<Pair<String, ConfigValue>>(), ConfigBackupSchema.decodeAll(emptyList()))
    }

    @Test
    fun everyExportedTypeCanBeReadBack() {
        // Mirrors the real export -> JSON -> import path: a set is flattened into a JSON
        // array, so it comes back from the parser as a List rather than a Set.
        val samples =
            listOf<Any?>(
                true,
                3,
                3L,
                1.5f,
                1.5,
                "text",
                setOf("x"),
            )
        for (sample in samples) {
            val typeName = ConfigBackupSchema.typeNameOf(sample)
            assertTrue("typeNameOf($sample) must not be null", typeName != null)
            val asJsonWouldDeliver = if (sample is Set<*>) sample.toList() else sample
            assertTrue(
                "round trip failed for $sample as $typeName",
                ConfigBackupSchema.decode(typeName, asJsonWouldDeliver) != null,
            )
        }
    }

    @Test
    fun anExportedStringSetReadsBackAsAKeyValueOfTheSameMembers() {
        val original = setOf("a", "b", "c")
        val typeName = ConfigBackupSchema.typeNameOf(original)
        val decoded = ConfigBackupSchema.decode(typeName, original.toList())
        assertTrue(decoded is ConfigValue.Texts)
        assertEquals(original, (decoded as ConfigValue.Texts).value)
    }

    @Test
    fun falseIsDistinctFromAnAbsentFlag() {
        // Guards against a parser that treats any non-null value as true.
        assertEquals(ConfigValue.Flag(false), ConfigBackupSchema.decode("Boolean", false))
        assertFalse(ConfigBackupSchema.decode("Boolean", false) == ConfigValue.Flag(true))
    }
}
