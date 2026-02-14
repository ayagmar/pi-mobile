# Testing Pi Mobile

## Running on Emulator

### 1. Start an Emulator

**Option A: Via Android Studio**
- Open Android Studio
- Tools → Device Manager → Create Device
- Pick a phone (Pixel 7 recommended)
- Download a system image (API 33 or 34)
- Click the play button to launch

**Option B: Via Command Line**

List available emulators:
```bash
$ANDROID_HOME/emulator/emulator -list-avds
```

Start one:
```bash
$ANDROID_HOME/emulator/emulator -avd Pixel_7_API_34 -netdelay none -netspeed full
```

### 2. Build and Install

Build the debug APK:
```bash
./gradlew :app:assembleDebug
```

Install on the running emulator:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or build + install in one go:
```bash
./gradlew :app:installDebug
```

### 3. Launch the App

The app should appear in the app drawer. Or launch via adb:
```bash
adb shell am start -n com.ayagmar.pimobile/.MainActivity
```

### 4. View Logs

Watch logs in real-time:
```bash
# All app logs
adb logcat | grep "PiMobile"

# Performance metrics
adb logcat | grep "PerfMetrics"

# Frame jank detection
adb logcat | grep "FrameMetrics"

# Everything
adb logcat -s PiMobile:D PerfMetrics:D FrameMetrics:D
```

## Testing with the Bridge

Since the app needs the bridge to function:

### 1. Start the Bridge on Your Laptop

```bash
cd bridge
pnpm install  # if not done
pnpm start
```

The bridge will print your Tailscale IP and port.

### 2. Configure the App

In the emulator app:
1. Tap "Add Host"
2. Enter your laptop's Tailscale IP (e.g., `100.x.x.x`)
3. Port: `8080` (or whatever the bridge uses)
4. Token: whatever you set in `bridge/.env` (default: none, or set `AUTH_TOKEN`)

### 3. Test the Connection

If the app shows "Connected" and lists your sessions, it's working.

If not, check:
- Is Tailscale running on both laptop and emulator host?
- Can the emulator reach your laptop? Test with: `adb shell ping 100.x.x.x`
- Is the bridge actually running? Check with: `curl http://100.x.x.x:8080/health`

## Common Issues

### "No hosts configured" shows immediately

Normal on first launch. Tap the hosts icon (top bar) to add one.

### "Connection failed"

- Check Tailscale is running on both ends
- Verify the IP address is correct
- Make sure the bridge is listening on 0.0.0.0 (not just localhost)
- Check `bridge/.env` has correct `PORT` and `AUTH_TOKEN`

### Sessions don't appear

- Check `~/.pi/agent/sessions/` exists on your laptop
- The bridge needs read access to that directory
- Check bridge logs for errors

### App crashes on resume

- Check `adb logcat` for stack traces
- Large sessions might cause OOM - try compacting first in pi

## Quick Development Cycle

For rapid iteration:

```bash
# Terminal 1: Keep logs open
adb logcat | grep -E "PiMobile|PerfMetrics|FrameMetrics"

# Terminal 2: Build and install after changes
./gradlew :app:installDebug

# The app stays open, just reinstalls
```

Or use Android Studio's "Apply Changes" for hot reload of Compose previews.

## Running Tests

Unit tests (on JVM):
```bash
./gradlew test
```

All quality checks:
```bash
./gradlew ktlintCheck detekt test
```

Bridge tests:
```bash
cd bridge && pnpm test
```
