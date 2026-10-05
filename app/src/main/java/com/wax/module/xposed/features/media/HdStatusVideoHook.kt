package com.wax.module.xposed.features.media

import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.features.general.Others
import com.wax.module.xposed.utils.ReflectionUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * The video half of HD Status.
 *
 * What it forces:
 *  * `ProcessVideoQuality`: the upload ceiling from the user's preference, a 3840 px
 *    longest edge, a 24 Mbps ceiling, the cached high-bitrate profile URL cleared,
 *    and constant-bitrate mode so the encoder does not collapse the bitrate.
 *  * `MediaDataVideoConfiguration.forceSingleTranscoding`, so a single transcode pass
 *    is used instead of a downscale followed by a re-encode.
 *  * The transcoder entry point's boolean flags, which is what stops WhatsApp
 *    treating an HD source as something to compress.
 *  * The video props behind WhatsApp's own quality picker.
 *  * The real-resolution and 60fps options, which were left visible in the settings
 *    screen with nothing reading them after `6a4df80a` removed their hooks.
 *
 * Every step is independent. A WhatsApp build missing one anchor loses that override
 * and keeps the rest, and nothing here throws into WhatsApp.
 */
class HdStatusVideoHook(
    private val classLoader: ClassLoader,
    private val targets: HdStatusTargets,
    private val log: (String) -> Unit,
) {
    /** True when the user asked for HD videos. */
    var enabled: Boolean = false
        private set

    /** True once at least one video hook is installed. */
    var installed: Boolean = false
        private set

    /** Send the video at its source resolution instead of a downscaled one. */
    var realResolution: Boolean = false
        private set

    /** Force 60 fps. */
    var highFrameRate: Boolean = false
        private set

    /** Field names the build requested but could not write. */
    val missingFields: MutableList<String> = ArrayList()

    /** Steps that were skipped because their target did not resolve. */
    val skippedSteps: MutableList<String> = ArrayList()

    /** Installs the video overrides. */
    fun install(
        requested: Boolean,
        limitMb: Int,
        realResolution: Boolean,
        highFrameRate: Boolean,
    ) {
        this.enabled = requested
        this.realResolution = realResolution
        this.highFrameRate = highFrameRate
        if (!requested) {
            log("HD videos off: no video hook installed")
            return
        }

        applyServerProps(limitMb)
        hookVideoLimits(limitMb)
        hookForceSingleTranscoding()
        hookTranscoderQualityFlags()
        hookRealResolution()
    }

    /**
     * The video ceilings as WhatsApp's own props.
     *
     * [Others] installs the hooks that read these maps, and the maps are static, so
     * populating them here takes effect whenever `Others` has run.
     */
    private fun applyServerProps(limitMb: Int) {
        for (id in listOf(594, 12852, 4686, 3654, 3183, 4685)) {
            Others.propsInteger[id] = HdStatusLimits.MAX_VIDEO_EDGE
        }
        for (id in listOf(3755, 3756, 3757, 3758)) {
            Others.propsInteger[id] = HdStatusLimits.MAX_VIDEO_BITRATE_BPS
        }
        Others.propsBoolean[5549] = true
        Others.propsBoolean[18888] = true
        Others.propsBoolean[14447] = false

        log(
            "video props: edge=${HdStatusLimits.MAX_VIDEO_EDGE} " +
                "bitrate=${HdStatusLimits.MAX_VIDEO_BITRATE_BPS} limitMb=$limitMb",
        )
    }

    /**
     * Forces the per-video ceilings on every `ProcessVideoQuality` instance.
     *
     * The upload ceiling comes from the user's preference, clamped by
     * [HdStatusLimits.videoLimitMb] so a stale or out-of-range stored value cannot
     * disable uploads.
     */
    private fun hookVideoLimits(limitMb: Int) {
        val limitsClass = targets.videoLimitsClass()
        if (limitsClass == null) {
            skip("videoLimits", "${HdStatusFields.VIDEO_LIMITS.name} not resolved")
            return
        }

        val fields = Unobfuscator.getAllMapFields(limitsClass)
        if (fields.isEmpty()) {
            skip("videoLimits", "${limitsClass.name} exposes no identifiable quality fields")
            return
        }

        val writer = HdStatusFieldWriter(limitsClass.simpleName, fields, log)
        var summarised = false
        XposedBridge.hookAllConstructors(
            limitsClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    if (!summarised) {
                        summarised = true
                        writer.summarise("video ${limitsClass.simpleName} fields")
                    }
                    writer.withTarget(instance) {
                        setInt(HdStatusFields.VIDEO_LIMIT_MB, limitMb)
                        setInt(HdStatusFields.VIDEO_MAX_EDGE, HdStatusLimits.MAX_VIDEO_EDGE)
                        setInt(HdStatusFields.VIDEO_MAX_BITRATE, HdStatusLimits.MAX_VIDEO_BITRATE_BPS)
                        setEnumConstant(
                            aliases = HdStatusFields.VIDEO_BITRATE_MODE,
                            constantName = CBR_CONSTANT,
                            fallbackOrdinal = 0,
                        )
                        // Clearing the cached profile stops WhatsApp reusing a
                        // low-bitrate encode it already produced for this video.
                        setNull(HdStatusFields.VIDEO_HIGH_BITRATE_URL)
                    }
                    if (writer.hasLosses && missingFields.isEmpty()) {
                        missingFields += writer.missingNames
                    }
                }
            },
        )

        installed = true
        log("video: hooked ${limitsClass.name} constructors (${fields.size} fields)")
    }

    /**
     * Forces a single transcode pass.
     *
     * Without this WhatsApp may downscale and then re-encode, which loses quality
     * twice even when the ceilings above are raised.
     */
    private fun hookForceSingleTranscoding() {
        val configClass = targets.videoConfigurationClass()
        val transcoderStart = targets.videoTranscoderStartMethod()
        if (configClass == null || transcoderStart == null) {
            skip(
                "forceSingleTranscoding",
                "${HdStatusFields.VIDEO_CONFIGURATION.name}=${configClass != null}, " +
                    "${HdStatusFields.VIDEO_TRANSCODER_START.name}=${transcoderStart != null}",
            )
            return
        }

        val fields = Unobfuscator.getAllMapFields(configClass)
        if (fields.isEmpty()) {
            skip("forceSingleTranscoding", "${configClass.name} exposes no identifiable fields")
            return
        }

        val writer = HdStatusFieldWriter(configClass.simpleName, fields, log)
        XposedBridge.hookMethod(
            transcoderStart,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val processor = param.args.firstOrNull() ?: return
                    writer.withTarget(processor) {
                        setBoolean(HdStatusFields.FORCE_SINGLE_TRANSCODING, true)
                    }
                    disableTranscodeShortcut(processor)
                }
            },
        )

        installed = true
        log("video: forceSingleTranscoding armed on ${Unobfuscator.getMethodDescriptor(transcoderStart)}")
    }

    /**
     * Clears the "already good enough, skip the transcode" boolean on the processor.
     *
     * Selected by position among the processor's own boolean fields, which is the
     * only signal available: the field is not named in any string. Bounds are checked
     * and the write is refused rather than guessed at when the shape does not match,
     * because writing the wrong boolean here would corrupt an unrelated decision.
     */
    private fun disableTranscodeShortcut(processor: Any) {
        try {
            val booleans = ReflectionUtils.getFieldsByType(processor.javaClass, java.lang.Boolean.TYPE)
            if (booleans.size <= TRANSCODE_SHORTCUT_INDEX) return
            val field = booleans[TRANSCODE_SHORTCUT_INDEX]
            field.isAccessible = true
            val before = field.getBoolean(processor)
            field.setBoolean(processor, false)
            log("video: transcode shortcut $before -> false on ${field.name}")
        } catch (t: Throwable) {
            log("video: transcode shortcut not cleared (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    /**
     * Enables the media pipeline's HD path for stories.
     *
     * The boolean is located by scanning for the single primitive boolean on the
     * process-spec object, which is how the previous implementation found it. The
     * previous code took `declaredFields.first { ... }`, which throws when the class
     * gains a second boolean; this one refuses instead, and says so.
     */
    private fun hookTranscoderQualityFlags() {
        val start = targets.mediaTranscoderStartMethod()
        if (start == null) {
            skip("mediaTranscoderStart", "${HdStatusFields.MEDIA_TRANSCODER_START.name} not resolved")
            return
        }

        XposedBridge.hookMethod(
            start,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val spec = param.args.firstOrNull() ?: return
                    val booleans =
                        spec.javaClass.declaredFields.filter {
                            it.type == Boolean::class.javaPrimitiveType
                        }
                    if (booleans.isEmpty()) {
                        log("video: media transcoder spec has no boolean field; left unchanged")
                        return
                    }
                    booleans.forEach { field ->
                        try {
                            field.isAccessible = true
                            val before = field.getBoolean(spec)
                            field.setBoolean(spec, true)
                            if (before != true) log("video: ${field.name} $before -> true")
                        } catch (t: Throwable) {
                            log("video: ${field.name} not writable (${t.javaClass.simpleName})")
                        }
                    }
                }
            },
        )

        installed = true
        log("video: media transcoder HD flag armed on ${Unobfuscator.getMethodDescriptor(start)}")
    }

    /**
     * Restores the real-resolution and 60fps options.
     *
     * Both preferences are still shown in the settings screen, but the hooks that
     * consumed them were deleted in `6a4df80a`, so the switches did nothing. The
     * output size is sanitised against the device's own encoder limits before it is
     * written, because forcing a size the encoder rejects makes the transcoder throw
     * and fails the upload.
     */
    private fun hookRealResolution() {
        if (!realResolution && !highFrameRate) return

        val method = targets.resolutionCorrectionMethod()
        if (method == null) {
            skip("realResolution", "${HdStatusFields.RESOLUTION_CORRECTION.name} not resolved")
            return
        }

        val outputFields =
            try {
                Unobfuscator.loadMediaQualityVideoFields(classLoader)
            } catch (t: Throwable) {
                skip("realResolution", "output fields unavailable (${t.javaClass.simpleName})")
                return
            }
        val sourceFields =
            try {
                Unobfuscator.loadMediaQualityOriginalVideoFields(classLoader)
            } catch (t: Throwable) {
                skip("realResolution", "source fields unavailable (${t.javaClass.simpleName})")
                return
            }

        val outputWriter = HdStatusFieldWriter("transcodeOutput", outputFields, log)
        val sourceWriter = HdStatusFieldWriter("transcodeSource", sourceFields, log)

        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val output = param.result ?: return

                    if (highFrameRate) {
                        outputWriter.withTarget(output) {
                            setInt(HdStatusFields.FRAME_RATE, HdStatusLimits.HIGH_FRAME_RATE)
                        }
                    }
                    if (!realResolution) return

                    val source = param.args.firstOrNull()
                    val size = readSourceSize(source, sourceFields)
                    if (size == null) {
                        log("video: source size unreadable; keeping WhatsApp's own size")
                        return
                    }
                    val safe = HdStatusVideoSize.sanitise(size.first, size.second)
                    outputWriter.withTarget(output) {
                        setInt(HdStatusFields.TARGET_WIDTH, safe.first)
                        setInt(HdStatusFields.TARGET_HEIGHT, safe.second)
                    }
                }
            },
        )

        installed = true
        log("video: real-resolution=$realResolution 60fps=$highFrameRate armed")
    }

    /**
     * Reads the source width, height and rotation out of the transcode input.
     *
     * Tries the named fields first and falls back to the JSON blob WhatsApp used to
     * pass instead, because that fallback is what kept the option working when the
     * fields were renamed.
     */
    private fun readSourceSize(
        source: Any?,
        fields: Map<String, java.lang.reflect.Field>,
    ): Pair<Int, Int>? {
        if (source == null) return null
        val width = fields.fieldByAliases(HdStatusFields.SOURCE_WIDTH)
        val height = fields.fieldByAliases(HdStatusFields.SOURCE_HEIGHT)
        if (width != null && height != null) {
            try {
                return Pair(width.getInt(source), height.getInt(source))
            } catch (_: Throwable) {
                // Fall through to the JSON path.
            }
        }
        return readSizeFromJson(source)
    }

    private fun readSizeFromJson(source: Any): Pair<Int, Int>? =
        try {
            val json = XposedHelpers.callMethod(source, JSON_BLOB_ACCESSOR) as? org.json.JSONObject
            json?.let { Pair(it.getInt("widthPx"), it.getInt("heightPx")) }
        } catch (_: Throwable) {
            null
        }

    private fun skip(
        step: String,
        reason: String,
    ) {
        skippedSteps += step
        log("video: $step skipped, $reason")
    }

    private companion object {
        /** `EncoderCapabilities.BITRATE_MODE_CBR`, by name so the enum is matched. */
        const val CBR_CONSTANT = "BITRATE_MODE_CBR"

        /** Position of the "skip the transcode" boolean among the processor booleans. */
        const val TRANSCODE_SHORTCUT_INDEX = 2

        /** Obfuscated accessor for the legacy JSON media blob. */
        const val JSON_BLOB_ACCESSOR = "A00"
    }
}

