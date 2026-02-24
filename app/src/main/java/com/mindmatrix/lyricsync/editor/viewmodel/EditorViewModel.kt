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

// ── Agent metadata ────────────────────────────────────────────────────────────
/** Represents a single singer / vocal agent. */
data class AgentInfo(
    val id:          String,  // TTML xml:id, e.g. "v1", "v2", "v3"
    val displayName: String   // Human-readable, e.g. "Singer 1"
)

open class EditorViewModel : ViewModel() {

    private lateinit var exoPlayer: ExoPlayer
    private var progressJob: Job? = null
    private var audioUri: Uri? = null

    // ── Lyrics / playback state ───────────────────────────────────────────────
    var lines by mutableStateOf<List<Line>>(emptyList())
    var currentLineIndex by mutableIntStateOf(0)
    var currentWordIndex by mutableIntStateOf(0)
    var albumArt   by mutableStateOf<ByteArray?>(null)
    var songTitle  by mutableStateOf<String?>(null)
    var artistName by mutableStateOf<String?>(null)
    var albumName  by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
        private set
    var playbackPosition by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set

    /** Allows the user to manually update song metadata shown in the TTML head. */
    fun setMetadata(title: String?, artist: String?, album: String?) {
        if (!title.isNullOrBlank())  songTitle  = title.trim()
        if (!artist.isNullOrBlank()) artistName = artist.trim()
        if (!album.isNullOrBlank())  albumName  = album.trim()
    }

    // ── Active-sync agent / role  ─────────────────────────────────────────────
    var currentAgent by mutableStateOf("v1")
    var isBgVocal    by mutableStateOf(false)

    fun setAgent(agent: String) { currentAgent = agent }
    fun toggleBgVocal()         { isBgVocal = !isBgVocal }

    // ── Line selection state ──────────────────────────────────────────────────
    /** Indices of currently selected lines (multi-select). */
    var selectedLineIndices by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** True while the user is in tap-and-hold selection mode. */
    var isSelectionMode by mutableStateOf(false)
        private set

    /** Long-press on a line: enter selection mode and select that line. */
    fun enterSelectionMode(lineIndex: Int) {
        isSelectionMode     = true
        selectedLineIndices = setOf(lineIndex)
    }

    /** Tap on a line while in selection mode: toggle its selection. */
    fun toggleLineSelection(lineIndex: Int) {
        selectedLineIndices = if (lineIndex in selectedLineIndices) {
            selectedLineIndices - lineIndex
        } else {
            selectedLineIndices + lineIndex
        }
        if (selectedLineIndices.isEmpty()) isSelectionMode = false
    }

    /** Exit selection mode and clear all selections. */
    fun clearSelection() {
        selectedLineIndices = emptySet()
        isSelectionMode     = false
    }

    // ── Singer registry ───────────────────────────────────────────────────────
    /** All declared singers; the first two are always v1 / v2. */
    var singers by mutableStateOf(
        listOf(
            AgentInfo("v1", "Singer 1"),
            AgentInfo("v2", "Singer 2")
        )
    )
        private set

    /**
     * Adds a new singer with an auto-generated id ("v3", "v4", …).
     * Returns the new AgentInfo, or the existing one if [displayName] is already registered.
     */
    fun addSinger(displayName: String): AgentInfo {
        val existing = singers.firstOrNull { it.displayName.equals(displayName, ignoreCase = true) }
        if (existing != null) return existing
        val nextId  = "v${singers.size + 1}"
        val newInfo = AgentInfo(nextId, displayName)
        singers     = singers + newInfo
        return newInfo
    }

    // ── Popup actions ─────────────────────────────────────────────────────────
    /**
     * Tags all selected lines with [agentId] (ttm:agent).
     * Clears selection afterwards.
     */
    fun tagSelectedLinesWithAgent(agentId: String) {
        val updated = lines.mapIndexed { idx, line ->
            if (idx in selectedLineIndices) line.also { it.agent = agentId }
            else line
        }
        lines = updated
        clearSelection()
    }

    /**
     * Inserts a new translation line after [afterIndex].
     * TTML output: ttm:role="x-translation" — Flamingo displays this as a lyric translation.
     */
    fun insertTranslationLine(afterIndex: Int, text: String) {
        if (text.isEmpty()) { clearSelection(); return }
        val words      = text.split(" ").map { Word(it) }
        val parentAgent = lines.getOrNull(afterIndex)?.agent
        val trLine     = Line(words = words, agent = parentAgent, role = "x-translation")
        val mutable    = lines.toMutableList()
        mutable.add(afterIndex + 1, trLine)
        lines = mutable
        clearSelection()
    }

