package com.mindmatrix.lyricsync.data.model

data class Line(
    val words: List<Word>,
    var begin: Long? = null, // in milliseconds
    var end: Long? = null // in milliseconds
)
