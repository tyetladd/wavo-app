# Android: playback controls in History, and an optional sound filter

## Intent

Two requests after a day of real dictations on a cheap Bluetooth headset.

1. History's `Play` is fire-and-forget (`MainActivity.kt:694`): no pause, no seek, no stop, and the
   row still says "Play" while the audio runs. The person wants one line of playback controls —
   timeline, pause/resume, stop — visible only while something plays.
2. Transcriptions came out with missing and wrong words, possibly from the headset's low level.
   The person wants a cheap cleanup of the sound before recognition: a high-pass filter and a noise
   gate, optional, behind a switch in Settings. The heavier paths (Silero VAD, a neural denoiser)
   were considered and set aside for now.

## Goals

1. A playback bar at the bottom of History while a recording plays: seek slider with elapsed and
   total time, pause/resume, stop. Hidden when nothing plays.
2. A "Clean up the sound" switch in Settings › Recording, **off by default**, that runs a
   high-pass + noise gate + quiet-voice lift over the audio before the engine hears it — both live
   dictation pieces and *Transcribe…* re-runs.
3. The kept WAV stays the raw capture, so the same recording can be transcribed with the filter on
   and off and the two versions compared.
4. Unit tests for the filter on synthetic signals; `make check` and `make test` pass.

## Non-goals

- Neural denoising or VAD models (DeepFilterNet, Silero): heavier dependencies and CPU cost; revisit
  only if the DSP pass proves insufficient.
- Isolating voice from music or babble — a high-pass and a gate cannot do that.
- Filtering the saved WAV, or a filtered copy beside it.
- Playback outside History: no notification, no service, no lock-screen controls for playback.
- Any desktop change.

## Anti-goals

- Cutting quiet speech. Missing words is the complaint; a gate with a fixed threshold or no
  hangover would make it worse. The gate is relative to the recording's own level and holds open
  through word tails.
- Behaviour the person cannot see and change in Settings (the project's standing rule).
- Perceptible added latency: the filter is one O(n) pass over each piece, negligible beside the
  engine.

## Approaches

**Playback.** One sensible shape: a bar pinned to the bottom of the History screen, shown while
`player != null`. Per-card inline controls were the alternative; they jump around the list and are
not the "one line" asked for. The bar reuses the existing `ic_play` / `ic_pause` / `ic_stop`
drawables and the `MediaPlayer` already there — no new dependency.

**Filter.** Three ways were laid out: (a) high-pass + noise gate DSP, nearly free, no model;
(b) Silero VAD trimming, a ~2 MB model that cuts non-speech stretches; (c) a neural denoiser, true
voice isolation but heavy on the phone. The person picked (a). It lives in the Rust core
(`android/core`, its own crate) as a new `filter.rs`, pure `f32`, no new dependency — wavo itself
has no denoiser or VAD and accepts audio as given.

## Design

### Playback bar (History)

- `HistoryScreen` wraps its list in a column; below it, while `player != null`, one row:
  pause/resume `IconButton`, `Slider` (0…duration), `elapsed / total` text, stop `IconButton`.
- Position polls `currentPosition` every 250 ms in a `LaunchedEffect(player)`; dragging the slider
  shows the drag position and seeks on release (`onValueChangeFinished`).
- Pause/resume toggles `MediaPlayer.pause()` / `start()` with a `paused` state; stop releases and
  nulls `player`, hiding the bar. Completion and leaving the screen already release it.
- The row's `Play` button is unchanged: tapping it (re)plays from the start and the bar moves to
  that recording. Times format as `m:ss`.

### Sound filter (core + settings)

- `filter.rs`: one pass, in order —
  1. **High-pass**: 2nd-order Butterworth biquad at 80 Hz, Q 0.707 — rumble, wind, headset crud.
  2. **Noise gate**: 20 ms frame energies; threshold relative to the recording's own median frame
     energy (~−25 dB), so quiet speech is not judged against an absolute floor; ~200 ms hangover
     keeps word tails; smoothed attack (~5 ms) and release (~100 ms) so edges are not clicks.
     Constants are starting points, tuned against the unit tests.
  3. **Quiet-voice lift**: boost only, peak toward 0.95, gain capped at ×10 (20 dB) — the low
     headset level, without blowing up gated residue.
- `Engine::transcribe` (`engine.rs:172`) takes a `filter: bool` and runs the pass **before** the
  silence check, so a quiet recording is lifted rather than skipped. Pieces are cut at pauses, so
  per-piece filtering with fresh state is safe.
- JNI: `Native.transcribe(handle, pcm, language, clean, filter)`; `Engine.transcribe` /
  `transcribeWith` pass `filter ?: settings.filter`, mirroring `clean`. Both call sites — live
  pieces (`Dictation.submit`) and *Transcribe…* (`HistoryScreen.retranscribe`) — get it by default.
- Settings: `App.settings.filter` (`prefs`, default false); a "Clean up the sound" switch in the
  Recording section beside "Clean up the text", saying it cuts rumble and steady noise and lifts a
  quiet voice before recognition, and that the recording itself is kept untouched.
- Tests in `filter.rs`: 50 Hz rumble attenuated, a 1 kHz tone preserved, noise-only stretches
  gated, a quiet tone lifted, a tone's tail kept through the hangover. Synthetic 16 kHz signals,
  no device, no fixtures in git.

## Calls made (not the person's)

- Bar at the screen bottom, row button unchanged; 250 ms polling.
- Filter order high-pass → gate → lift; the constants above; boost-only lift capped at 20 dB.
- Filter applies to live dictation too, not only re-runs — a Settings switch reads as pipeline-wide.
- Default off, matching the person's "optional".
- One doc for both features; playback first, filter second.
