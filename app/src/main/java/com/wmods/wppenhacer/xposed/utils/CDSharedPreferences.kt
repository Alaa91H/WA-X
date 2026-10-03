package com.wmods.wppenhacer.xposed.utils

import android.content.SharedPreferences
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * A custom implementation of [SharedPreferences] that reads from and writes to
 * an XML file located in any arbitrary directory.
 */
class CDSharedPreferences(private val xmlFile: File) : SharedPreferences {

    private val lock = Any()
    private var preferencesMap = mutableMapOf<String, Any?>()
    private val listeners = mutableListOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    init {
        loadData()
    }

    private fun loadData() {
        synchronized(lock) {
            if (!xmlFile.exists() || !xmlFile.isFile) return

            try {
                FileInputStream(xmlFile).use { inputStream ->
                    val parser = Xml.newPullParser()
                    parser.setInput(inputStream, "UTF-8")

                    var eventType = parser.eventType
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            val tagName = parser.name
                            val key = parser.getAttributeValue(null, "name")

                            if (key != null) {
                                when (tagName) {
                                    "string" -> preferencesMap[key] = parser.nextText()
                                    "boolean" -> preferencesMap[key] =
                                        parser.getAttributeValue(null, "value")?.toBoolean() ?: false
                                    "int" -> preferencesMap[key] =
                                        parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                                    "long" -> preferencesMap[key] =
                                        parser.getAttributeValue(null, "value")?.toLongOrNull() ?: 0L
                                    "float" -> preferencesMap[key] =
                                        parser.getAttributeValue(null, "value")?.toFloatOrNull() ?: 0f
                                    "set" -> {
                                        val values = linkedSetOf<String>()
                                        var nestedEvent = parser.next()
                                        while (!(nestedEvent == XmlPullParser.END_TAG && parser.name == "set")) {
                                            if (nestedEvent == XmlPullParser.START_TAG && parser.name == "string") {
                                                values.add(parser.nextText())
                                            }
                                            nestedEvent = parser.next()
                                        }
                                        preferencesMap[key] = values
                                    }
                                }
                            }
                        }
                        eventType = parser.next()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveData(mapToSave: Map<String, Any?>): Boolean {
        synchronized(lock) {
            return try {
                if (xmlFile.parentFile?.exists() == false && xmlFile.parentFile?.mkdirs() == false) {
                    return false
                }

                FileOutputStream(xmlFile).use { outputStream ->
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
                    outputStream.fd.sync()
                }
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
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

    override fun getStringSet(key: String?, defValues: Set<String?>?): Set<String?>? =
        synchronized(lock) {
            if (key == null) return@synchronized defValues
            val stored = preferencesMap[key] as? Set<*> ?: return@synchronized defValues
            stored.filterIsInstance<String>().toSet()
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
            val changedKeys = linkedSetOf<String>()
            val listenersSnapshot: List<SharedPreferences.OnSharedPreferenceChangeListener>

            synchronized(lock) {
                val updated = if (clearAll) {
                    changedKeys.addAll(preferencesMap.keys)
                    mutableMapOf()
                } else {
                    preferencesMap.toMutableMap()
                }

                for (key in keysToRemove) {
                    if (updated.remove(key) != null) changedKeys.add(key)
                }

                for ((key, value) in localChanges) {
                    val storedValue = if (value is Set<*>) value.toSet() else value
                    if (updated[key] != storedValue) {
                        updated[key] = storedValue
                        changedKeys.add(key)
                    }
                }

                if (!saveData(updated)) return false

                preferencesMap = updated
                listenersSnapshot = listeners.toList()
                localChanges.clear()
                keysToRemove.clear()
                clearAll = false
            }

            for (key in changedKeys) {
                listenersSnapshot.forEach { listener ->
                    listener.onSharedPreferenceChanged(this@CDSharedPreferences, key)
                }
            }
            return true
        }
    }
}
