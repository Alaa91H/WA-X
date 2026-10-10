package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterFailureReasonTest {
    @Test fun badTokenHasAFixedDiagnosticReasonWithoutExceptionDetails() {
        assertEquals(
            ControlCenterFailureReason.BAD_TOKEN,
            ControlCenterFailureReason.fromClassName("android.view.WindowManager\$BadTokenException"),
        )
    }

    @Test fun otherConstructionFailuresUseAGenericFixedDiagnosticReason() {
        assertEquals(
            ControlCenterFailureReason.WINDOW_CREATE_FAILED,
            ControlCenterFailureReason.fromClassName("java.lang.IllegalStateException"),
        )
    }
}
