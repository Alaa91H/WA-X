package com.wax.module.modern;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Reject obsolete resolvers that would crash WhatsApp if replaced with null. */
public final class ModernVoidReplacementPolicy {
    private ModernVoidReplacementPolicy() {}

    public static boolean isSafe(Method method) {
        return method != null
                && method.getReturnType() == Void.TYPE
                && !Modifier.isAbstract(method.getModifiers())
                && !Modifier.isNative(method.getModifiers());
    }
}
