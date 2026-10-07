package com.wax.module.health

import android.content.Context
import android.os.Build
import android.os.Process
import com.wax.module.BuildConfig
import java.io.File

/**
 * The process's health reporter, and the bootstrap that attaches storage to it.
 *
 * A runtime has one reporter per process, and it has to exist before the stages it
 * describes. That ordering is the whole reason this object exists: the failure M00 recorded
 * as [M00-DEF-02] was not that DexKit could fail, it was that the failure happened before
 * anything capable of recording it had been created, so it could only be logged and
 * forgotten.
 *
 * Before [install] is called the reporter is volatile — it records in memory and writes
 * nothing — which is the honest state of a process that has no usable data directory yet.
 * [attachStore] upgrades it in place the moment one exists, so a failure recorded early is
 * still there to be written later.
 */
object RuntimeHealth {
    private val lock = Any()
    private var reporter: HealthReporter? = null
    private var store: RuntimeHealthStore? = null

    /** The reporter for this process. */
    fun current(): HealthReporter =
        synchronized(lock) {
            reporter ?: HealthReporter(
                identity = RuntimeIdentity.unknown(BuildConfig.VERSION_NAME),
                sessions = RuntimeSessions.unknown(),
                store = store,
            ).also { reporter = it }
        }

    /** Installs the process's reporter. Replaces any volatile one. */
    fun install(reporter: HealthReporter) {
        synchronized(lock) {
            this.reporter = reporter
        }
    }

    /** Whether a reporter has been installed for this process. */
    fun isInstalled(): Boolean = synchronized(lock) { reporter != null }

    /**
     * Starts the runtime's health for one target process.
     *
     * Called once, before the fragile work — DexKit, resolvers, hooks — so that a failure
     * in any of them has somewhere to go.
     */
    fun beginForTarget(
        packageName: String,
        processName: String,
        targetVersionName: String? = null,
        targetVersionCode: Long? = null,
    ): HealthReporter {
        val identity =
            RuntimeIdentity(
                packageName = packageName,
                processName = processName,
                pid = Process.myPid(),
                moduleVersion = BuildConfig.VERSION_NAME,
                targetVersionName = targetVersionName,
                targetVersionCode = targetVersionCode,
                androidSdk = Build.VERSION.SDK_INT,
            )
        val sessions =
            RuntimeSessions.create(
                BootIdentity.of(
                    elapsedRealtimeMillis = android.os.SystemClock.elapsedRealtime(),
                    nowMillis = System.currentTimeMillis(),
                ),
            )
        val created =
            HealthReporter(
                identity = identity,
                sessions = sessions,
                store = synchronized(lock) { store },
            )
        install(created)
        return created
    }

    /**
     * Attaches file-backed storage.
     *
     * The module's own data directory is the only place a health document may live: it is
     * read back by the manager process, it is not shared with the target, and its contents
     * are redacted before they are written. Any failure to prepare it leaves the reporter
     * volatile rather than propagating, because a storage problem must not become a
     * bootstrap failure.
     */
    fun attachStore(context: Context) {
        val created = runCatching { RuntimeHealthStore(File(context.filesDir, DIRECTORY)) }.getOrNull() ?: return
        synchronized(lock) {
            if (store != null) return
            store = created
            reporter =
                reporter?.let { existing ->
                    HealthReporter(
                        identity = existing.identity(),
                        sessions = existing.sessions(),
                        store = created,
                    )
                }
        }
    }

    /** Reads back the stored document for one target, if storage is attached. */
    fun restore(
        packageName: String,
        processName: String,
    ): RuntimeHealthCodec.StoredHealth {
        val sink = synchronized(lock) { store } ?: return RuntimeHealthCodec.StoredHealth.EMPTY
        return sink.read("$packageName|$processName")
    }

    /** Discards the process's reporter. For tests and for a session that is being replaced. */
    fun reset() {
        synchronized(lock) {
            reporter = null
            store = null
        }
    }

    /** The directory health documents live in, inside the module's own storage. */
    const val DIRECTORY: String = "runtime-health"

    /**
     * The target's version, read without assuming the package is installed.
     *
     * Package visibility is not guaranteed for an arbitrary package name, so a failure to
     * read the version leaves it unknown rather than failing the bootstrap: a missing
     * version degrades what a report can say, and nothing more.
     */
    fun targetVersion(
        context: Context,
        packageName: String,
    ): Pair<String?, Long?> =
        runCatching {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            info.versionName to info.longVersionCode
        }.getOrDefault(null to null)
}
