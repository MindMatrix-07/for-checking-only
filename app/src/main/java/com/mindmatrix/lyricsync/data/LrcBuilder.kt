package com.mindmatrix.lyricsync.data

import com.mindmatrix.lyricsync.data.model.Line
import java.util.concurrent.TimeUnit

class LrcBuilder {
    fun build(lines: List<Line>): String {
        val sb = StringBuilder()
        lines.forEach { line ->
            line.begin?.let { begin ->
                val time = formatTime(begin)
                val text = line.words.joinToString(" ") { it.text }
                sb.append("[$time]$text\n")
            }
        }
        return sb.toString()
    }

    private fun formatTime(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        val hundredths = (millis % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, hundredths)
    }
}
