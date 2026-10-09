package com.wax.module.modern;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ModernMinorFixesFeatureTest {
    @Test public void matchesOnlyTheExactDocumentPickerClass() {
        assertTrue(ModernMinorFixesFeature.matchesDocumentPicker(
                "com.whatsapp.documentpicker.DocumentPickerActivity"));
        assertFalse(ModernMinorFixesFeature.matchesDocumentPicker(
                "com.whatsapp.documentpicker.DocumentPickerActivity$Internal"));
        assertFalse(ModernMinorFixesFeature.matchesDocumentPicker(
                "com.whatsapp.w4b.documentpicker.DocumentPickerActivity"));
        assertFalse(ModernMinorFixesFeature.matchesDocumentPicker(null));
    }

    @Test public void recognizesAlreadyInitializedProviderThroughCauseChain() {
        Throwable nested = new RuntimeException("wrapper",
                new IllegalStateException("MlKitContext is already initialized"));
        assertTrue(ModernMinorFixesFeature.isAlreadyInitialized(nested));
    }

    @Test public void leavesOtherFailuresUnrecognized() {
        assertFalse(ModernMinorFixesFeature.isAlreadyInitialized(null));
        assertFalse(ModernMinorFixesFeature.isAlreadyInitialized(
                new IllegalStateException("some other failure")));
        assertFalse(ModernMinorFixesFeature.isAlreadyInitialized(
                new IllegalArgumentException("MlKitContext is already initialized")));
    }

    @Test public void optInFlagIsNeverImplicitlyEnabled() {
        assertTrue(ModernMinorFixesFeature.ENABLE_KEY.startsWith("modern.feature."));
        assertFalse(ModernMinorFixesFeature.ENABLE_KEY.isBlank());
    }
}
