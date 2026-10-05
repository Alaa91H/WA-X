package com.wax.module.media

/** The presets T118 defines, plus the explicit custom slot. */
enum class MediaQualityPreset {
    /** No transcoding: the original file is sent. */
    ORIGINAL,

    /** Visually near-lossless, larger files. */
    HIGH,

    /** The default trade-off. */
    BALANCED,

    /** Smallest files that stay recognisable. */
    DATA_SAVER,

    /** User-chosen values; validated like any other input. */
    CUSTOM,
}

/** Image encoding controls (T119). */
data class ImageQuality(
    /** Longest edge in pixels, or null to keep the original resolution. */
    val maxDimension: Int?,
    /** JPEG quality, 0..100. */
    val jpegQuality: Int,
    /** Whether EXIF and similar metadata are preserved. */
    val keepMetadata: Boolean,
    /** Target format; only formats that are safe to encode without loss of support. */
    val format: String,
) {
    /** Why these values cannot be used, or an empty list. */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (jpegQuality !in 0..100) problems.add("JPEG quality must be between 0 and 100.")
        if (maxDimension != null && maxDimension <= 0) problems.add("The maximum dimension must be positive.")
        if (format.lowercase() !in SAFE_FORMATS) {
            problems.add("Unsupported image format \"$format\"; use one of ${SAFE_FORMATS.joinToString(", ")}.")
        }
        return problems
    }

    companion object {
        /** Formats the pipeline is known to encode correctly. */
        val SAFE_FORMATS: Set<String> = setOf("jpeg", "png", "webp")
    }
}

/** Video encoding controls (T120). */
data class VideoQuality(
    /** Longest edge in pixels, or null to keep the original resolution. */
    val maxHeight: Int?,
    /** Target bitrate in kbit/s, or null to keep the original. */
    val bitrateKbps: Int?,
    /** Target frame rate, or null to keep the original. */
    val frameRate: Int?,
    /** Target codec name, or null to keep the original. */
    val codec: String?,
) {
    /** Why these values cannot be used, or an empty list. */
    fun validate(): List<String> {
        val problems = ArrayList<String>()
        if (maxHeight != null && maxHeight <= 0) problems.add("The maximum height must be positive.")
        if (bitrateKbps != null && bitrateKbps < MIN_BITRATE_KBPS) {
            problems.add("The bitrate must be at least $MIN_BITRATE_KBPS kbit/s.")
        }
        if (frameRate != null && frameRate !in MIN_FRAME_RATE..MAX_FRAME_RATE) {
            problems.add("The frame rate must be between $MIN_FRAME_RATE and $MAX_FRAME_RATE.")
        }
        if (codec != null && codec.lowercase() !in SAFE_CODECS) {
            problems.add("Unsupported codec \"$codec\"; use one of ${SAFE_CODECS.joinToString(", ")}.")
        }
        return problems
    }

    companion object {
        /** Codecs the encoder is known to write correctly. */
        val SAFE_CODECS: Set<String> = setOf("h264", "hevc", "av1")

        /** Below this, video is unusable rather than merely small. */
        const val MIN_BITRATE_KBPS: Int = 100

        const val MIN_FRAME_RATE: Int = 15
        const val MAX_FRAME_RATE: Int = 60
    }
}

/**
 * The preset table for image and video quality.
 *
 * Presets are data, compiled in and validated by the same [ImageQuality.validate] /
 * [VideoQuality.validate] the custom path uses. That means a preset can never be "special"
 * and bypass a range check, and adding a preset is a table entry rather than new logic.
 * `CUSTOM` has no table entry on purpose: a caller that wants custom must supply values,
 * which is the difference between "custom" and "whatever the defaults happened to be".
 */
object MediaQualityPresets {
    /** The image settings for [preset], or null for [MediaQualityPreset.CUSTOM]. */
    fun image(preset: MediaQualityPreset): ImageQuality? =
        when (preset) {
            MediaQualityPreset.ORIGINAL -> ImageQuality(null, 100, keepMetadata = true, format = "jpeg")
            MediaQualityPreset.HIGH -> ImageQuality(2560, 90, keepMetadata = false, format = "jpeg")
            MediaQualityPreset.BALANCED -> ImageQuality(1600, 80, keepMetadata = false, format = "jpeg")
            MediaQualityPreset.DATA_SAVER -> ImageQuality(1024, 65, keepMetadata = false, format = "jpeg")
            MediaQualityPreset.CUSTOM -> null
        }

    /** The video settings for [preset], or null for [MediaQualityPreset.CUSTOM]. */
    fun video(preset: MediaQualityPreset): VideoQuality? =
        when (preset) {
            MediaQualityPreset.ORIGINAL -> VideoQuality(null, null, null, null)
            MediaQualityPreset.HIGH -> VideoQuality(1080, 4000, 30, "h264")
            MediaQualityPreset.BALANCED -> VideoQuality(720, 2500, 30, "h264")
            MediaQualityPreset.DATA_SAVER -> VideoQuality(480, 1000, 24, "h264")
            MediaQualityPreset.CUSTOM -> null
        }

    /** Validates an image choice, preset or custom. */
    fun validateImage(quality: ImageQuality): List<String> = quality.validate()

    /** Validates a video choice, preset or custom. */
    fun validateVideo(quality: VideoQuality): List<String> = quality.validate()
}
