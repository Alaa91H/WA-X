package com.wax.module.xposed.features.media

import com.wax.module.xposed.core.devkit.Unobfuscator
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The single place where the WhatsApp classes and methods behind HD Status are
 * resolved.
 *
 * Every WhatsApp-version-specific fact the feature depends on lives here, and each
 * lookup returns an [HdStatusResolution] rather than throwing, so a caller can keep
 * working with whatever did resolve and report what did not. That is the difference
 * between "HD Status silently does nothing" and "HD Status logged exactly which of
 * the seven targets this WhatsApp build is missing".
 *
 * Nothing here hooks anything. Hooking lives in [HdStatusImageHook] and
 * [HdStatusVideoHook]; this type only resolves, so a failure can never leave a
 * half-installed hook behind.
 */
class HdStatusTargets(
    private val classLoader: ClassLoader,
    private val log: (String) -> Unit,
) {
    /** Resolved targets, filled in by [resolveAll]. */
    private val resolved = HashMap<String, Any?>()

    /**
     * Resolves every target HD Status can use and reports the outcome.
     *
     * Resolution failures are collected, not thrown. One missing target costs the
     * feature the corresponding override and nothing else, which is what keeps a
     * WhatsApp update from disabling the whole feature.
     */
    fun resolveAll() {
        log("resolving HD Status targets")

        step("videoLimits") { Unobfuscator.loadProcessVideoQualityClass(classLoader) }
            ?.let { record(HdStatusFields.VIDEO_LIMITS.name, it) }

        step("videoConfiguration") { Unobfuscator.loadMediaDataVideoConfigurationClass(classLoader) }
            ?.let { record(HdStatusFields.VIDEO_CONFIGURATION.name, it) }

        step("videoTranscoderStart") { Unobfuscator.loadVideoTranscoderStartMethod(classLoader) }
            ?.let { record(HdStatusFields.VIDEO_TRANSCODER_START.name, it) }

        step("mediaTranscoderStart") { resolveMediaTranscoderStart() }
            ?.let { record(HdStatusFields.MEDIA_TRANSCODER_START.name, it) }

        step("imageLimits") { Unobfuscator.loadProcessImageQualityClass(classLoader) }
            ?.let { record(HdStatusFields.IMAGE_LIMITS.name, it) }

        step("qualitySelectionGate") { Unobfuscator.loadMediaQualitySelectionMethod(classLoader) }
            ?.let { record(HdStatusFields.QUALITY_SELECTION_GATE.name, it) }

        step("bottomBarConfig") { Unobfuscator.loadBottomBarConfigClass(classLoader) }
            ?.let { record(HdStatusFields.BOTTOM_BAR_CONFIG.name, it) }

        step("resolutionCorrection") { Unobfuscator.loadMediaQualityVideoMethod2(classLoader) }
            ?.let { record(HdStatusFields.RESOLUTION_CORRECTION.name, it) }

        log(
            "targets: " +
                resolved.entries.joinToString(", ") { (name, value) ->
                    "$name=" + (value?.let { it.javaClass.simpleName } ?: "missing")
                },
        )
    }

    /** The `ProcessVideoQuality` class, or null when this build does not have it. */
    fun videoLimitsClass(): Class<*>? = resolved[HdStatusFields.VIDEO_LIMITS.name] as? Class<*>

    /** The `MediaDataVideoConfiguration` class, or null. */
    fun videoConfigurationClass(): Class<*>? = resolved[HdStatusFields.VIDEO_CONFIGURATION.name] as? Class<*>

    /** The transcoder entry point that decides whether a video is re-encoded. */
    fun videoTranscoderStartMethod(): Method? = resolved[HdStatusFields.VIDEO_TRANSCODER_START.name] as? Method

    /** The media pipeline entry point whose boolean enables HD for stories. */
    fun mediaTranscoderStartMethod(): Method? = resolved[HdStatusFields.MEDIA_TRANSCODER_START.name] as? Method

    /** The `ProcessImageQuality` class, or null. */
    fun imageLimitsClass(): Class<*>? = resolved[HdStatusFields.IMAGE_LIMITS.name] as? Class<*>

    /** The method that gates WhatsApp's own quality picker. */
    fun qualitySelectionGateMethod(): Method? = resolved[HdStatusFields.QUALITY_SELECTION_GATE.name] as? Method

    /** The `BottomBarConfig` class, or null. */
    fun bottomBarConfigClass(): Class<*>? = resolved[HdStatusFields.BOTTOM_BAR_CONFIG.name] as? Class<*>

    /** The method that reports the corrected output resolution of a transcode. */
    fun resolutionCorrectionMethod(): Method? = resolved[HdStatusFields.RESOLUTION_CORRECTION.name] as? Method

    /** True when the video half has at least the targets it cannot work without. */
    fun canApplyVideo(): Boolean = videoLimitsClass() != null

    /** True when the image half has at least the target it cannot work without. */
    fun canApplyImage(): Boolean = imageLimitsClass() != null

    /**
     * Resolves [HdStatusFields.MEDIA_TRANSCODER_START].
     *
     * `Unobfuscator.loadMediaTranscoderStart` used to end in `.first()` on a dexkit
     * result, so a WhatsApp build without the anchor threw `NoSuchElementException`.
     * Because that call sat in the middle of the video block, the exception aborted
     * every override after it: one renamed anchor silently disabled the rest of the
     * video path. The anchors are now tried in order and a miss is a null, reported
     * here rather than thrown at the caller.
     */
    private fun resolveMediaTranscoderStart(): Method? {
        val resolved =
            HdStatusFields.MEDIA_TRANSCODER_START.resolve { candidate ->
                Unobfuscator.findFirstMethodUsingStrings(classLoader, StringMatchType.Contains, candidate)
            }
        report(HdStatusFields.MEDIA_TRANSCODER_START, resolved)
        return resolved.valueOrNull()
    }

    /** Runs one resolution step, turning a resolver failure into a logged null. */
    private inline fun <T> step(
        name: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (t: Throwable) {
            log("$name: unresolved (${t.javaClass.simpleName}: ${t.message})")
            null
        }

    private fun record(
        name: String,
        value: Any,
    ) {
        resolved[name] = value
        log("$name: ${describe(value)}")
    }

    private fun report(
        anchor: HdStatusAnchor,
        resolution: HdStatusResolution<*>,
    ) {
        when (resolution) {
            is HdStatusResolution.Resolved -> log("${anchor.name}: matched '${resolution.matchedCandidate}'")
            is HdStatusResolution.Missing -> log("${anchor.name}: unresolved [${resolution.reason}]")
        }
    }

    private fun describe(value: Any): String =
        when (value) {
            is Class<*> -> value.name
            is Method -> Unobfuscator.getMethodDescriptor(value) ?: value.name
            else -> value.javaClass.name
        }
}

/**
 * Reads a field by alias list off a field map, for the places where a write is not
 * needed and only a read is.
 */
fun Map<String, Field>.fieldByAliases(aliases: List<String>): Field? {
    for (name in aliases) {
        val field = this[name] ?: continue
        field.isAccessible = true
        return field
    }
    return null
}
