# Wavo for Android

Local dictation on a phone: a voice keyboard that types what you say into any app, and a screen to dictate, copy and share. Speech is recognized on the phone by [wavo](https://github.com/vladkens/wavo), the engine of the desktop app, with the same models; only a model's download uses the network.

A first version: it builds, and its engine and JNI layer are tested on a computer, but it has not run on a phone yet. The baseline phone is a Pixel 7 Pro.

## What is here

- `core/`: a Rust library, the engine behind JNI. It wraps wavo (the CPU backend with NEON, or Vulkan), downloads models into the app's folder (its own downloader: wavo's takes file locks, which Rust lacks on Android), and reuses the desktop app's `src/cleanup.rs` and `src/numbers.rs` as they are. It keeps the engine's threads off the phone's slowest cores.
- `app/`: the Kotlin app. `WavoIme` is the keyboard, `MainActivity` the screens (Dictate, Models, History, Settings), `Engine` the one thread that owns the model.

## Build

Needs the Android SDK with NDK 27.2.12479018, Rust with the `aarch64-linux-android` target, [cargo-ndk](https://github.com/bbqsrc/cargo-ndk) and Gradle 8.13 or later (no Gradle wrapper is kept in git).

```sh
rustup target add aarch64-linux-android
cargo install --locked cargo-ndk
cd android
gradle :app:assembleDebug        # builds core/ first; the APK is in app/build/outputs/apk/debug/
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-Pwavo.abis=arm64-v8a,x86_64` adds the emulator's ABI (`rustup target add x86_64-linux-android` first).

## Test

```sh
cd android/core && cargo test --release       # the wrapper and the desktop cleanup's tests
cd android && gradle :app:testDebugUnitTest   # the Kotlin logic
```

With a model and a recording, the engine's test transcribes on the CPU as the phone does:

```sh
WAVO_TEST_MODEL=parakeet-ultra-Q8_0.gguf WAVO_TEST_WAV=jfk.wav WAVO_TEST_TEXT=country \
  cargo test --release transcribes -- --nocapture
```

## Use

1. Open Wavo, download a model in Models (Parakeet Ultra for 25 European languages, GigaAM v3 for Russian, Moonshine for the smallest) and allow the microphone at the first dictation.
2. Settings › Keyboard › Turn on, and enable "Wavo voice keyboard".
3. In any app, switch to the Wavo keyboard, then tap the microphone and tap again to stop, or hold it and speak. ⌨ goes back to the previous keyboard.

## Not yet

- The text while speaking (the desktop's live preview), and pause detection.
- A system speech recognizer service, for other keyboards' microphone buttons.
- Recordings in the history, and transcribing audio files.
- A download that goes on with the app closed: it stops with the app and resumes at the next tap on Download.
- The GPU backend is a setting, untested on phone GPUs.
