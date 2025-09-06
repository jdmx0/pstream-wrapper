# Android TV Web App with Visual Cursor

A production-ready Android TV application that wraps a movie website in a fullscreen WebView with a smooth, DPAD-controlled visual cursor system.

## Features

- **Fullscreen WebView** with configurable movie site URL
- **Visual Cursor** that moves smoothly with DPAD controls
- **Dual Mode System**: Focus Mode (native TV navigation) ↔ Cursor Mode (visual cursor)
- **Choreographer Physics**: Frame-perfect 60fps movement with configurable acceleration
- **IME Discipline**: Automatic cursor hiding when text inputs are focused
- **Snap-to-Clickable**: Jump cursor to nearest interactive element
- **Edge Scrolling**: Smooth page scrolling when cursor reaches screen edges

## Architecture

```
TvWebActivity (Main Activity)
├── CursorOverlay (Visual cursor View)
├── CursorController (Physics & state management)
├── JSBridge (Android ↔ JavaScript communication)
└── tv_cursor.js (Snap-to helper)
```

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

## Configuration

### Physics Tuning

Edit `app/src/main/res/values/configs.xml`:

```xml
<resources>
    <!-- Cursor Physics -->
    <integer name="max_speed_px_s">1400</integer>      <!-- Max cursor speed (px/sec) -->
    <integer name="accel_px_s2">4200</integer>        <!-- Acceleration (px/sec²) -->
    <integer name="friction_px_s2">4800</integer>     <!-- Deceleration friction -->
    <integer name="edge_margin_dp">32</integer>       <!-- Edge scroll margin -->

    <!-- Cursor Visual -->
    <integer name="cursor_core_radius_dp">16</integer> <!-- Inner dot radius -->
    <integer name="cursor_ring_radius_dp">6</integer>   <!-- Outer ring radius -->
</resources>
```

### Tuning Guide

#### Smoothness Issues
- **Choppy movement**: Reduce `max_speed_px_s` or increase `friction_px_s2`
- **Too fast acceleration**: Decrease `accel_px_s2`
- **Overshoot on stop**: Increase `friction_px_s2`

#### Edge Scrolling
- **Scroll too sensitive**: Increase `edge_margin_dp`
- **Scroll too slow**: Decrease `edge_margin_dp`

#### Visual Cursor
- **Too small**: Increase `cursor_core_radius_dp` and `cursor_ring_radius_dp`
- **Hard to see**: Cursor automatically adapts to content contrast

## Troubleshooting

### Common Issues

#### "Choppy vs Smooth" Movement
**Symptoms**: Cursor movement feels inconsistent or jerky

**Solutions**:
1. **Check dt clamping**: Ensure `dtS` is clamped to reasonable values (< 100ms)
2. **Verify Choreographer**: Movement must use `Choreographer.postFrameCallback`
3. **Check key repeat**: Never base movement on key repeat count
4. **Frame rate**: Ensure 60fps target (no battery saver interference)

#### IME Stuck Issues
**Symptoms**: Cursor doesn't hide when typing, or stays hidden after typing

**Solutions**:
1. **Check JS injection**: Ensure `tv_cursor.js` loads successfully
2. **Verify focus events**: DOM `focusin`/`focusout` events must call `TVBridge.onDomFocusChanged()`
3. **IME visibility**: Use `WindowInsets` listener for IME detection

#### Mode Toggle Problems
**Symptoms**: Can't switch between Focus/Cursor modes

**Solutions**:
1. **Check PLAY/PAUSE key**: Ensure key event reaches `dispatchKeyEvent`
2. **State guards**: Verify no conflicting state prevents mode changes
3. **Logging**: Enable debug logging to trace mode switches

### Debug Logging

Enable verbose logging in `CursorController.kt`:
```kotlin
private const val TAG = "CursorController"
// Add logging around mode changes, IME events, focus changes
```

### Performance

#### Frame Drops
- **Cause**: Heavy WebView content or complex physics calculations
- **Solution**: Clamp dt, reduce physics complexity, optimize drawing

#### Memory Issues
- **Cause**: Large WebView cache or leaked MotionEvents
- **Solution**: Set reasonable cache size, recycle MotionEvents properly

## Testing

### Acceptance Tests

#### 1. Smoothness Test
- Hold DPAD_RIGHT for 2+ seconds
- **Expected**: Smooth acceleration to max speed, clean deceleration on release
- **Failure**: Jerkiness, inconsistent speed, key-repeat artifacts

#### 2. Edge Scroll Test
- Move cursor to screen edge
- **Expected**: Smooth page scrolling with inertia
- **Failure**: Jerky scrolling, no inertia, wrong direction

#### 3. Click Test
- Position cursor over button/link
- Press CENTER
- **Expected**: Reliable activation of target element
- **Failure**: Miss-clicks, phantom clicks, wrong target

#### 4. IME Discipline Test
- Navigate to search input field
- Press CENTER to focus
- **Expected**: Cursor hides, DPAD moves text caret
- Press BACK to unfocus
- **Expected**: Previous cursor mode restored

#### 5. Mode Toggle Test
- Press PLAY/PAUSE repeatedly
- **Expected**: Reliable switching without stuck states
- **Failure**: Mode gets stuck, requires restart

## Development

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

### Adding Features

#### New Key Bindings
Add to `TvWebActivity.dispatchKeyEvent()`:
```kotlin
when (event.keyCode) {
    KeyEvent.KEYCODE_NEW_KEY -> {
        // Handle new feature
        return true
    }
}
```

#### Custom Physics
Modify `CursorController.updatePhysics()`:
```kotlin
// Add custom easing or physics
velocityX = customEasing(velocityX, targetVelocityX, dtS)
```

#### JS Integration
Extend `JSBridge` for new web features:
```kotlin
@JavascriptInterface
fun onCustomEvent(data: String) {
    // Handle custom web events
}
```

## Security Notes

- **Input Validation**: All user inputs validated before processing
- **JS Injection**: Only trusted scripts loaded from assets
- **Permissions**: Minimal permissions (INTERNET only)
- **WebView Security**: JavaScript enabled only for required features

## License

This project is provided as-is for educational and development purposes.

## Contributing

1. Follow Android TV design guidelines
2. Maintain 60fps performance target
3. Test on real Android TV hardware
4. Document configuration changes
5. Include acceptance test coverage