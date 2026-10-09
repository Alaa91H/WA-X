package com.wax.module.modern;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModernInvocationThrottleTest {
    @Test public void firstInvocationEmitsThenSuppressesUntilNextWindow() {
        ModernInvocationThrottle throttle = new ModernInvocationThrottle(30000L);
        assertTrue(throttle.accept(0L));
        assertFalse(throttle.accept(1L));
        assertFalse(throttle.accept(29999L));
        assertTrue(throttle.accept(30000L));
        assertFalse(throttle.accept(30001L));
    }

    @Test public void monotonicClockResetDoesNotSuppressForever() {
        ModernInvocationThrottle throttle = new ModernInvocationThrottle(100L);
        assertTrue(throttle.accept(50000L));
        assertTrue(throttle.accept(1L));
        assertFalse(throttle.accept(2L));
        assertFalse(throttle.accept(-1L));
        assertThrows(IllegalArgumentException.class, () -> new ModernInvocationThrottle(0));
    }

    @Test public void simultaneousHookCallsAuthorizeOnlyOneReport() throws Exception {
        ModernInvocationThrottle throttle = new ModernInvocationThrottle(30000L);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(24);
        AtomicInteger successes = new AtomicInteger();
        try {
            for (int i = 0; i < 24; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (throttle.accept(15000L)) successes.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(1, successes.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
