package com.mindmatrix.lyricsync.editor

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mindmatrix.lyricsync.data.TtmlBuilder
import com.mindmatrix.lyricsync.data.model.Line
import com.mindmatrix.lyricsync.data.model.Word
import com.mindmatrix.lyricsync.editor.viewmodel.AgentInfo
import com.mindmatrix.lyricsync.editor.viewmodel.EditorViewModel
import kotlinx.coroutines.launch

// ── Theme colours ─────────────────────────────────────────────────────────────
private val charcoal       = Color(0xFF121212)
private val accentV1       = Color(0xFF4CAF50) // Green for Singer 1
private val accentV2       = Color(0xFF9C27B0) // Purple/Violet for Singer 2
private val accentV3       = Color(0xFF2196F3) // Blue
private val accentV4       = Color(0xFFFF9800) // Orange
private val accentV5       = Color(0xFFE91E63) // Pink
private val accentV6       = Color(0xFF009688) // Teal
private val accentV7       = Color(0xFFFFC107) // Amber
private val accentV8       = Color(0xFFFF5722) // Deep Orange

private val accentBg       = Color(0xFFFF9800) // Orange for background
private val accentTranslation = Color(0xFF00BCD4)
private val accentRoman    = Color(0xFFFFEB3B) // Yellow for romanisation
private val greenSynced    = Color(0xFF4CAF50)
private val selectionTint  = Color(0xFF2979FF)

