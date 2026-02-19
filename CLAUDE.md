# CLAUDE.md

## Project Overview

Darvis Flow Android — floating overlay voice-to-text app. Hold a bubble, speak, text is copied to clipboard. Optional Prompt Mode sends text to a cloud LLM for restructuring.

## Commands

```bash
# Build (from Android Studio or command line)
./gradlew assembleDebug
./gradlew installDebug    # Install on connected device
```

## Architecture

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Settings screen — API keys, provider selector, start/stop overlay |
| `DarvisApp.kt` | Application class — notification channel setup |
| `overlay/OverlayService.kt` | Foreground service with floating bubble, recording pipeline |
| `overlay/AudioRecorder.kt` | MediaRecorder wrapper — records M4A/AAC to cache dir |
| `api/WhisperApi.kt` | OpenAI Whisper API — sends audio, returns transcription |
| `api/PromptStructurer.kt` | Cloud LLM call — Anthropic/OpenAI/OpenRouter |
| `util/Settings.kt` | DataStore persistence for all settings |

## Key Patterns

- **Foreground Service** keeps recording alive when app is backgrounded
- **SYSTEM_ALERT_WINDOW** for the floating bubble overlay
- **FLAG_NOT_FOCUSABLE** on bubble window — doesn't steal focus from other apps
- **DataStore** for settings (not SharedPreferences)
- **OkHttp** for all HTTP calls (Whisper API + LLM providers)
- **Coroutines** for async work (recording → transcription → structuring → clipboard)
- **Result<T>** pattern for error handling — fallback to raw text on any failure

## Pipeline Flow

```
Hold bubble → AudioRecorder.start() → M4A file in cache →
Release → AudioRecorder.stop() →
WhisperApi.transcribe(file) → text →
[if prompt_enabled] PromptStructurer.structure(text) → structured →
ClipboardManager.setPrimaryClip() → Toast → Notification
```

## Settings Storage

All settings in DataStore (`darvis_settings`):
- `whisper_api_key` — OpenAI API key for Whisper
- `prompt_enabled` — boolean toggle
- `prompt_provider` — "anthropic" | "openai" | "openrouter"
- `prompt_model` — model string (e.g. "anthropic/claude-3.5-haiku")
- `prompt_api_key` — LLM provider API key

## Design

- Dark theme matching desktop Darvis brand (#0D0D12 background, #B450FF accent)
- Bubble: 64dp circle with purple border, mic icon
- Recording state: icon tints magenta
- Processing state: icon dims to 50% alpha

## Permissions Required

RECORD_AUDIO, SYSTEM_ALERT_WINDOW, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, INTERNET, POST_NOTIFICATIONS
