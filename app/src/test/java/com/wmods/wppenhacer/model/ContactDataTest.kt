package com.wmods.wppenhacer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDataTest {
    @Test
    fun aNameIsPreferredOverTheJid() {
        val contact = ContactData("Alice", "1234567890@s.whatsapp.net")
        assertEquals("Alice", contact.getDisplayName())
    }

    @Test
    fun theJidUserPartIsUsedWhenThereIsNoName() {
        val contact = ContactData(null, "1234567890@s.whatsapp.net")
        assertEquals("1234567890", contact.getDisplayName())
    }

    @Test
    fun anEmptyNameFallsBackToTheJid() {
        val contact = ContactData("", "1234567890@s.whatsapp.net")
        assertEquals("1234567890", contact.getDisplayName())
    }

    @Test
    fun aJidWithoutAServerSuffixIsUsedVerbatim() {
        val contact = ContactData(null, "1234567890")
        assertEquals("1234567890", contact.getDisplayName())
    }

    @Test
    fun aGroupJidYieldsTheGroupIdentifier() {
        val contact = ContactData(null, "1234567890-1600000000@g.us")
        assertEquals("1234567890-1600000000", contact.getDisplayName())
    }

    @Test
    fun aLidJidYieldsTheDeviceIdentifier() {
        val contact = ContactData(null, "1234567890@lid")
        assertEquals("1234567890", contact.getDisplayName())
    }

    @Test
    fun aBroadcastJidYieldsTheBroadcastIdentifier() {
        val contact = ContactData(null, "1234567890@broadcast")
        assertEquals("1234567890", contact.getDisplayName())
    }

    @Test
    fun aContactWithNeitherNameNorJidDisplaysAsEmpty() {
        assertEquals("", ContactData(null, null).getDisplayName())
    }

    @Test
    fun aJidThatIsOnlyAnAtSignDisplaysAsEmpty() {
        assertEquals("", ContactData(null, "@").getDisplayName())
    }

    @Test
    fun aWhitespaceNameIsTreatedAsPresent() {
        // Only null and empty fall through, matching the original behaviour.
        assertEquals(" ", ContactData(" ", "123@s.whatsapp.net").getDisplayName())
    }
}
