package com.mindmatrix.lyricsync.data

import android.util.Xml
import com.mindmatrix.lyricsync.data.model.Line
import com.mindmatrix.lyricsync.data.model.Word
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

class TtmlParser {

    @Throws(Exception::class)
    fun parse(inputStream: InputStream): List<Line> {
        inputStream.use { stream ->
            // Disable namespace processing so we can read "ttm:agent" as a raw attribute name
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            val lines = mutableListOf<Line>()
            var currentLine: Line? = null
            var currentAgent: String? = null
            var currentRole: String? = null
            val words = mutableListOf<Word>()

            // State for current span being built
            var inSpan = false
            var spanBegin: Long? = null
            var spanEnd: Long? = null
            var spanAgent: String? = null
            var spanRole: String? = null
            val spanText = StringBuilder()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p" -> {
                                val begin = parser.getAttributeValue(null, "begin")
                                val end   = parser.getAttributeValue(null, "end")
                                currentAgent = parser.getAttributeValue(null, "ttm:agent")
                                    ?: parser.getAttributeValue(null, "agent")
                                currentRole  = parser.getAttributeValue(null, "ttm:role")
                                    ?: parser.getAttributeValue(null, "role")

                                currentLine = Line(
                                    words  = emptyList(),
                                    begin  = begin?.let { parseTime(it) },
                                    end    = end?.let   { parseTime(it) },
                                    agent  = currentAgent,
                                    role   = currentRole
                                )
                                words.clear()
                            }
                            "span" -> {
                                // Start accumulating span content
                                inSpan = true
                                spanText.clear()
                                val begin = parser.getAttributeValue(null, "begin")
                                val end   = parser.getAttributeValue(null, "end")
                                spanBegin = begin?.let { parseTime(it) }
                                spanEnd   = end?.let   { parseTime(it) }
                                spanAgent = parser.getAttributeValue(null, "ttm:agent")
                                    ?: parser.getAttributeValue(null, "agent")
                                    ?: currentAgent
                                spanRole  = parser.getAttributeValue(null, "ttm:role")
                                    ?: parser.getAttributeValue(null, "role")
                                    ?: currentRole
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        // Accumulate text inside spans (or plain text inside <p> for unsynced lines)
                        if (inSpan) {
                            spanText.append(parser.text)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "span" -> {
                                if (inSpan) {
                                    val text = spanText.toString().trim()
                                    if (text.isNotEmpty()) {
                                        words.add(
                                            Word(
                                                text  = text,
                                                begin = spanBegin,
                                                end   = spanEnd,
                                                agent = spanAgent,
                                                role  = spanRole
                                            )
                                        )
                                    }
                                    inSpan = false
                                    spanText.clear()
                                }
                            }
                            "p" -> {
                                currentLine?.let { line ->
                                    lines.add(line.copy(words = words.toList()))
                                }
                                currentAgent = null
                                currentRole  = null
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
            return lines
        }
    }

    private fun parseTime(time: String): Long {
        val parts   = time.split(":", ".")
        val hours   = parts[0].toLong()
        val minutes = parts[1].toLong()
        val seconds = parts[2].toLong()
        val milliseconds = parts.getOrNull(3)?.toLong() ?: 0
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + milliseconds
    }
}