    /**
     * Inserts a new romanization line after [afterIndex].
     * TTML output: ttm:role="x-roman" — commonly used for phonetic versions.
     */
    fun insertRomanizationLine(afterIndex: Int, text: String) {
        if (text.isEmpty()) { clearSelection(); return }
        val words       = text.split(" ").map { Word(it) }
        val parentAgent = lines.getOrNull(afterIndex)?.agent
        val roLine      = Line(words = words, agent = parentAgent, role = "x-roman")
        val mutable     = lines.toMutableList()
        mutable.add(afterIndex + 1, roLine)
        lines = mutable
        clearSelection()
    }

    /**
     * Inserts a new background-vocal line after [afterIndex].
     * The line gets [role] = "x-bg" and inherits the agent of the target line (if any).
     * Timing is left unset so it will be synced normally later.
     */
    fun insertBackgroundLine(afterIndex: Int, text: String) {
        if (text.isEmpty()) { clearSelection(); return }

        val words       = text.split(" ").map { Word(it) }
        val parentAgent = lines.getOrNull(afterIndex)?.agent
        val bgLine      = Line(words = words, agent = parentAgent, role = "x-bg")

        val mutable = lines.toMutableList()
        mutable.add(afterIndex + 1, bgLine)
        lines = mutable
        clearSelection()
    }

    /**
     * In bulk selection mode, maps each line of [text] to a selected line index.
     * Sequential mapping: 1st line of text -> 1st selected index, etc.
     */
    fun bulkInsertSecondaryLines(indices: List<Int>, text: String, role: String) {
        val newLinesText = text.lines().filter { it.isNotBlank() }
        if (newLinesText.isEmpty() || indices.isEmpty()) { clearSelection(); return }

        val sortedIndices = indices.sortedDescending()
        val mutableLines  = lines.toMutableList()

        // Match indices to text lines sequentially (up to the minimum count of either)
        val pairCount = minOf(sortedIndices.size, newLinesText.size)
        // We use sortedIndices (ascending original positions) for sequential mapping
        val mappingIndices = indices.sorted() 

        // To avoid index shifting issues while inserting multiple lines,
        // we process from the end of the list (descending indices).
        // But the mapping should be 1st original line -> 1st selected.
        
        // Let's create the paired list first
        val pairs = mutableListOf<Pair<Int, String>>()
        for (i in 0 until pairCount) {
            pairs.add(mappingIndices[i] to newLinesText[i])
        }

        // Now sort pairs by index descending to insert without breaking index integrity
        pairs.sortByDescending { it.first }

        for (p in pairs) {
            val idx   = p.first
            val words = p.second.split(" ").map { Word(it) }
            val agent = lines.getOrNull(idx)?.agent
            val newLine = Line(words = words, agent = agent, role = role)
            mutableLines.add(idx + 1, newLine)
        }

        lines = mutableLines
        clearSelection()
    }

    // ── ExoPlayer listener ────────────────────────────────────────────────────
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@EditorViewModel.isPlaying = isPlaying
            progressJob?.cancel()
            if (isPlaying) {
                progressJob = viewModelScope.launch {
                    while (isActive) {
                        playbackPosition = exoPlayer.currentPosition
                        delay(16)
                    }
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!timeline.isEmpty) {
                val newDuration = exoPlayer.duration
                if (newDuration != C.TIME_UNSET) duration = newDuration
            }
        }
    }

    // ── Audio loading ─────────────────────────────────────────────────────────
    open fun loadAudio(context: Context, uri: Uri) {
        audioUri = uri
        if (::exoPlayer.isInitialized) exoPlayer.release()
        exoPlayer = ExoPlayer.Builder(context).build().apply { addListener(playerListener) }
        exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        exoPlayer.prepare()

        albumArt   = null
        songTitle  = null
        artistName = null
        albumName  = null
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            songTitle  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artistName = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            albumName  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (songTitle.isNullOrBlank()) {
                songTitle = getFileName(context, uri)?.substringBeforeLast('.')
            }
            albumArt = retriever.embeddedPicture
            val mediaDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            if (duration <= 0) duration = mediaDuration
            retriever.release()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            } finally { cursor?.close() }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }

    // ── Transport ─────────────────────────────────────────────────────────────
    open fun play()  { if (::exoPlayer.isInitialized) exoPlayer.play() }
    open fun pause() { if (::exoPlayer.isInitialized) exoPlayer.pause() }
    open fun seekTo(position: Long) {
        if (::exoPlayer.isInitialized) {
            exoPlayer.seekTo(position)
            playbackPosition = position
        }
    }

    // ── Lyrics loading ────────────────────────────────────────────────────────
    open fun loadLyrics(plainLyrics: String) {
        var lastAgent = "v1"
        lines = plainLyrics.lines().map { rawLine ->
            val trimmed = rawLine.trimStart()
            val (lineText, agent, role) = when {
                trimmed.startsWith("v1:", ignoreCase = true) -> {
                    lastAgent = "v1"
                    Triple(rawLine.trimStart().removePrefix("v1:").removePrefix("V1:"), "v1", null)
                }
                trimmed.startsWith("v2:", ignoreCase = true) -> {
                    lastAgent = "v2"
                    Triple(rawLine.trimStart().removePrefix("v2:").removePrefix("V2:"), "v2", null)
                }
                trimmed.startsWith("bg:", ignoreCase = true) ->
                    Triple(rawLine.trimStart().removePrefix("bg:").removePrefix("BG:"), lastAgent, "x-bg")
                trimmed.startsWith("tr:", ignoreCase = true) ->
                    Triple(rawLine.trimStart().removePrefix("tr:").removePrefix("TR:"), lastAgent, "x-translation")
                trimmed.startsWith("ro:", ignoreCase = true) ->
                    Triple(rawLine.trimStart().removePrefix("ro:").removePrefix("RO:"), lastAgent, "x-roman")
                else -> Triple(rawLine, lastAgent, null)
            }
            val words = if (lineText.isEmpty()) emptyList()
                        else lineText.split(" ").map { Word(it) }
            Line(words = words, agent = agent.takeIf { words.isNotEmpty() }, role = role)
        }
        currentLineIndex = 0
        currentWordIndex = 0
    }

    // ── Sync ──────────────────────────────────────────────────────────────────
