package com.wax.module.modern;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public final class ModernHookRegistryTest {
    @Test public void repeatedSingleHookDoesNotInstallTwice() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger installs = new AtomicInteger();
        ModernHookRegistry.Registration hook =
                new ModernHookRegistry.Registration("startup", () -> {
                    installs.incrementAndGet();
                    return () -> {};
                });
        assertTrue(registry.installOnce("bootstrap", hook));
        assertFalse(registry.installOnce("bootstrap", hook));
        assertEquals(1, installs.get());
        assertEquals(1, registry.installedCount("bootstrap"));
    }

    @Test public void otherFeaturesHaveIndependentLifecycle() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger removed = new AtomicInteger();
        registry.installOnce("feature-one", new ModernHookRegistry.Registration("same-id", () -> () -> { removed.incrementAndGet(); }));
        registry.installOnce("feature-two", new ModernHookRegistry.Registration("another-id", () -> () -> { removed.incrementAndGet(); }));
        registry.removeFeature("feature-one");
        assertEquals(1, removed.get());
        assertEquals(0, registry.installedCount("feature-one"));
        assertEquals(1, registry.installedCount("feature-two"));
    }

    @Test public void hookIdentifierMustBeGloballyUniquePerProcess() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger attempted = new AtomicInteger();
        registry.installOnce("feature-one", new ModernHookRegistry.Registration("shared-id", () -> () -> {}));
        assertThrows(IllegalArgumentException.class, () -> registry.installOnce("feature-two",
                new ModernHookRegistry.Registration("shared-id", () -> {
                    attempted.incrementAndGet();
                    return () -> {};
                })));
        assertEquals(0, attempted.get());
    }
    @Test public void groupInstallsAllOrRollsBackInReverseOrder() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        StringBuilder order = new StringBuilder();
        Throwable thrown = null;
        try {
            registry.installFeature("pilot", Arrays.asList(
                    new ModernHookRegistry.Registration("one", () -> {
                        order.append("1");
                        return () -> order.append("a");
                    }),
                    new ModernHookRegistry.Registration("two", () -> {
                        order.append("2");
                        return () -> order.append("b");
                    }),
                    new ModernHookRegistry.Registration("three", () -> {
                        throw new IllegalStateException("resolver unavailable");
                    })
            ));
        } catch (Throwable error) {
            thrown = error;
        }
        assertNotNull(thrown);
        assertEquals("12ba", order.toString());
        assertEquals(0, registry.installedCount("pilot"));
    }

    @Test public void failedGroupCannotUnhookExistingHooks() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger removed = new AtomicInteger();
        registry.installOnce("privacy", new ModernHookRegistry.Registration("hook", () -> () -> { removed.incrementAndGet(); }));
        try {
            registry.installFeature("media", Arrays.asList(
                    new ModernHookRegistry.Registration("first", () -> () -> { removed.incrementAndGet(); }),
                    new ModernHookRegistry.Registration("second", () -> { throw new Exception("not found"); })
            ));
            fail("Expected to reject the feature");
        } catch (Exception expected) {
            assertEquals("not found", expected.getMessage());
        }
        assertEquals(1, removed.get());
        assertEquals(1, registry.installedCount("privacy"));
        assertEquals(0, registry.installedCount("media"));
    }

    @Test public void duplicateFeatureHooksRejectedBeforeInstallation() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger installs = new AtomicInteger();
        ModernHookRegistry.Registration hook = new ModernHookRegistry.Registration("same",
                () -> { installs.incrementAndGet(); return () -> {}; });
        assertThrows(IllegalArgumentException.class,
                () -> registry.installFeature("pilot", Arrays.asList(hook, hook)));
        assertEquals(0, installs.get());
        registry.installOnce("pilot", hook);
        assertThrows(IllegalArgumentException.class,
                () -> registry.installFeature("pilot", Collections.singletonList(hook)));
        assertEquals(1, installs.get());
    }

    @Test public void rollbackFailureIsSuppressedOnOriginalError() {
        ModernHookRegistry registry = new ModernHookRegistry();
        try {
            registry.installFeature("pilot", Arrays.asList(
                    new ModernHookRegistry.Registration("one", () -> () -> {
                        throw new IllegalStateException("failed unhook");
                    }),
                    new ModernHookRegistry.Registration("two", () -> {
                        throw new IllegalArgumentException("failed hook");
                    })
            ));
            fail("expected failure");
        } catch (Throwable error) {
            assertEquals("failed hook", error.getMessage());
            assertEquals(1, error.getSuppressed().length);
            assertEquals(1, registry.installedCount("pilot"));
        }
    }

    @Test public void removingFeatureUnhooksInReverseOrder() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        StringBuilder order = new StringBuilder();
        registry.installFeature("pilot", Arrays.asList(
                new ModernHookRegistry.Registration("a", () -> () -> order.append("a")),
                new ModernHookRegistry.Registration("b", () -> () -> order.append("b"))
        ));
        registry.removeFeature("pilot");
        assertEquals("ba", order.toString());
        assertEquals(0, registry.installedCount("pilot"));
    }

    @Test public void unsuccessfulUnhookStaysTrackedUntilRetry() throws Throwable {
        ModernHookRegistry registry = new ModernHookRegistry();
        AtomicInteger attempts = new AtomicInteger();
        registry.installOnce("pilot", new ModernHookRegistry.Registration("a", () -> () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary");
        }));
        assertThrows(IllegalStateException.class, () -> registry.removeFeature("pilot"));
        assertEquals(1, registry.installedCount("pilot"));
        registry.removeFeature("pilot");
        assertEquals(0, registry.installedCount("pilot"));
    }

    @Test public void invalidIdsCannotEnterRegistry() {
        ModernHookRegistry registry = new ModernHookRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.installedCount(" "));
        assertThrows(IllegalArgumentException.class,
                () -> new ModernHookRegistry.Registration(" name ", () -> () -> {}));
    }
}
