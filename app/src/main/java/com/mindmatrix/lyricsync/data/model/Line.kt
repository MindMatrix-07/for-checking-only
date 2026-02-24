package com.mindmatrix.lyricsync.data.model

data class Line(
    val words: List<Word>,
    var begin: Long? = null,   // in milliseconds
    var end: Long? = null,     // in milliseconds
    var agent: String? = null, // e.g. "v1", "v2" — maps to ttm:agent
    var role: String? = null   // e.g. "x-bg" — maps to ttm:role
)
