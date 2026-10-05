package com.wax.module.settings

import com.wax.module.platform.TargetApp
import com.wax.module.storage.BackupV3Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A backup must preserve the three scopes.
 *
 * Flattening them into one key space is the failure this guards against: a restored
 * file would look fine and silently apply Business's settings to WhatsApp.
 */
class TargetScopedBackupTest {
    private lateinit var store: InMemorySettingsStore
    private val global = SettingsScope.Global
    private val wa = SettingsScope.Target(TargetApp.WHATSAPP)
    private val business = SettingsScope.Target(TargetApp.WHATSAPP_BUSINESS)

    @Before
    fun setUp() {
        store = InMemorySettingsStore()
    }

    private fun populated(): InMemorySettingsStore {
        store.writeBoolean(global, "showonline", true)
        store.writeString(global, "status_style", "2")
        store.writeBoolean(wa, "showonline", false)
        store.writeString(wa, "status_style", "1")
        store.writeBoolean(business, "voicenote_speed", true)
        return store
    }

    @Test
    fun `a round trip preserves all three scopes`() {
        val document = TargetScopedBackup.toDocument(populated(), "1.0.0", 1_700_000_000_000L)

        val restored = InMemorySettingsStore()
        val result = TargetScopedBackup.restore(restored, document)

        assertTrue(result is TargetScopedBackup.RestoreResult.Restored)
        assertEquals(true, restored.readBoolean(global, "showonline"))
        assertEquals("2", restored.readString(global, "status_style"))
        assertEquals(false, restored.readBoolean(wa, "showonline"))
        assertEquals("1", restored.readString(wa, "status_style"))
        assertEquals(true, restored.readBoolean(business, "voicenote_speed"))
        // WhatsApp must not have gained Business's key, and vice versa.
        assertEquals(null, restored.readBoolean(wa, "voicenote_speed"))
        assertEquals(null, restored.readString(business, "status_style"))
    }

    @Test
    fun `the document keeps the existing backup schema`() {
        val document = TargetScopedBackup.toDocument(populated(), "1.0.0", 0L)
        assertEquals(BackupV3Codec.CURRENT_SCHEMA, document.schemaVersion)

        val json = TargetScopedBackup.encode(populated(), "1.0.0", 0L)
        assertTrue("sections missing from the encoded document", json.contains("\"sections\""))
        assertTrue("global section missing", json.contains("\"global\""))
        assertTrue("whatsapp section missing", json.contains("\"whatsapp\""))
        assertTrue("business section missing", json.contains("\"business\""))
    }

    @Test
    fun `empty scopes are omitted rather than written as empty objects`() {
        val document = TargetScopedBackup.toDocument(store, "1.0.0", 0L)
        assertTrue(document.sections.isEmpty())
    }

    @Test
    fun `an unknown section is refused and nothing is written`() {
        val target = InMemorySettingsStore()
        target.writeBoolean(global, "existing", true)

        val document = TargetScopedBackup.toDocument(populated(), "1.0.0", 0L)
        val tampered =
            document.copy(
                sections = document.sections + ("nonsense" to document.sections.getValue("global")),
            )

        val result = TargetScopedBackup.restore(target, tampered)

        assertTrue(result is TargetScopedBackup.RestoreResult.Rejected)
        // Untouched: a refused restore must not half-apply.
        assertEquals(true, target.readBoolean(global, "existing"))
        assertEquals(0, target.keysWithOverrides(SettingsScope.Target(TargetApp.WHATSAPP)).size)
    }

    @Test
    fun `a section that is not an object is refused`() {
        val target = InMemorySettingsStore()
        val document = TargetScopedBackup.toDocument(populated(), "1.0.0", 0L)
        val tampered =
            document.copy(
                sections =
                    mapOf(
                        "global" to
                            com.wax.module.platform.JsonValue
                                .Arr(emptyList()),
                    ),
            )

        assertTrue(TargetScopedBackup.restore(target, tampered) is TargetScopedBackup.RestoreResult.Rejected)
        assertEquals(0, target.keysWithOverrides(global).size)
    }

    @Test
    fun `a document with no global section is valid and restores targets only`() {
        val full = TargetScopedBackup.toDocument(populated(), "1.0.0", 0L)
        val whatsappOnly =
            full.copy(
                sections = mapOf("whatsapp" to full.sections.getValue("whatsapp")),
            )

        val target = InMemorySettingsStore()
        assertTrue(
            TargetScopedBackup.restore(target, whatsappOnly) is TargetScopedBackup.RestoreResult.Restored,
        )
        assertEquals("1", target.readString(SettingsScope.Target(TargetApp.WHATSAPP), "status_style"))
        assertEquals(0, target.keysWithOverrides(SettingsScope.Global).size)
    }

    @Test
    fun `restoring replaces previous contents rather than merging`() {
        val target = InMemorySettingsStore()
        target.writeBoolean(SettingsScope.Target(TargetApp.WHATSAPP), "stale_key", true)

        TargetScopedBackup.restore(target, TargetScopedBackup.toDocument(populated(), "1.0.0", 0L))

        assertFalse(target.keysWithOverrides(SettingsScope.Target(TargetApp.WHATSAPP)).contains("stale_key"))
    }
}
