package com.wax.module.xposed.features.media

import android.content.SharedPreferences
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.devkit.Unobfuscator
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge

/**
 * HD Status: send images and videos at full quality.
 *
 * This class is the feature entry point only. All WhatsApp-specific knowledge lives
 * in [HdStatusTargets] (what to resolve), [HdStatusImageHook] and [HdStatusVideoHook]
 * (what to install), and the pure decision logic lives in [HdStatusResolution]. What
 * is left here is reading the preferences and isolating each step so one failure
 * cannot take the rest down.
 *
 * Why it was restructured: everything used to be a single `doHook()` holding one big
 * `if (videoQuality)` and one `if (imageQuality)` block with about twenty writes of
 * the form `fields["name"]?.setInt(...)`. That shape failed four ways at once, all of
 * them silent:
 *
 *  * A renamed field became a no-op, so the switch was on and nothing happened.
 *  * A *mis-paired* field, which the `toString()`-derived field map can produce, threw
 *    `IllegalArgumentException` from inside WhatsApp's own constructor.
 *  * `loadMediaTranscoderStart` ended in `.first()`, so one stale anchor raised
 *    `NoSuchElementException` mid-block and every override after it never ran.
 *  * The video size preference was read as `max(preference, 90)`, where 90 is the seek
 *    bar's *maximum*, making the result always 90 and the slider inert.
 *
 * On top of that there was no output at all, so none of it could be diagnosed.
 */
class MediaQuality(
    loader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(loader, preferences) {
    private var targets: HdStatusTargets? = null
    private var imageHook: HdStatusImageHook? = null
    private var videoHook: HdStatusVideoHook? = null

    override fun doHook() {
        val imageQuality = prefs.getBoolean(PREF_IMAGE_QUALITY, false)
        val videoQuality = prefs.getBoolean(PREF_VIDEO_QUALITY, false)
        val realResolution = prefs.getBoolean(PREF_REAL_RESOLUTION, false)
        val highFrameRate = prefs.getBoolean(PREF_MAX_FPS, false)
        val limitMb = HdStatusLimits.videoLimitMb(prefs.getFloat(PREF_VIDEO_LIMIT_MB, 60f).toInt())

        log(
            "images=$imageQuality videos=$videoQuality realResolution=$realResolution " +
                "60fps=$highFrameRate limitMb=$limitMb",
        )

        // Resolve once, up front. Everything below reads from this set, and a target
        // that fails to resolve costs only its own override.
        val resolved = HdStatusTargets(classLoader) { log(it) }
        targets = resolved
        step("resolve") { resolved.resolveAll() }

        // Revealed even with both quality switches off: this is what makes WhatsApp
        // show its own per-item HD picker, which is how a user picks HD for one item.
        step("qualityPicker") { revealQualityPicker(resolved) }

        if (videoQuality) {
            step("video") {
                videoHook =
                    HdStatusVideoHook(classLoader, resolved) { log(it) }.apply {
                        install(true, limitMb, realResolution, highFrameRate)
                    }
            }
        }

        if (imageQuality) {
            step("image") {
                imageHook = HdStatusImageHook(resolved) { log(it) }.apply { install(true) }
            }
        }

        report()
    }

    /**
     * Reveals WhatsApp's own per-item quality picker for stories.
     *
     * The previous implementation cached its choice in the *hooked app's* prefs as
     * `legacy_quality_selection` and returned early forever once it had latched. When
     * WhatsApp renamed the gate, the module kept taking the dead branch with no way
     * out short of clearing WhatsApp's app data. Both paths are now attempted on
     * every load, so a renamed gate self-heals on the next launch instead of staying
     * broken until the user wipes app data.
     */
    private fun revealQualityPicker(targets: HdStatusTargets) {
        val gate = targets.qualitySelectionGateMethod()
        if (gate != null) {
            XposedBridge.hookMethod(gate, XC_TRUE_REPLACEMENT)
            log("picker gate armed on ${Unobfuscator.getMethodDescriptor(gate)}")
        } else {
            log("picker gate unresolved (${HdStatusFields.QUALITY_SELECTION_GATE.name})")
        }

        val configClass = targets.bottomBarConfigClass() ?: return
        val fields = Unobfuscator.getAllMapFields(configClass)
        if (fields.isEmpty()) {
            log("picker: ${configClass.name} exposes no identifiable fields")
            return
        }

        val writer = HdStatusFieldWriter(configClass.simpleName, fields) { log(it) }
        XposedBridge.hookAllConstructors(
            configClass,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    writer.withTarget(instance) {
                        setBoolean(HdStatusFields.SUPPORTS_HD_QUALITY, true)
                    }
                }
            },
        )
        log("picker: supportsHdQuality armed on ${configClass.name}")
    }

    /**
     * Runs one step, turning any failure into a logged line.
     *
     * This is the isolation the old monolithic `doHook()` lacked: an exception from a
     * video target can no longer prevent the image hooks from installing, and a
     * WhatsApp build with nothing recognisable degrades to "no HD Status" instead of
     * a partial and unpredictable set of overrides.
     */
    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            log("step '$name' failed (${t.javaClass.simpleName}: ${t.message})")
            log(t)
        }
    }

    /** Prints the closing state, so one logcat read says what is active. */
    private fun report() {
        val video = videoHook
        val image = imageHook
        val parts =
            buildList {
                add("image=" + if (image?.installed == true) "installed" else "off")
                add("video=" + if (video?.installed == true) "installed" else "off")
                video?.let {
                    add("realResolution=${it.realResolution}")
                    add("60fps=${it.highFrameRate}")
                    if (it.skippedSteps.isNotEmpty()) add("videoSkipped=${it.skippedSteps.joinToString("/")}")
                }
                if (image != null && image.missingFields.isNotEmpty()) {
                    add("imageMissing=${image.missingFields.distinct().joinToString("/")}")
                }
                if (video != null && video.missingFields.isNotEmpty()) {
                    add("videoMissing=${video.missingFields.distinct().joinToString("/")}")
                }
            }
        log("summary: ${parts.joinToString(" ")}")
    }

    override fun getPluginName(): String = "HD Status"

    private companion object {
        const val PREF_IMAGE_QUALITY = "imagequality"
        const val PREF_VIDEO_QUALITY = "videoquality"
        const val PREF_REAL_RESOLUTION = "video_real_resolution"
        const val PREF_MAX_FPS = "video_maxfps"
        const val PREF_VIDEO_LIMIT_MB = "video_limit_size"

        /** Stateless, so one shared instance is enough. */
        val XC_TRUE_REPLACEMENT: XC_MethodReplacement = XC_MethodReplacement.returnConstant(true)
    }
}
