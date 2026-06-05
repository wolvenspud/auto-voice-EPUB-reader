# AutoVoice EPUB Reader

An Android EPUB reader that reads books aloud with **per-character voices**. It imports EPUBs
(and scrapes Syosetu web novels), uses an LLM to attribute each line of dialogue to a speaker,
and synthesises distinct voices per character — narration in a neutral narrator voice, each
character in their own. Built for Japanese web novels but works on any EPUB.

Voices come from a **VOICEVOX** engine you run on your PC (free, high-quality Japanese TTS), with
**Android's built-in TTS** as a zero-config fallback. The engine layer is modular, so cloud TTS
backends (OpenAI, etc.) can be dropped in later.

## Download & install the app

1. Grab the APK from [`dist/auto-voice-reader-v1.0.0.apk`](dist/auto-voice-reader-v1.0.0.apk)
   (open it on GitHub and tap **Download**, or `git clone` and copy it off).
2. On your phone, enable **Install unknown apps** for your browser/file manager, then open the APK
   to install. (It's a debug build, self-signed — Android will warn about the unknown source.)

The app runs on Android 8.0 (API 26) and up.

## Run the VOICEVOX server on your PC

The per-character voices are synthesised by a VOICEVOX engine on your PC; the phone talks to it
over your local network. With [Docker](https://docs.docker.com/get-docker/) installed:

```bash
cd voicevox-server
docker compose up -d
```

This starts the engine listening on `0.0.0.0:50021` (reachable from other devices on your LAN) and
restarts automatically. To stop it: `docker compose down`.

GPU users can swap the image tag in `voicevox-server/docker-compose.yml` to `nvidia-latest` and add
a `deploy`/`gpus` block for faster synthesis.

## Point the app at your server

The app defaults to `http://192.168.0.161:50021`. If your PC's LAN IP differs, set the right URL in
the app: **Settings → VOICEVOX → Engine URL** (`http://<your-PC-LAN-IP>:50021`).

- Find your PC's LAN IP: `ip route get 1.1.1.1` (Linux) / `ipconfig` (Windows) / `ifconfig` (macOS).
- The phone and PC must be on the same network.
- An emulator on the build machine instead uses `http://10.0.2.2:50021` (host loopback).

## Per-character voices (optional, needs an LLM key)

1. In **Settings**, enable **Character attribution** and paste an Anthropic (Claude) or OpenAI API
   key. Attribution and auto-casting use a cheap model (`claude-haiku-4-5` / `gpt-4o-mini`).
2. Open a book → **Character Voices** → **Auto-cast**. Characters are listed by how often they
   speak; each is assigned a gender-matched VOICEVOX voice (narrator defaults to 青山龍星 / ノーマル).
3. Tap any character to override their voice. Changing a voice re-synthesises that character's audio.

Without a key, every line uses the single narrator voice.

## Building from source

See `CLAUDE.md` for the toolchain. In short: `JAVA_HOME=/opt/android-studio/jbr ./gradlew
:app:assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk` (Gradle 8.9, AGP 8.5.0,
compileSdk 35, minSdk 26).
