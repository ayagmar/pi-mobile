# Pi Mobile

An Android client for the [Pi coding agent](https://github.com/badlogic/pi-mono). Control your coding sessions from your phone over Tailscale.

## What This Does

Pi runs on your laptop. This app lets you:
- Browse and resume coding sessions from anywhere
- Chat with the agent, send prompts, abort, steer
- Switch between projects (different working directories)
- Manage models and thinking levels
- Handle extension dialogs (confirmations, inputs, selections)

The connection goes over Tailscale, so it works anywhere without port forwarding.

## Architecture

```
Phone (this app)  <--Tailscale-->  Laptop (bridge)  <--local-->  pi --mode rpc
```

The bridge is a small Node.js service that translates WebSocket to pi's stdin/stdout JSON protocol. The app connects to the bridge, not directly to pi.

## Setup

### 1. Laptop Setup

Install pi if you haven't:
```bash
npm install -g @mariozechner/pi-coding-agent
```

Clone and start the bridge:
```bash
git clone https://github.com/yourusername/pi-mobile.git
cd pi-mobile/bridge
pnpm install
pnpm start
```

The bridge binds to your Tailscale IP on port 8080 by default. It spawns pi processes on demand per working directory.

### 2. Phone Setup

Install the APK or build from source:
```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. Connect

1. Add a host in the app:
   - Host: your laptop's Tailscale IP (100.x.x.x)
   - Port: 8080 (or whatever the bridge uses)
   - Token: set this in bridge/.env as AUTH_TOKEN

2. The app will fetch your sessions from `~/.pi/agent/sessions/`

3. Tap a session to resume it

## How It Works

### Sessions

Sessions are grouped by working directory (cwd). Each session is a JSONL file in `~/.pi/agent/sessions/--path--/`. The bridge reads these files directly since pi's RPC doesn't have a list-sessions command.

### Process Management

The bridge manages one pi process per cwd:
- First connection to a project spawns pi
- Process stays alive with idle timeout
- Reconnecting reuses the existing process
- Crash restart with exponential backoff

### Message Flow

```
User types prompt
    ↓
App sends WebSocket → Bridge
    ↓
Bridge writes to pi stdin (JSON line)
    ↓
pi processes, writes events to stdout
    ↓
Bridge forwards events → App
    ↓
App renders streaming text/tools
```

## Troubleshooting

### Can't connect

1. Check Tailscale is running on both devices
2. Verify the bridge is running: `curl http://100.x.x.x:8080/health`
3. Check the token matches exactly
4. Try the laptop's Tailscale IP, not hostname

### Sessions don't appear

1. Check `~/.pi/agent/sessions/` exists on laptop
2. Verify the bridge has read permissions
3. Check bridge logs for errors

### Streaming is slow/choppy

1. Check logcat for `PerfMetrics` - see actual timing numbers
2. Look for `FrameMetrics` jank warnings
3. Verify WiFi/cellular connection is stable
4. Try closer to the laptop (same room)

### App crashes on resume

1. Check logcat for out-of-memory errors
2. Large session histories can cause issues
3. Try compacting the session first: `/compact` in pi, then resume

## Development

### Project Structure

```
app/              - Android app (Compose UI, ViewModels)
core-rpc/         - RPC protocol models and parsing
core-net/         - WebSocket transport and connection management
core-sessions/    - Session caching and repository
bridge/           - Node.js bridge service
```

### Running Tests

```bash
# Android tests
./gradlew test

# Bridge tests
cd bridge && pnpm test

# All quality checks
./gradlew ktlintCheck detekt test
```

### Logs to Watch

```bash
# Performance metrics
adb logcat | grep "PerfMetrics"

# Frame jank during streaming
adb logcat | grep "FrameMetrics"

# General app logs
adb logcat | grep "PiMobile"

# Bridge logs (on laptop)
pnpm start 2>&1 | tee bridge.log
```

## Configuration

### Bridge Environment Variables

Create `bridge/.env`:

```env
PORT=8080
AUTH_TOKEN=your-secret-token
IDLE_TIMEOUT_MS=300000
```

### App Build Variants

Debug builds include logging and assertions. Release builds (if you make them) strip these for smaller size.

## Security Notes

- Token auth is required - don't expose the bridge without it
- The bridge binds to all interfaces by default (change to Tailscale IP only if desired)
- All traffic goes over Tailscale's encrypted mesh
- Session data stays on the laptop; the app only displays it

## Limitations

- No offline mode - requires live connection to laptop
- Large tool outputs are truncated (400+ chars collapsed by default)
- Session history loads once on resume, not incrementally
- Image attachments not supported (text only)

## License

MIT
