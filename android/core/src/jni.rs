// Copyright (c) vladkens | MIT License | https://github.com/vladkens/wavo-app

//! The functions of the Kotlin class `io.github.tyetladd.wavo.Native`. Lists and results cross as
//! JSON strings; an error, or a panic of wavo or wgpu, is thrown as a `RuntimeException` and the
//! app carries on.

use std::panic::{self, AssertUnwindSafe};
use std::path::PathBuf;
use std::time::{Duration, Instant};

use jni::JNIEnv;
use jni::objects::{JClass, JFloatArray, JObject, JString, JValue};
use jni::sys::{jlong, jstring};

use crate::engine::{self, Engine, Result};

/// Runs `f`; on an error or a panic throws it to Kotlin and returns `fallback`.
fn guard<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Result<T>) -> T {
  let failed = match panic::catch_unwind(AssertUnwindSafe(|| f(env))) {
    Ok(Ok(value)) => return value,
    Ok(Err(e)) => e,
    Err(panic) => {
      let why = panic.downcast_ref::<&str>().map(|s| s.to_string());
      let why = why.or_else(|| panic.downcast_ref::<String>().cloned());
      format!("the engine crashed: {}", why.unwrap_or_else(|| "no message".into()))
    }
  };
  // With an exception pending (a callback threw), that one stands.
  if !env.exception_check().unwrap_or(false) {
    let _ = env.throw_new("java/lang/RuntimeException", failed);
  }

  fallback
}

fn string(env: &mut JNIEnv, s: &JString) -> Result<String> {
  Ok(env.get_string(s).map_err(|e| e.to_string())?.into())
}

/// `None` for Kotlin's `null`.
fn optional(env: &mut JNIEnv, s: &JString) -> Result<Option<String>> {
  if s.is_null() { Ok(None) } else { string(env, s).map(Some) }
}

fn java(env: &mut JNIEnv, s: String) -> Result<jstring> {
  Ok(env.new_string(s).map_err(|e| e.to_string())?.into_raw())
}

fn json(value: &impl serde::Serialize) -> Result<String> {
  serde_json::to_string(value).map_err(|e| e.to_string())
}

/// `models(root: String): String`: [engine::catalog] as a JSON array.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_models<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  root: JString<'l>,
) -> jstring {
  guard(&mut env, std::ptr::null_mut(), |env| {
    let root = PathBuf::from(string(env, &root)?);
    let all = json(&engine::catalog(&root))?;
    java(env, all)
  })
}

/// `pull(root: String, name: String, progress: Progress): String`: downloads a model and returns
/// its file. `progress.onProgress(done: Long, total: Long): Boolean` is called a few times a
/// second, and stops the download by returning `false`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_pull<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  root: JString<'l>,
  name: JString<'l>,
  progress: JObject<'l>,
) -> jstring {
  guard(&mut env, std::ptr::null_mut(), |env| {
    let (root, name) = (PathBuf::from(string(env, &root)?), string(env, &name)?);
    let mut last: Option<Instant> = None;
    let path = engine::pull(&root, &name, |done, total| {
      let due = last.is_none_or(|at| at.elapsed() > Duration::from_millis(200));
      if !due && done < total {
        return true;
      }

      last = Some(Instant::now());
      let args = [JValue::Long(done as jlong), JValue::Long(total as jlong)];
      let go = env.call_method(&progress, "onProgress", "(JJ)Z", &args).and_then(|v| v.z());
      // A callback that threw stops the download, and its exception goes on to Kotlin.
      go.unwrap_or(false)
    })?;
    java(env, path.to_string_lossy().into_owned())
  })
}

/// `remove(root: String, name: String): Long`: deletes a model; the bytes freed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_remove<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  root: JString<'l>,
  name: JString<'l>,
) -> jlong {
  guard(&mut env, 0, |env| {
    let (root, name) = (PathBuf::from(string(env, &root)?), string(env, &name)?);
    Ok(engine::remove(&root, &name)? as jlong)
  })
}

/// `load(path: String, backend: String): Long`: a loaded model's handle, for the calls below and
/// in the end for `free`. Call it, and `transcribe`, on one thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_load<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  path: JString<'l>,
  backend: JString<'l>,
) -> jlong {
  guard(&mut env, 0, |env| {
    let (path, backend) = (PathBuf::from(string(env, &path)?), string(env, &backend)?);
    let engine = Engine::load(&path, &backend)?;
    Ok(Box::into_raw(Box::new(engine)) as jlong)
  })
}

/// The engine of a handle `load` returned and `free` hasn't taken.
fn engine<'a>(handle: jlong) -> Result<&'a Engine> {
  // SAFETY: a handle other than 0 is a pointer `load` made with Box::into_raw, and the Kotlin
  // side frees it once, on the thread that makes these calls.
  unsafe { (handle as *const Engine).as_ref() }.ok_or_else(|| "no model is loaded".to_owned())
}

/// `device(handle: Long): String`: what the model runs on, `CPU (NEON, 4 threads)`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_device<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  handle: jlong,
) -> jstring {
  guard(&mut env, std::ptr::null_mut(), |env| {
    let device = engine(handle)?.device();
    java(env, device)
  })
}

/// `languages(handle: Long): String`: a JSON array of the codes the model can be told; empty for
/// a model that can't be.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_languages<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  handle: jlong,
) -> jstring {
  guard(&mut env, std::ptr::null_mut(), |env| {
    let codes = json(&engine(handle)?.languages())?;
    java(env, codes)
  })
}

/// `transcribe(handle: Long, pcm: FloatArray, language: String?, clean: Boolean): String`:
/// [engine::Heard] as JSON, of 16 kHz mono samples in [-1, 1].
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_transcribe<'l>(
  mut env: JNIEnv<'l>,
  _: JClass<'l>,
  handle: jlong,
  pcm: JFloatArray<'l>,
  language: JString<'l>,
  clean: jni::sys::jboolean,
) -> jstring {
  guard(&mut env, std::ptr::null_mut(), |env| {
    let language = optional(env, &language)?;
    let len = env.get_array_length(&pcm).map_err(|e| e.to_string())? as usize;
    let mut samples = vec![0.0f32; len];
    env.get_float_array_region(&pcm, 0, &mut samples).map_err(|e| e.to_string())?;

    let heard = engine(handle)?.transcribe(&samples, language.as_deref(), clean != 0)?;
    let heard = json(&heard)?;
    java(env, heard)
  })
}

/// `free(handle: Long)`: unloads the model; the handle is over.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_tyetladd_wavo_Native_free<'l>(
  _: JNIEnv<'l>,
  _: JClass<'l>,
  handle: jlong,
) {
  if handle != 0 {
    // SAFETY: as in `engine`; after this the Kotlin side holds 0.
    drop(unsafe { Box::from_raw(handle as *mut Engine) });
  }
}
