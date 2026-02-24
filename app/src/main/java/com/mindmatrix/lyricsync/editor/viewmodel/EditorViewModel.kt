package com.mindmatrix.lyricsync.editor.viewmodel

import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.mindmatrix.lyricsync.data.model.Line
import com.mindmatrix.lyricsync.data.model.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.ID3v23Frame
import org.jaudiotagger.tag.id3.ID3v24Frame
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
import java.io.File
import java.io.IOException

open class EditorViewModel : ViewModel() {

    private lateinit var exoPlayer: ExoPlayer
    private var progressJob: Job? = null
    private var audioUri: Uri? = null

    // State
    var lines by mutableStateOf<List<Line>>(emptyList())
    var currentLineIndex by mutableIntStateOf(0)
    var currentWordIndex by mutableIntStateOf(0)
    var albumArt by mutableStateOf<ByteArray?>(null)
    var songTitle by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
        private set
    var playbackPosition by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@EditorViewModel.isPlaying = isPlaying
            progressJob?.cancel()
            if (isPlaying) {
                progressJob = viewModelScope.launch {
                    while (isActive) {
                        playbackPosition = exoPlayer.currentPosition
                        delay(16) // ~60fps update for "zero latency" feel in progress bar
                    }
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!timeline.isEmpty) {
                val newDuration = exoPlayer.duration
                if (newDuration != C.TIME_UNSET) {
                    duration = newDuration
                }
            }
        }
    }

    open fun loadAudio(context: Context, uri: Uri) {
        audioUri = uri
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(playerListener)
        }

        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()

        albumArt = null
        songTitle = null
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            
            // Try to get title from metadata
            songTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            
            // If no title in metadata, get the file name
            if (songTitle.isNullOrBlank()) {
                songTitle = getFileName(context, uri)
                // Remove file extension if present
                songTitle = songTitle?.substringBeforeLast('.')
            }

            albumArt = retriever.embeddedPicture
            val mediaDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            if (duration <= 0) { // Use as a fallback
                duration = mediaDuration
            }
            retriever.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    open fun play() {
        if (::exoPlayer.isInitialized) exoPlayer.play()
    }

    open fun pause() {
        if (::exoPlayer.isInitialized) exoPlayer.pause()
    }

    open fun seekTo(position: Long) {
        if (::exoPlayer.isInitialized) {
            exoPlayer.seekTo(position)
            playbackPosition = position // Manually update position after seek
        }
    }

    open fun loadLyrics(plainLyrics: String) {
        val lyricLines = plainLyrics.lines()
        lines = lyricLines.map { lineText ->
            val words = if (lineText.trim().isEmpty()) {
                emptyList()
            } else {
                lineText.trim().split(Regex("\\s+")).map { Word(it) }
            }
            Line(words = words)
        }
        currentLineIndex = 0
        currentWordIndex = 0
    }

    open fun onLineSync() {
        if (!::exoPlayer.isInitialized || currentLineIndex >= lines.size) return

        val currentTime = exoPlayer.currentPosition
        val currentLine = lines[currentLineIndex]

        if (currentWordIndex < currentLine.words.size) {
            val word = currentLine.words[currentWordIndex]
            word.begin = currentTime

            if (currentWordIndex == 0) {
                currentLine.begin = currentTime
                var prevIndex = currentLineIndex - 1
                while (prevIndex >= 0) {
                    val prevLine = lines[prevIndex]
                    if (prevLine.end == null) {
                        prevLine.end = currentTime
                        if (prevLine.words.isNotEmpty() && prevLine.words.last().end == null) {
                            prevLine.words.last().end = currentTime
                        }
                    }
                    if (prevLine.words.isNotEmpty()) break
                    prevIndex--
                }
            } else {
                currentLine.words[currentWordIndex - 1].end = currentTime
            }

            currentWordIndex++

            if (currentWordIndex == currentLine.words.size) {
                val nextLineIsBlank = (currentLineIndex + 1 < lines.size && lines[currentLineIndex + 1].words.isEmpty())
                if (!nextLineIsBlank && currentLineIndex + 1 < lines.size) {
                    currentLineIndex++
                    currentWordIndex = 0
                }
            }
        } else if (currentLine.end == null) {
            currentLine.end = currentTime
            if (currentLine.words.isNotEmpty()) {
                currentLine.words.last().end = currentTime
            }
        } else {
            var nextLineIndex = currentLineIndex + 1
            while (nextLineIndex < lines.size && lines[nextLineIndex].words.isEmpty()) {
                val emptyLine = lines[nextLineIndex]
                emptyLine.begin = currentTime
                emptyLine.end = currentTime
                nextLineIndex++
            }

            if (nextLineIndex < lines.size) {
                currentLineIndex = nextLineIndex
                currentWordIndex = 0
                val newLine = lines[currentLineIndex]
                newLine.begin = currentTime
                newLine.words[0].begin = currentTime
                currentWordIndex = 1
            } else {
                currentLineIndex = nextLineIndex
            }
        }

        lines = lines.toList() 
    }

    open fun undoLastSync() {
        if (currentWordIndex > 0) {
            currentWordIndex--
            val line = lines[currentLineIndex]
            val word = line.words[currentWordIndex]
            word.begin = null
            word.end = null
            if (currentWordIndex == 0) {
                line.begin = null
            }
            lines = lines.toList()
            return
        }

        var prevLineIndex = currentLineIndex - 1
        while (prevLineIndex >= 0 && lines[prevLineIndex].words.isEmpty()) {
            prevLineIndex--
        }

        if (prevLineIndex >= 0) {
            currentLineIndex = prevLineIndex
            currentWordIndex = lines[prevLineIndex].words.size 
            undoLastSync() 
        }
    }

    open fun jumpToWord(lineIndex: Int, wordIndex: Int) {
        if (lineIndex in lines.indices) {
            currentLineIndex = lineIndex
            val line = lines[lineIndex]
            if (wordIndex in 0..line.words.size) {
                currentWordIndex = wordIndex
            }
        }
    }

    open fun calibrateSync(offsetMs: Long) {
        lines.forEach { line ->
            line.begin = line.begin?.let { (it + offsetMs).coerceAtLeast(0) }
            line.end = line.end?.let { (it + offsetMs).coerceAtLeast(0) }
            line.words.forEach { word ->
                word.begin = word.begin?.let { (it + offsetMs).coerceAtLeast(0) }
                word.end = word.end?.let { (it + offsetMs).coerceAtLeast(0) }
            }
        }
        lines = lines.toList() 
    }

    open fun tagAudioWithTtml(context: Context, ttmlContent: String, onComplete: (Boolean) -> Unit) {
        val uri = audioUri ?: return onComplete(false)
        
        viewModelScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                // 1. Minification using Regex to strip whitespace between tags
                val minifiedTtml = ttmlContent.replace(Regex(">\\s+<"), "><")
                
                val fileName = getFileName(context, uri) ?: "audio.mp3"
                val extension = fileName.substringAfterLast('.', "mp3")

                // 2. Create a temporary file with the original extension for JAudioTagger format detection
                tempFile = File(context.cacheDir, "tagging_${System.currentTimeMillis()}.$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile!!.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Could not open input stream")

                // 3. Load the file using jaudiotagger
                val audioFile = AudioFileIO.read(tempFile!!)
                val tag = audioFile.tagOrCreateDefault
                
                // 4. Implement specific tagging logic based on format
                if (extension.lowercase() == "mp3" || extension.lowercase() == "wav") {
                    if (tag is AbstractID3v2Tag) {
                        // FIX: Manual iteration to remove specific TXXX frames with description 'LYRICS'
                        val fields = tag.getFields("TXXX")
                        val iterator = fields.iterator()
                        while (iterator.hasNext()) {
                            val tagField = iterator.next()
                            val body = (tagField as? AbstractID3v2Frame)?.body as? FrameBodyTXXX
                            if (body?.description == "LYRICS") {
                                iterator.remove()
                            }
                        }

                        // Add the new TTML Frame with UTF-8 encoding for full character support
                        val txxxBody = FrameBodyTXXX(TextEncoding.UTF_8, "LYRICS", minifiedTtml)
                        val newFrame = if (tag is ID3v24Tag) ID3v24Frame("TXXX") else ID3v23Frame("TXXX")
                        newFrame.body = txxxBody
                        tag.setField(newFrame)
                    } else {
                        // Fallback for non-ID3v2 tags
                        tag.setField(FieldKey.LYRICS, minifiedTtml)
                    }
                } else {
                    // Standard LYRICS field for M4A (©lyr), FLAC (Vorbis), OGG
                    tag.setField(FieldKey.LYRICS, minifiedTtml)
                }
                
                audioFile.commit()

                // 5. Commit changes back to original file using Scoped Storage optimized "rwt" mode
                try {
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                        tempFile!!.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    } ?: throw IOException("Could not open output stream for writing")
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                        Log.e("EditorViewModel", "Modification permission required from user")
                    }
                    throw e
                }

                // 6. Save sidecar .ttml file in same folder automatically
                saveSidecarTtml(context, uri, minifiedTtml)

                withContext(Dispatchers.Main) { onComplete(true) }
            } catch (e: Exception) {
                Log.e("EditorViewModel", "Universal tagging failed", e)
                withContext(Dispatchers.Main) { onComplete(false) }
            } finally {
                try {
                    tempFile?.delete()
                } catch (e: Exception) {
                    Log.e("EditorViewModel", "Error cleaning up temp file", e)
                }
            }
        }
    }

    private fun saveSidecarTtml(context: Context, audioUri: Uri, content: String) {
        try {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            
            resolver.query(audioUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(0) ?: "audio.mp3"
                    val audioName = displayName.substringBeforeLast('.')
                    val relativePath = cursor.getString(1) ?: "Music/"
                    
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "$audioName.ttml")
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/ttml+xml")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Files.getContentUri("external")
                    }
                    
                    val newUri = resolver.insert(collection, values)
                    if (newUri != null) {
                        resolver.openOutputStream(newUri)?.use { output ->
                            output.write(content.toByteArray())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EditorViewModel", "Failed to save sidecar TTML", e)
        }
    }

    open fun saveFile(context: Context, uri: Uri, content: String) {
        try {
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(content.toByteArray())
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (::exoPlayer.isInitialized) {
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
    }
}
