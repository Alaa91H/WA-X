package com.wax.module.modern

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects the #449 release-on-reply state against duplicate receipt jobs. */
class ModernReceiptAtomicReleaseTest {
    @Test fun twoSimultaneousReceiptJobsCanConsumeOneArmingOnlyOnce() {
        val pool = Executors.newFixedThreadPool(12)
        try {
            repeat(30) { round ->
                ModernReceiptPrivacyFeature.clearArmedConversations()
                assertTrue(
                    ModernReceiptPrivacyFeature.onSendCompleted(
                        "opaque-chat-key",
                        ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
                        1_000L + round,
                    ),
                )
                val start = CountDownLatch(1)
                val tasks = (0 until 12).map {
                    pool.submit(Callable {
                        start.await()
                        ModernReceiptPrivacyFeature.consumeArming("opaque-chat-key", 1_001L + round)
                    })
                }
                start.countDown()
                val released = tasks.count { it.get(5, TimeUnit.SECONDS) }
                assertEquals("One successful send permits just one receipt release", 1, released)
                assertEquals(0, ModernReceiptPrivacyFeature.armedCount())
            }
        } finally {
            ModernReceiptPrivacyFeature.clearArmedConversations()
            pool.shutdownNow()
        }
    }

    @Test fun afterReplyCannotBeReportedArmedWithoutAnInstalledNativeSendCallback() {
        assertEquals(
            ModernReceiptPrivacyFeature.Outcome.UNSUPPORTED,
            ModernReceiptPrivacyFeature.afterReplyOperationalState(
                requested = true,
                readHook = ModernReceiptPrivacyFeature.Outcome.INSTALLED,
            ),
        )
        assertEquals(
            ModernReceiptPrivacyFeature.Outcome.DISABLED,
            ModernReceiptPrivacyFeature.afterReplyOperationalState(false, ModernReceiptPrivacyFeature.Outcome.INSTALLED),
        )
        assertEquals(
            ModernReceiptPrivacyFeature.Outcome.RESOLVER_MISSING,
            ModernReceiptPrivacyFeature.afterReplyOperationalState(true, ModernReceiptPrivacyFeature.Outcome.RESOLVER_MISSING),
        )
    }

    @Test fun overflowingClockCannotArmPermanentOrExpiredPermission() {
        ModernReceiptPrivacyFeature.clearArmedConversations()
        assertFalse(
            ModernReceiptPrivacyFeature.onSendCompleted(
                "opaque-chat-key",
                ModernReceiptPrivacyFeature.SendResult.SUCCEEDED,
                Long.MAX_VALUE,
            ),
        )
        assertFalse(ModernReceiptPrivacyFeature.isArmed("opaque-chat-key", 10L))
        ModernReceiptPrivacyFeature.clearArmedConversations()
    }
}
