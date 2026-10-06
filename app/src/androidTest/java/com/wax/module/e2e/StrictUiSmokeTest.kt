package com.wax.module.e2e

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wax.module.R
import com.wax.module.activities.MainActivity
import com.wax.module.ui.targets.TargetSettingsActivity
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The first on-device E2E contract. The strict JaCoCo gate deliberately requires
 * 100% Android-test coverage, so this smoke suite is a starting point, not an
 * exemption: uncovered production code still fails the release.
 */
@RunWith(AndroidJUnit4::class)
class StrictUiSmokeTest {
    @Test
    fun mainNavigationSurfacesAreRendered() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withId(R.id.navigation_home)).check(matches(isDisplayed()))
            onView(withId(R.id.navigation_chat)).check(matches(isDisplayed()))
            onView(withId(R.id.navigation_privacy)).check(matches(isDisplayed()))
            onView(withId(R.id.navigation_media)).check(matches(isDisplayed()))
            onView(withId(R.id.navigation_colors)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun targetSettingsSurfaceReachesResumedState() {
        ActivityScenario.launch(TargetSettingsActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.RESUMED)
        }
    }
}
