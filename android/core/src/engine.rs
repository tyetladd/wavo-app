// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

//! The engine without JNI: the models in the app's folder, and a loaded model that turns a
//! recording into the text the person gets.

use std::ops::ControlFlow;
use std::path::{Path, PathBuf};
use std::time::Instant;

use serde::Serialize;
use wavo::hub::MODELS;
use wavo::{Backend, Options};

use crate::{cleanup, cores, store};

/// 16 kHz mono, what the models take.
pub const SAMPLE_RATE: usize = 16_000;

/// A recording shorter than this has no word in it: a tap on the button by mistake.
const MIN_SAMPLES: usize = SAMPLE_RATE * 3 / 10;

/// The loudest sample of a recording that is silence: a muted microphone gives zeros, a quiet
/// room about 0.001. Whisper makes text up from silence, so it never gets it.
const SILENCE: f32 = 0.004;

pub type Result<T> = std::result::Result<T, String>;

fn text(e: impl std::fmt::Display) -> String {
  e.to_string()
}

// MARK: Models

/// A model of wavo's list, as the models screen shows it.
#[derive(Debug, Serialize)]
pub struct Entry {
  pub name: &'static str,
  pub family: &'static str,
  pub maker: &'static str,
  /// For people: `25 European languages`.
  pub languages: &'static str,
  /// For programs: `["ru"]`.
  pub langs: &'static [&'static str],
  pub size: u64,
  pub punctuated: bool,
  /// Times faster than `whisper-turbo`, measured on a desktop GPU: only the order means anything.
  pub speed: Option<f32>,
  /// The file, once downloaded.
  pub path: Option<PathBuf>,
}

/// wavo's models, with the files of those downloaded into `root`.
pub fn catalog(root: &Path) -> Vec<Entry> {
  MODELS
    .iter()
    .map(|m| Entry {
      name: m.name,
      family: m.family,
      maker: m.maker,
      languages: m.languages,
      langs: m.langs,
      size: m.size,
      punctuated: m.punctuated,
      speed: m.speed(),
      path: store::find(root, m),
    })
    .collect()
}

/// Downloads a model into `root` and returns its file; a download cut short resumes. `progress`
/// gets the bytes done and the total, and stops the download by returning `false`.
pub fn pull(root: &Path, name: &str, progress: impl FnMut(u64, u64) -> bool) -> Result<PathBuf> {
  store::pull(root, name, progress)
}

/// Deletes a model from `root`; the bytes freed.
pub fn remove(root: &Path, name: &str) -> Result<u64> {
  store::remove(root, name)
}

// MARK: Engine

/// What a recording said.
#[derive(Debug, Default, PartialEq, Serialize)]
pub struct Heard {
  pub text: String,
  /// The language of the text, when the model says it.
  pub language: Option<String>,
  /// How long the engine took, and how long the recording is.
  pub took_ms: u64,
  pub audio_ms: u64,
}

/// A loaded model. Load it and call it on one thread: [cores::pin] moves that thread.
pub struct Engine {
  model: wavo::Model,
}

impl Engine {
  /// Loads the model file on `backend`: `cpu`, or `gpu` (Vulkan).
  pub fn load(path: &Path, backend: &str) -> Result<Self> {
    let backend = match backend {
      "cpu" => Backend::Cpu,
      "gpu" => Backend::Gpu,
      other => return Err(format!("backend is {other:?}, not cpu or gpu")),
    };
    // Before wavo counts the cores for its threads, which it does once.
    cores::pin();
    Ok(Self { model: wavo::Model::load_with(path, backend).map_err(text)? })
  }

  /// `CPU (NEON, 4 threads)`, `Mali-G710 (Vulkan, integrated)`.
  pub fn device(&self) -> String {
    self.model.device().to_string()
  }

  /// The codes the model takes as a language; empty when it can't be told one.
  pub fn languages(&self) -> Vec<String> {
    match self.model.takes_language() {
      true => self.model.languages().to_vec(),
      false => Vec::new(),
    }
  }

