package com.wax.module.xposed.core.db

import android.annotation.SuppressLint
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.text.TextUtils
import com.wax.module.xposed.utils.Utils
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.stream.Collectors

class MessageStore private constructor() {
    private var sqLiteDatabase: SQLiteDatabase? = null

    init {
        val dataDir = Utils.getAccountDataDir()
        val dbFile = File(dataDir, "/databases/msgstore.db")
        if (dbFile.exists()) {
            sqLiteDatabase =
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
        }
    }

    companion object {
        @Volatile
        private var mInstance: MessageStore? = null

        @JvmStatic
        fun getInstance(): MessageStore =
            mInstance?.takeIf { it.sqLiteDatabase?.isOpen == true }
                ?: synchronized(this) {
                    mInstance?.takeIf { it.sqLiteDatabase?.isOpen == true }
                        ?: MessageStore().also { mInstance = it }
                }
    }

    fun getMessageById(id: Long): String {
        val db = sqLiteDatabase ?: return ""
        var message = ""
        try {
            val columns = arrayOf("c0content")
            val selection = "docid=?"
            val selectionArgs = arrayOf(id.toString())

            db
                .query("message_ftsv2_content", columns, selection, selectionArgs, null, null, null)
                .use { cursor ->
                    if (cursor.moveToFirst()) {
                        message = cursor.getString(cursor.getColumnIndexOrThrow("c0content"))
                    }
                }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return message
    }

    fun getCurrentMessageByKey(messageKey: String): String {
        val db = sqLiteDatabase ?: return ""
        val columns = arrayOf("text_data")
        val selection = "key_id=?"
        val selectionArgs = arrayOf(messageKey)
        try {
            db.query("message", columns, selection, selectionArgs, null, null, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return ""
    }

    fun getIdfromKey(messageKey: String): Long {
        val db = sqLiteDatabase ?: return -1
        val columns = arrayOf("_id")
        val selection = "key_id=?"
        val selectionArgs = arrayOf(messageKey)
        try {
            db.query("message", columns, selection, selectionArgs, null, null, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return -1
    }

    fun getMediaFromID(id: Long): String? {
        val db = sqLiteDatabase ?: return null
        val columns = arrayOf("file_path")
        val selection = "message_rowId=?"
        val selectionArgs = arrayOf(id.toString())
        try {
            db
                .query("message_media", columns, selection, selectionArgs, null, null, null)
                .use { cursor ->
                    if (cursor.moveToFirst()) {
                        return cursor.getString(0)
                    }
                }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return null
    }

    fun getCurrentMessageByID(rowId: Long): String {
        val db = sqLiteDatabase ?: return ""
        val columns = arrayOf("text_data")
        val selection = "_id=?"
        val selectionArgs = arrayOf(rowId.toString())
        try {
            db.query("message", columns, selection, selectionArgs, null, null, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return ""
    }

    fun getOriginalMessageKey(id: Long): String {
        val db = sqLiteDatabase ?: return ""
        var message = ""
        val sql =
            "SELECT parent_message_rowId, key_id FROM message_add_on WHERE parent_message_rowId=\"$id\""
        try {
            db.rawQuery(sql, null).use { cursor ->
                if (cursor.moveToFirst()) {
                    message = cursor.getString(1)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return message
    }

    fun getAudioListByMessageList(messageList: List<String>?): List<String> {
        val db = sqLiteDatabase
        if (db == null || messageList.isNullOrEmpty()) {
            return ArrayList()
        }

        val list = ArrayList<String>()
        val placeholders = messageList.stream().map { "?" }.collect(Collectors.joining(","))
        val sql = "SELECT message_type FROM message WHERE key_id IN ($placeholders)"
        try {
            db.rawQuery(sql, messageList.toTypedArray()).use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        if (cursor.getInt(0) == 2) {
                            list.add(cursor.getString(0))
                        }
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }

        return list
    }

    @Synchronized
    fun executeWritableSQL(
        sql: String,
        maxRetries: Int = 3,
        retryDelayMs: Long = 500L,
    ) {
        val dataDir = Utils.getAccountDataDir()
        val dbFile = File(dataDir, "/databases/msgstore.db")
        if (!dbFile.exists()) return

        var retries = 0
        while (retries < maxRetries) {
            var writeDb: SQLiteDatabase? = null
            try {
                writeDb =
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    )
                try {
                    writeDb.rawQuery("PRAGMA busy_timeout = 3000;", null).close()
                } catch (ignored: Exception) {
                }

                writeDb.execSQL(sql)
                return
            } catch (e: Exception) {
                if (e is android.database.sqlite.SQLiteDatabaseLockedException ||
                    e is android.database.sqlite.SQLiteTableLockedException
                ) {
                    retries++
                    if (retries >= maxRetries) {
                        XposedBridge.log(e)
                    } else {
                        try {
                            Thread.sleep(retryDelayMs * retries)
                        } catch (ignored: InterruptedException) {
                        }
                    }
                } else {
                    XposedBridge.log(e)
                    return
                }
            } finally {
                try {
                    writeDb?.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    fun storeMessageRead(messageId: String) {
        XposedBridge.log("storeMessageRead: $messageId")
        executeWritableSQL("UPDATE message SET status = 1 WHERE key_id = \"$messageId\"")
    }

    fun isReadMessageStatus(messageId: String): Boolean {
        val db = sqLiteDatabase ?: return false
        var result = false
        var cursor: Cursor? = null
        try {
            val columns = arrayOf("status")
            val selection = "key_id=?"
            val selectionArgs = arrayOf(messageId)

            cursor = db.query("message", columns, selection, selectionArgs, null, null, null)
            if (cursor.moveToFirst()) {
                result = cursor.getInt(cursor.getColumnIndexOrThrow("status")) == 1
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        } finally {
            cursor?.close()
        }
        return result
    }

    fun getDatabase(): SQLiteDatabase? = sqLiteDatabase

    @SuppressLint("Recycle")
    @Synchronized
    fun getFirstMessageInfoByChatRawJid(rawJid: String): MessageInfo? {
        val db = getDatabase()
        if (db == null || TextUtils.isEmpty(rawJid)) {
            return null
        }

        val sql =
            """
            WITH resolved(jid_rowId) AS (
                SELECT _id FROM jid WHERE raw_string=?
                UNION
                SELECT jm.jid_rowId FROM jid_map jm
                INNER JOIN jid j ON j._id = jm.lid_rowId
                WHERE j.raw_string=?
                UNION
                SELECT jm.lid_rowId FROM jid_map jm
                INNER JOIN jid j ON j._id = jm.jid_rowId
                WHERE j.raw_string=?
            ), chat_target AS (
                SELECT _id FROM chat WHERE jid_rowId IN (SELECT jid_rowId FROM resolved)
            )
            SELECT m._id, m.sort_id, m.chat_rowId
            FROM message m
            INNER JOIN chat_target c ON c._id = m.chat_rowId
            ORDER BY m.sort_id ASC, m._id ASC
            LIMIT 1
            """.trimIndent()

        try {
            db.rawQuery(sql, arrayOf(rawJid, rawJid, rawJid)).use { cursor ->
                if (cursor.moveToFirst()) {
                    return MessageInfo(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2))
                }
            }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }
        return null
    }

    fun deleteStatusByMessageKey(
        messageKey: String?,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        Utils.databaseExecutor.execute {
            val result = deleteStatusByMessageKeySync(messageKey)
            callback?.invoke(result)
        }
    }

    @Synchronized
    private fun deleteStatusByMessageKeySync(messageKey: String?): Boolean {
        if (messageKey.isNullOrEmpty()) {
            return false
        }
        val dbFile = File(Utils.application.filesDir.parentFile, "/databases/status.db")
        val statusDbInstance: SQLiteDatabase? =
            if (dbFile.exists()) {
                try {
                    SQLiteDatabase.openDatabase(
                        dbFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    )
                } catch (e: Exception) {
                    XposedBridge.log(e)
                    null
                }
            } else {
                null
            }

        if (statusDbInstance != null && statusDbInstance.isOpen) {
            try {
                var statusRowId: Long? = null
                var mediaFilePath: String? = null

                try {
                    statusDbInstance
                        .query(
                            "status",
                            arrayOf("rowId"),
                            "uuid=?",
                            arrayOf(messageKey),
                            null,
                            null,
                            null,
                        ).use { cursor ->
                            if (cursor.moveToFirst()) {
                                statusRowId = cursor.getLong(0)
                            }
                        }
                } catch (e: Exception) {
                    XposedBridge.log(e)
                }

                if (statusRowId != null) {
                    try {
                        statusDbInstance
                            .rawQuery(
                                "SELECT st.thumbnail_path, mc.file_path " +
                                    "FROM status_thumbnail st " +
                                    "LEFT JOIN media_content mc ON st.media_content_rowId = mc.rowId " +
                                    "WHERE st.status_rowId = ? LIMIT 1",
                                arrayOf(statusRowId.toString()),
                            ).use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val thumbPath = if (cursor.isNull(0)) null else cursor.getString(0)
                                    val mcPath = if (cursor.isNull(1)) null else cursor.getString(1)
                                    mediaFilePath =
                                        when {
                                            !thumbPath.isNullOrEmpty() -> thumbPath
                                            !mcPath.isNullOrEmpty() -> mcPath
                                            else -> null
                                        }
                                }
                            }
                    } catch (e: Exception) {
                        XposedBridge.log(e)
                    }

                    if (mediaFilePath.isNullOrEmpty()) {
                        try {
                            statusDbInstance
                                .rawQuery(
                                    "SELECT mc.file_path " +
                                        "FROM status_media_link sml " +
                                        "JOIN media_content mc ON sml.media_content_rowId = mc.rowId " +
                                        "WHERE sml.status_rowId = ? LIMIT 1",
                                    arrayOf(statusRowId.toString()),
                                ).use { cursor ->
                                    if (cursor.moveToFirst()) {
                                        mediaFilePath = if (cursor.isNull(0)) null else cursor.getString(0)
                                    }
                                }
                        } catch (e: Exception) {
                            XposedBridge.log(e)
                        }
                    }

                    var deleted = false
                    try {
                        deleted = statusDbInstance.delete("status", "rowId=?", arrayOf(statusRowId.toString())) > 0
                    } catch (e: Exception) {
                        XposedBridge.log(e)
                    }

                    if (deleted) {
                        deleteStatusMediaFile(mediaFilePath)
                        return true
                    }
                }
            } finally {
                try {
                    statusDbInstance.close()
                } catch (e: Exception) {
                    XposedBridge.log(e)
                }
            }
        }

        val msgStoreFile = File(Utils.application.filesDir.parentFile, "/databases/msgstore.db")
        val writeDb =
            if (msgStoreFile.exists()) {
                try {
                    SQLiteDatabase.openDatabase(
                        msgStoreFile.absolutePath,
                        null,
                        SQLiteDatabase.OPEN_READWRITE,
                    )
                } catch (e: Exception) {
                    XposedBridge.log(e)
                    null
                }
            } else {
                null
            } ?: return false

        var messageRowId: Long? = null
        var senderJidRowId: Long? = null
        var chatRowId: Long? = null
        var mediaFilePath: String? = null
        var deleted = false

        try {
            try {
                writeDb
                    .rawQuery(
                        "SELECT _id, sender_jid_rowId, chat_rowId " +
                            "FROM message " +
                            "WHERE key_id=? AND from_me=0 " +
                            "ORDER BY _id DESC LIMIT 1",
                        arrayOf(messageKey),
                    ).use { cursor ->
                        if (cursor.moveToFirst()) {
                            messageRowId = if (cursor.isNull(0)) null else cursor.getLong(0)
                            senderJidRowId = if (cursor.isNull(1)) null else cursor.getLong(1)
                            chatRowId = if (cursor.isNull(2)) null else cursor.getLong(2)
                        }
                    }
            } catch (e: Exception) {
                XposedBridge.log(e)
            }

            if (messageRowId == null || senderJidRowId == null || chatRowId == null) {
                return false
            }

            try {
                writeDb
                    .rawQuery(
                        "SELECT file_path FROM message_media WHERE message_rowId=? LIMIT 1",
                        arrayOf(messageRowId.toString()),
                    ).use { mediaCursor ->
                        if (mediaCursor.moveToFirst()) {
                            mediaFilePath = mediaCursor.getString(0)
                        }
                    }
            } catch (e: Exception) {
                XposedBridge.log(e)
            }

            try {
                deleted = writeDb.delete("message", "_id=?", arrayOf(messageRowId.toString())) > 0
            } catch (e: Exception) {
                XposedBridge.log(e)
                deleted = false
            }

            if (deleted) {
                refreshStatusRow(writeDb, senderJidRowId!!, chatRowId!!)
            }
        } finally {
            try {
                writeDb.close()
            } catch (e: Exception) {
                XposedBridge.log(e)
            }
        }

        if (deleted) {
            deleteStatusMediaFile(mediaFilePath)
        }

        return deleted
    }

    private fun refreshStatusRow(
        db: SQLiteDatabase,
        senderJidRowId: Long,
        chatRowId: Long,
    ) {
        var latestMessageId: Long = -1
        var latestTimestamp: Long = 0
        var totalCount = 0
        var unseenCount = 0
        var firstUnreadMessageId: Long? = null

        try {
            db
                .rawQuery(
                    "SELECT _id, timestamp, status " +
                        "FROM message " +
                        "WHERE sender_jid_rowId=? AND chat_rowId=? " +
                        "ORDER BY timestamp DESC, _id DESC",
                    arrayOf(senderJidRowId.toString(), chatRowId.toString()),
                ).use { cursor ->
                    var first = true
                    while (cursor.moveToNext()) {
                        val rowId = cursor.getLong(0)
                        val ts = cursor.getLong(1)
                        val status = cursor.getInt(2)

                        if (first) {
                            latestMessageId = rowId
                            latestTimestamp = ts
                            first = false
                        }

                        totalCount++
                        if (status == 0) {
                            unseenCount++
                            firstUnreadMessageId = rowId
                        }
                    }
                }
        } catch (e: Exception) {
            XposedBridge.log(e)
        }

        if (totalCount == 0) {
            db.delete("status", "jid_rowId=?", arrayOf(senderJidRowId.toString()))
            return
        }

        db.execSQL(
            "UPDATE status " +
                "SET message_table_id=?, " +
                "timestamp=?, " +
                "total_count=?, " +
                "unseen_count=?, " +
                "unseen_count_close_friends=CASE " +
                "WHEN unseen_count_close_friends IS NULL THEN NULL " +
                "WHEN unseen_count_close_friends > ? THEN ? " +
                "ELSE unseen_count_close_friends END, " +
                "first_unread_message_table_id=? " +
                "WHERE jid_rowId=?",
            arrayOf<Any?>(
                latestMessageId,
                latestTimestamp,
                totalCount,
                unseenCount,
                unseenCount,
                unseenCount,
                firstUnreadMessageId,
                senderJidRowId,
            ),
        )

        db.execSQL(
            "UPDATE status " +
                "SET last_read_message_table_id = CASE " +
                "WHEN last_read_message_table_id IN (" +
                "SELECT _id FROM message WHERE sender_jid_rowId=? AND chat_rowId=?" +
                ") THEN last_read_message_table_id ELSE NULL END, " +
                "last_read_receipt_sent_message_table_id = CASE " +
                "WHEN last_read_receipt_sent_message_table_id IN (" +
                "SELECT _id FROM message WHERE sender_jid_rowId=? AND chat_rowId=?" +
                ") THEN last_read_receipt_sent_message_table_id ELSE NULL END, " +
                "autodownload_limit_message_table_id = CASE " +
                "WHEN autodownload_limit_message_table_id IN (" +
                "SELECT _id FROM message WHERE sender_jid_rowId=? AND chat_rowId=?" +
                ") THEN autodownload_limit_message_table_id ELSE NULL END " +
                "WHERE jid_rowId=?",
            arrayOf<Any>(
                senderJidRowId,
                chatRowId,
                senderJidRowId,
                chatRowId,
                senderJidRowId,
                chatRowId,
                senderJidRowId,
            ),
        )
    }

    private fun deleteStatusMediaFile(relativePath: String?) {
        if (relativePath.isNullOrEmpty()) {
            return
        }

        val candidates = ArrayList<File>()
        val app = Utils.application
        val appName = app.applicationInfo.loadLabel(app.packageManager).toString()
        val mediaDirs = app.externalMediaDirs

        if (relativePath.startsWith("/")) {
            candidates.add(File(relativePath))
        }

        if (mediaDirs.isNotEmpty()) {
            candidates.add(File(mediaDirs[0], "$appName/$relativePath"))
        }

        for (candidate in candidates) {
            try {
                if (candidate.exists() && candidate.isFile && candidate.delete()) {
                    break
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    data class MessageInfo(
        val rowId: Long,
        val sortId: Long,
        val chatRowId: Long,
    )
}
