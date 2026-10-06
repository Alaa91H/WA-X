package com.wax.module

import android.app.Activity
import com.wax.module.compat.UpdateOffer
import com.wax.module.compat.UpdateReleaseClient
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.components.AlertDialogWpp
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import io.noties.markwon.Markwon
import java.text.SimpleDateFormat
import java.util.Locale

class UpdateChecker(
    private val mActivity: Activity,
) : Runnable {
    override fun run() {
        try {
            val release = UpdateReleaseClient.fetchLatestRelease()
            val currentVersion = UpdateOffer.normaliseModuleVersion(BuildConfig.VERSION_NAME)

            if (
                UpdateOffer.shouldOffer(
                    releaseVersion = release.version,
                    currentVersion = currentVersion,
                    ignoredVersion = ModuleRuntime.getPrivString("ignored_version", ""),
                )
            ) {
                mActivity.runOnUiThread {
                    showUpdateDialog(
                        version = release.version,
                        changelog = release.changelog,
                        publishedAt = release.publishedAt,
                        releaseUrl = release.htmlUrl,
                    )
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    private fun showUpdateDialog(
        version: String,
        changelog: String,
        publishedAt: String,
        releaseUrl: String,
    ) {
        try {
            val markwon = Markwon.create(mActivity)
            val dialog = AlertDialogWpp(mActivity)

            val formattedDate = formatPublishedDate(publishedAt)

            val message =
                buildString {
                    append("📦 **Version:** `").append(version).append("`\n")
                    if (formattedDate.isNotEmpty()) {
                        append("📅 **Released:** ").append(formattedDate).append("\n")
                    }
                    append("\n### What's New\n\n").append(changelog)
                }

            dialog.setTitle("🎉 New Update Available!")
            dialog.setMessage(markwon.toMarkdown(message))
            dialog.setNegativeButton("Ignore") { dialog, _ ->
                ModuleRuntime.setPrivString("ignored_version", version)
                dialog.dismiss()
            }
            dialog.setPositiveButton("Update Now") { dialog, _ ->
                Utils.openLink(mActivity, releaseUrl)
                dialog.dismiss()
            }
            dialog.show()
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    private fun formatPublishedDate(isoDate: String?): String {
        if (isoDate.isNullOrEmpty()) return ""

        return try {
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            val date = isoFormat.parse(isoDate)
            if (date != null) {
                val displayFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
                displayFormat.format(date)
            } else {
                ""
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
            ""
        }
    }
}
