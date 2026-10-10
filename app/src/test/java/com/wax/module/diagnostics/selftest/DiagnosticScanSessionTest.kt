package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticScanSessionTest {
    @Test fun rejectsOverlappingScansAndAcceptsOnlyCurrentProgress() {
        val session = DiagnosticScanSession()
        val first = requireNotNull(session.begin())
        assertNull(session.begin())
        assertTrue(session.acceptsProgress(first))
        assertEquals(DiagnosticScanSession.Completion.FINISHED, session.finish(first))
        assertFalse(session.acceptsProgress(first))
        val second = requireNotNull(session.begin())
        assertTrue(second > first)
        assertEquals(DiagnosticScanSession.Completion.STALE, session.finish(first))
        assertTrue(session.acceptsProgress(second))
    }

    @Test fun cancelledRunCannotPostProgressAndMustFinishBeforeRetry() {
        val session = DiagnosticScanSession()
        val token = requireNotNull(session.begin())
        assertTrue(session.cancel())
        assertFalse(session.acceptsProgress(token))
        assertNull(session.begin())
        assertEquals(DiagnosticScanSession.Completion.CANCELLED, session.finish(token))
        assertNotNull(session.begin())
    }

    @Test fun destructionRejectsOldCallbacksAndReports() {
        val session = DiagnosticScanSession()
        val token = requireNotNull(session.begin())
        session.invalidate()
        assertEquals(DiagnosticScanSession.Completion.STALE, session.finish(token))
        assertFalse(session.acceptsProgress(token))
        assertFalse(session.cancel())
        assertNotNull(session.begin())
    }

    @Test fun cannotCancelNothing() = assertFalse(DiagnosticScanSession().cancel())
}
