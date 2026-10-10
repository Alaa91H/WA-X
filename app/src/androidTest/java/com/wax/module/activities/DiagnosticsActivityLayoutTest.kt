package com.wax.module.activities

import android.graphics.Rect
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wax.module.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression: a deep scan must never push the ZIP actions off-screen. */
@RunWith(AndroidJUnit4::class)
class DiagnosticsActivityLayoutTest {
    @Test
    fun longDeepScanReportKeepsZipActionsVisible() {
        ActivityScenario.launch(DiagnosticsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val scroll = activity.findViewById<ScrollView>(R.id.diagnostics_report_scroll)
                val content = scroll.getChildAt(0) as LinearLayout
                val results = content.getChildAt(content.childCount - 1) as LinearLayout
                val reportText = results.getChildAt(0) as TextView
                reportText.text = (1..400).joinToString("\n") { "hook.feature.$it: NOT_TESTED" }
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val scroll = activity.findViewById<ScrollView>(R.id.diagnostics_report_scroll)
                val actions = activity.findViewById<LinearLayout>(R.id.diagnostics_actions)
                val zipRow = actions.getChildAt(2) as LinearLayout
                val export = zipRow.getChildAt(0) as Button
                val import = zipRow.getChildAt(1) as Button

                assertTrue("Report is scrollable", scroll.canScrollVertically(1))
                assertEquals(activity.getString(R.string.diagnostics_export), export.text.toString())
                assertEquals(activity.getString(R.string.diagnostics_import), import.text.toString())
                assertTrue("Export is still on screen", export.getGlobalVisibleRect(Rect()))
                assertTrue("Import is still on screen", import.getGlobalVisibleRect(Rect()))
            }
        }
    }
}
