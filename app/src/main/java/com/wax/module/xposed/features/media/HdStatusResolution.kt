package com.wax.module.xposed.features.media

/*
 * The pure decision logic behind the HD Status hooks.
 *
 * Everything here is free of Android, reflection and Xposed types on purpose. The
 * version-specific knowledge used to be inline string literals spread across
 * [MediaQuality] and the Unobfuscator, which
 * made it impossible to test and impossible to report: when a WhatsApp build
 * stopped matching an anchor, the corresponding override was skipped by a null-safe
 * `?.` and the feature simply did nothing, with no log line and no counter.
 *
 * Keeping the anchors, the aliases and the clamping here means:
 *
 *  * a WhatsApp rename is a one-line change to a list, not an edit inside a hook;
 *  * the choice between candidates can be unit tested against recorded real
 *    layouts, which is the only kind of testing available off-device;
 *  * the caller always learns *why* a target did not resolve.
 */

/**
 * One WhatsApp artifact HD Status needs, and how to find it.
 */
data class HdStatusAnchor(
    /** Resolver name, used in diagnostics so a failure names the missing piece. */
    val name: String,
    /**
     * Candidate anchors in priority order, one per known WhatsApp generation.
     *
     * The first candidate that matches wins. Order matters: an anchor that is
     * specific to the media pipeline must come before a generic one, otherwise a
     * generic match on an unrelated class can win and the override lands on the
     * wrong object.
     */
    val candidates: List<String>,
)

/**
 * Field-name aliases for the quality configuration objects.
 *
 * These classes are Kotlin data classes, so their property names survive
 * obfuscation, but they get renamed whenever WhatsApp refactors the class. Each
 * entry lists the names seen so far, newest first.
 */
object HdStatusFields {
    /** `ProcessVideoQuality`: the per-media quality ceiling WhatsApp applies. */
    val VIDEO_LIMITS =
        HdStatusAnchor(
            name = "ProcessVideoQuality",
            candidates =
                listOf(
                    "ProcessVideoQuality(",
                    "VideoQuality(",
                    "MediaQualityLimits(",
                ),
        )

    /** `MediaDataVideoConfiguration`: whether WhatsApp is allowed to transcode. */
    val VIDEO_CONFIGURATION =
        HdStatusAnchor(
            name = "MediaDataVideoConfiguration",
            candidates =
                listOf(
                    "MediaDataVideoConfiguration(",
                    "VideoTranscodeConfiguration(",
                ),
        )

    /** The transcoder entry point that decides whether to re-encode at all. */
    val VIDEO_TRANSCODER_START =
        HdStatusAnchor(
            name = "VideoTranscoder/transcodeVideoNew",
            candidates =
                listOf(
                    "VideoTranscoder/transcodeVideoNew/",
                    "VideoTranscoder/transcodeVideo/",
                    "VideoTranscoder/startTranscode",
                ),
        )

    /** The media pipeline entry point whose boolean enables HD for stories. */
    val MEDIA_TRANSCODER_START =
        HdStatusAnchor(
            name = "MediaTranscode/Starting",
            candidates =
                listOf(
                    "MediaTranscode/Starting",
                    "MediaTranscode/start",
                ),
        )

    /** `ProcessImageQuality`: the image ceiling, applied on re-encode. */
    val IMAGE_LIMITS =
        HdStatusAnchor(
            name = "ProcessImageQuality",
            candidates =
                listOf(
                    "ProcessImageQuality(",
                    "ImageQuality(",
                ),
        )

    /** The gate that reveals WhatsApp's own quality picker for stories. */
    val QUALITY_SELECTION_GATE =
        HdStatusAnchor(
            name = "media quality selection gate",
            candidates =
                listOf(
                    "enable_media_quality_tool",
                    "show_media_quality_toggle",
                    "media_quality_selection_enabled",
                ),
        )

    /** `BottomBarConfig.supportsHdQuality`, the older location of the same gate. */
    val BOTTOM_BAR_CONFIG =
        HdStatusAnchor(
            name = "BottomBarConfig",
            candidates =
                listOf(
                    "BottomBarConfig(",
                    "StoriesTabConfig(",
                ),
        )

    /** The resolution-correction method used by the real-resolution path. */
    val RESOLUTION_CORRECTION =
        HdStatusAnchor(
            name = "getCorrectedResolution",
            candidates =
                listOf(
                    "getCorrectedResolution",
                    "correctedResolution",
                ),
        )

    /** Field names on `ProcessVideoQuality`, newest first. */
    val VIDEO_LIMIT_MB = listOf("videoLimitMb", "videoLimitMB", "maxVideoSizeMb")
    val VIDEO_MAX_EDGE = listOf("videoMaxEdge", "maxVideoEdge", "videoEdge")
    val VIDEO_MAX_BITRATE = listOf("videoMaxBitrate", "maxVideoBitrate")
    val VIDEO_HIGH_BITRATE_URL = listOf("mainHighBitRate", "mainHighBitrate")
    val VIDEO_BITRATE_MODE = listOf("videoBitrateMode", "bitrateMode")

