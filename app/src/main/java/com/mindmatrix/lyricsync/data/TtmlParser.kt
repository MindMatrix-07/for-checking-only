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
            val parser: XmlPullParser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, null)

            val lines = mutableListOf<Line>()
            var currentLine: Line? = null
            val words = mutableListOf<Word>()

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "p" -> {
                                val begin = parser.getAttributeValue(null, "begin")
                                val end = parser.getAttributeValue(null, "end")
                                currentLine = Line(
                                    words = emptyList(),
                                    begin = begin?.let { parseTime(it) },
                                    end = end?.let { parseTime(it) }
                                )
                                words.clear()
                            }
                            "span" -> {
                                val begin = parser.getAttributeValue(null, "begin")
                                val end = parser.getAttributeValue(null, "end")
                                val text = parser.nextText()
                                if (text != null) {
                                    words.add(
                                        Word(
                                            text = text.trim(),
                                            begin = begin?.let { parseTime(it) },
                                            end = end?.let { parseTime(it) }
                                        )
                                    )
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "p") {
                            currentLine?.let { line ->
                                lines.add(line.copy(words = words.toList()))
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
        val parts = time.split(":", ".")
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val seconds = parts[2].toLong()
        val milliseconds = parts.getOrNull(3)?.toLong() ?: 0
        return (hours * 3600 + minutes * 60 + seconds) * 1000 + milliseconds
    }
}
