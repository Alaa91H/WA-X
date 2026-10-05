package com.wmods.wppenhacer.notifications

import com.wmods.wppenhacer.platform.ChatKind
import com.wmods.wppenhacer.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

class NotificationsTest {
    private lateinit var store: InMemoryKeyValueStore

    @Before
    fun setUp() {
        store = InMemoryKeyValueStore()
    }

    // --- T135/T136: profiles and privacy ----------------------------------------------

    @Test
    fun aChatWithoutAProfileGetsStockBehaviour() {
        val profiles = NotificationProfileStore(store)
        val profile = profiles.profileFor("chat", ChatKind.CONTACT)
        assertEquals(NotificationImportance.DEFAULT, profile.importance)
        assertEquals(NotificationPrivacyLevel.FULL, profile.privacy.level)
        assertTrue(profile.privacy.showsSender())
        assertTrue(profile.privacy.showsText())
        assertTrue(profile.privacy.showsMediaPreview())
    }

    @Test
    fun aSavedProfileRoundTrips() {
        val profiles = NotificationProfileStore(store)
        profiles.save(
            NotificationProfile(
                chatId = "chat",
                kind = ChatKind.GROUP,
                soundEnabled = false,
                importance = NotificationImportance.LOW,
                privacy =
                    NotificationPrivacy(
                        level = NotificationPrivacyLevel.HIDE_CONTENT,
                        hideMediaPreview = true,
                        hideOnLockScreen = true,
                    ),
                customActions = setOf(NotificationActions.TRANSLATE),
            ),
        )
        val reloaded = NotificationProfileStore(store).profileFor("chat", ChatKind.GROUP)
        assertFalse(reloaded.soundEnabled)
        assertEquals(NotificationImportance.LOW, reloaded.importance)
        assertTrue(reloaded.privacy.showsSender())
        assertFalse(reloaded.privacy.showsText())
        assertFalse(reloaded.privacy.showsOnLockScreen())
        assertEquals(setOf(NotificationActions.TRANSLATE), reloaded.customActions)
        assertEquals(1, profiles.profiles().size)
    }

    @Test
    fun everyPrivacyLevelExposesWhatItSays() {
        val privacy = NotificationPrivacy(NotificationPrivacyLevel.HIDE_SENDER)
        assertFalse(privacy.showsSender())
        assertFalse(privacy.showsText())
        assertFalse(privacy.showsMediaPreview())
        assertTrue(privacy.showsOnLockScreen())

        val all = NotificationPrivacy(NotificationPrivacyLevel.HIDE_ALL)
        assertFalse(all.showsOnLockScreen())
    }

    // --- T139: quiet hours ------------------------------------------------------------

    @Test
    fun anOvernightQuietWindowIncludesTheNextMorning() {
        val quiet = QuietHours(LocalTime.parse("22:00"), LocalTime.parse("07:00"), setOf(DayOfWeek.FRIDAY))
        assertTrue(quiet.isQuietAt(LocalTime.parse("23:00"), DayOfWeek.FRIDAY))
        assertTrue("Saturday morning belongs to Friday night", quiet.isQuietAt(LocalTime.parse("02:00"), DayOfWeek.SATURDAY))
        assertFalse(quiet.isQuietAt(LocalTime.parse("12:00"), DayOfWeek.SATURDAY))
        assertFalse(quiet.isQuietAt(LocalTime.parse("23:00"), DayOfWeek.MONDAY))
    }

    @Test
    fun aSameDayWindowEndsExclusively() {
        val quiet = QuietHours(LocalTime.parse("09:00"), LocalTime.parse("17:00"))
        assertTrue(quiet.isQuietAt(LocalTime.parse("09:00"), DayOfWeek.MONDAY))
        assertFalse(quiet.isQuietAt(LocalTime.parse("17:00"), DayOfWeek.MONDAY))
    }

    @Test
    fun aDisabledWindowNeverApplies() {
        val quiet = QuietHours(LocalTime.parse("22:00"), LocalTime.parse("07:00"), enabled = false)
        assertFalse(quiet.isQuietAt(LocalTime.parse("23:00"), DayOfWeek.MONDAY))
    }

    @Test
    fun theGlobalWindowIsTheDefaultAndAChatCanOverrideIt() {
        val profiles = NotificationProfileStore(store)
        profiles.setGlobalQuietHours(QuietHours(LocalTime.parse("22:00"), LocalTime.parse("07:00")))
        assertTrue(profiles.isQuietNow("a", ChatKind.CONTACT, LocalTime.parse("23:00"), DayOfWeek.MONDAY))

        profiles.save(
            NotificationProfile(
                chatId = "a",
                kind = ChatKind.CONTACT,
                quietHours = QuietHours(LocalTime.parse("22:00"), LocalTime.parse("07:00"), enabled = false),
            ),
        )
        assertFalse("a chat's own settings win", profiles.isQuietNow("a", ChatKind.CONTACT, LocalTime.parse("23:00"), DayOfWeek.MONDAY))
        assertTrue(profiles.isQuietNow("b", ChatKind.CONTACT, LocalTime.parse("23:00"), DayOfWeek.MONDAY))
    }

    // --- T138: OTP detection ----------------------------------------------------------

    @Test
    fun aVerificationCodeIsDetectedFromEnglishText() {
        assertEquals("123456", OtpDetector.findCode("Your verification code is 123456"))
        assertEquals("4321", OtpDetector.findCode("OTP: 4321"))
        assertTrue(OtpDetector.containsCode("PIN 98765"))
    }

    @Test
    fun arabicAndPersianDigitsAreNormalised() {
        assertEquals("1234", OtpDetector.findCode("رمز التحقق ١٢٣٤"))
        assertEquals("5678", OtpDetector.findCode("كود ۵۶۷۸"))
        assertEquals("12345", OtpDetector.normaliseDigits("١٢٣٤٥"))
    }

    @Test
    fun phoneNumbersAmountsAndBareDigitsAreNotCodes() {
        assertNull(OtpDetector.findCode("Call me on 4915112345678"))
        assertNull(OtpDetector.findCode("That costs 25000 today"))
        assertNull("a code without a verification keyword is too risky to copy", OtpDetector.findCode("123456"))
        assertNull(OtpDetector.findCode("Order 1234567890123 shipped"))
    }

    // --- T137: actions ----------------------------------------------------------------

    @Test
    fun notificationActionsRequireTheirContent() {
        val textOnly = NotificationActions.available(hasText = true, hasMedia = false, hasOtp = false).map { it.id }
        assertTrue(textOnly.contains(NotificationActions.TRANSLATE))
        assertFalse(textOnly.contains(NotificationActions.SAVE_MEDIA))
        assertFalse(textOnly.contains(NotificationActions.COPY_OTP))

        val withOtp = NotificationActions.available(true, false, true).map { it.id }
        assertTrue(withOtp.contains(NotificationActions.COPY_OTP))
        assertTrue(withOtp.contains(NotificationActions.QUICK_REPLY))

        val mediaOnly = NotificationActions.available(false, true, false).map { it.id }
        assertEquals(listOf(NotificationActions.SAVE_MEDIA), mediaOnly)
    }
}
