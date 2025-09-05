## A pstream wrapper for Android TV.

## Installation

To sideload the APK, it is recommended to use ADB

- `adb install pstream-unofficial-wip.apk`
- use the `adb shell input text "xyz"` command to enter long form text in Pstream settings.
- enjoy and let me know if any issues!


## Configuration

Movement and appearance can be customized in `app/src/main/res/values/config.xml`: just rebuild in AStudio

- `cursor_size`: Size of the visual cursor (default: 120px)
- `cursor_alpha`: Transparency of cursor (default: 200/255)
- `move_step`: Cursor movement speed (default: 20px)
- `scroll_step`: Page scroll amount when cursor hits edge (default: 80px)
- `frame_delay`: Animation frame rate (default: 16ms ~60fps)
- `inactivity_timeout`: Time before cursor auto-hides (default: 10 seconds)
- `cache_size_mb`: HTTP cache size (default: 50MB)

## Building

1. Ensure Android SDK and Gradle are installed
2. Clone/copy the project to your workspace
3. Open in Android Studio or build with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```

## Usage

- Use D-pad to move the visual cursor
- Press OK/Enter to click at cursor position
- Cursor automatically hides during video playback
- Press OK during video to show cursor again
- Back button navigates web history or exits fullscreen video

## Architecture

- **MainActivity.kt**: Core app logic with cursor control and WebView management
- **Lean MVP Design**: Focused on essential streaming features without bloat
- **Kotlin**: Modern Android development with null safety
- **WebView**: Standard Android WebView with enhanced TV navigation
- **OkHttp**: Efficient networking with caching for video streams

## Permissions

- `INTERNET`: Web browsing and streaming
- `ACCESS_NETWORK_STATE`: Network connectivity detection

## TV Optimization

- Landscape orientation forced for optimal viewing
- Touchscreen features disabled for D-pad navigation
- Hardware acceleration enabled for smooth performance
