package com.wax.module.modern;

import static org.junit.Assert.*;
import java.lang.reflect.Method;
import org.junit.Test;

public final class ModernVoidReplacementPolicyTest {
    static final class Sample {
        void safeInstance() {}
        static void safeStatic() {}
        int nonVoid() { return 1; }
        native void nativeCall();
    }

    interface AbstractCall {
        void abstractCall();
    }

    @Test public void onlyConcreteVoidMethodsCanBeSkippedWithoutReturnMismatch() throws Exception {
        assertTrue(ModernVoidReplacementPolicy.isSafe(Sample.class.getDeclaredMethod("safeInstance")));
        assertTrue(ModernVoidReplacementPolicy.isSafe(Sample.class.getDeclaredMethod("safeStatic")));
        assertFalse(ModernVoidReplacementPolicy.isSafe(Sample.class.getDeclaredMethod("nonVoid")));
        assertFalse(ModernVoidReplacementPolicy.isSafe(Sample.class.getDeclaredMethod("nativeCall")));
        assertFalse(ModernVoidReplacementPolicy.isSafe(AbstractCall.class.getDeclaredMethod("abstractCall")));
        assertFalse(ModernVoidReplacementPolicy.isSafe(null));
    }
}
