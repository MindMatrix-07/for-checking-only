# 🎤 LyricSync

**LyricSync** is a powerful, gesture-driven Android application designed for high-precision lyric synchronization. It enables creators to sync text with audio, manage multi-singer (duet) parts, handle background vocals, and export industry-standard TTML files compatible with modern streaming platforms.

![LyricSync Banner](https://raw.githubusercontent.com/MindMatrix-07/LyricSync/master/app/src/main/res/drawable/banner.png) *(Note: Placeholder for actual banner)*

## ✨ Key Features

### ⚡ Intuitive Synchronization
- **Gesture-Based Selection**: 0.6s long-press to select lines; tap to toggle in selection mode.
- **Word-Level Sync**: Support for high-precision word-by-word timing.
- **Micro-Calibration**: Adjust sync timing globally with millisecond precision.
- **Undo System**: One-tap undo for accidental syncs with automatic audio backtracking.

### 🎭 Advanced Vocal Management
- **Multi-Singer Support**: Synchronize duets and group tracks with up to 8 distinct singer profiles and color-coded visuals.
- **Background Vocals**: Special handling for background lines (`x-bg`) with optional word-sync.
- **Romanization**: Easy insertion and management of phonetic (`x-roman`) lines.
- **Translation**: Support for lyric translations (`x-translation`).

### 💾 Persistence & Protection
- **Auto-Save**: Automatic session persistence. Never lose your progress due to app closures or crashes.
- **Exit Protection**: Confirmation dialog to prevent accidental data loss on back press.
- **Session Recovery**: Restart the app and pick up exactly where you left off.

### 📤 Export & Integration
- **TTML Builder**: Export standards-compliant TTML with rich metadata (Title, Artist, Album, Agents).
- **Direct Tagging**: Embed synchronized lyrics directly into MP3/WAV files using ID3v2 tagging.
- **Sidecar Files**: Automatically generates `.ttml` sidecar files in your Music folder.

## 🛠️ Tech Stack
- **Framework**: [Jetpack Compose](https://developer.android.com/compose) (Modern Android UI)
- **Language**: [Kotlin](https://kotlinlang.org/)
- **Media**: [androidx.media3 (ExoPlayer)](https://developer.android.com/guide/topics/media/media3)
- **Tagging**: [Jaudiotagger](http://www.jthink.net/jaudiotagger/)
- **Architecture**: MVVM with AndroidViewModel for session persistence.

## 🚀 Getting Started

### Prerequisites
- Android Studio Ladybug or later.
- JDK 11 or higher.

### Installation
1. Clone the repository:
   ```bash
   git clone https://github.com/MindMatrix-07/LyricSync.git
   ```
2. Open the project in Android Studio.
3. Build and run the `app` module on an Android device or emulator (API 24+).

## 📖 Usage
1. **Load Audio**: Pick an MP3 or WAV file.
2. **Import Lyrics**: Paste your raw lyrics or import an existing TTML.
3. **Sync**: Use the sync button to time lines/words in real-time.
4. **Tag Singers**: Use the selection menu to assign lines to different singers.
5. **Save/Export**: Embed the lyrics into the file or save the TTML.

---
*Developed with ❤️ by the MindMatrix team.*