    /** Field names on `MediaDataVideoConfiguration`, newest first. */
    val FORCE_SINGLE_TRANSCODING =
        listOf(
            "forceSingleTranscoding",
            "forceSingleTranscode",
        )

    /** Field names on `ProcessImageQuality`, newest first. */
    val IMAGE_MAX_KB = listOf("maxKb", "maxKB", "imageMaxKb")
    val IMAGE_QUALITY = listOf("quality", "imageQuality")
    val IMAGE_MAX_EDGE = listOf("maxEdge", "maxPixel", "imageMaxEdge")

    /** Field names on `BottomBarConfig`, newest first. */
    val SUPPORTS_HD_QUALITY = listOf("supportsHdQuality", "supportsHDQuality")

    /** Field names on the transcode-parameter object returned by the resolution fix. */
    val TARGET_WIDTH = listOf("targetWidth", "outputWidth")
    val TARGET_HEIGHT = listOf("targetHeight", "outputHeight")
    val FRAME_RATE = listOf("frameRate", "outputFrameRate")

    /** Field names on the source-resolution object passed into the transcode call. */
    val SOURCE_WIDTH = listOf("widthPx", "width")
    val SOURCE_HEIGHT = listOf("heightPx", "height")
    val ROTATION_ANGLE = listOf("rotationAngle", "rotation")
}

/**
 * The quality values HD Status forces.
 *
 * These were 1920 / 10 Mbps after `2d0dfcb9` replaced the original 3840 / 24 Mbps
 * with the `EDGE_WIDTH` / `BITRATE` constants, which silently halved what the
 * feature actually produced. The 4K-capable values are restored here and the
 * intermediate values are gone, so there is one number per knob again.
 */
object HdStatusLimits {
    /** Longest edge WhatsApp is allowed to keep. 4K-class, matching the original. */
    const val MAX_VIDEO_EDGE = 3840

    /** Ceiling in bits per second. 24 Mbps, matching the original. */
    const val MAX_VIDEO_BITRATE_BPS = 24_000_000

    /** Default upload ceiling in MB, and the floor a preference may not go below. */
    const val DEFAULT_VIDEO_LIMIT_MB = 60
    const val MIN_VIDEO_LIMIT_MB = 30
    const val MAX_VIDEO_LIMIT_MB = 90

    /** Image ceiling in KB, JPEG quality percentage and longest edge in pixels. */
    const val MAX_IMAGE_KB = 50 * 1024
    const val MAX_IMAGE_QUALITY = 100
    const val MAX_IMAGE_EDGE = 6000

    /** Frame rate forced by the 60fps option. */
    const val HIGH_FRAME_RATE = 60

    /**
     * Resolves the effective upload ceiling from the stored preference.
     *
     * The regression this replaces was `max(preference, 90)`, which used the seek
     * bar's *maximum* as the floor. Because the seek bar tops out at 90 MB, the
     * result was always 90 and the "increase video size limit" slider could not
     * change anything. The floor is now the seek bar's minimum, so the preference
     * is honoured across its whole range and clamped if the stored value is
     * corrupt or out of range.
     */
    fun videoLimitMb(preferenceMb: Int): Int = preferenceMb.coerceIn(MIN_VIDEO_LIMIT_MB, MAX_VIDEO_LIMIT_MB)
}

/** The outcome of trying to resolve one HD Status target. */
sealed interface HdStatusResolution<out T> {
    /** Resolved, optionally noting which candidate matched. */
    data class Resolved<T>(
        val value: T,
        val matchedCandidate: String,
    ) : HdStatusResolution<T>

    /** Not resolved, with every candidate that was tried, for the diagnostic line. */
    data class Missing(
        val anchor: HdStatusAnchor,
        val reason: String,
    ) : HdStatusResolution<Nothing>

    val isResolved: Boolean get() = this is Resolved

    /** The resolved value, or null. */
    fun valueOrNull(): T? = (this as? Resolved)?.value
}

/**
 * Walks [HdStatusAnchor.candidates] in order and returns the first hit.
 *
 * [probe] is what actually performs one lookup; keeping it a parameter is what
 * lets the ordering be tested without a WhatsApp dex. A failing probe is reported
 * as a miss rather than thrown, because one stale anchor must not hide a later
 * candidate that still works.
 */
fun <T : Any> HdStatusAnchor.resolve(probe: (String) -> T?): HdStatusResolution<T> {
    val failures = ArrayList<String>(candidates.size)
    for (candidate in candidates) {
        val hit =
            try {
                probe(candidate)
            } catch (t: Throwable) {
                failures += "$candidate -> ${t.javaClass.simpleName}: ${t.message}"
                null
            }
        if (hit != null) return HdStatusResolution.Resolved(hit, candidate)
        failures += "$candidate -> no match"
    }
    return HdStatusResolution.Missing(this, failures.joinToString("; "))
}

/** Picks the first field name in [aliases] that [present] reports as existing. */
fun resolveFieldName(
    aliases: List<String>,
    present: (String) -> Boolean,
): String? = aliases.firstOrNull { present(it) }
