# [UNOFFICIAL] Pstream Wrapper for Android TV

support pstream[.]mov

## Features

- **Fullscreen WebView** with configurable movie site URL
- **Visual Cursor** that moves smoothly with DPAD controls
- **Dual Mode System**: Focus Mode (native TV navigation) <> Cursor Mode (visual cursor)
- **Choreographer Physics**: Frame-perfect 60fps movement with configurable acceleration
- **IME Discipline**: Automatic cursor hiding when text inputs are focused
- **Edge Scrolling**: Smooth page scrolling when cursor reaches screen edges

## Quick Start

### Prerequisites
- Android Studio Arctic Fox or later
- Android TV device or emulator
- Minimum SDK 21 (Android 5.0)

### Build & Run

1. **Clone and open in Android Studio**
   ```bash
   cd pstream
   # Open in Android Studio
   ```

2. **Configure Movie Site URL**
   - Edit `app/build.gradle.kts`:
   ```kotlin
   buildConfigField("String", "MOVIE_SITE_URL", "\"https://your-movie-site.com\"")
   ```

3. **Build and install**
   ```bash
   ./gradlew assembleDebug
   # Install on TV device/emulator
   ```

4. **Run on Android TV**
   - The app will launch in Focus Mode (native TV navigation)
   - Press **PLAY/PAUSE** to toggle to Cursor Mode
   - Use DPAD to move cursor, CENTER to click

## Controls

### Mode Switching
- **PLAY/PAUSE**: Toggle between Focus Mode ↔ Cursor Mode
- **BACK**: Exit Cursor Mode → Focus Mode (or go back in WebView)

### Cursor Mode
- **DPAD**: Move cursor with smooth acceleration
- **CENTER/ENTER**: Click at cursor position
- **INFO/Y**: Snap cursor to nearest clickable element

### Focus Mode
- **DPAD**: Native Android TV focus navigation
- **CENTER/ENTER**: Activate focused element



## Troubleshooting
- **SEARCHING CONTENT**: When using the main search feature, sometimes it does not select the text input field properly: fix by double clicking in a fast succession until you the digital keyboard properly show
- **FUNCTIONALITY** -- If the wrapper is slow, acting weird or otherwise: turn on Low Performance Mode in Pstream settings.
- **INSTALLATION**: Use ADB when possible for the smoothest experience, during initial setup you can use ``adb shell input text`` command for faster input.



### Code Structure

```
app/src/main/
├── AndroidManifest.xml          # TV permissions & intent filters
├── java/com/example/pstream/
│   ├── TvWebActivity.kt         # Main activity
│   ├── CursorOverlay.kt         # Visual cursor view
│   ├── CursorController.kt      # Physics & state machine
│   └── JSBridge.kt              # Android-JS communication
├── assets/
│   └── tv_cursor.js             # Snap-to helper script
└── res/values/
    ├── configs.xml              # Physics tuning constants
    └── strings.xml              # App strings
```
