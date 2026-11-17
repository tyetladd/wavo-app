// Copyright (c) tyetladd | MIT License | https://github.com/tyetladd/wavo-app

//! The sound cleanup before recognition: a high-pass, a noise gate and a quiet-voice lift, one
//! pass over 16 kHz mono samples. No model — cheap enough to run on every piece of a dictation.
//! The kept WAV is never touched; this runs on the samples the engine hears.

/// The samples cleaned, their count unchanged: rumble below 80 Hz taken out, stretches far below
/// the recording's own voice level gated shut (with a hangover so word tails survive), and a quiet
/// recording lifted toward full level, boost only. The gate's reference is the 75th percentile of
/// the frame levels, not the median: a dictation is mostly pauses, and a median of zero would gate
/// nothing.
pub fn clean(pcm: &[f32]) -> Vec<f32> {
  if pcm.is_empty() {
    return Vec::new();
  }
  let mut out = high_pass(pcm);
  gate(&mut out);
  lift(&mut out);
  out
}

const RATE: f32 = 16_000.0;

/** A second-order Butterworth high-pass at 80 Hz (Q 0.7071), transposed direct-form II. */
fn high_pass(pcm: &[f32]) -> Vec<f32> {
  let w0 = 2.0 * std::f32::consts::PI * 80.0 / RATE;
  let alpha = w0.sin() / (2.0 * std::f32::consts::FRAC_1_SQRT_2);
  let a0 = 1.0 + alpha;
  let b0 = (1.0 + w0.cos()) / 2.0 / a0;
  let b1 = -(1.0 + w0.cos()) / a0;
  let b2 = b0;
  let a1 = -2.0 * w0.cos() / a0;
  let a2 = (1.0 - alpha) / a0;
  let (mut z1, mut z2) = (0.0f32, 0.0f32);
  pcm
    .iter()
    .map(|&x| {
      let y = b0 * x + z1;
      z1 = b1 * x - a1 * y + z2;
      z2 = b2 * x - a2 * y;
      y
    })
    .collect()
}

fn rms(x: &[f32]) -> f32 {
  (x.iter().map(|v| v * v).sum::<f32>() / x.len() as f32).sqrt()
}

/**
 * Shut the stretches far below the recording's own voice: 20 ms frames, the threshold 25 dB
 * under the 75th-percentile frame level, a 200 ms hangover so word tails survive, and smoothed
 * edges (5 ms attack, 100 ms release) so the gate is not a click.
 */
fn gate(pcm: &mut [f32]) {
  const FRAME: usize = 320;
  const HANGOVER: usize = 10;
  let frames: Vec<f32> = pcm.chunks(FRAME).map(rms).collect();
  let mut sorted = frames.clone();
  sorted.sort_by(f32::total_cmp);
  let voice = sorted[(sorted.len() * 3 / 4).min(sorted.len() - 1)];
  let threshold = voice * 10f32.powf(-25.0 / 20.0);

  let mut open = vec![false; frames.len()];
  let mut hold = 0usize;
  for (i, &r) in frames.iter().enumerate() {
    if r > threshold {
      hold = HANGOVER + 1;
    }
    open[i] = hold > 0;
    hold = hold.saturating_sub(1);
  }

  let attack = 1.0 - (-1.0 / (0.005 * RATE)).exp();
  let release = 1.0 - (-1.0 / (0.100 * RATE)).exp();
  let mut g = 0.0f32;
  for (i, x) in pcm.iter_mut().enumerate() {
    let target = if open[i / FRAME] { 1.0 } else { 0.0 };
    g += (target - g) * if target > g { attack } else { release };
    *x *= g;
  }
}

