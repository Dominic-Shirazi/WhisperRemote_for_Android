# WhisperRemote for Android

A floating-button dictation app that records audio, sends it to a self-hosted [OpenAI Whisper](https://github.com/openai/whisper) server, and pastes the transcription directly into whatever text field is active — system-wide.

## How It Works

1. A persistent floating microphone button overlays all apps.
2. Tap to record; tap again to stop.
3. The audio is sent to your Whisper server over HTTP.
4. The transcription is injected into the currently focused text field via Android's Accessibility Service.

No cloud, no subscription — just your own hardware running Whisper.

## Requirements

- Android 14+ (API 34)
- A self-hosted Whisper server (see server setup below)
- Network access to the server — [Tailscale](https://tailscale.com/) works great for remote access

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Capture microphone input |
| `SYSTEM_ALERT_WINDOW` | Display the floating button over other apps |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| Accessibility Service | Paste transcription into the active text field |

## Setup

### 1. Server

Run a Whisper HTTP server on your machine. A minimal Flask example:

```python
from flask import Flask, request, jsonify
import whisper, tempfile, os

app = Flask(__name__)
model = whisper.load_model("base")

@app.route("/transcribe", methods=["POST"])
def transcribe():
    f = request.files["file"]
    with tempfile.NamedTemporaryFile(delete=False, suffix=".m4a") as tmp:
        f.save(tmp.name)
        result = model.transcribe(tmp.name)
        os.unlink(tmp.name)
    return jsonify({"text": result["text"]})

if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

### 2. App Configuration

1. Install and open **WhisperRemote**.
2. Enter your server URL in the text field (default is a Tailscale IP — replace with yours).
3. Tap **Save Settings** and grant all requested permissions.
4. Enable the **WhisperRemote Accessibility Service** when prompted.

### 3. Usage

- The floating microphone button will appear over all apps.
- Tap it to start recording; tap again to send and transcribe.
- The transcription is pasted into the active text field automatically.

## Building from Source

```bash
git clone https://github.com/Dominic-Shirazi/WhisperRemote_for_Android.git
cd WhisperRemote_for_Android
./gradlew assembleDebug
```

Open in Android Studio (Ladybug or newer) for full IDE support.

## License

MIT — see [LICENSE](LICENSE).

## Attributions

See [ATTRIBUTIONS.md](ATTRIBUTIONS.md) for third-party library licenses.