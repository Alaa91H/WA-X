package com.wax.module.activities

import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.widget.LinearLayout
import androidx.core.net.toUri
import com.google.android.material.button.MaterialButton
import com.wax.module.BuildConfig
import com.wax.module.R
import com.wax.module.activities.base.BaseActivity
import com.wax.module.databinding.ActivityAboutBinding

/**
 * About, maintainer and legal information for WA X.
 *
 * Two identities are kept strictly apart here, because collapsing them would be
 * both inaccurate and unfair:
 *
 *  * the **active maintainer** is Alaa, who owns this project going forward;
 *  * **upstream and earlier contributors** are credited for the work they actually
 *    wrote, in a separate section.
 *
 * Presenting an earlier author as a current maintainer is wrong, and presenting the
 * current maintainer as the author of inherited code would be equally wrong.
 */
class AboutActivity : BaseActivity() {
    /**
     * People credited for their own contributions to this codebase and its
     * predecessors. These are historical credits, not current maintainers.
     */
    private val upstreamCredits =
        listOf(
            "Dev4Mod / WaEnhancer (upstream)" to UPSTREAM_REPOSITORY,
            "frknkrc44" to "https://github.com/frknkrc44",
            "mubashardev" to "https://github.com/mubashardev",
            "masbentoooredoo" to "https://github.com/masbentoooredoo",
            "zhongerxll" to "https://github.com/zhongerxll",
            "BryanGIG" to "https://github.com/BryanGIG",
            "rizqi-developer" to "https://github.com/rizqi-developer",
            "pedroborraz" to "https://github.com/pedroborraz",
            "ahmedtohamy1" to "https://github.com/ahmedtohamy1",
            "mohdafix" to "https://github.com/mohdafix",
            "maulana-kurniawan" to "https://github.com/maulana-kurniawan",
            "erzachn" to "https://github.com/erzachn",
            "cvnertnc" to "https://github.com/cvnertnc",
            "rkorossy" to "https://github.com/rkorossy",
            "StupidRepo" to "https://github.com/StupidRepo",
            "Blank517" to "https://github.com/Blank517",
            "astola-studio" to "https://github.com/astola-studio",
            "Strange-IPmart" to "https://github.com/Strange-IPmart",
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvTagline.text = getString(R.string.wax_tagline)
        binding.tvVersion.text =
            getString(R.string.wax_version_format, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        binding.btnGithub.setOnClickListener { openUrl(GITHUB_REPOSITORY) }
        binding.btnTelegram.setOnClickListener { openUrl(PERSONAL_TELEGRAM) }
        binding.btnCommunity.setOnClickListener { openUrl(COMMUNITY) }
        binding.btnProfile.setOnClickListener { openUrl(MAINTAINER_PROFILE) }
        binding.btnEmail.setOnClickListener { openUrl("mailto:$EMAIL") }
        binding.btnSupport.setOnClickListener { openUrl(SUPPORT) }

        binding.tvLicenseDetails.text = getString(R.string.wax_license_terms)
        binding.tvDisclaimer.text = getString(R.string.wax_disclaimer)
        binding.tvNoticesDescription.text = getString(R.string.wax_open_source_notices_desc)

        // Credits are informational: they open a browser and must never be a
        // prerequisite for reading the licence or the disclaimer.
        val margin = resources.getDimensionPixelSize(R.dimen.spacing_small)
        upstreamCredits.forEachIndexed { index, (name, url) ->
            val params =
                LinearLayout
                    .LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index > 0) topMargin = margin
                    }
            val button =
                MaterialButton(ContextThemeWrapper(this, R.style.ModernButton_Outlined)).apply {
                    text = name
                    setIconResource(R.drawable.ic_github)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = margin
                    layoutParams = params
                    setOnClickListener { openUrl(url) }
                }
            binding.noticesContainer.addView(button)
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    private companion object {
        const val GITHUB_REPOSITORY = "https://github.com/Alaa91H/WA-X"
        const val MAINTAINER_PROFILE = "https://github.com/Alaa91H/Alaa91H"
        const val PERSONAL_TELEGRAM = "https://t.me/Alaa91h"
        const val COMMUNITY = "https://t.me/WAXposed"
        const val UPSTREAM_REPOSITORY = "https://github.com/Dev4Mod/WaEnhancer"
        const val EMAIL = "alahus2591@gmail.com"
        const val SUPPORT = "https://ko-fi.com/alaa91h"
    }
}