/** A quiet recording toward full level: boost only, peak to 0.95, the gain capped at ten. */
fn lift(pcm: &mut [f32]) {
  let peak = pcm.iter().fold(0.0f32, |m, x| m.max(x.abs()));
  if peak > 0.0 && peak < 0.95 {
    let gain = (0.95 / peak).min(10.0);
    for x in pcm.iter_mut() {
      *x *= gain;
    }
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  const RATE: usize = 16_000;

  fn sine(hz: f32, secs: f32, amp: f32) -> Vec<f32> {
    let n = (RATE as f32 * secs) as usize;
    (0..n).map(|i| amp * (2.0 * std::f32::consts::PI * hz * i as f32 / RATE as f32).sin()).collect()
  }

  /** Deterministic uniform noise in [-amp, amp], so the tests never change between runs. */
  fn noise(secs: f32, amp: f32, seed: u32) -> Vec<f32> {
    let n = (RATE as f32 * secs) as usize;
    let mut s = seed;
    (0..n)
      .map(|_| {
        s = s.wrapping_mul(1664525).wrapping_add(1013904223);
        amp * (s as f32 / u32::MAX as f32 * 2.0 - 1.0)
      })
      .collect()
  }

  fn peak(x: &[f32]) -> f32 {
    x.iter().fold(0.0f32, |m, v| m.max(v.abs()))
  }

  /** The Goertzel magnitude at one frequency, normalized by length: a band's level. */
  fn band(x: &[f32], hz: f32) -> f32 {
    let coeff = 2.0 * (2.0 * std::f32::consts::PI * hz / RATE as f32).cos();
    let (mut s1, mut s2) = (0.0f32, 0.0f32);
    for &v in x {
      let s0 = v + coeff * s1 - s2;
      s2 = s1;
      s1 = s0;
    }
    (s1 * s1 + s2 * s2 - coeff * s1 * s2).sqrt() / x.len() as f32
  }

  #[test]
  fn high_pass_cuts_rumble_and_keeps_voice() {
    // Voice and rumble together, as a recording has them: the lift scales both bands alike, so
    // their ratio is the filter's own doing. A second-order 80 Hz high-pass gives |H(50)| ≈ 0.36.
    let mut sig = sine(1000.0, 1.0, 0.5);
    for (i, v) in sine(50.0, 1.0, 0.5).into_iter().enumerate() {
      sig[i] += v;
    }
    let out = clean(&sig);
    let voice = band(&out, 1000.0) / band(&sig, 1000.0);
    let rumble = band(&out, 50.0) / band(&sig, 50.0);
    // No upper bound on the voice band: the filter's phase shift can lower the composite peak, and
    // the lift then raises the whole recording — correctly. The ratio is the filter's own doing.
    assert!(voice > 0.9, "the voice band was lost: {voice}");
    assert!(rumble < 0.5 * voice, "50 Hz rumble not cut: {rumble} against {voice}");

    // A voice alone keeps its level (the lift may raise a quiet peak toward 0.95).
    let voice = sine(1000.0, 1.0, 0.9);
    let r = rms(&clean(&voice)) / rms(&voice);
    assert!((0.9..=1.1).contains(&r), "1 kHz level changed: {r}");
  }

  #[test]
  fn gate_silences_a_noise_only_stretch() {
    // Half a second of voice, a second of quiet noise, voice again. The noise stretch's own tail,
    // well past the hangover and the release, must be shut; the whole stretch stays far below the
    // voice around it.
    let mut sig = sine(1000.0, 0.5, 0.5);
    let noise_start = sig.len();
    sig.extend(noise(1.0, 0.005, 7));
    let tone2 = sig.len();
    sig.extend(sine(1000.0, 0.5, 0.5));
    let out = clean(&sig);

    let lift = rms(&out[..noise_start]) / rms(&sig[..noise_start]);
    let mid_in = &sig[noise_start..tone2];
    let mid = &out[noise_start..tone2];
    let tail_in = &mid_in[mid_in.len() / 2..];
    let tail = &mid[mid.len() / 2..];
    assert!(rms(tail) < 0.05 * rms(tail_in) * lift, "the noise tail survives: {}", rms(tail));
    assert!(rms(mid) < 0.1 * rms(&out[..noise_start]), "the stretch is not far below the voice");
  }

  #[test]
  fn hangover_keeps_the_tail_after_voice() {
    // Half a second of voice, then quiet noise for 0.8 s: the first 0.15 s rides the hangover and
    // stays audible, the last 0.3 s — past the hangover and three release time constants — is
    // gated. Voice dominates the upper quartile, as in a dictation.
    let mut sig = sine(1000.0, 0.5, 0.5);
    let noise_start = sig.len();
    sig.extend(noise(0.8, 0.01, 11));
    let end = sig.len();
    sig.extend(vec![0.0; RATE / 2]);
    let out = clean(&sig);

    let lift = rms(&out[..noise_start]) / rms(&sig[..noise_start]);
    let kept = RATE * 15 / 100; // the first 0.15 s of the stretch
    let tail_in = &sig[noise_start..noise_start + kept];
    let tail = &out[noise_start..noise_start + kept];
    assert!(rms(tail) > 0.5 * rms(tail_in) * lift, "the hangover dropped the tail: {}", rms(tail));
    // Past the hangover (0.2 s) and three release time constants (0.3 s): the last 0.3 s.
    let rest_in = &sig[noise_start + RATE / 2..end];
    let rest = &out[noise_start + RATE / 2..end];
    assert!(
      rms(rest) < 0.1 * rms(rest_in) * lift,
      "the stretch past the hangover survives: {}",
      rms(rest)
    );
  }

  #[test]
  fn lift_raises_a_quiet_voice_with_a_cap() {
    let moderate = sine(1000.0, 0.5, 0.3);
    let p = peak(&clean(&moderate));
    assert!((0.93..=0.97).contains(&p), "a 0.3 voice should reach about 0.95: {p}");

    let quiet = sine(1000.0, 0.5, 0.02);
    let p = peak(&clean(&quiet));
    assert!((0.19..=0.21).contains(&p), "the gain should cap at ten: {p}");
  }

  #[test]
  fn a_quiet_continuous_voice_survives_and_is_lifted() {
    // An all-speech recording with no quiet frames to learn a floor from: the gate must judge the
    // voice against itself and keep it. The anti-goal test — missing words is the complaint.
    let quiet = sine(1000.0, 1.0, 0.01);
    let p = peak(&clean(&quiet));
    assert!(p >= 0.09, "a quiet continuous voice was gated away: {p}");
  }

  #[test]
  fn empty_and_tiny_inputs_pass_through() {
    assert!(clean(&[]).is_empty());
    let silence = vec![0.0; 160];
    let out = clean(&silence);
    assert_eq!(out.len(), 160);
    assert_eq!(peak(&out), 0.0);
    assert_eq!(clean(&[0.5; 10]).len(), 10);
  }
}
