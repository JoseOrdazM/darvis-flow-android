# Darvis Flow — Android

Voice-to-text with a floating overlay. Hold the bubble, speak, text is copied to your clipboard. Optionally restructure speech into prompts via cloud LLM.

## Features

- 🫧 **Floating bubble** — Overlay that works on top of any app
- 🎙️ **Hold to record** — Hold bubble → record, release → transcribe
- ☁️ **Cloud Whisper** — OpenAI Whisper API for fast, accurate transcription
- ⚡ **Prompt Mode** — Cloud LLM restructures speech into organized prompts
- 📋 **Clipboard + Toast** — Result copied automatically, paste anywhere
- 🔔 **Notification** — Full text in expandable notification

## Setup

1. Open in Android Studio
2. Build and run on device (API 26+)
3. Enter your OpenAI API key in settings
4. Tap "Start Darvis"
5. Grant permissions (mic, overlay, notifications)
6. Hold the floating bubble to record!

## Prompt Mode

Optional. Toggle in settings, then configure:
- **Provider**: Anthropic, OpenAI, or OpenRouter
- **Model**: Any model the provider supports
- **API Key**: Per-provider key

When enabled, transcribed text is sent to the LLM for restructuring before copying to clipboard. Falls back to raw text on any error.

## Architecture

```
MainActivity.kt        — Settings screen (API keys, provider, toggle)
├── util/Settings.kt   — DataStore persistence
overlay/
├── OverlayService.kt  — Foreground service + floating bubble + pipeline
├── AudioRecorder.kt   — MediaRecorder wrapper (M4A/AAC)
api/
├── WhisperApi.kt      — OpenAI Whisper API client
├── PromptStructurer.kt — Cloud LLM (Anthropic/OpenAI/OpenRouter)
```

## Pipeline

```
Hold bubble → Record audio (M4A) → Release →
  Whisper API transcription →
  [Optional: Prompt Mode structuring] →
  Copy to clipboard + Toast + Notification
```

## Permissions

| Permission | Why |
|-----------|-----|
| `RECORD_AUDIO` | Microphone capture |
| `SYSTEM_ALERT_WINDOW` | Floating bubble overlay |
| `FOREGROUND_SERVICE` | Keep recording alive in background |
| `INTERNET` | Whisper + LLM API calls |
| `POST_NOTIFICATIONS` | Result notifications |

## Phase 2 (Future)

- AccessibilityService for auto-paste into focused input field
- Toggle mode (tap to start/stop)
- Waveform animation on bubble during recording
- Per-provider API key storage

## Tech Stack

- Kotlin + Android SDK (minSdk 26)
- OkHttp for networking
- DataStore for settings persistence
- Material 3 dark theme (matches desktop Darvis brand)

## Related

- [Darvis Flow (Desktop)](https://github.com/DaviRolim/dravis-flow) — Tauri + Rust version for macOS/Windows
