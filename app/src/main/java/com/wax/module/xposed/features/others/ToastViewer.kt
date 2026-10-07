package com.wax.module.xposed.features.others

import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.text.TextUtils
import android.widget.Toast
import com.wax.module.R
import com.wax.module.xposed.core.Feature
import com.wax.module.xposed.core.ModuleRuntime.getContactName
import com.wax.module.xposed.core.ModuleRuntime.getCurrentUserJid
import com.wax.module.xposed.core.ModuleRuntime.stripJID
import com.wax.module.xposed.core.components.FMessageWpp
import com.wax.module.xposed.core.components.FMessageWpp.UserJid
import com.wax.module.xposed.core.components.FStatusWpp
import com.wax.module.xposed.core.components.WaContactWpp.Companion.getWaContactFromJid
import com.wax.module.xposed.core.db.MessageStore.Companion.getInstance
import com.wax.module.xposed.core.devkit.Unobfuscator.findFirstClassUsingName
import com.wax.module.xposed.core.devkit.Unobfuscator.loadOnInsertReceipt
import com.wax.module.xposed.core.devkit.Unobfuscator.loadSeenReceiptForStatus
import com.wax.module.xposed.features.general.Tasker
import com.wax.module.xposed.utils.ReflectionUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ToastViewer(
    classLoader: ClassLoader,
    preferences: SharedPreferences,
) : Feature(classLoader, preferences) {
    private data class ViewerOptions(
        val messageToast: Boolean,
        val statusToast: Boolean,
    )

    private data class ReceiptEvent(
        val rowId: Long,
        // Both are nullable because the JID is: a contact WhatsApp cannot resolve to a raw
        // string has no name and no JID, and every consumer of these two already accepts
        // null - stripJID, showViewedToast and Tasker.sendTaskerEvent are all declared that
        // way. Stating them as non-null only moved the null check somewhere less obvious.
        val contactName: String?,
        val rawJid: String?,
        val options: ViewerOptions,
    )

    override fun doHook() {
        val options = currentOptions()
        if (!options.messageToast && !options.statusToast) return

        startCleanupTask()
        hookMessageReceipts()
        hookStatusReceipts()
    }

    private fun hookMessageReceipts() {
        XposedBridge.hookMethod(
            loadOnInsertReceipt(classLoader),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    processMessageReceipts(param, currentOptions())
                }
            },
        )
    }

    private fun hookStatusReceipts() {
        XposedBridge.hookMethod(
            loadSeenReceiptForStatus(classLoader),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    processStatusReceipt(param)
                }
            },
        )
    }

    private fun processStatusReceipt(param: MethodHookParam) {
        val receiptType = param.args.filterIsInstance<Int>().firstOrNull() ?: return
        if (receiptType != VIEWED_RECEIPT_TYPE) return

        val statusObject = statusObjectFrom(param) ?: return
        val status = FStatusWpp(statusObject)
        if (!status.fStatusKey.isFromMe) return

        val userJid =
            runCatching { UserJid(param.args[0]) }.getOrElse {
                XposedBridge.log(it)
                return
            }
        val contactName = getWaContactFromJid(userJid)?.displayName ?: getContactName(userJid)
        val options = currentOptions()
        if (options.statusToast) {
            showViewedToast(R.string.viewed_your_status, contactName)
        }
        Tasker.sendTaskerEvent(contactName, userJid.phoneNumber, EVENT_VIEWED_STATUS)
    }

    private fun statusObjectFrom(param: MethodHookParam): Any? =
        param.args.firstOrNull { FStatusWpp.type.isInstance(it) }
            ?: runCatching {
                ReflectionUtils
                    .findFieldUsingFilter(param.thisObject.javaClass) { field ->
                        FStatusWpp.type.isAssignableFrom(field.type)
                    }.get(param.thisObject)
            }.onFailure(XposedBridge::log)
                .getOrNull()

    private fun processMessageReceipts(
        param: MethodHookParam,
        options: ViewerOptions,
    ) {
        val receipts =
            (param.args.firstOrNull() as? Collection<*>)
                ?: listOf(param.args.firstOrNull())
        val jidClass =
            findFirstClassUsingName(
                classLoader,
                StringMatchType.EndsWith,
                "jid.Jid",
            )

        receipts.filterNotNull().forEach { receipt ->
            processReceipt(receipt, jidClass, options)
        }
    }

    private fun processReceipt(
        receipt: Any,
        jidClass: Class<*>,
        options: ViewerOptions,
    ) {
        val receiptType =
            ReflectionUtils
                .getFieldByType(receipt.javaClass, Int::class.javaPrimitiveType)
                ?.getInt(receipt)
                ?: return
        if (receiptType != VIEWED_RECEIPT_TYPE) return

        val rowId =
            ReflectionUtils
                .getFieldByType(receipt.javaClass, Long::class.javaPrimitiveType)
                ?.getLong(receipt)
                ?: return
        val jidField = ReflectionUtils.getFieldByExtendType(receipt.javaClass, jidClass) ?: return
        val userJid =
            runCatching { UserJid(jidField.get(receipt)) }.getOrElse {
                XposedBridge.log(it)
                return
            }
        val message =
            ReflectionUtils
                .getFieldByExtendType(receipt.javaClass, FMessageWpp.type)
                ?.let { field -> runCatching { field.get(receipt) }.onFailure(XposedBridge::log).getOrNull() }

        Utils.databaseExecutor.execute {
            processReceiptInDatabase(rowId, userJid, message, options)
        }
    }

    private fun processReceiptInDatabase(
        fallbackRowId: Long,
        userJid: UserJid,
        message: Any?,
        options: ViewerOptions,
    ) {
        runCatching {
            val database = getInstance().getDatabase() ?: return
            val contactName = getContactName(userJid).takeUnless(TextUtils::isEmpty) ?: userJid.phoneNumber
            val rowId = message?.let { FMessageWpp(it).rowId } ?: fallbackRowId
            checkDatabase(
                database,
                ReceiptEvent(
                    rowId = rowId,
                    contactName = contactName,
                    rawJid = userJid.phoneRawString,
                    options = options,
                ),
            )
        }.onFailure(XposedBridge::log)
    }

    private fun checkDatabase(
        database: SQLiteDatabase,
        event: ReceiptEvent,
    ) {
        database
            .query(
                "message",
                arrayOf("participant_hash", "chat_row_id"),
                "_id = ?",
                arrayOf(event.rowId.toString()),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) return
                val participantHash = cursor.getString(cursor.getColumnIndexOrThrow("participant_hash"))
                if (participantHash != null) {
                    emitViewedStatus(event)
                    return
                }

                if (isCurrentUser(event.rawJid)) return
                val chatId = cursor.getLong(cursor.getColumnIndexOrThrow("chat_row_id"))
                if (isDirectChat(database, chatId)) {
                    emitViewedMessage(event)
                }
            }
    }

    private fun emitViewedStatus(event: ReceiptEvent) {
        if (event.options.statusToast) {
            showViewedToast(R.string.viewed_your_status, event.contactName)
        }
        Tasker.sendTaskerEvent(event.contactName, stripJID(event.rawJid), EVENT_VIEWED_STATUS)
    }

    private fun emitViewedMessage(event: ReceiptEvent) {
        val key = "${event.rawJid}_$EVENT_VIEWED_MESSAGE"
        if (!markEventIfDue(key)) return

        Tasker.sendTaskerEvent(event.contactName, stripJID(event.rawJid), EVENT_VIEWED_MESSAGE)
        if (event.options.messageToast) {
            showViewedToast(R.string.viewed_your_message, event.contactName)
        }
    }

    private fun isCurrentUser(rawJid: String?): Boolean = getCurrentUserJid()?.phoneRawString == rawJid

    private fun isDirectChat(
        database: SQLiteDatabase,
        chatId: Long,
    ): Boolean =
        database
            .query(
                "chat",
                arrayOf("_id"),
                "_id = ? AND subject IS NULL",
                arrayOf(chatId.toString()),
                null,
                null,
                null,
            ).use { it.moveToFirst() }

    private fun markEventIfDue(key: String): Boolean =
        synchronized(lastEventTimeMap) {
            val now = System.currentTimeMillis()
            val previous = lastEventTimeMap[key]
            if (previous != null && now - previous < MIN_INTERVAL) {
                false
            } else {
                lastEventTimeMap[key] = now
                true
            }
        }

    private fun showViewedToast(
        stringRes: Int,
        contactName: String?,
    ) {
        Utils.showToast(
            Utils.application.getString(stringRes, contactName),
            Toast.LENGTH_LONG,
        )
    }

    private fun currentOptions(): ViewerOptions =
        ViewerOptions(
            messageToast = prefs.getBoolean("toast_viewed_message", false),
            statusToast = prefs.getBoolean("toast_viewed_status", false),
        )

    private fun startCleanupTask() {
        if (!cleanupStarted.compareAndSet(false, true)) return
        scheduler.scheduleWithFixedDelay(
            {
                val expiry = System.currentTimeMillis() - MIN_INTERVAL
                synchronized(lastEventTimeMap) {
                    lastEventTimeMap.entries.removeIf { entry -> entry.value <= expiry }
                }
            },
            CLEANUP_INTERVAL,
            CLEANUP_INTERVAL,
            TimeUnit.SECONDS,
        )
    }

    override fun getPluginName(): String = "Toast Viewer"

    companion object {
        private const val VIEWED_RECEIPT_TYPE = 13
        private const val EVENT_VIEWED_STATUS = "viewed_status"
        private const val EVENT_VIEWED_MESSAGE = "viewed_message"
        private const val MIN_INTERVAL: Long = 1000
        private const val CLEANUP_INTERVAL: Long = 30

        private val cleanupStarted = AtomicBoolean(false)
        private val lastEventTimeMap = ConcurrentHashMap<String, Long>()
        private val scheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "WA X-ToastViewerCleanup").apply {
                    isDaemon = true
                }
            }
    }
}
