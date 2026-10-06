package com.wax.module.preference

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.wax.module.utils.FilePicker
import com.wax.module.utils.RealPathUtil
import com.wax.module.xposed.utils.Utils
import org.w3c.dom.Document
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class FileReaderPreference
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
        defStyleRes: Int = 0,
    ) : Preference(context, attrs, defStyleAttr, defStyleRes),
        Preference.OnPreferenceClickListener,
        FilePicker.OnFilePickedListener,
        FilePicker.OnUriPickedListener {
        private val xmlMimeType = arrayOf("text/xml", "application/xml")
        private var xmlContent: String? = null
        private var filePath: String? = null

        init {
            init(context)
        }

        override fun onPreferenceClick(preference: Preference): Boolean {
            // This preference uses Android's document picker. The returned URI carries
            // temporary read access, so READ_MEDIA_* and MANAGE_EXTERNAL_STORAGE are not
            // required just to choose and parse an XML file.
            FilePicker.setOnFilePickedListener(this)
            FilePicker.setOnUriPickedListener(this)
            FilePicker.fileCapture.launch(xmlMimeType)
            return true
        }

        override fun onFilePicked(file: File) {
            if (!file.canRead()) {
                Toast.makeText(context, R.string.unable_to_read_this_file, Toast.LENGTH_SHORT).show()
                return
            }
            processXmlFile(file)
        }

        override fun onUriPicked(uri: Uri) {
            Utils.executor.execute {
                try {
                    val realPath = RealPathUtil.getRealFilePath(context, uri)
                    if (realPath != null) {
                        val file = File(realPath)
                        processXmlFileInBg(file)
                    } else {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        if (inputStream != null) {
                            processXmlStreamInBg(inputStream, uri.lastPathSegment ?: "XML")
                        }
                    }
                } catch (e: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "Error processing XML file: " + e.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        private fun processXmlFile(file: File) {
            Utils.executor.execute {
                processXmlFileInBg(file)
            }
        }

        private fun processXmlFileInBg(file: File) {
            try {
                FileInputStream(file).use { fis ->
                    processXmlStreamInBg(fis, file.absolutePath)
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Error reading XML file: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun processXmlStreamInBg(
            inputStream: InputStream,
            path: String,
        ) {
            try {
                val dbFactory =
                    DocumentBuilderFactory.newInstance().apply {
                        isExpandEntityReferences = false
                    }
                val dBuilder = dbFactory.newDocumentBuilder()
                val doc: Document = dBuilder.parse(inputStream)
                doc.documentElement.normalize()

                val transformerFactory = TransformerFactory.newInstance()
                val transformer = transformerFactory.newTransformer()
                val source = DOMSource(doc)
                val writer = StringWriter()
                val result = StreamResult(writer)
                transformer.transform(source, result)

                val content = writer.toString()

                Handler(Looper.getMainLooper()).post {
                    this.xmlContent = content
                    this.filePath = path

                    sharedPreferences?.edit()?.putString(key, content)?.apply()
                    summary = path
                    Toast.makeText(context, "XML file loaded successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, "Error parsing XML: " + e.message, Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun init(context: Context) {
            onPreferenceClickListener = this

            val savedXml = PreferenceManager.getDefaultSharedPreferences(context).getString(key, null)
            if (savedXml != null) {
                xmlContent = savedXml
                summary = if (filePath != null) filePath else "XML content loaded"
            }
        }
    }
