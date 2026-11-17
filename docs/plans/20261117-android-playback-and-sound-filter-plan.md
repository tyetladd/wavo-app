# Android Playback Bar and Sound Filter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Playback controls in History while a recording plays, and an optional high-pass + noise gate + quiet-voice lift before recognition.

**Architecture:** The playback bar is Compose state around the `MediaPlayer` History already owns. The filter is a new pure-`f32` module in the `android/core` Rust crate, applied inside `Engine::transcribe` behind a new boolean that travels Kotlin settings → JNI → engine, so live dictation pieces and *Transcribe…* re-runs share one choke point. The kept WAV stays raw.

**Tech Stack:** Kotlin + Jetpack Compose (material3), Rust (`android/core`, its own workspace, JNI via `jni` 0.21), JUnit4, cargo test.

**Spec:** `docs/plans/20261117-android-playback-and-sound-filter.md`

## Global Constraints

- Every new source file starts with `// Copyright (c) tyetladd | MIT License | https://github.com/tyetladd/wavo-app` (Android's own files).
- A commit is one short lowercase line, no body.
- No new dependencies: the filter is hand-written DSP; the bar reuses `MediaPlayer` and the existing `ic_play` / `ic_pause` / `ic_stop` drawables.
- No implicit settings: the filter runs only when `settings.filter` is on; the UI shows the stored value; default **off**.
- Build and test with the release profile: `cd android/core && cargo test --release`; `cd android && gradle :app:testDebugUnitTest`; APK via `gradle :app:assembleDebug` (builds core first).
- The filter preserves sample count and the `[-1, 1]` range; it never writes to the kept WAV.
- Screens are verified on a phone (the project's readme: unit tests have no Android behind them); device work goes through ARTEMIS, and the device is the person's choice when more than one is connected.
- No generated or binary files in git; test signals are synthesized in the test code.

## Review Focus

- **Seek and rapid row-switching on a released player** — dragging the slider just as a recording completes, or tapping Play on another row mid-play, must not throw; the bar hides or moves cleanly. Pinned in Task 4's device steps.
- **Tiny and empty pieces** — live pieces can be shorter than 0.3 s; the filter must return them without panic, length preserved. Pinned by `empty_and_tiny_inputs_pass_through` in Task 2.
- **All-speech recordings** — no quiet frames to learn a floor from; the median-relative gate must not chop consonants (the missing-words complaint). Pinned by `a_quiet_continuous_voice_survives_and_is_lifted` in Task 2.
- **JNI arity drift** — `Native.transcribe` gains a parameter on both sides at once; a mismatch is a runtime `UnsatisfiedLinkError`, invisible to the compiler. Pinned by Task 4's dictate-with-filter-on/off smoke.
- **Toggling the switch mid-dictation** — pieces already submitted keep the old value, new pieces read the new one; nothing crashes or restarts. Pinned in Task 4's device steps.

---

### Task 1: Playback bar in History

**Files:**
- Modify: `android/app/src/main/java/io/github/tyetladd/wavo/MainActivity.kt` (HistoryScreen, ~628–811; imports ~26–92)

**Interfaces:**
- Consumes: `clock(ms: Long): String` (`Dictation.kt:332`, formats `m:ss`); drawables `R.drawable.ic_play` / `ic_pause` / `ic_stop`; the existing `player: MediaPlayer?` state and `play(entry)` in HistoryScreen.
- Produces: nothing later tasks rely on (UI only).

- [ ] **Step 1: Add the playback state and wire `play()`**

In `HistoryScreen`, beside `var player …` add:

```kotlin
var paused by remember { mutableStateOf(false) }
var position by remember { mutableIntStateOf(0) }
var duration by remember { mutableIntStateOf(0) }
var drag by remember { mutableStateOf<Float?>(null) }
```

In `play(entry)`: after `prepare()` set `duration = this.duration`, `paused = false`, `position = 0`, `drag = null`. Add `fun stop() { player?.release(); player = null }`; the completion listener already releases and nulls `player`, which hides the bar. The existing `DisposableEffect` release stays.

- [ ] **Step 2: Add the bar below the list**

Wrap the `LazyColumn` in a `Column(Modifier.fillMaxSize())`; the list takes `Modifier.weight(1f)`. After it, while `player != null`, one `Row` (fillMaxWidth, padding horizontal 16.dp / vertical 4.dp, `CenterVertically`):

```
⏸/▶ IconButton   Slider(weight(1f))   "0:12 / 1:03" Text(bodySmall)   ⏹ IconButton
```

- Pause/resume: `IconButton` with `painterResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)`; onClick toggles `MediaPlayer.pause()` / `start()` and `paused`.
- Slider: `value = (drag ?: position.toFloat())`, `valueRange = 0f..duration.toFloat().coerceAtLeast(1f)`, `onValueChange = { drag = it }`, `onValueChangeFinished = { drag?.let { player?.seekTo(it.toInt()); position = it.toInt() }; drag = null }`.
- Time: `Text("${clock((drag ?: position.toFloat()).toLong())} / ${clock(duration.toLong())}")`.
- Stop: `IconButton` with `painterResource(R.drawable.ic_stop)`, onClick `stop()`.
- Position poll: `LaunchedEffect(player) { val mp = player ?: return@LaunchedEffect; while (true) { position = mp.currentPosition; delay(250) } }` (cancelled automatically when `player` changes or the screen leaves).
- Add import `androidx.compose.material3.Slider`; `mutableIntStateOf` and `delay` follow the file's existing imports.
- The row's `Play` button is unchanged.

- [ ] **Step 3: Build and run the Kotlin tests**

Run: `cd android && gradle :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; existing LogicTest/PillTest pass (the bar has no pure logic of its own — `clock` is already tested by use; device verification is Task 4).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/io/github/tyetladd/wavo/MainActivity.kt
git commit -m "android: playback controls in history"
```

---

### Task 2: The sound filter DSP

**Files:**
- Create: `android/core/src/filter.rs`
- Modify: `android/core/src/lib.rs` (add `mod filter;` beside `mod cores;`)

**Interfaces:**
- Consumes: nothing.
- Produces: `pub fn clean(pcm: &[f32]) -> Vec<f32>` in `filter` — one pass, high-pass → gate → lift, same sample count, 16 kHz mono `[-1, 1]`. Task 3 calls `filter::clean(pcm)`.

- [ ] **Step 1: Write the failing tests**

In `filter.rs`'s `#[cfg(test)] mod tests`, synthesize signals at `SAMPLE_RATE = 16_000` (a local const; `sine(hz, secs, amp)` helper, and a deterministic LCG noise helper — no fixtures, no `hound`):

```rust
#[test] fn high_pass_cuts_rumble_and_keeps_voice()
// 1 s of 50 Hz at 0.9: output rms < 0.05 × input rms.
// 1 s of 1 kHz at 0.9: output rms within 10% of input rms.

#[test] fn gate_silences_a_noise_only_stretch()
// 0.5 s of 1 kHz at 0.5, 0.5 s of LCG noise at 0.005, 0.5 s of tone again.
// The middle stretch's output rms < 0.1 × the tone stretches' output rms.

#[test] fn hangover_keeps_the_tail_after_voice()
// 0.3 s of 1 kHz at 0.5, then 0.15 s of noise at 0.01, then 0.5 s of silence.
// The 0.15 s stretch keeps rms > 0.5 × its input rms (gate held open 200 ms).

#[test] fn lift_raises_a_quiet_voice_with_a_cap()
// 1 kHz at 0.3 for 0.5 s: output peak in 0.93..=0.97 (lifted toward 0.95).
// 1 kHz at 0.02 for 0.5 s: output peak in 0.19..=0.21 (gain capped at ×10).

#[test] fn a_quiet_continuous_voice_survives_and_is_lifted()
// 1 kHz at 0.01 for 1 s — an all-speech recording, no quiet frames.
// Output peak >= 0.09 (not gated away; lifted ×10). The anti-goal test.

#[test] fn empty_and_tiny_inputs_pass_through()
// clean(&[]) is empty; clean(&[0.0; 160]) has len 160 and stays silent;
// clean(&[0.5; 10]) has len 10, no panic.
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd android/core && cargo test --release filter::`
Expected: FAIL — `clean` not defined (add `mod filter;` to `lib.rs` and a stub `pub fn clean(pcm: &[f32]) -> Vec<f32> { pcm.to_vec() }` first so the failure is assertion, not compile).

- [ ] **Step 3: Implement `clean`**

Three stages over the samples, in order; the constants are the spec's starting points:

1. **High-pass** — RBJ biquad, 2nd-order Butterworth, `fc = 80.0`, `Q = 0.7071`, `fs = 16_000`:
   `w0 = 2π·fc/fs`, `alpha = sin(w0)/(2Q)`;
   `b0 = b2 = (1+cos w0)/2`, `b1 = -(1+cos w0)`; `a0 = 1+alpha`, `a1 = -2·cos w0`, `a2 = 1-alpha`;
   divide all by `a0`; run transposed direct-form II, state starts at zero.
2. **Gate** — 320-sample (20 ms) frames, frame RMS; threshold = median frame RMS × `10f32.powf(-25.0/20.0)` (≈0.056). A frame opens the gate when its RMS > threshold; it stays open 10 frames (200 ms) past the last open frame (hangover). Per-sample gain moves toward 1 (open) or 0 (closed) with one-pole coefficients `attack = 1 − exp(−1/(0.005·16_000))`, `release = 1 − exp(−1/(0.100·16_000))`; multiply the sample by the gain. Empty input: return empty before any of this; a zero median closes nothing extra (threshold 0 → every nonzero frame opens).
3. **Lift** — `peak = max|x|`; if `0 < peak < 0.95`, multiply everything by `(0.95/peak).min(10.0)`. Boost only.

- [ ] **Step 4: Run to verify they pass**

Run: `cd android/core && cargo test --release filter::`
Expected: all six PASS. If the gate constants fail a test, tune the constants (not the tests' intent) and note the final values in a doc comment on `clean`.

- [ ] **Step 5: Lint and commit**

Run: `cd android/core && cargo +nightly fmt && cargo clippy --release --all-targets -- -D warnings`
Expected: clean.

```bash
git add android/core/src/filter.rs android/core/src/lib.rs
git commit -m "android: a high-pass, gate and lift for the sound"
```

---

### Task 3: Wire the filter through engine, JNI and settings

**Files:**
- Modify: `android/core/src/engine.rs:172-178` (signature + filter before the silence check) and `:254` (existing test call)
- Modify: `android/core/src/jni.rs:201-218` (doc comment, signature, call)
- Modify: `android/app/src/main/java/io/github/tyetladd/wavo/Native.kt:36`
- Modify: `android/app/src/main/java/io/github/tyetladd/wavo/Engine.kt:78-110` (both functions)
- Modify: `android/app/src/main/java/io/github/tyetladd/wavo/App.kt` (Settings, beside `var clean` ~32)
- Modify: `android/app/src/main/java/io/github/tyetladd/wavo/MainActivity.kt` (settings state ~927, Recording section ~1119)

**Interfaces:**
- Consumes: `filter::clean(pcm: &[f32]) -> Vec<f32>` (Task 2).
- Produces: `Engine::transcribe(&self, pcm: &[f32], language: Option<&str>, clean: bool, filter: bool) -> Result<Heard>`; `Native.transcribe(handle: Long, pcm: FloatArray, language: String?, clean: Boolean, filter: Boolean): String`; `Engine.transcribe(pcm, clean: Boolean? = null, filter: Boolean? = null, done)` and `transcribeWith(model, pcm, clean = null, filter = null, done)`; `App.settings.filter: Boolean` (prefs key `"filter"`, default false).

- [ ] **Step 1: Engine side**

`engine.rs`: add `filter: bool` as the last parameter of `transcribe`. Before the `MIN_SAMPLES`/`SILENCE` check:

```rust
let filtered;
let pcm = if filter { filtered = filter::clean(pcm); &filtered[..] } else { pcm };
```

so a quiet recording is lifted rather than skipped; `audio_ms` still comes from the (unchanged) length. Update the doc comment: `filter` runs the sound cleanup before the silence check. Update the existing test call at `:254` to `engine.transcribe(&pcm, None, true, false)`.

`jni.rs`: add `filter: jni::sys::jboolean` after `clean`, pass `filter != 0`, update the doc comment's signature to `transcribe(handle: Long, pcm: FloatArray, language: String?, clean: Boolean, filter: Boolean): String`.

- [ ] **Step 2: Rust tests and lint**

Run: `cd android/core && cargo test --release && cargo clippy --release --all-targets -- -D warnings`
Expected: PASS (the model test stays `#[ignore]`d), clippy clean.

- [ ] **Step 3: Kotlin side**

- `Native.kt:36`: append `filter: Boolean` to the external fun.
- `Engine.kt`: both `transcribe` and `transcribeWith` gain `filter: Boolean? = null` after `clean`, and pass `filter ?: settings.filter` as the last argument to `Native.transcribe`. Doc comments: `filter` is the setting's unless given, as `clean` is.
- `App.kt` Settings, beside `clean`:

```kotlin
/** A high-pass, a noise gate and a quiet-voice lift before recognition; the WAV stays raw. */
var filter: Boolean
    get() = prefs.getBoolean("filter", false)
    set(value) = prefs.edit().putBoolean("filter", value).apply()
```

- `MainActivity.kt` settings: `var filter by remember { mutableStateOf(settings.filter) }` beside the others (~927). In `Section("Recording")`, as its first Row (before "Record in the background"), the same Row/Column/Switch pattern as "Clean up the text":

```kotlin
Text("Clean up the sound")
Text(
    "A high-pass filter and a noise gate before recognition: cuts rumble and steady " +
        "noise, and lifts a quiet voice. The recording itself is kept untouched.",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
)
// Switch(checked = filter, onCheckedChange = { filter = it; settings.filter = it })
```

`Dictation.submit` and `HistoryScreen.retranscribe` need no change — the defaults pick the setting up.

- [ ] **Step 4: Build and Kotlin tests**

Run: `cd android && gradle :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests pass. (JNI arity is verified on the phone in Task 4 — the compiler cannot see it.)

- [ ] **Step 5: Commit**

```bash
git add android/core/src/engine.rs android/core/src/jni.rs \
  android/app/src/main/java/io/github/tyetladd/wavo/Native.kt \
  android/app/src/main/java/io/github/tyetladd/wavo/Engine.kt \
  android/app/src/main/java/io/github/tyetladd/wavo/App.kt \
  android/app/src/main/java/io/github/tyetladd/wavo/MainActivity.kt
git commit -m "android: clean up the sound, a setting before recognition"
```

---

### Task 4: On the phone, and the full check

**Files:** none new; fixes land in the files above if the device finds anything.

**Interfaces:**
- Consumes: the APK from Task 3 (`android/app/build/outputs/apk/debug/`), both features complete.
- Produces: verified behavior; the branch ready for review.

- [ ] **Step 1: Pick the device and install**

Run: `adb devices -l`. If more than one device is connected, ask the person which to use before anything else. Then `adb -s <serial> install -r android/app/build/outputs/apk/debug/app-debug.apk` (rebuild first if Task 3's APK is stale).

- [ ] **Step 2: ARTEMIS — the playback bar**

With `mobile_run_task` on the chosen device: open Wavo › History, tap Play on a kept recording, and verify — the bar appears with the clock running; pause freezes it and resume continues; dragging the slider seeks (audio jumps); stop hides the bar; a recording playing to its end hides the bar; Play on another row moves the bar; rapid Play-taps across rows and a seek at the moment of completion never crash the app.

- [ ] **Step 3: ARTEMIS — the filter**

Settings › Recording: "Clean up the sound" is present and off. Dictate a sentence with it off (normal text). Turn it on, dictate again — text still arrives (the JNI smoke), and the kept WAV plays back unchanged. In History, *Transcribe…* the same recording with the switch on and off: two versions appear, both pointing at one audio file. Toggle the switch while a background dictation runs: no crash, the dictation finishes.

- [ ] **Step 4: The full check**

Run: `make check && make test` (desktop, unaffected but the rule stands), then `cd android/core && cargo +nightly fmt --check && cargo clippy --release --all-targets -- -D warnings`, then `cd android && gradle :app:testDebugUnitTest`.
Expected: all pass. Fix and commit anything the device or the checks found, one short lowercase line per commit.