open fun onLineSync() {
    if (!::exoPlayer.isInitialized || currentLineIndex >= lines.size) return
    val currentTime = exoPlayer.currentPosition
    val currentLine = lines[currentLineIndex]

    // Special case: Background, Translation, or Romanization lines
    // We sync these as a single unit (one tap for the whole line) for better UX.
    if (currentLine.role != null && currentWordIndex == 0) {
        currentLine.begin = currentTime
        currentLine.agent = currentAgent
        // If we manually toggled BG vocal chip, override role if it was null
        if (currentLine.role == null && isBgVocal) currentLine.role = "x-bg"
        
        currentLine.words.forEach {
            it.begin = currentTime
            it.end   = currentTime + 200 // Default 200ms words for block lines
        }
        
        handlePreviousLinesTiming(currentTime)
        
        currentLineIndex++
        currentWordIndex = 0
        lines = lines.toList()
        return
    }

    if (currentWordIndex < currentLine.words.size) {
        val word = currentLine.words[currentWordIndex]
        word.begin = currentTime
        if (currentWordIndex == 0) {
            currentLine.begin = currentTime
            currentLine.agent = currentAgent
            currentLine.role  = if (isBgVocal) "x-bg" else null
            handlePreviousLinesTiming(currentTime)
        } else {
            currentLine.words[currentWordIndex - 1].end = currentTime
        }
        currentWordIndex++
        if (currentWordIndex == currentLine.words.size) {
            val nextIsBlank = (currentLineIndex + 1 < lines.size && lines[currentLineIndex + 1].words.isEmpty())
            if (!nextIsBlank && currentLineIndex + 1 < lines.size) {
                currentLineIndex++; currentWordIndex = 0
            }
        }
    } else if (currentLine.end == null) {
        currentLine.end = currentTime
        if (currentLine.words.isNotEmpty()) currentLine.words.last().end = currentTime
    } else {
        var nextLineIndex = currentLineIndex + 1
        while (nextLineIndex < lines.size && lines[nextLineIndex].words.isEmpty()) {
            val emptyLine = lines[nextLineIndex]
            emptyLine.begin = currentTime; emptyLine.end = currentTime
            nextLineIndex++
        }
        if (nextLineIndex < lines.size) {
            currentLineIndex = nextLineIndex; currentWordIndex = 0
            val newLine = lines[currentLineIndex]
            newLine.begin = currentTime; newLine.agent = currentAgent
            newLine.role  = if (isBgVocal) "x-bg" else null
            newLine.words[0].begin = currentTime; currentWordIndex = 1
        } else {
            currentLineIndex = nextLineIndex
        }
    }
    lines = lines.toList()
}

/** Helper to close timing on preceding lines when a new line starts. */
private fun handlePreviousLinesTiming(currentTime: Long) {
    var prevIndex = currentLineIndex - 1
    while (prevIndex >= 0) {
        val prevLine = lines[prevIndex]
        if (prevLine.end == null) {
            prevLine.end = currentTime
            if (prevLine.words.isNotEmpty() && prevLine.words.last().end == null)
                prevLine.words.last().end = currentTime
        }
        if (prevLine.words.isNotEmpty()) break
        prevIndex--
    }
}

