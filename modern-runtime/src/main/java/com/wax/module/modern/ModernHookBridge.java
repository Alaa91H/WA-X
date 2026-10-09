package com.wax.module.modern;

import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Executable;
import java.util.Objects;

/**
 * The ONLY direct hook adapter used by the modern runtime.
 * Feature implementations should not depend on the legacy XposedBridge API.
 */
public final class ModernHookBridge {
    private final XposedInterface framework;

    public ModernHookBridge(XposedInterface framework) {
        this.framework = Objects.requireNonNull(framework, "framework");
    }

    public XposedInterface.HookHandle intercept(
            Executable method, String identifier, XposedInterface.Hooker hooker) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(hooker, "hooker");
        return framework.hook(method)
                .setId(Objects.requireNonNull(identifier, "identifier"))
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }
}
