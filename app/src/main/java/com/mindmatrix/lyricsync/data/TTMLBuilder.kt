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

    /**
     * Builds a TTML string from [lines], embedding full Flamingo-compatible metadata:
     *  - Song title, artist, album in <ttm:title> / <ttm:desc> / <ttm:copyright>
     *  - <translations> list for every distinct translation language declared on lines
     *  - <ttm:agent> declarations for every distinct singer found in lines
     *  - ttm:agent + ttm:role on every <p> and <span>
     */
    fun build(
        lines:      List<Line>,
        title:      String? = null,
        artist:     String? = null,
        album:      String? = null,
    ): String {
        val docFactory = DocumentBuilderFactory.newInstance()
        val docBuilder = docFactory.newDocumentBuilder()
        val doc        = docBuilder.newDocument()

        // ── Root <tt> ─────────────────────────────────────────────────────────
        val root = doc.createElement("tt")
        root.setAttribute("xmlns",     "http://www.w3.org/ns/ttml")
        root.setAttribute("xmlns:ttm", "http://www.w3.org/ns/ttml#metadata")
        root.setAttribute("xmlns:tts", "http://www.w3.org/ns/ttml#styling")
        doc.appendChild(root)

        // ── <head> ────────────────────────────────────────────────────────────
        val head = doc.createElement("head")
        root.appendChild(head)

        // ── <metadata> ────────────────────────────────────────────────────────
        val metadata = doc.createElement("metadata")
        head.appendChild(metadata)

        // Song title
        if (!title.isNullOrBlank()) {
            val el = doc.createElement("ttm:title")
            el.appendChild(doc.createTextNode(title))
            metadata.appendChild(el)
        }

        // Artist as <ttm:desc>
        if (!artist.isNullOrBlank()) {
            val el = doc.createElement("ttm:desc")
            el.appendChild(doc.createTextNode(artist))
            metadata.appendChild(el)
        }

        // Album as <ttm:copyright>
        if (!album.isNullOrBlank()) {
            val el = doc.createElement("ttm:copyright")
            el.appendChild(doc.createTextNode(album))
            metadata.appendChild(el)
        }

        // Agent declarations — one per distinct agent found in lines
        val distinctAgents = lines.mapNotNull { it.agent }.distinct()
        for (agentId in distinctAgents) {
            val agentEl = doc.createElement("ttm:agent")
            agentEl.setAttribute("xml:id", agentId)
            agentEl.setAttribute("type", "person")
            val nameEl = doc.createElement("ttm:name")
            nameEl.setAttribute("type", "full")
            nameEl.appendChild(doc.createTextNode(agentLabel(agentId)))
            agentEl.appendChild(nameEl)
            metadata.appendChild(agentEl)
        }

        // <translations> – Flamingo-specific: list any translation lines
        // (lines with role "x-translation"). We declare the block so the player
        // knows the file contains embedded translations.
        val hasTranslations = lines.any { it.role == "x-translation" }
        if (hasTranslations) {
            val translationsEl = doc.createElement("translations")
            // Flamingo expects at least one <translation> child; we use a generic entry.
            val tEl = doc.createElement("translation")
            tEl.setAttribute("lang", "default")
            translationsEl.appendChild(tEl)
            metadata.appendChild(translationsEl)
        }

        // ── <styling> ─────────────────────────────────────────────────────────
        val styling = doc.createElement("styling")
        head.appendChild(styling)
        val style = doc.createElement("style")
        style.setAttribute("xml:id",        "s1")
        style.setAttribute("tts:color",     "white")
        style.setAttribute("tts:textAlign", "center")
        styling.appendChild(style)

        // ── <layout> ──────────────────────────────────────────────────────────
        val layout = doc.createElement("layout")
        head.appendChild(layout)
        val region = doc.createElement("region")
        region.setAttribute("xml:id", "r1")
        layout.appendChild(region)

        // ── <body> ────────────────────────────────────────────────────────────
        val body = doc.createElement("body")
        body.setAttribute("style",  "s1")
        body.setAttribute("region", "r1")
        root.appendChild(body)

        val div = doc.createElement("div")
        body.appendChild(div)

        for (line in lines) {
            val p = doc.createElement("p")
            val lineBegin = line.begin ?: 0L
            val lineEnd   = line.end   ?: 0L
            p.setAttribute("begin", formatTime(lineBegin))
            p.setAttribute("end",   formatTime(lineEnd))
            line.agent?.let { p.setAttribute("ttm:agent", it) }
            line.role?.let  { 
                p.setAttribute("ttm:role", it)
                p.setAttribute("role", it) // Dual attribute for compatibility
            }

            for (word in line.words) {
                if (word.begin != null) {
                    val span = doc.createElement("span")
                    span.setAttribute("begin", formatTime(word.begin!!))
                    // Fallback word end to line end if word.end is null
                    val wordEnd = word.end ?: lineEnd
                    span.setAttribute("end",   formatTime(wordEnd))
                    
                    (word.agent ?: line.agent)?.let { span.setAttribute("ttm:agent", it) }
                    (word.role  ?: line.role)?.let  { 
                        span.setAttribute("ttm:role", it)
                        span.setAttribute("role", it)
                    }
                    span.appendChild(doc.createTextNode(word.text + " "))
                    p.appendChild(span)
                } else {
                    // Unsynced words show for the whole line duration
                    p.appendChild(doc.createTextNode(word.text + " "))
                }
            }
            div.appendChild(p)
        }

        // ── Serialise ─────────────────────────────────────────────────────────
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        val sw = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(sw))
        return sw.buffer.toString()
    }

    companion object {
        fun agentLabel(id: String): String = when (id) {
            "v1" -> "Singer 1"
            "v2" -> "Singer 2"
            else -> id.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }

        fun formatTime(millis: Long): String {
            val hours      = TimeUnit.MILLISECONDS.toHours(millis)
            val minutes    = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
            val seconds    = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
            val hundredths = (millis % 1000) / 10
            return if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d.%02d", hours, minutes, seconds, hundredths)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d.%02d", minutes, seconds, hundredths)
            }
        }
    }
}
