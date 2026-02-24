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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.mindmatrix.lyricsync.editor.viewmodel.AgentInfo
import com.mindmatrix.lyricsync.editor.viewmodel.EditorViewModel
import kotlinx.coroutines.launch

// ── Theme colours ─────────────────────────────────────────────────────────────
private val charcoal       = Color(0xFF121212)
private val accentV2       = Color(0xFF7B61FF)
private val accentBg       = Color(0xFFFF9800)
private val accentTranslation = Color(0xFF00BCD4)
private val accentRoman    = Color(0xFFFFEB3B) // Yellow for romanisation
private val greenSynced    = Color(0xFF4CAF50)
private val selectionTint  = Color(0xFF2979FF)

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

    // Dialogs
    if (showLyricsDialog) {
        LyricsInputDialog(
            songTitle   = songTitle,
            onCalibrate = { viewModel.calibrateSync(it) },
            onTag = {
                viewModel.tagAudioWithTtml(context, buildTtml(viewModel)) { success ->
                    Toast.makeText(context, if (success) "Song tagged!" else "Error tagging", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showLyricsDialog = false },
            onConfirm = { lyrics -> viewModel.loadLyrics(lyrics); showLyricsDialog = false }
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

    if (showAddBgDialog) {
        val insertAfterIndex = selectedIndices.maxOrNull() ?: 0
        AddBgLineDialog(
            onConfirm = { text -> viewModel.insertBackgroundLine(insertAfterIndex, text); showAddBgDialog = false },
            onDismiss = { showAddBgDialog = false }
        )
    }

    if (showAddTranslationDialog) {
        val insertAfterIndex = selectedIndices.maxOrNull() ?: 0
        AddTranslationDialog(
            onConfirm = { text -> viewModel.insertTranslationLine(insertAfterIndex, text); showAddTranslationDialog = false },
            onDismiss = { showAddTranslationDialog = false }
        )
    }

    if (showAddRomanizationDialog) {
        val insertAfterIndex = selectedIndices.maxOrNull() ?: 0
        AddRomanizationDialog(
            onConfirm = { text -> viewModel.insertRomanizationLine(insertAfterIndex, text); showAddRomanizationDialog = false },
            onDismiss = { showAddRomanizationDialog = false }
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
                    onWordDoubleTap     = { li, wi -> viewModel.jumpToWord(li, wi) },
                    onLineLongPress     = { li -> viewModel.enterSelectionMode(li) },
                    onLineSelectToggle  = { li -> viewModel.toggleLineSelection(li) },
                    modifier            = Modifier.fillMaxSize()
                )
            }
            Controls(viewModel)
        }

        // FABs
        if (!isSelectionMode) {
            Column(
                modifier            = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp),
                horizontalAlignment = Alignment.End
            ) {
                FloatingActionButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Filled.MusicNote, "Load Audio")
                }
                FloatingActionButton(onClick = { showLyricsDialog = true }, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Article, "Load Lyrics")
                }
                // Metadata FAB
                FloatingActionButton(
                    onClick  = { showMetadataDialog = true },
                    modifier = Modifier.padding(8.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Filled.Info, "Song Metadata", tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                FloatingActionButton(onClick = { fileSaver.launch("lyrics.ttml") }, modifier = Modifier.padding(8.dp)) {
                    Icon(Icons.Filled.Save, "Export TTML")
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
                onTagSinger         = { showTagSingerDialog = true },
                onAddBgLine         = { showAddBgDialog = true },
                onAddTranslation    = { showAddTranslationDialog = true },
                onAddRomanization   = { showAddRomanizationDialog = true },
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
    val listState      = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val playbackLineIndex = remember(playbackPosition, lines) {
        lines.indexOfFirst { line ->
            val b = line.begin; val e = line.end
            b != null && e != null && playbackPosition >= b && playbackPosition <= e
        }
    }

    LaunchedEffect(currentLineIndex, playbackLineIndex) {
        val target = if (playbackLineIndex != -1) playbackLineIndex else currentLineIndex
        if (target != -1 && target < lines.size) {
            coroutineScope.launch {
                listState.animateScrollToItem(target, -(listState.layoutInfo.viewportSize.height / 3))
            }
        }
    }

    LazyColumn(
        state          = listState,
        modifier       = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 200.dp, horizontal = 16.dp)
    ) {
        itemsIndexed(lines) { lineIndex, line ->
            val isV2          = line.agent == "v2"
            val isBg          = line.role  == "x-bg"
            val isTranslation = line.role  == "x-translation"
            val isRoman       = line.role  == "x-roman"
            val isSelected    = lineIndex in selectedLineIndices

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) selectionTint.copy(alpha = 0.20f) else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, selectionTint.copy(0.5f), RoundedCornerShape(8.dp)) else Modifier)
                    .pointerInput(isSelectionMode) {
                        detectTapGestures(
                            onLongPress = { if (!isSelectionMode) onLineLongPress(lineIndex) },
                            onTap       = { if (isSelectionMode) onLineSelectToggle(lineIndex) }
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Checkbox circle in selection mode
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
                        horizontalAlignment = when {
                            isTranslation || isRoman -> Alignment.CenterHorizontally
                            isV2          -> Alignment.End
                            else          -> Alignment.Start
                        }
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
                            else          -> accentV2
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

                        // Words
                        FlowRow(
                            modifier              = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = when {
                                isTranslation || isRoman -> Arrangement.Center
                                isV2          -> Arrangement.End
                                else          -> Arrangement.Start
                            },
                            verticalArrangement   = Arrangement.spacedBy(6.dp)
                        ) {
                            line.words.forEachIndexed { wordIndex, word ->
                                val isSynced         = word.begin != null
                                val isActivePlayback = isSynced &&
                                        playbackPosition >= word.begin!! &&
                                        (word.end == null || playbackPosition <= word.end!!)

                                val base = when {
                                    isRoman       -> accentRoman
                                    isTranslation -> accentTranslation
                                    isV2          -> accentV2
                                    else          -> Color.White
                                }

                                val textColor = when {
                                    isActivePlayback -> if (isV2) accentV2 else if (isTranslation) accentTranslation else if (isRoman) accentRoman else Color.Green
                                    isSynced         -> base.copy(alpha = if (isV2 || isTranslation || isRoman) 0.7f else 0.8f)
                                    isBg || isTranslation || isRoman -> base.copy(alpha = 0.55f)
                                    else             -> base
                                }

                                Text(
                                    text       = word.text,
                                    color      = textColor,
                                    fontSize   = when {
                                        isRoman || isTranslation -> 16.sp
                                        isBg                     -> 18.sp
                                        else                     -> 24.sp
                                    },
                                    fontWeight = if (isActivePlayback) FontWeight.Bold else FontWeight.Normal,
                                    fontStyle  = if (isBg || isTranslation || isRoman) FontStyle.Italic else FontWeight.Normal,
                                    modifier   = Modifier.pointerInput(isSelectionMode) {
                                        detectTapGestures(
                                            onDoubleTap = { if (!isSelectionMode) onWordDoubleTap(lineIndex, wordIndex) },
                                            onLongPress = { if (!isSelectionMode) onLineLongPress(lineIndex) }
                                        )
                                    }
                                )
                            }
                        }
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
    onTagSinger:       () -> Unit,
    onAddBgLine:       () -> Unit,
    onAddTranslation:  () -> Unit,
    onAddRomanization: () -> Unit,
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
                Text(
                    "$selectedCount line${if (selectedCount == 1) "" else "s"} selected",
                    color      = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
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

            // Row 2: Add Translation + Add Romanisation
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick  = onAddTranslation,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accentTranslation),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Translate, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Translation", fontSize = 13.sp)
                }

                Button(
                    onClick  = onAddRomanization,
                    modifier = Modifier.weight(1f),
                    colors   = ButtonDefaults.buttonColors(containerColor = accentRoman.copy(alpha = 0.9f)),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Abc, null, modifier = Modifier.size(18.dp), tint = charcoal)
                    Spacer(Modifier.width(4.dp))
                    Text("Roman", fontSize = 13.sp, color = charcoal)
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
    var selectedAgent by remember { mutableStateOf(singers.firstOrNull()?.id ?: "v1") }
    var showAddField  by remember { mutableStateOf(false) }
    var newSingerName by remember { mutableStateOf("") }
    var localSingers  by remember { mutableStateOf(singers) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF1E1E2E),
        title            = { Text("Tag Singer", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
        text = {
            Column {
                Text("Select which singer to assign to the selected lines.", color = Color.LightGray, fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))

                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(localSingers) { singer ->
                        val isChosen = singer.id == selectedAgent
                        Surface(
                            shape    = RoundedCornerShape(50),
                            color    = if (isChosen) accentV2 else Color.White.copy(0.08f),
                            modifier = Modifier
                                .clickable { selectedAgent = singer.id }
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
                                selectedAgent = added.id
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
            Button(onClick = { onConfirm(selectedAgent) }, colors = ButtonDefaults.buttonColors(containerColor = accentV2), shape = RoundedCornerShape(10.dp)) {
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
fun AddTranslationDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = {
            Column {
                Text("Add Translation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text("A translation line (ttm:role=\"x-translation\") will be inserted after selection.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp)
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
fun AddRomanizationDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1E1E2E),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Abc, null, tint = accentRoman, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Add Romanisation", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("A phonetic/roman line (ttm:role=\"x-roman\") will be inserted after selection.", color = Color.LightGray, fontSize = 12.sp, lineHeight = 16.sp)
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4, label = { Text("Romanisation text") },
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

// ─────────────────────────────────────────────────────────────────────────────
//  Controls
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun Controls(viewModel: EditorViewModel) {
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

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = currentAgent == "v1", onClick = { viewModel.setAgent("v1") }, label = { Text("v1", fontSize = 13.sp) },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = greenSynced.copy(0.25f), selectedLabelColor = greenSynced))
            FilterChip(selected = currentAgent == "v2", onClick = { viewModel.setAgent("v2") }, label = { Text("v2", fontSize = 13.sp) },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentV2.copy(0.25f), selectedLabelColor = accentV2))
            Spacer(Modifier.width(16.dp))
            FilterChip(selected = isBgVocal, onClick = { viewModel.toggleBgVocal() }, label = { Text("BG vocal", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Filled.MicNone, null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.padding(horizontal = 4.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accentBg.copy(0.25f), selectedLabelColor = accentBg))
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
fun LyricsInputDialog(songTitle: String?, onCalibrate: (Long) -> Unit, onTag: () -> Unit, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
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
                Button(onClick = onTag, modifier = Modifier.fillMaxWidth()) { Text("Tag Song with TTML") }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    waitingForLyrics = true
                    val q = songTitle?.split(Regex("[\\s\\-_]+"))?.filter { it.isNotBlank() }?.take(2)?.joinToString("%20") ?: ""
                    uriHandler.openUri("https://lrclib.net/search/$q")
                }, modifier = Modifier.fillMaxWidth()) { Text("Search LRCLIB") }
                Spacer(Modifier.height(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = Color.White.copy(0.07f), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("💡 Prefix lines:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("v1:/v2: singer, bg: harmony, tr: trans, ro: roman", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(250.dp), label = { Text("Paste lyrics") })
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("Load") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun EditorScreenPreview() {
    MaterialTheme { EditorScreen(viewModel = EditorViewModel()) }
}
