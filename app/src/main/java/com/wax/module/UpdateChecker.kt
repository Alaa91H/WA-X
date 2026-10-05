package com.wax.module

import android.app.Activity
import com.wax.module.compat.UpdateOffer
import com.wax.module.xposed.core.ModuleRuntime
import com.wax.module.xposed.core.components.AlertDialogWpp
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import io.noties.markwon.Markwon
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class UpdateChecker(private val mActivity: Activity) : Runnable {

    companion object {
        private const val LATEST_RELEASE_API = "https://api.github.com/repos/Alaa91H/WA X/releases/latest"
        private const val TELEGRAM_UPDATE_URL = "https://t.me/Alaa91h"

        private val DEFAULT_CHANGELOG = UpdateOffer.DEFAULT_CHANGELOG

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
        }
    }

    override fun run() {
        try {
            val request = okhttp3.Request.Builder()
                .url(LATEST_RELEASE_API)
                .build()

            val releaseVersion: String
            val changelog: String
            val publishedAt: String

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return

                val content = response.body.string()
                val release = JSONObject(content)
                val tagName = release.optString("tag_name", "")

                if (tagName.isBlank()) return

                releaseVersion = UpdateOffer.normaliseTag(tagName)
                changelog = release.optString("body", DEFAULT_CHANGELOG).trim()
                publishedAt = release.optString("published_at", "")
            }

            if (releaseVersion.isBlank()) return

            val currentVersion = UpdateOffer.normaliseModuleVersion(BuildConfig.VERSION_NAME)
            if (UpdateOffer.shouldOffer(
                    releaseVersion = releaseVersion,
                    currentVersion = currentVersion,
                    ignoredVersion = ModuleRuntime.getPrivString("ignored_version", "")
                )
            ) {
                mActivity.runOnUiThread {
                    showUpdateDialog(releaseVersion, changelog, publishedAt)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
    }

    private fun showUpdateDialog(version: String, changelog: String, publishedAt: String) {
        try {
            val markwon = Markwon.create(mActivity)
            val dialog = AlertDialogWpp(mActivity)

            val formattedDate = formatPublishedDate(publishedAt)

            val message = buildString {
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
                Utils.openLink(mActivity, TELEGRAM_UPDATE_URL)
                dialog.dismiss()
            }
            dialog.show()
        } catch (e: Exception) {
            e.printStackTrace()
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
            } else ""
        } catch (e: Exception) {
            XposedBridge.log(e)
            ""
        }
    }
}
