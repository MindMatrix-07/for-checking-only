package com.mindmatrix.lyricsync.data.model

data class Word(
    val text: String,
    var begin: Long? = null, // in milliseconds
    var end: Long? = null // in milliseconds
)
