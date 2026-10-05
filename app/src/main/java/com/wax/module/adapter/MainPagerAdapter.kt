package com.wax.module.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.wax.module.ui.fragments.CustomizationFragment
import com.wax.module.ui.fragments.GeneralFragment
import com.wax.module.ui.fragments.HomeFragment
import com.wax.module.ui.fragments.MediaFragment
import com.wax.module.ui.fragments.PrivacyFragment
import com.wax.module.ui.fragments.RecordingsFragment

class MainPagerAdapter(
    fragmentActivity: FragmentActivity,
) : FragmentStateAdapter(fragmentActivity) {
    private val isRecordingEnabled =
        PreferenceManager
            .getDefaultSharedPreferences(fragmentActivity)
            .getBoolean("call_recording_enable", false)

    override fun createFragment(position: Int): Fragment =
        when (position) {
            1 -> GeneralFragment()
            2 -> PrivacyFragment()
            3 -> MediaFragment()
            4 -> CustomizationFragment()
            5 -> RecordingsFragment()
            else -> HomeFragment()
        }

    override fun getItemCount(): Int = if (isRecordingEnabled) 6 else 5
}
