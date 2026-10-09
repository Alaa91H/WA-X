package com.wax.module.modern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class ModernMenuHomePolicyTest {
    @Test public void selectsOnlyMainProcessHomeScreens() {
        assertEquals("com.whatsapp.home.ui.HomeActivity",
                ModernMenuHomePolicy.homeClassName("com.whatsapp"));
        assertEquals("com.whatsapp.w4b.home.ui.HomeActivity",
                ModernMenuHomePolicy.homeClassName("com.whatsapp.w4b"));
        assertNull(ModernMenuHomePolicy.homeClassName("com.whatsapp:push"));
        assertNull(ModernMenuHomePolicy.homeClassName("com.wax.module"));
        assertNull(ModernMenuHomePolicy.homeClassName(null));
    }

    @Test public void excludesUnrelatedOrNestedActivities() {
        assertTrue(ModernMenuHomePolicy.isTargetHome(
                "com.whatsapp", "com.whatsapp.home.ui.HomeActivity"));
        assertFalse(ModernMenuHomePolicy.isTargetHome(
                "com.whatsapp", "com.whatsapp.home.ui.HomeActivity$Inner"));
        assertFalse(ModernMenuHomePolicy.isTargetHome(
                "com.whatsapp", "com.whatsapp.conversation.Conversation"));
        assertFalse(ModernMenuHomePolicy.isTargetHome(
                "com.whatsapp:push", "com.whatsapp.home.ui.HomeActivity"));
        assertFalse(ModernMenuHomePolicy.isTargetHome(
                "com.whatsapp", "com.whatsapp.w4b.home.ui.HomeActivity"));
    }

    @Test public void preservesOriginalMenuCallbackSemantics() {
        assertTrue(ModernMenuHomePolicy.shouldContribute(Boolean.TRUE, 0));
        assertTrue(ModernMenuHomePolicy.shouldContribute(Boolean.FALSE, 5));
        assertFalse(ModernMenuHomePolicy.shouldContribute(Boolean.FALSE, 0));
        assertFalse(ModernMenuHomePolicy.shouldContribute(null, 0));
        assertFalse(ModernMenuHomePolicy.shouldContribute("true", 0));
    }
}
