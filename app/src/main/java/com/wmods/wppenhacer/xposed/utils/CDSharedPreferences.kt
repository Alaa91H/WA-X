package com.wmods.wppenhacer.xposed.utils

import android.content.SharedPreferences
import android.util.AtomicFile
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileOutputStream

/**
 * SharedPreferences implementation backed by an XML file in an arbitrary directory.
 */
class CDSharedPreferences(private val xmlFile: File) : SharedPreferences {

    private val lock = Any()
    private val atomicFile = AtomicFile(xmlFile)
    private var preferencesMap = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        loadData()
    }

    private fun loadData() {
        synchronized(lock) {
            try {
                atomicFile.openRead().use { inputStream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(inputStream, "UTF-8")
                    val loaded = mutableMapOf<String, Any?>()

                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            val tagName = parser.name
                            val key = parser.getAttributeValue(null, "name")

                            if (key != null) {
                                when (tagName) {
                                    "string" -> loaded[key] = parser.nextText()
                                    "boolean" -> loaded[key] =
                                        parser.getAttributeValue(null, "value")?.toBoolean() ?: false
                                    "int" -> loaded[key] =
                                        parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                                    "long" -> loaded[key] =
                                        parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L
                                    "float" -> loaded[key] =
                                        parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                                    "set" -> loaded[key] = readStringSet(parser)
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                    preferencesMap = loaded
                }
            } catch (_: Exception) {
                preferencesMap.clear()
            }
        }
    }

    private fun readStringSet(parser: XmlPullParser): Set<String?> {
        val values = linkedSetOf<String?>()
        var eventType = parser.next()
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.END_TAG && parser.name == "set") break
            if (eventType == XmlPullParser.START_TAG && parser.name == "string") {
                values.add(parser.nextText())
            }
            eventType = parser.next()
        }
        return values
    }

    private fun saveData(mapToSave: Map<String, Any?>): Boolean {
        val parent = xmlFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            return false
        }

        var outputStream: FileOutputStream? = null
        return try {
            outputStream = atomicFile.startWrite()
            val serializer: XmlSerializer = Xml.newSerializer()
            serializer.setOutput(outputStream, "UTF-8")
            serializer.startDocument("UTF-8", true)
            serializer.startTag(null, "map")

            for ((key, value) in mapToSave) {
                when (value) {
                    is String -> {
                        serializer.startTag(null, "string")
                        serializer.attribute(null, "name", key)
                        serializer.text(value)
                        serializer.endTag(null, "string")
                    }

                    is Boolean -> {
                        serializer.startTag(null, "boolean")
                        serializer.attribute(null, "name", key)
                        serializer.attribute(null, "value", value.toString())
                        serializer.endTag(null, "boolean")
                    }

                    is Int -> {
                        serializer.startTag(null, "int")
                        serializer.attribute(null, "name", key)
                        serializer.attribute(null, "value", value.toString())
                        serializer.endTag(null, "int")
                    }

                    is Long -> {
                        serializer.startTag(null, "long")
                        serializer.attribute(null, "name", key)
                        serializer.attribute(null, "value", value.toString())
                        serializer.endTag(null, "long")
                    }

                    is Float -> {
                        serializer.startTag(null, "float")
                        serializer.attribute(null, "name", key)
                        serializer.attribute(null, "value", value.toString())
                        serializer.endTag(null, "float")
                    }

                    is Set<*> -> {
                        serializer.startTag(null, "set")
                        serializer.attribute(null, "name", key)
                        value.filterIsInstance<String>().forEach { item ->
                            serializer.startTag(null, "string")
                            serializer.text(item)
                            serializer.endTag(null, "string")
                        }
                        serializer.endTag(null, "set")
                    }
                }
            }

            serializer.endTag(null, "map")
            serializer.endDocument()
            serializer.flush()
            atomicFile.finishWrite(outputStream)
            outputStream = null
            true
        } catch (_: Exception) {
            outputStream?.let(atomicFile::failWrite)
            false
        }
    }

    override fun getAll(): Map<String, *> = synchronized(lock) {
        preferencesMap.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
    }

    override fun getString(key: String, defValue: String?): String? = synchronized(lock) {
        preferencesMap[key] as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String?>?): Set<String?>? =
        synchronized(lock) {
            if (key == null) return@synchronized defValues?.toSet()
            (preferencesMap[key] as? Set<String?>)?.toSet() ?: defValues?.toSet()
        }

    override fun getInt(key: String, defValue: Int): Int = synchronized(lock) {
        preferencesMap[key] as? Int ?: defValue
    }

    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) {
        preferencesMap[key] as? Long ?: defValue
    }

    override fun getFloat(key: String, defValue: Float): Float = synchronized(lock) {
        preferencesMap[key] as? Float ?: defValue
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) {
        preferencesMap[key] as? Boolean ?: defValue
    }

    override fun contains(key: String): Boolean = synchronized(lock) {
        preferencesMap.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = CustomEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(lock) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        synchronized(lock) { listeners.remove(listener) }
    }

    private inner class CustomEditor : SharedPreferences.Editor {
        private val localChanges = mutableMapOf<String, Any?>()
        private val keysToRemove = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value == null) return remove(key)
            localChanges[key] = value
            keysToRemove.remove(key)
            return this
        }

        override fun putStringSet(
            key: String?,
            values: Set<String?>?
        ): SharedPreferences.Editor {
            if (key == null) return this
            if (values == null) return remove(key)
            localChanges[key] = values.filterNotNull().toSet()
            keysToRemove.remove(key)
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            localChanges[key] = value
            keysToRemove.remove(key)
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            localChanges[key] = value
            keysToRemove.remove(key)
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            localChanges[key] = value
            keysToRemove.remove(key)
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            localChanges[key] = value
            keysToRemove.remove(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            keysToRemove.add(key)
            localChanges.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            localChanges.clear()
            keysToRemove.clear()
            return this
        }

        override fun apply() {
            commit()
        }

        override fun commit(): Boolean {
            val changedKeys: Set<String>
            val listenerSnapshot: List<SharedPreferences.OnSharedPreferenceChangeListener>

            synchronized(lock) {
                val before = preferencesMap.toMap()
                val updated = if (clearAll) mutableMapOf() else preferencesMap.toMutableMap()

                keysToRemove.forEach(updated::remove)
                localChanges.forEach { (key, value) ->
                    updated[key] = if (value is Set<*>) value.toSet() else value
                }

                val changed = (before.keys + updated.keys)
                    .filterTo(linkedSetOf()) { before[it] != updated[it] }

                if (changed.isNotEmpty() && !saveData(updated)) return false

                preferencesMap = updated
                changedKeys = changed
                listenerSnapshot = listeners.toList()
                localChanges.clear()
                keysToRemove.clear()
                clearAll = false
            }

            for (key in changedKeys) {
                listenerSnapshot.forEach { listener ->
                    listener.onSharedPreferenceChanged(this@CDSharedPreferences, key)
                }
            }
            return true
        }
    }
}