/**
 * Clamps a requested output size to what a video encoder will actually accept.
 *
 * Forcing a size the encoder rejects makes the transcoder fail the whole upload, so
 * the size is reduced to the largest even value under the 4K ceiling instead. Even
 * dimensions and the ceiling are the two constraints that matter in practice: width
 * alignment beyond 2 is honoured by the encoder via its own configuration on the
 * builds this module supports.
 */
object HdStatusVideoSize {
    /** Never emit a dimension below this; encoders reject 0 and 1. */
    const val MIN_EDGE = 2

    /** Longest edge the module will ask for, matching the prop ceiling. */
    const val MAX_EDGE = HdStatusLimits.MAX_VIDEO_EDGE

    /** Returns the width and height to request, preserving the aspect ratio. */
    fun sanitise(
        width: Int,
        height: Int,
    ): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(FALLBACK_LANDSCAPE.first, FALLBACK_LANDSCAPE.second)

        // floor() must be applied after making the value even: rounding 1 down to the
        // nearest even number would otherwise yield 0, which no encoder accepts.
        val longest = maxOf(width, height)
        if (longest <= MAX_EDGE) return floor(even(width), even(height))

        val scale = MAX_EDGE.toDouble() / longest.toDouble()
        return floor(
            even(Math.round(width * scale).toInt()),
            even(Math.round(height * scale).toInt()),
        )
    }

    private fun even(value: Int): Int = if (value % 2 == 0) value else value - 1

    private fun floor(
        width: Int,
        height: Int,
    ): Pair<Int, Int> = Pair(maxOf(MIN_EDGE, width), maxOf(MIN_EDGE, height))

    private val FALLBACK_LANDSCAPE = Pair(1280, 720)
}
