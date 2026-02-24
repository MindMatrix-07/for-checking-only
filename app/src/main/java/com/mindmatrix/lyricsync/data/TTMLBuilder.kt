package com.mindmatrix.lyricsync.data

import com.mindmatrix.lyricsync.data.model.Line
import java.io.StringWriter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class TtmlBuilder {

    fun build(lines: List<Line>): String {
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()

        val doc = docBuilder.newDocument()
        val rootElement = doc.createElement("tt")
        rootElement.setAttribute("xmlns", "http://www.w3.org/ns/ttml")
        doc.appendChild(rootElement)

        val headElement = doc.createElement("head")
        rootElement.appendChild(headElement)

        val stylingElement = doc.createElement("styling")
        headElement.appendChild(stylingElement)

        val styleElement = doc.createElement("style")
        styleElement.setAttribute("xml:id", "s1")
        styleElement.setAttribute("tts:color", "white")
        styleElement.setAttribute("tts:textAlign", "center")
        stylingElement.appendChild(styleElement)

        val layoutElement = doc.createElement("layout")
        headElement.appendChild(layoutElement)

        val regionElement = doc.createElement("region")
        regionElement.setAttribute("xml:id", "r1")
        layoutElement.appendChild(regionElement)

        val bodyElement = doc.createElement("body")
        bodyElement.setAttribute("style", "s1")
        bodyElement.setAttribute("region", "r1")
        rootElement.appendChild(bodyElement)

        val divElement = doc.createElement("div")
        bodyElement.appendChild(divElement)

        for (line in lines) {
            val pElement = doc.createElement("p")
            pElement.setAttribute("begin", formatTime(line.begin ?: 0))
            pElement.setAttribute("end", formatTime(line.end ?: 0))

            var lineText = ""
            for (word in line.words) {
                if (word.begin != null && word.end != null) {
                    val spanElement = doc.createElement("span")
                    spanElement.setAttribute("begin", formatTime(word.begin!!))
                    spanElement.setAttribute("end", formatTime(word.end!!))
                    spanElement.appendChild(doc.createTextNode(word.text + " "))
                    pElement.appendChild(spanElement)
                } else {
                    lineText += word.text + " "
                }
            }
            if (lineText.isNotEmpty()) {
                pElement.appendChild(doc.createTextNode(lineText.trim()))
            }

            divElement.appendChild(pElement)
        }

        val transformerFactory = TransformerFactory.newInstance()
        val transformer = transformerFactory.newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val stringWriter = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(stringWriter))
        return stringWriter.buffer.toString()
    }

    companion object {
        fun formatTime(millis: Long): String {
            val hours = TimeUnit.MILLISECONDS.toHours(millis)
            val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
            val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
            val hundredths = (millis % 1000) / 10
            return if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d.%02d", hours, minutes, seconds, hundredths)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d.%02d", minutes, seconds, hundredths)
            }
        }
    }
}