  /// The text of 16 kHz mono samples in [-1, 1], cut at pauses when longer than the model's
  /// window. `language` is the person's, if they set one: the model transcribes in it when it can
  /// be told, and the cleanup follows it when the model doesn't name its own. `clean` takes the
  /// hesitation sounds out and writes Russian numbers in digits, as the desktop app does.
  pub fn transcribe(&self, pcm: &[f32], language: Option<&str>, clean: bool) -> Result<Heard> {
    let audio_ms = (pcm.len() * 1000 / SAMPLE_RATE) as u64;
    let loudest = pcm.iter().fold(0.0f32, |m, x| m.max(x.abs()));
    if pcm.len() < MIN_SAMPLES || loudest < SILENCE {
      return Ok(Heard { audio_ms, ..Heard::default() });
    }

    let told = language
      .filter(|code| self.model.takes_language() && self.model.languages().iter().any(|l| l == code));
    let mut options = Options::default();
    options.language = told.map(str::to_owned);

    let started = Instant::now();
    let heard = self
      .model
      .transcribe_long(pcm, &options, |_| ControlFlow::Continue(()))
      .map_err(text)?;
    let took_ms = started.elapsed().as_millis() as u64;

    // A model of one language says it; Parakeet v3 doesn't, and the person's setting stands in.
    let spoken = heard.language.as_deref().or(language);
    let text = match clean {
      true => cleanup::clean(heard.text.trim(), spoken),
      false => heard.text.trim().to_owned(),
    };
    Ok(Heard { text, language: heard.language, took_ms, audio_ms })
  }
}

#[cfg(test)]
mod tests {
  use super::*;

  #[test]
  fn catalog_lists_every_model_and_none_downloaded() {
    let root = std::env::temp_dir().join(format!("wavo-android-test-{}", std::process::id()));
    let all = catalog(&root);
    assert_eq!(all.len(), MODELS.len());
    assert!(all.iter().all(|m| m.path.is_none()));
    assert!(all.iter().any(|m| m.name == "gigaam-v3" && m.langs == ["ru"]));

    let json = serde_json::to_string(&all).unwrap();
    assert!(json.contains(r#""name":"parakeet-ultra""#));
  }

  #[test]
  fn unknown_backend_fails_before_the_file_is_read() {
    let e = Engine::load(Path::new("/no/such.gguf"), "npu").err().unwrap();
    assert!(e.contains("npu"), "{e}");
  }

  /// `WAVO_TEST_MODEL=<gguf> WAVO_TEST_WAV=<16 kHz mono wav> WAVO_TEST_TEXT=<a word of it>`: a real
  /// model on the CPU, as on the phone.
  #[test]
  fn transcribes_a_recording_on_the_cpu() {
    let (Ok(model), Ok(wav)) = (std::env::var("WAVO_TEST_MODEL"), std::env::var("WAVO_TEST_WAV"))
    else {
      eprintln!("skipped: WAVO_TEST_MODEL and WAVO_TEST_WAV aren't set");
      return;
    };
    let mut reader = hound::WavReader::open(&wav).unwrap();
    assert_eq!((reader.spec().channels, reader.spec().sample_rate), (1, 16_000));
    let pcm: Vec<f32> = reader.samples::<i16>().map(|s| s.unwrap() as f32 / 32768.0).collect();

    let engine = Engine::load(Path::new(&model), "cpu").unwrap();
    let heard = engine.transcribe(&pcm, None, true).unwrap();
    eprintln!("{}: {heard:?}", engine.device());
    assert!(!heard.text.is_empty());
    if let Ok(word) = std::env::var("WAVO_TEST_TEXT") {
      assert!(heard.text.to_lowercase().contains(&word.to_lowercase()), "{}", heard.text);
    }

    // Silence and a tap by mistake never reach the model.
    assert_eq!(engine.transcribe(&vec![0.0; 32_000], None, true).unwrap().text, "");
    assert_eq!(engine.transcribe(&pcm[..1000], None, true).unwrap().text, "");
  }
}
