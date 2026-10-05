package com.wax.module.xposed.features.media

import android.graphics.Bitmap
import android.graphics.RecordingCanvas
import android.os.Build
import com.wax.module.xposed.core.devkit.Unobfuscator
import com.wax.module.xposed.features.general.Others
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * The image half of HD Status.
 *
 * Kept separate from [HdStatusVideoHook] on purpose. Both halves used to live in one
 * `doHook()`, so an exception raised while resolving a *video* target aborted the
 * image overrides declared after it, and the other way round. Each half installs
 * independently and every step inside it is isolated, so neither can stop the other.
 *
 * What it forces:
 *  * `ProcessImageQuality`: raise the KB ceiling, set the quality to 100 and lift the
 *    longest-edge ceiling, so WhatsApp's re-encode is not lossy.
 *  * The image props behind WhatsApp's own quality picker, so the picker and the
 *    upload request agree with the ceilings above.
 *  * `RecordingCanvas.throwIfCannotDraw`, which otherwise crashes the media preview
 *    now that the module allows bitmaps wider than a typical preview surface.
 */
class HdStatusImageHook(
    private val targets: HdStatusTargets,
    private val log: (String) -> Unit,
) {

    /** True when the user asked for HD images. */
    var enabled: Boolean = false
        private set

    /** True once at least one image hook is installed. */
    var installed: Boolean = false
        private set

    /** Field names the build requested but could not write. */
    val missingFields: MutableList<String> = ArrayList()

    /**
     * Installs the image overrides.
     *
     * Each step is independently guarded by the caller, so this returns normally
     * even when every target is missing; the result is reported through [log].
     */
    fun install(requested: Boolean) {
        enabled = requested
        if (!requested) {
            log("HD images off: no image hook installed")
            return
        }
        applyServerProps()
        hookImageLimits()
        hookPreviewGuard()
    }

    /**
     * The image ceilings, also published as WhatsApp's own experiment props.
     *
     * Written through [Others] because that is where the props hooks live: the
     * values sit in shared maps that `Others` reads when WhatsApp queries a prop.
     * Setting them here before `Others` installs its hooks is fine, because the maps
     * are static and read at query time.
     */
    private fun applyServerProps() {
        for (id in listOf(1577, 6030, 2656, 15752, 15746)) {
            Others.propsInteger[id] = HdStatusLimits.MAX_IMAGE_KB
        }
        for (id in listOf(1581, 1575, 1578, 6029, 2655, 15749)) {
            Others.propsInteger[id] = HdStatusLimits.MAX_IMAGE_QUALITY
        }
        for (id in listOf(1576, 2654, 6032, 15748, 3068)) {
            Others.propsInteger[id] = HdStatusLimits.MAX_IMAGE_EDGE
        }

        // Keep WhatsApp's own lossy paths off, and stop it computing a manual
        // quality that would contradict the ceilings set here.
        Others.propsBoolean[6033] = true
        Others.propsBoolean[9569] = false
        Others.propsBoolean[26289] = true
        Others.propsBoolean[22375] = true
        Others.propsBoolean[14447] = false

        log(
            "image props: maxKb=${HdStatusLimits.MAX_IMAGE_KB} " +
                "quality=${HdStatusLimits.MAX_IMAGE_QUALITY} edge=${HdStatusLimits.MAX_IMAGE_EDGE}"
        )
    }

    /**
     * Forces the ceilings on every `ProcessImageQuality` instance.
     *
     * Writes go through [HdStatusFieldWriter], so a renamed field is reported and
     * skipped instead of silently doing nothing, and a field whose type does not
     * match is never written to, which is what stops a mis-parsed field map from
     * throwing inside WhatsApp's constructor.
     */
    private fun hookImageLimits() {
        val limitsClass = targets.imageLimitsClass()
        if (limitsClass == null) {
            log("image: ${HdStatusFields.IMAGE_LIMITS.name} not resolved; server props only")
            return
        }

        val fields = Unobfuscator.getAllMapFields(limitsClass)
        if (fields.isEmpty()) {
            log("image: ${limitsClass.name} exposes no identifiable quality fields; skipped")
            return
        }

        val writer = HdStatusFieldWriter(limitsClass.simpleName, fields, log)
        XposedBridge.hookAllConstructors(limitsClass, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val instance = param.thisObject ?: return
                writer.withTarget(instance) {
                    setInt(HdStatusFields.IMAGE_MAX_KB, HdStatusLimits.MAX_IMAGE_KB)
                    setInt(HdStatusFields.IMAGE_QUALITY, HdStatusLimits.MAX_IMAGE_QUALITY)
                    setInt(HdStatusFields.IMAGE_MAX_EDGE, HdStatusLimits.MAX_IMAGE_EDGE)
                }
                if (missingFields.isEmpty() && writer.hasLosses) {
                    missingFields += writer.missingNames
                    writer.summarise("image ${limitsClass.simpleName}")
                }
            }
        })

        installed = true
        log("image: hooked ${limitsClass.name} constructors (${fields.size} fields)")
    }

    /**
     * Stops the media preview from throwing when a bitmap does not fit the surface.
     *
     * Necessary because the image edge ceiling is 6000 px, wider than a typical
     * preview surface. Scoped to the single `throwIfCannotDraw` overload taking a
     * bitmap, so no other canvas behaviour changes.
     */
    private fun hookPreviewGuard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("image: preview guard not needed below API 29")
            return
        }
        XposedHelpers.findAndHookMethod(
            RecordingCanvas::class.java,
            "throwIfCannotDraw",
            Bitmap::class.java,
            XC_MethodReplacement.DO_NOTHING
        )
        log("image: media preview guard installed")
    }
}
