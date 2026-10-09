package com.wax.module.modern;

import java.lang.reflect.Constructor;
import java.util.Arrays;

/** Pure constructor-signature and argument policy for the migrated ShareLimit feature. */
public final class ModernShareLimitPolicy {
    private ModernShareLimitPolicy() {}

    public static boolean matchesConstructor(Constructor<?> constructor) {
        return constructor != null && constructor.getParameterCount() > 0
                && constructor.getParameterTypes()[0] == int.class;
    }

    public static Object[] forwardedArgs(Object[] original, boolean enabled) {
        if (!enabled || original == null || original.length == 0
                || !(original[0] instanceof Integer)) {
            return original;
        }
        Object[] modified = Arrays.copyOf(original, original.length);
        modified[0] = Integer.MAX_VALUE;
        return modified;
    }
}
