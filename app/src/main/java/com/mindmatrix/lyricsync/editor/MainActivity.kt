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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mindmatrix.lyricsync.data.TtmlBuilder
import com.mindmatrix.lyricsync.data.model.Line
import com.mindmatrix.lyricsync.editor.viewmodel.EditorViewModel
import kotlinx.coroutines.launch
import java.net.URLEncoder

// App-wide theme colors
private val charcoal = Color(0xFF121212)

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

@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val context = LocalContext.current
    val lines = viewModel.lines
    val currentLineIndex = viewModel.currentLineIndex
    val currentWordIndex = viewModel.currentWordIndex
    val playbackPosition = viewModel.playbackPosition
    val albumArt = viewModel.albumArt
    val songTitle = viewModel.songTitle

    var showLyricsDialog by remember { mutableStateOf(false) }

    // Use OpenDocument and take persistable permissions
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            viewModel.loadAudio(context, it)
        }
    }

    val fileSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/ttml")) { uri: Uri? ->
        uri?.let {
            val content = TtmlBuilder().build(lines)
            viewModel.saveFile(context, it, content)
            
            // Auto-embed after picking save location
            viewModel.tagAudioWithTtml(context, content) { success ->
                val msg = if (success) "Lyrics embedded and saved!" else "Error embedding lyrics"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showLyricsDialog) {
        LyricsInputDialog(
            songTitle = songTitle,
            onCalibrate = { viewModel.calibrateSync(it) },
            onTag = {
                val ttml = TtmlBuilder().build(lines)
                viewModel.tagAudioWithTtml(context, ttml) { success ->
                    val msg = if (success) "Song tagged successfully!" else "Error tagging song"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showLyricsDialog = false },
            onConfirm = { lyrics ->
                viewModel.loadLyrics(lyrics)
                showLyricsDialog = false
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Background(albumArt = albumArt)

        // Dummy row to add status bar padding
        Spacer(modifier = Modifier.statusBarsPadding().fillMaxWidth())

        Column(Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                LyricsView(
                    lines = lines,
                    currentLineIndex = currentLineIndex,
                    currentWordIndex = currentWordIndex,
                    playbackPosition = playbackPosition,
                    onWordDoubleTap = { lineIndex, wordIndex ->
                        viewModel.jumpToWord(lineIndex, wordIndex)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            Controls(viewModel)
        }

        // Floating Action Buttons for file operations
        Column(
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 16.dp),
            horizontalAlignment = Alignment.End
        ) {
            FloatingActionButton(
                onClick = { audioPicker.launch(arrayOf("audio/*")) },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.Filled.MusicNote, "Load Audio")
            }
            FloatingActionButton(
                onClick = { showLyricsDialog = true },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Article, "Load Lyrics")
            }
            FloatingActionButton(
                onClick = { fileSaver.launch("lyrics.ttml") },
                modifier = Modifier.padding(8.dp)
            ) {
                Icon(Icons.Filled.Save, "Export TTML")
            }
        }
    }
}

@Composable
private fun Background(albumArt: ByteArray?) {
    val image = albumArt?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }

    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = "Album Art Background",
            modifier = Modifier.fillMaxSize().blur(32.dp),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(modifier = Modifier.fillMaxSize().background(charcoal))
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(colors = listOf(charcoal.copy(alpha = 0.7f), Color.Transparent, charcoal.copy(alpha = 0.9f)))
        )
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsView(
    lines: List<Line>,
    currentLineIndex: Int,
    currentWordIndex: Int,
    playbackPosition: Long,
    onWordDoubleTap: (Int, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val playbackLineIndex = remember(playbackPosition, lines) {
        lines.indexOfFirst { line ->
            val begin = line.begin
            val end = line.end
            begin != null && end != null && playbackPosition >= begin && playbackPosition <= end
        }
    }

    LaunchedEffect(currentLineIndex, playbackLineIndex) {
        val targetIndex = if (playbackLineIndex != -1) playbackLineIndex else currentLineIndex
        if (targetIndex != -1 && targetIndex < lines.size) {
            coroutineScope.launch {
                listState.animateScrollToItem(targetIndex, - (listState.layoutInfo.viewportSize.height / 3))
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 200.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.Start 
    ) {
        itemsIndexed(lines) { lineIndex, line ->
            FlowRow(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                line.words.forEachIndexed { wordIndex, word ->
                    val isSynced = word.begin != null
                    val isActiveInPlayback = isSynced && playbackPosition >= word.begin!! && 
                            (word.end == null || playbackPosition <= word.end!!)

                    val textColor = when {
                        isActiveInPlayback -> Color.Green
                        isSynced -> Color(0xFF4CAF50).copy(alpha = 0.8f)
                        else -> Color.White
                    }

                    val fontWeight = if (isActiveInPlayback) FontWeight.Bold else FontWeight.Normal

                    Text(
                        text = word.text,
                        color = textColor,
                        fontSize = 24.sp,
                        fontWeight = fontWeight,
                        modifier = Modifier.pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { onWordDoubleTap(lineIndex, wordIndex) }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun Controls(viewModel: EditorViewModel) {
    val isPlaying = viewModel.isPlaying
    val playbackPosition = viewModel.playbackPosition
    val duration = viewModel.duration

    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp, start = 32.dp, end = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ProgressBar(playbackPosition, duration) { newPosition ->
            viewModel.seekTo(newPosition)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.undoLastSync() }) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White, modifier = Modifier.size(48.dp))
            }

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f))
                    .clickable(
                        onClick = { if (isPlaying) viewModel.pause() else viewModel.play() },
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, color = charcoal)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) "Pause" else "Play",
                    tint = charcoal,
                    modifier = Modifier.size(56.dp)
                )
            }

            IconButton(onClick = { viewModel.onLineSync() }) {
                Icon(Icons.Filled.Check, "Sync Line", tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressBar(playbackPosition: Long, duration: Long, onSeek: (Long) -> Unit) {
    var isSeeking by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableLongStateOf(0L) }

    val sliderPosition = if (duration > 0) {
        val currentPosition = if (isSeeking) seekPosition else playbackPosition
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = sliderPosition,
            onValueChange = {
                if (duration > 0) {
                    isSeeking = true
                    seekPosition = (it * duration).toLong()
                }
            },
            onValueChangeFinished = {
                if (duration > 0) {
                    onSeek(seekPosition)
                }
                isSeeking = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.Gray
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(com.mindmatrix.lyricsync.data.TtmlBuilder.formatTime(playbackPosition), color = Color.White, fontSize = 12.sp)
            Text(com.mindmatrix.lyricsync.data.TtmlBuilder.formatTime(duration), color = Color.White, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsInputDialog(
    songTitle: String?,
    onCalibrate: (Long) -> Unit,
    onTag: () -> Unit,
    onDismiss: () -> Unit, 
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    var waitingForLyrics by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val clipboardText = item?.text?.toString() ?: ""
            if (clipboardText.isNotBlank()) {
                text = clipboardText
                waitingForLyrics = false 
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && waitingForLyrics) {
                pasteFromClipboard()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = charcoal,
        title = {
            Column {
                Text("Lyrics Options", color = Color.White)
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Sync Calibration", color = Color.LightGray, fontSize = 14.sp)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onCalibrate(10) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("+10ms")
                    }
                    Button(
                        onClick = { onCalibrate(-10) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("-10ms")
                    }
                }
                
                Divider(color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))

                Button(
                    onClick = onTag,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                ) {
                    Icon(Icons.Filled.Label, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tag Song with TTML Lyrics")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { 
                        waitingForLyrics = true
                        val searchQuery = songTitle
                            ?.split(Regex("[\\s\\-_]+"))
                            ?.filter { it.isNotBlank() }
                            ?.take(2)
                            ?.joinToString("%20")
                            ?: ""

                        val url = "https://lrclib.net/search/$searchQuery"
                        uriHandler.openUri(url)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Get Lyrics from LRCLIB")
                }
            }
        },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().height(250.dp),
                label = { Text("Paste plain lyrics here") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.LightGray,
                    cursorColor = Color.White,
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color.Gray
                )
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("Load") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF121212)
@Composable
fun EditorScreenPreview() {
    MaterialTheme {
        EditorScreen(viewModel = EditorViewModel())
    }
}
