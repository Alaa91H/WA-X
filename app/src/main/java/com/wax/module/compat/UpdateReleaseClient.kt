package com.wax.module.compat

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class LatestRelease(
    val tagName: String,
    val version: String,
    val changelog: String,
    val publishedAt: String,
    val htmlUrl: String,
)

/**
 * Single source of truth for WA X release metadata.
 *
 * Both update entry points use this client so repository coordinates, GitHub API
 * headers, response validation, and parsing cannot drift apart.
 */
object UpdateReleaseClient {
    const val REPOSITORY: String = "Alaa91H/WA-X"
    const val LATEST_RELEASE_API: String = "https://api.github.com/repos/Alaa91H/WA-X/releases/latest"
    const val LATEST_RELEASE_PAGE: String = "https://github.com/Alaa91H/WA-X/releases/latest"

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Throws(IOException::class)
    fun fetchLatestRelease(): LatestRelease {
        val request =
            Request
                .Builder()
                .url(LATEST_RELEASE_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "WA-X-Android")
                .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub update check failed with HTTP ${response.code}")
            }

            val release = JSONObject(response.body.string())
            val tagName = release.optString("tag_name", "").trim()
            val version = UpdateOffer.normaliseTag(tagName)
            if (!UpdateOffer.isValidVersion(version)) {
                throw IOException("GitHub latest release returned an invalid version tag")
            }

            val changelog =
                release
                    .optString("body", UpdateOffer.DEFAULT_CHANGELOG)
                    .trim()
                    .ifBlank { UpdateOffer.DEFAULT_CHANGELOG }
            val publishedAt = release.optString("published_at", "").trim()
            val apiHtmlUrl = release.optString("html_url", "").trim()
            val htmlUrl =
                apiHtmlUrl.takeIf {
                    it.startsWith("https://github.com/$REPOSITORY/releases/")
                } ?: LATEST_RELEASE_PAGE

            return LatestRelease(
                tagName = tagName,
                version = version,
                changelog = changelog,
                publishedAt = publishedAt,
                htmlUrl = htmlUrl,
            )
        }
    }
}