private fun getSingerColor(agent: String?): Color {
    if (agent == null) return Color.White
    val firstAgent = agent.split(" ").firstOrNull() ?: return Color.White
    return when (firstAgent) {
        "v1" -> accentV1
        "v2" -> accentV2
        "v3" -> accentV3
        "v4" -> accentV4
        "v5" -> accentV5
        "v6" -> accentV6
        "v7" -> accentV7
        "v8" -> accentV8
        else -> accentV2 // Default to Purple
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: EditorViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = charcoal) {
                    EditorScreen(viewModel)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────
/** Build TTML passing all collected metadata from the ViewModel. */
private fun buildTtml(vm: EditorViewModel) =
    TtmlBuilder().build(
        lines  = vm.lines,
        title  = vm.songTitle,
        artist = vm.artistName,
        album  = vm.albumName
    )

// ─────────────────────────────────────────────────────────────────────────────
//  EditorScreen
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context          = LocalContext.current
    val lines            = viewModel.lines
    val currentLineIndex = viewModel.currentLineIndex
    val currentWordIndex = viewModel.currentWordIndex
    val playbackPosition = viewModel.playbackPosition
    val albumArt         = viewModel.albumArt
    val songTitle        = viewModel.songTitle
    val isSelectionMode  = viewModel.isSelectionMode
    val selectedIndices  = viewModel.selectedLineIndices

    var showLyricsDialog      by remember { mutableStateOf(false) }
    var showMetadataDialog    by remember { mutableStateOf(false) }
    var showTagSingerDialog   by remember { mutableStateOf(false) }
    var showAddBgDialog       by remember { mutableStateOf(false) }
    var showAddTranslationDialog by remember { mutableStateOf(false) }
    var showAddRomanizationDialog by remember { mutableStateOf(false) }
    var showEditLineDialog       by remember { mutableStateOf(false) }
    var showEditChoiceDialog     by remember { mutableStateOf(false) }
    var showEditSyncDialog       by remember { mutableStateOf(false) }
    var editSessionLines         by remember { mutableStateOf<List<Line>>(emptyList()) }
    var editSessionStartIndex    by remember { mutableIntStateOf(-1) }
    var editingLineIndex         by remember { mutableIntStateOf(-1) }
    var showBgModeDialog         by remember { mutableStateOf(false) }
    var showBgSingerDialog       by remember { mutableStateOf(false) }
    var bgFlowText               by remember { mutableStateOf("") }
    var bgFlowIsConvert          by remember { mutableStateOf(false) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { e.printStackTrace() }
            viewModel.loadAudio(context, it)
        }
    }

    val fileSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/ttml")) { uri: Uri? ->
        uri?.let {
            val content = buildTtml(viewModel)
            viewModel.saveFile(context, it, content)
            viewModel.tagAudioWithTtml(context, content) { success ->
                Toast.makeText(context, if (success) "Lyrics embedded!" else "Error embedding", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val ttmlPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            viewModel.importTtml(context, it)
            showLyricsDialog = false // Close dialog after successful import
        }
    }

    // Dialogs
    if (showLyricsDialog) {
        LyricsInputDialog(
            songTitle          = songTitle,
            rawLyrics          = viewModel.rawLyrics,
            allowBgWordSync    = viewModel.allowBgWordSync,
            onCalibrate        = { viewModel.calibrateSync(it) },
            onImport = {
                showLyricsDialog = false // Dismiss dialog FIRST before launching picker
                ttmlPicker.launch(arrayOf("*/*"))
            },
            onDismiss = { showLyricsDialog = false },
            onConfirm = { lyrics, bgSync ->
                viewModel.rawLyrics = lyrics
                viewModel.allowBgWordSync = bgSync
                viewModel.loadLyrics(lyrics)
                showLyricsDialog = false
            }
        )
    }

    if (showMetadataDialog) {
        MetadataDialog(
            initialTitle  = viewModel.songTitle  ?: "",
            initialArtist = viewModel.artistName ?: "",
            initialAlbum  = viewModel.albumName  ?: "",
            onConfirm = { t, ar, al ->
                viewModel.setMetadata(t, ar, al)
                showMetadataDialog = false
            },
            onDismiss = { showMetadataDialog = false }
        )
    }

    if (showTagSingerDialog) {
        TagSingerDialog(
            singers     = viewModel.singers,
            onAddSinger = { name -> viewModel.addSinger(name) },
            onConfirm   = { agentId -> viewModel.tagSelectedLinesWithAgent(agentId); showTagSingerDialog = false },
            onDismiss   = { showTagSingerDialog = false }
        )
    }

    if (showEditLineDialog && editingLineIndex in lines.indices) {
        val lineToEdit = lines[editingLineIndex]
        val initialText = lineToEdit.words.joinToString(" ") { it.text }
        EditLineDialog(
            initialText = initialText,
            onConfirm   = { newText ->
                viewModel.editLineText(editingLineIndex, newText)
                showEditLineDialog = false
            },
            onDismiss   = { showEditLineDialog = false }
        )
    }

    if (showBgModeDialog) {
        MultiChoiceDialog(
            title   = "Background Line",
            options = listOf("Create New Line", "Convert Selected to BG"),
            onChoice = { choice ->
                showBgModeDialog = false
                if (choice == 0) {
                    showAddBgDialog = true // Flow to text input
                } else {
                    bgFlowIsConvert = true
                    showBgSingerDialog = true // Flow to singer selection
                }
            },
            onDismiss = { showBgModeDialog = false }
        )
    }

    if (showBgSingerDialog) {
        TagSingerDialog(
            singers     = viewModel.singers,
            onAddSinger = { name -> viewModel.addSinger(name) },
            onConfirm   = { agentId ->
                if (bgFlowIsConvert) {
                    viewModel.tagSelectedLinesWithAgent(agentId)
                    // Also force role to x-bg
                    selectedIndices.forEach { lines[it].role = "x-bg" }
                } else {
                    val insertAfterIndex = selectedIndices.maxOrNull() ?: lines.size - 1
                    viewModel.insertBackgroundLine(insertAfterIndex, bgFlowText)
                    // Tag the newly inserted line
                    if (insertAfterIndex + 1 < lines.size) {
                        lines[insertAfterIndex + 1].agent = agentId
                    }
                }
                showBgSingerDialog = false
                bgFlowIsConvert = false
                bgFlowText = ""
                viewModel.clearSelection()
            },
            onDismiss   = { showBgSingerDialog = false; bgFlowIsConvert = false; bgFlowText = "" }
        )
    }

    if (showAddBgDialog) {
        val insertAfterIndex = selectedIndices.maxOrNull() ?: (lines.size - 1)
        // Guard: only 1 BG line allowed per main lyric line
        val nextLine = lines.getOrNull(insertAfterIndex + 1)
        if (nextLine?.role == "x-bg") {
            Toast.makeText(context, "Only 1 harmony line allowed per lyric line", Toast.LENGTH_SHORT).show()
            showAddBgDialog = false
        } else {
            AddBgLineDialog(
                onConfirm = { text ->
                    bgFlowText = text
                    bgFlowIsConvert = false
                    showAddBgDialog = false
                    showBgSingerDialog = true // Go to second step: Singer
                },
                onDismiss = { showAddBgDialog = false }
            )
        }
    }

    if (showAddTranslationDialog) {
        val insertAfterIndex = selectedIndices.maxOrNull() ?: 0
        AddTranslationDialog(
            isBulk    = selectedIndices.size > 1,
            onConfirm = { text ->
                if (selectedIndices.size > 1) {
                    viewModel.bulkInsertSecondaryLines(selectedIndices.toList(), text, "x-translation")
                } else {
                    viewModel.insertTranslationLine(insertAfterIndex, text)
                }
                showAddTranslationDialog = false
            },
            onDismiss = { showAddTranslationDialog = false }
        )
    }

    if (showAddRomanizationDialog) {
        val insertAfterIndex = selectedIndices.maxOrNull() ?: 0
        AddRomanizationDialog(
            isBulk    = selectedIndices.size > 1,
            onConfirm = { text ->
                if (selectedIndices.size > 1) {
                    viewModel.bulkInsertSecondaryLines(selectedIndices.toList(), text, "x-roman")
                } else {
                    viewModel.insertRomanizationLine(insertAfterIndex, text)
                }
                showAddRomanizationDialog = false
            },
            onDismiss = { showAddRomanizationDialog = false }
        )
    }

    if (showEditChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showEditChoiceDialog = false },
            containerColor = Color(0xFF1E1E2E),
            title = { Text("Edit Options", color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text("Choose how you want to edit the selected lines.", color = Color.LightGray) },
            confirmButton = {
                Button(
                    onClick = { 
                        showEditChoiceDialog = false
                        showEditSyncDialog = true
                        // Prepare session
                        val sortedIndices = selectedIndices.sorted()
                        editSessionStartIndex = sortedIndices.first()
                        editSessionLines = sortedIndices.map { lines[it].copy(
                            words = lines[it].words.map { w -> w.copy() } // Deep copy
                        ) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentV2),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Sync, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit Sync")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { 
                        showEditChoiceDialog = false
                        if (selectedIndices.size == 1) {
                            editingLineIndex = selectedIndices.first()
                            showEditLineDialog = true
                        } else {
                            // Theoretically shouldn't be here if button was enabled correctly for multi-text-edit
                            // but for now text edit remains single-line.
                            Toast.makeText(context, "Text edit only supports single line", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                ) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit Text")
                }
            }
        )
    }

    if (showEditSyncDialog) {
        EditSyncDialog(
            initialLines     = editSessionLines,
            startIndex       = editSessionStartIndex,
            allLines         = lines,
            playbackPosition = playbackPosition,
            isPlaying        = viewModel.isPlaying,
            onPlayPause      = { if (viewModel.isPlaying) viewModel.pause() else viewModel.play() },
            onSeek           = { viewModel.seekTo(it) },
            onConfirm        = { updated ->
                viewModel.updateLinesRange(editSessionStartIndex, updated)
                showEditSyncDialog = false
                viewModel.clearSelection()
            },
            onDismiss        = { showEditSyncDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Background(albumArt = albumArt)
        Spacer(modifier = Modifier.statusBarsPadding().fillMaxWidth())

        Column(Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                LyricsView(
                    lines               = lines,
                    currentLineIndex    = currentLineIndex,
                    currentWordIndex    = currentWordIndex,
                    playbackPosition    = playbackPosition,
                    isSelectionMode     = isSelectionMode,
                    selectedLineIndices = selectedIndices,
                    onWordDoubleTap     = { li, wi -> 
                        viewModel.jumpToWord(li, wi)
                        viewModel.lines.getOrNull(li)?.words?.getOrNull(wi)?.begin?.let { viewModel.seekTo(it) }
                    },
                    onLineLongPress     = { li -> viewModel.enterSelectionMode(li) },
                    onLineSelectToggle  = { li -> viewModel.toggleLineSelection(li) },
                    modifier            = Modifier.fillMaxSize()
                )
            }
            Controls(viewModel)
        }

        // FABs
        // ── Top Action Dropdown ─────────────────────────────────────────────
        if (!isSelectionMode) {
            var showMenu by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
            ) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(48.dp)
                        .background(charcoal.copy(alpha = 0.6f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(Icons.Filled.MoreVert, "More actions", tint = Color.White)
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(charcoal)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Load Audio", color = Color.White) },
                        onClick = { showMenu = false; audioPicker.launch(arrayOf("audio/*")) },
                        leadingIcon = { Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(0.7f)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Load Lyrics", color = Color.White) },
                        onClick = { showMenu = false; showLyricsDialog = true },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Article, null, tint = Color.White.copy(0.7f)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Song Metadata", color = Color.White) },
                        onClick = { showMenu = false; showMetadataDialog = true },
                        leadingIcon = { Icon(Icons.Filled.Info, null, tint = Color.White.copy(0.7f)) }
                    )
                    Divider(color = Color.White.copy(0.1f))
                    DropdownMenuItem(
                        text = { Text("Export TTML", color = Color.White) },
                        onClick = { showMenu = false; fileSaver.launch("lyrics.ttml") },
                        leadingIcon = { Icon(Icons.Filled.Save, null, tint = Color.White.copy(0.7f)) }
                    )
                }
            }
        }

        // ── Selection action bar ──────────────────────────────────────────────
        AnimatedVisibility(
            visible  = isSelectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter    = slideInVertically { it } + fadeIn(),
            exit     = slideOutVertically { it } + fadeOut()
        ) {
            SelectionActionBar(
                selectedCount       = selectedIndices.size,
                isPlaying           = viewModel.isPlaying,
                onPlayPause         = { if (viewModel.isPlaying) viewModel.pause() else viewModel.play() },
                onSeek              = { viewModel.seekTo(viewModel.playbackPosition + it) },
                onTagSinger         = { showTagSingerDialog = true },
                onAddBgLine         = { showBgModeDialog = true },
                onAddTranslation    = { showAddTranslationDialog = true },
                onAddRomanization   = { showAddRomanizationDialog = true },
                onDelete            = { viewModel.deleteSelectedLines(selectedIndices); viewModel.clearSelection() },
                onEdit              = { 
                    if (selectedIndices.isNotEmpty()) {
                        showEditChoiceDialog = true
                    }
                },
                onSelectAll         = { viewModel.selectAll() },
                onCancel            = { viewModel.clearSelection() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Background
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun Background(albumArt: ByteArray?) {
    val image = albumArt?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    if (image != null) {
        Image(bitmap = image, contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(32.dp), contentScale = ContentScale.Crop)
    } else {
        Box(modifier = Modifier.fillMaxSize().background(charcoal))
    }
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(charcoal.copy(0.7f), Color.Transparent, charcoal.copy(0.9f)))
    ))
}

// ─────────────────────────────────────────────────────────────────────────────
//  LyricsView
// ─────────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsView(
    lines:               List<Line>,
    currentLineIndex:    Int,
    currentWordIndex:    Int,
    playbackPosition:    Long,
    isSelectionMode:     Boolean,
    selectedLineIndices: Set<Int>,
    onWordDoubleTap:     (Int, Int) -> Unit,
    onLineLongPress:     (Int) -> Unit,
    onLineSelectToggle:  (Int) -> Unit,
    modifier:            Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Single source of truth for current playback line index, computed efficiently
    val playbackLineIndex by remember(lines) {
        derivedStateOf {
            lines.indexOfFirst { line ->
                val b = line.begin; val e = line.end
                b != null && e != null && playbackPosition >= b && playbackPosition <= e
            }
        }
    }

    LaunchedEffect(currentLineIndex, playbackLineIndex) {
        val target = if (playbackLineIndex != -1) playbackLineIndex else currentLineIndex
        if (target != -1 && target < lines.size) {
            // Threshold: only scroll if the item is far from the viewport center
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val isTargetVisible = visibleItems.any { it.index == target }
            
            if (!isTargetVisible || visibleItems.firstOrNull { it.index == target }?.let { 
                it.offset < 200 || it.offset > layoutInfo.viewportSize.height - 300 
            } == true) {
                // Centering the item: offset = -(viewportHeight / 2) + approximate item half-height
                listState.animateScrollToItem(target, -(layoutInfo.viewportSize.height / 2) + 50)
            }
        }
    }

    LazyColumn(
        state          = listState,
        modifier       = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 100.dp, bottom = 300.dp, start = 16.dp, end = 16.dp)
    ) {
        itemsIndexed(
            items = lines,
            key   = { index, line -> "${index}_${line.words.firstOrNull()?.text ?: ""}" }
        ) { lineIndex, line ->
            val isSelected = lineIndex in selectedLineIndices
            
            LyricLineItem(
                lineIndex           = lineIndex,
                line                = line,
                playbackPosition    = playbackPosition,
                isSelectionMode     = isSelectionMode,
                isSelected          = isSelected,
                onWordDoubleTap     = onWordDoubleTap,
                onLineLongPress     = onLineLongPress,
                onLineSelectToggle  = onLineSelectToggle
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricLineItem(
    lineIndex:          Int,
    line:               Line,
    playbackPosition:   Long,
    isSelectionMode:    Boolean,
    isSelected:         Boolean,
    onWordDoubleTap:    (Int, Int) -> Unit,
    onLineLongPress:    (Int) -> Unit,
    onLineSelectToggle: (Int) -> Unit
) {
    val isV1          = line.agent?.contains("v1") == true
    val isV2          = line.agent?.contains("v2") == true
    val isBg          = line.role == "x-bg"
    val isTranslation = line.role == "x-translation"
    val isRoman       = line.role == "x-roman"


    val singerColor = getSingerColor(line.agent)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) selectionTint.copy(alpha = 0.20f) else Color.Transparent)
            .then(if (isSelected) Modifier.border(1.dp, selectionTint.copy(0.5f), RoundedCornerShape(8.dp)) else Modifier)
            .pointerInput(isSelectionMode) {
                if (isSelectionMode) {
                    detectTapGestures(onTap = { onLineSelectToggle(lineIndex) })
                } else {
                    awaitPointerEventScope {
                        val down = awaitFirstDown()
                        val result = withTimeoutOrNull(2000) {
                            waitForUpOrCancellation()
                        }
                        if (result == null) {
                            // Timeout reached: 2s hold
                            onLineLongPress(lineIndex)
                        } else {
                            // Released before 2s: it's a tap
                            line.begin?.let { onWordDoubleTap(lineIndex, 0) }
                        }
                    }
                }
            }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .background(if (isSelected) selectionTint else Color.White.copy(0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(8.dp))
            }

            Column(
                modifier            = Modifier.weight(1f),
                horizontalAlignment = Alignment.Start
            ) {
                // Badge
                val badgeText = when {
                    isRoman       -> "romanisation"
                    isTranslation && isV2 -> "v2 · tr"
                    isTranslation         -> "translation"
                    isV2 && isBg          -> "v2 · bg"
                    isV2                  -> "v2"
                    isBg                  -> "bg"
                    else                  -> null
                }
                val badgeColor = when {
                    isRoman       -> accentRoman
                    isTranslation -> accentTranslation
                    isBg          -> accentBg
                    else          -> singerColor
                }
                if (badgeText != null) {
                    Text(
                        text       = badgeText,
                        color      = badgeColor,
                        fontSize   = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(badgeColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                }

                FlowRow(
                    modifier              = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = when {
                        isTranslation || isRoman -> Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                        isV2          -> Arrangement.spacedBy(8.dp, Alignment.End)
                        else          -> Arrangement.spacedBy(8.dp, Alignment.Start)
                    },
                    verticalArrangement   = Arrangement.spacedBy(6.dp)
                ) {
                    line.words.forEachIndexed { wordIndex, word ->
                        val wordBegin = word.begin
                        val wordEnd   = word.end
                        val isSynced  = wordBegin != null
                        
                        val isActivePlayback = if (isBg) {
                            val lineBegin = line.begin
                            val lineEnd   = line.end
                            lineBegin != null && playbackPosition >= lineBegin && (lineEnd == null || playbackPosition <= lineEnd)
                        } else {
                            isSynced && wordBegin != null &&
                                    playbackPosition >= wordBegin &&
                                    (wordEnd == null || playbackPosition <= wordEnd)
                        }

                        val baseColor = when {
                            isRoman        -> accentRoman
                            isTranslation  -> accentTranslation
                            line.agent != null -> singerColor
                            else           -> Color.White
                        }

                        val finalTextColor = when {
                            isActivePlayback -> {
                                if (isBg) Color(0xFFFFA500) // Orange for BG active sync
                                else if (line.agent != null) singerColor 
                                else Color.Green
                            }
                            isSynced -> baseColor.copy(alpha = if (line.agent != null || isTranslation || isRoman) 0.9f else 0.8f)
                            else -> Color.White // Unsynced words are always white
                        }

                        val weight = if (isActivePlayback) FontWeight.Bold else FontWeight.Normal
                        val style  = if (isBg || isTranslation || isRoman) FontStyle.Italic else FontStyle.Normal

                        Text(
                            text       = word.text,
                            color      = finalTextColor,
                            fontSize   = if (isRoman || isTranslation) 16.sp else if (isBg) 18.sp else 24.sp,
                            fontWeight = weight,
                            fontStyle  = style,
                            modifier   = Modifier
                                .pointerInput(isSelectionMode, isSynced) {
                                    detectTapGestures(
                                        onTap = {
                                            if (!isSelectionMode && isSynced) {
                                                onWordDoubleTap(lineIndex, wordIndex)
                                            }
                                        }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Selection action bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun SelectionActionBar(
    selectedCount:     Int,
    isPlaying:         Boolean,
    onPlayPause:       () -> Unit,
    onSeek:            (Long) -> Unit,
    onTagSinger:       () -> Unit,
    onAddBgLine:       () -> Unit,
    onAddTranslation:  () -> Unit,
    onAddRomanization: () -> Unit,
    onDelete:          () -> Unit,
    onEdit:            () -> Unit,
    onSelectAll:       () -> Unit,
    onCancel:          () -> Unit
) {
    Surface(
        modifier        = Modifier.fillMaxWidth().navigationBarsPadding(),
        color           = Color(0xFF1E1E2E),
        shape           = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        shadowElevation = 16.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "$selectedCount selected",
                        color      = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = onSelectAll, colors = ButtonDefaults.textButtonColors(contentColor = accentV2)) {
                        Text("Select All", fontSize = 12.sp)
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Filled.Close, "Cancel", tint = Color.LightGray)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Row 1: Tag Singer + Add BG Line
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick  = onTagSinger,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accentV2),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Label, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tag Singer", fontSize = 13.sp)
                }

                Button(
                    onClick  = onAddBgLine,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accentBg),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.MicNone, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add BG Line", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            // Row 2: Add Translation + Add Romanisation + Edit
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick  = onAddTranslation,
                    modifier = Modifier.weight(0.7f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accentTranslation),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape    = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Translate, null, modifier = Modifier.size(18.dp))
                }

                Button(
                    onClick  = onAddRomanization,
                    modifier = Modifier.weight(0.7f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accentRoman.copy(alpha = 0.9f)),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape    = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Abc, null, modifier = Modifier.size(20.dp), tint = charcoal)
                }

                Button(
                    onClick  = onEdit,
                    modifier = Modifier.weight(0.8f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color.Gray),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape    = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Edit, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", fontSize = 12.sp)
                }
                
                Button(
                    onClick  = onDelete,
                    modifier = Modifier.weight(0.8f),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape    = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Del", fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider(color = Color.White.copy(0.05f))
            Spacer(Modifier.height(12.dp))

            // Row 3: Playback Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onSeek(-5000) }) {
                    Icon(Icons.Filled.Replay5, null, tint = Color.LightGray, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(20.dp))
                FloatingActionButton(
                    onClick = onPlayPause,
                    containerColor = accentV2,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.width(20.dp))
                IconButton(onClick = { onSeek(5000) }) {
                    Icon(Icons.Filled.Forward5, null, tint = Color.LightGray, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Metadata Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun MetadataDialog(
    initialTitle:  String,
    initialArtist: String,
    initialAlbum:  String,
    onConfirm:     (String, String, String) -> Unit,
    onDismiss:     () -> Unit
) {
    var title  by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }
    var album  by remember { mutableStateOf(initialAlbum) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor     = Color.White,
        unfocusedTextColor   = Color.LightGray,
        focusedBorderColor   = accentTranslation,
        unfocusedBorderColor = Color.Gray,
        cursorColor          = accentTranslation,
        focusedLabelColor    = accentTranslation
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1E1E2E),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = accentTranslation, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Song Metadata", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Auto-collected from audio tags. Edit and these will be saved into the TTML <head>.",
                    color    = Color.LightGray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = title,
                    onValueChange = { title = it },
                    label         = { Text("Song Title") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    leadingIcon   = { Icon(Icons.Filled.MusicNote, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                    colors        = fieldColors
                )
                OutlinedTextField(
                    value         = artist,
                    onValueChange = { artist = it },
                    label         = { Text("Artist") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    leadingIcon   = { Icon(Icons.Filled.Person, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                    colors        = fieldColors
                )
                OutlinedTextField(
                    value         = album,
                    onValueChange = { album = it },
                    label         = { Text("Album") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    leadingIcon   = { Icon(Icons.Filled.Album, null, tint = Color.Gray, modifier = Modifier.size(18.dp)) },
                    colors        = fieldColors
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(title, artist, album) },
                colors  = ButtonDefaults.buttonColors(containerColor = accentTranslation),
                shape   = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) {
                Text("Cancel")
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Tag Singer Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun TagSingerDialog(
    singers:     List<AgentInfo>,
    onAddSinger: (String) -> AgentInfo,
    onConfirm:   (String) -> Unit,
    onDismiss:   () -> Unit
) {
    val selectedAgents = remember { mutableStateListOf(singers.firstOrNull()?.id ?: "v1") }
    var showAddField   by remember { mutableStateOf(false) }
    var newSingerName  by remember { mutableStateOf("") }
    var localSingers   by remember { mutableStateOf(singers) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1E1E2E),
        title            = { Text("Tag Singer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text("Select singer(s) to assign to the selected lines (multi-select supported).", color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(localSingers) { singer ->
                        val isChosen = selectedAgents.contains(singer.id)
                        Surface(
                            shape    = RoundedCornerShape(50),
                            color    = if (isChosen) accentV2 else Color.White.copy(0.08f),
                            modifier = Modifier
                                .clickable {
                                    if (isChosen) {
                                        if (selectedAgents.size > 1) selectedAgents.remove(singer.id)
                                    } else {
                                        selectedAgents.add(singer.id)
                                    }
                                }
                                .border(if (isChosen) 0.dp else 1.dp, Color.White.copy(0.15f), RoundedCornerShape(50))
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (isChosen) { Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                                Text(singer.displayName, color = if (isChosen) Color.White else Color.LightGray, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(6.dp))
                                Text("(${singer.id})", color = (if (isChosen) Color.White else Color.LightGray).copy(0.6f), fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (showAddField) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newSingerName, onValueChange = { newSingerName = it },
                            modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("Singer name", color = Color.Gray) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                                focusedBorderColor = accentV2, unfocusedBorderColor = Color.Gray, cursorColor = accentV2
                            )
                        )
                        IconButton(onClick = {
                            if (newSingerName.isNotBlank()) {
                                val added = onAddSinger(newSingerName.trim())
                                localSingers = localSingers + added
                                if (!selectedAgents.contains(added.id)) selectedAgents.add(added.id)
                                newSingerName = ""; showAddField = false
                            }
                        }) { Icon(Icons.Filled.Check, "Add", tint = accentV2) }
                        IconButton(onClick = { showAddField = false; newSingerName = "" }) {
                            Icon(Icons.Filled.Close, "Cancel", tint = Color.Gray)
                        }
                    }
                } else {
                    TextButton(onClick = { showAddField = true }, colors = ButtonDefaults.textButtonColors(contentColor = accentV2)) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add Singer", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedAgents.joinToString(" ")) }, colors = ButtonDefaults.buttonColors(containerColor = accentV2), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Add BG Line Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AddBgLineDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = {
            Column {
                Text("Add Background Line", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("A harmony line (ttm:role=\"x-bg\") will be inserted after selection.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4, label = { Text("Harmony text") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                        focusedBorderColor = accentBg, unfocusedBorderColor = Color.Gray, cursorColor = accentBg, focusedLabelColor = accentBg
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }, enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accentBg), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.MicNone, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Add Translation Dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AddTranslationDialog(isBulk: Boolean = false, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = {
            Column {
                Text(if (isBulk) "Bulk Translations" else "Add Translation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isBulk) "Each line pasted here will be assigned to one selected line sequentially. Lines amount should exactly match the selection count."
                    else "A translation line (ttm:role=\"x-translation\") will be inserted after selection.",
                    color = if (isBulk) Color.Yellow.copy(0.7f) else Color.LightGray,
                    fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4, label = { Text("Translation text") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                        focusedBorderColor = accentTranslation, unfocusedBorderColor = Color.Gray, cursorColor = accentTranslation, focusedLabelColor = accentTranslation
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }, enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accentTranslation), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Translate, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Insert")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Add Romanization Dialog  ✦ NEW
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AddRomanizationDialog(isBulk: Boolean = false, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Abc, null, tint = accentRoman, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isBulk) "Bulk Romanisation" else "Add Romanisation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (isBulk) "Each line pasted here will be assigned to one selected line sequentially. Lines amount should exactly match the selection count."
                    else "A phonetic/roman line (ttm:role=\"x-roman\") will be inserted after selection.",
                    color = if (isBulk) Color.Yellow.copy(0.7f) else Color.LightGray,
                    fontSize = 12.sp, lineHeight = 16.sp
                )
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = if (isBulk) 4 else 2, maxLines = if (isBulk) 12 else 4, 
                    label = { Text(if (isBulk) "Paste romanised lines" else "Romanisation text") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                        focusedBorderColor = accentRoman, unfocusedBorderColor = Color.Gray, cursorColor = accentRoman, focusedLabelColor = accentRoman
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onConfirm(text) }, enabled = text.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = accentRoman), shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Filled.Check, null, modifier = Modifier.size(16.dp), tint = charcoal)
                Spacer(Modifier.width(6.dp))
                Text("Insert", color = charcoal)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { Text("Cancel") }
        }
    )
}

@Composable
fun EditLineDialog(initialText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = { Text("Edit Line Text", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray,
                    focusedBorderColor = accentV2, unfocusedBorderColor = Color.Gray, cursorColor = accentV2
                )
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }, colors = ButtonDefaults.buttonColors(containerColor = accentV2)) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { Text("Cancel") }
        }
    )
}

@Composable
fun MultiChoiceDialog(title: String, options: List<String>, onChoice: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = { Text(title, color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                options.forEachIndexed { index, option ->
                    Button(
                        onClick = { onChoice(index) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.05f))
                    ) {
                        Text(option, color = Color.White)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)) { Text("Cancel") }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Controls
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun Controls(viewModel: EditorViewModel) {
    val context          = LocalContext.current
    val isPlaying        = viewModel.isPlaying
    val playbackPosition = viewModel.playbackPosition
    val duration         = viewModel.duration
    val currentAgent     = viewModel.currentAgent
    val isBgVocal        = viewModel.isBgVocal
    val isSelectionMode  = viewModel.isSelectionMode

    if (isSelectionMode) { Spacer(Modifier.height(185.dp)); return }

    Column(
        modifier            = Modifier.fillMaxWidth().padding(bottom = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProgressBar(playbackPosition, duration) { viewModel.seekTo(it) }
        Spacer(Modifier.height(8.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { 
                    viewModel.clearSelection() 
                    viewModel.enterSelectionMode(0) // Start selection mode
                    Toast.makeText(context, "Select lines to romanise", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = charcoal.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, accentRoman.copy(0.4f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Filled.Translate, null, modifier = Modifier.size(14.dp), tint = accentRoman)
                Spacer(Modifier.width(4.dp))
                Text("Roman", fontSize = 11.sp, color = accentRoman)
            }

            Button(
                onClick = { 
                    viewModel.clearSelection()
                    viewModel.enterSelectionMode(0) // Start selection mode
                    Toast.makeText(context, "Select lines to translate", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = charcoal.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, accentTranslation.copy(0.4f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Filled.Language, null, modifier = Modifier.size(14.dp), tint = accentTranslation)
                Spacer(Modifier.width(4.dp))
                Text("Trans", fontSize = 11.sp, color = accentTranslation)
            }

            Button(
                onClick = { viewModel.toggleBgVocal() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isBgVocal) accentBg.copy(alpha = 0.2f) else charcoal.copy(alpha = 0.6f)
                ),
                border = BorderStroke(1.dp, if (isBgVocal) accentBg else accentBg.copy(0.3f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Filled.MicNone, null, modifier = Modifier.size(14.dp), tint = if (isBgVocal) accentBg else Color.Gray)
                Spacer(Modifier.width(4.dp))
                Text("BG", fontSize = 11.sp, color = if (isBgVocal) accentBg else Color.Gray)
            }

            val singerColor = getSingerColor(currentAgent)
            Button(
                onClick = { viewModel.cycleSinger() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = charcoal.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, singerColor.copy(0.4f)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 4.dp)
            ) {
                Icon(Icons.Filled.Person, null, modifier = Modifier.size(14.dp), tint = singerColor)
                Spacer(Modifier.width(4.dp))
                val singerLabel = currentAgent.replaceFirstChar { it.uppercase() }
                Text(singerLabel, fontSize = 11.sp, color = singerColor)
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { viewModel.undoLastSync() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Box(
                modifier = Modifier.size(80.dp).clip(CircleShape).background(Color.White.copy(0.9f))
                    .clickable(onClick = { if (isPlaying) viewModel.pause() else viewModel.play() }),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, tint = charcoal, modifier = Modifier.size(56.dp))
            }
            IconButton(onClick = { viewModel.onLineSync() }) {
                Icon(Icons.Filled.Check, "Sync", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Progress bar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun ProgressBar(playbackPosition: Long, duration: Long, onSeek: (Long) -> Unit) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPos   by remember { mutableLongStateOf(0L) }
    val sliderPos = if (duration > 0) ((if (isSeeking) seekPos else playbackPosition).toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(value = sliderPos, onValueChange = { if (duration > 0) { isSeeking = true; seekPos = (it * duration).toLong() } },
            onValueChangeFinished = { if (duration > 0) onSeek(seekPos); isSeeking = false },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(TtmlBuilder.formatTime(playbackPosition), color = Color.White, fontSize = 12.sp)
            Text(TtmlBuilder.formatTime(duration), color = Color.White, fontSize = 12.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Lyrics input dialog
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun LyricsInputDialog(
    songTitle: String?, 
    rawLyrics: String, 
    allowBgWordSync: Boolean,
    onCalibrate: (Long) -> Unit, 
    onImport: () -> Unit, 
    onDismiss: () -> Unit, 
    onConfirm: (String, Boolean) -> Unit
) {
    var text by remember { mutableStateOf(rawLyrics) }
    var bgSyncChecked by remember { mutableStateOf(allowBgWordSync) }
    var waitingForLyrics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun pasteFromClipboard() {
        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val t = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        if (t.isNotBlank()) { text = t; waitingForLyrics = false }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME && waitingForLyrics) pasteFromClipboard() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = charcoal,
        title = {
            Column {
                Text("Lyrics Options", color = Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import TTML") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    waitingForLyrics = true
                    val q = songTitle?.split(Regex("[\\s\\-_]+"))?.filter { it.isNotBlank() }?.take(2)?.joinToString("%20") ?: ""
                    uriHandler.openUri("https://lrclib.net/search/$q")
                }, modifier = Modifier.fillMaxWidth()) { Text("Search LRCLIB") }
                Spacer(Modifier.height(12.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(0.07f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("💡 Tip:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Separate lines ONLY if there is an instrumental break.", color = Color.Yellow.copy(0.8f), fontSize = 11.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("Prefixes: v1:/v2: singer, bg: harmony, tr: trans, ro: roman", color = Color.Gray, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { bgSyncChecked = !bgSyncChecked },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = bgSyncChecked,
                        onCheckedChange = { bgSyncChecked = it },
                        colors = CheckboxDefaults.colors(checkedColor = accentBg, uncheckedColor = Color.Gray)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Allow word-by-word sync for BG lyrics", color = Color.White, fontSize = 13.sp)
                }
            }
        },
        text = {
            OutlinedTextField(
                value = text, 
                onValueChange = { text = it }, 
                modifier = Modifier.fillMaxWidth().height(250.dp), 
                label = { Text("Paste lyrics") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.LightGray,
                    focusedBorderColor = accentV2,
                    unfocusedBorderColor = Color.Gray,
                    cursorColor = accentV2,
                    focusedLabelColor = accentV2
                )
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text, bgSyncChecked) }) { Text("Load") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun EditorScreenPreview() {
    MaterialTheme { EditorScreen(viewModel = EditorViewModel()) }
}
@Composable
fun EditSyncDialog(
    initialLines:     List<Line>,
    startIndex:       Int,
    allLines:         List<Line>,
    playbackPosition: Long,
    isPlaying:        Boolean,
    onPlayPause:      () -> Unit,
    onSeek:           (Long) -> Unit,
    onConfirm:        (List<Line>) -> Unit,
    onDismiss:        () -> Unit
) {
    // Local copy of lines, stripped of sync for re-syncing
    var localLines by remember { mutableStateOf(initialLines.map { it.copy(
        begin = null, end = null,
        words = it.words.map { w -> w.copy(begin = null, end = null) }
    ) }) }
    var currentLineIndex by remember { mutableIntStateOf(0) }
    var currentWordIndex by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()

    // Seek to lead-in on start
    LaunchedEffect(Unit) {
        var leadInTime = 0L
        for (i in (startIndex - 1) downTo 0) {
            val line = allLines[i]
            val lastWord = line.words.lastOrNull { it.begin != null }
            if (lastWord != null) {
                leadInTime = lastWord.end ?: lastWord.begin ?: 0L
                break
            }
            if (line.begin != null) {
                leadInTime = line.end ?: line.begin ?: 0L
                break
            }
        }
        onSeek(maxOf(0L, leadInTime - 2000L)) // 2s lead-in
    }

    fun onSync() {
        if (currentLineIndex >= localLines.size) return
        val updated = localLines.toMutableList()
        val line = updated[currentLineIndex]
        val currentTime = playbackPosition
        
        if (currentWordIndex < line.words.size) {
            val word = line.words[currentWordIndex]
            word.begin = currentTime
            if (currentWordIndex == 0) {
                line.begin = currentTime
            } else {
                line.words[currentWordIndex - 1].end = currentTime
            }
            currentWordIndex++
            if (currentWordIndex == line.words.size) {
                // Done with words, next tap will end the line
            }
        } else if (line.end == null) {
            line.end = currentTime
            if (line.words.isNotEmpty()) line.words.last().end = currentTime
            if (currentLineIndex + 1 < localLines.size) {
                currentLineIndex++
                currentWordIndex = 0
            }
        }
        localLines = updated
    }

    fun onUndo() {
        if (currentWordIndex > 0) {
            val updated = localLines.toMutableList()
            val line    = updated[currentLineIndex]
            currentWordIndex--
            line.words[currentWordIndex].begin = null
            line.words[currentWordIndex].end   = null
            if (currentWordIndex == 0) line.begin = null
            else line.words[currentWordIndex - 1].end = null
            localLines = updated
            // Auto-rewind 2s from the last synced word
            val lastSynced = findLastSynced(updated, currentLineIndex, currentWordIndex)
            onSeek(maxOf(0L, (lastSynced?.end ?: lastSynced?.begin ?: playbackPosition) - 2000L))
            return
        }
        if (currentLineIndex > 0) {
            currentLineIndex--
            val updated = localLines.toMutableList()
            val line    = updated[currentLineIndex]
            currentWordIndex = line.words.size
            localLines = updated
            onUndo() // Recurse
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = charcoal
    ) {
        Column(Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier              = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text("Edit Sync Mode", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, null, tint = Color.LightGray) }
            }

            // Progress
            LinearProgressIndicator(
                progress = if (localLines.isEmpty()) 0f else (currentLineIndex.toFloat() / localLines.size.toFloat()),
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color    = accentV2, trackColor = Color.White.copy(0.1f)
            )

            // Lyrics List (Range only)
            LazyColumn(
                modifier   = Modifier.weight(1f).fillMaxWidth(),
                state      = listState,
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(localLines) { idx, line ->
                    LyricLineItem(
                        line                = line,
                        lineIndex           = idx,
                        playbackPosition    = playbackPosition,
                        isSelectionMode     = false,
                        isSelected          = false,
                        onWordDoubleTap     = { _, _ -> },
                        onLineLongPress     = { },
                        onLineSelectToggle  = { }
                    )
                }
            }

            // Footer Controls
            Surface(
                color           = Color.Black.copy(0.4f),
                modifier        = Modifier.fillMaxWidth(),
                shape           = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(Modifier.padding(24.dp).navigationBarsPadding()) {
                    // Playback Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { onSeek(playbackPosition - 5000) }) {
                            Icon(Icons.Filled.Replay5, null, tint = Color.LightGray)
                        }
                        Spacer(Modifier.width(16.dp))
                        FloatingActionButton(
                            onClick = onPlayPause,
                            containerColor = Color.White.copy(0.1f),
                            contentColor = Color.White,
                            shape = CircleShape,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, null)
                        }
                        Spacer(Modifier.width(16.dp))
                        IconButton(onClick = { onSeek(playbackPosition + 5000) }) {
                            Icon(Icons.Filled.Forward5, null, tint = Color.LightGray)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Sync & Undo & Confirm
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick  = ::onUndo,
                            modifier = Modifier.weight(0.5f).height(56.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(0.3f)),
                            shape    = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, null)
                        }

                        Button(
                            onClick  = ::onSync,
                            modifier = Modifier.weight(1.5f).height(56.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = accentV2),
                            shape    = RoundedCornerShape(16.dp)
                        ) {
                            Text("SYNC", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        }

                        Button(
                            onClick  = { onConfirm(localLines) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            shape    = RoundedCornerShape(16.dp)
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        }
    }
}

private fun findLastSynced(lines: List<Line>, currentLine: Int, currentWord: Int): Word? {
    for (i in currentLine downTo 0) {
        val line = lines[i]
        val startWord = if (i == currentLine) currentWord - 1 else line.words.size - 1
        for (j in startWord downTo 0) {
            val w = line.words[j]
            if (w.begin != null) return w
        }
    }
    return null
}
