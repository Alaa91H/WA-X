package com.wax.module.modern;

import static org.junit.Assert.*;
import java.lang.reflect.Constructor;
import org.junit.Test;

public final class ModernShareLimitPolicyTest {
    static final class Candidate {
        Candidate(int limit, Object source) {}
        Candidate(String text) {}
        Candidate(Integer wrapper) {}
        Candidate() {}
    }

    @Test public void onlyPrimitiveFirstIntegerConstructorsMatch() throws Exception {
        Constructor<?> valid = Candidate.class.getDeclaredConstructor(int.class, Object.class);
        assertTrue(ModernShareLimitPolicy.matchesConstructor(valid));
        assertFalse(ModernShareLimitPolicy.matchesConstructor(
                Candidate.class.getDeclaredConstructor(String.class)));
        assertFalse(ModernShareLimitPolicy.matchesConstructor(
                Candidate.class.getDeclaredConstructor(Integer.class)));
        assertFalse(ModernShareLimitPolicy.matchesConstructor(
                Candidate.class.getDeclaredConstructor()));
        assertFalse(ModernShareLimitPolicy.matchesConstructor(null));
    }

    @Test public void enabledHookChangesOnlyFirstArgumentUsingCopy() {
        Object untouched = new Object();
        Object[] args = {7, untouched};
        Object[] modified = ModernShareLimitPolicy.forwardedArgs(args, true);
        assertNotSame(args, modified);
        assertEquals(Integer.MAX_VALUE, modified[0]);
        assertSame(untouched, modified[1]);
        assertEquals(7, args[0]);
    }

    @Test public void disabledAndUnexpectedArgumentsPassThroughUnchanged() {
        Object[] args = {9, "other"};
        assertSame(args, ModernShareLimitPolicy.forwardedArgs(args, false));
        Object[] text = {"9", "other"};
        assertSame(text, ModernShareLimitPolicy.forwardedArgs(text, true));
        Object[] empty = {};
        assertSame(empty, ModernShareLimitPolicy.forwardedArgs(empty, true));
        assertNull(ModernShareLimitPolicy.forwardedArgs(null, true));
    }
}