// ── Undo ──────────────────────────────────────────────────────────────────
open fun undoLastSync() {
        if (currentWordIndex > 0) {
            currentWordIndex--
            val line = lines[currentLineIndex]
            val word = line.words[currentWordIndex]
            word.begin = null; word.end = null
            if (currentWordIndex == 0) { line.begin = null; line.agent = null; line.role = null }
            lines = lines.toList(); return
        }
        var prevLineIndex = currentLineIndex - 1
        while (prevLineIndex >= 0 && lines[prevLineIndex].words.isEmpty()) prevLineIndex--
        if (prevLineIndex >= 0) {
            currentLineIndex = prevLineIndex
            currentWordIndex = lines[prevLineIndex].words.size
            undoLastSync()
        }
    }

    // ── Jump / calibrate ──────────────────────────────────────────────────────
    open fun jumpToWord(lineIndex: Int, wordIndex: Int) {
        if (lineIndex in lines.indices) {
            currentLineIndex = lineIndex
            val line = lines[lineIndex]
            if (wordIndex in 0..line.words.size) currentWordIndex = wordIndex
        }
    }

    open fun calibrateSync(offsetMs: Long) {
        lines.forEach { line ->
            line.begin = line.begin?.let { (it + offsetMs).coerceAtLeast(0) }
            line.end   = line.end?.let   { (it + offsetMs).coerceAtLeast(0) }
            line.words.forEach { word ->
                word.begin = word.begin?.let { (it + offsetMs).coerceAtLeast(0) }
                word.end   = word.end?.let   { (it + offsetMs).coerceAtLeast(0) }
            }
        }
        lines = lines.toList()
    }

    // ── Tagging ───────────────────────────────────────────────────────────────
    open fun tagAudioWithTtml(context: Context, ttmlContent: String, onComplete: (Boolean) -> Unit) {
        val uri = audioUri ?: return onComplete(false)
        viewModelScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                val minifiedTtml = ttmlContent.replace(Regex(">\\s+<"), "><")
                val fileName     = getFileName(context, uri) ?: "audio.mp3"
                val extension    = fileName.substringAfterLast('.', "mp3")

                tempFile = File(context.cacheDir, "tagging_${System.currentTimeMillis()}.$extension")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile!!.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("Could not open input stream")

                val audioFile = AudioFileIO.read(tempFile!!)
                val tag       = audioFile.tagOrCreateDefault

                if (extension.lowercase() == "mp3" || extension.lowercase() == "wav") {
                    if (tag is AbstractID3v2Tag) {
                        val fields = tag.getFields("TXXX"); val iterator = fields.iterator()
                        while (iterator.hasNext()) {
                            val body = (iterator.next() as? AbstractID3v2Frame)?.body as? FrameBodyTXXX
                            if (body?.description == "LYRICS") iterator.remove()
                        }
                        val txxxBody = FrameBodyTXXX(TextEncoding.UTF_8, "LYRICS", minifiedTtml)
                        val newFrame = if (tag is ID3v24Tag) ID3v24Frame("TXXX") else ID3v23Frame("TXXX")
                        newFrame.body = txxxBody; tag.setField(newFrame)
                    } else tag.setField(FieldKey.LYRICS, minifiedTtml)
                } else tag.setField(FieldKey.LYRICS, minifiedTtml)

                audioFile.commit()

                try {
                    context.contentResolver.openOutputStream(uri, "rwt")?.use { output ->
                        tempFile!!.inputStream().use { it.copyTo(output) }
                    } ?: throw IOException("Could not open output stream")
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException)
                        Log.e("EditorViewModel", "Permission required")
                    throw e
                }

                saveSidecarTtml(context, uri, minifiedTtml)
                withContext(Dispatchers.Main) { onComplete(true) }
            } catch (e: Exception) {
                Log.e("EditorViewModel", "Tagging failed", e)
                withContext(Dispatchers.Main) { onComplete(false) }
            } finally {
                try { tempFile?.delete() } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    private fun saveSidecarTtml(context: Context, audioUri: Uri, content: String) {
        try {
            val resolver   = context.contentResolver
            val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            resolver.query(audioUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName  = cursor.getString(0) ?: "audio.mp3"
                    val relativePath = cursor.getString(1) ?: "Music/"
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "${displayName.substringBeforeLast('.')}.ttml")
                        put(MediaStore.MediaColumns.MIME_TYPE,    "application/ttml+xml")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    }
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else
                        MediaStore.Files.getContentUri("external")
                    resolver.insert(collection, values)?.let { newUri ->
                        resolver.openOutputStream(newUri)?.use { it.write(content.toByteArray()) }
                    }
                }
            }
        } catch (e: Exception) { Log.e("EditorViewModel", "Sidecar save failed", e) }
    }

    open fun saveFile(context: Context, uri: Uri, content: String) {
        try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
        } catch (e: IOException) { e.printStackTrace() }
    }

    override fun onCleared() {
        super.onCleared()
        if (::exoPlayer.isInitialized) {
            exoPlayer.removeListener(playerListener)
            exoPlayer.release()
        }
    }
}
