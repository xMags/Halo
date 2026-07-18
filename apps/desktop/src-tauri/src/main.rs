// Halo desktop shell. Architecture (settled by the 2026-07-18 Windows spike —
// see DESKTOP-HANDOFF.md in the memory repo for the full findings):
// mpv renders into the top-level window (`wid` embedding); the transparent
// WebView2 composites the React UI above it; JS drives mpv over a generic
// command/property channel. The UI keeps an opaque background except on the
// player screen, so mpv's surface only shows where the UI opens a hole.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod mpv;
mod oauth;

use std::sync::Arc;
use tauri::{Emitter, Manager};

use mpv::{Event, Mpv, MPV_FORMAT_DOUBLE, MPV_FORMAT_FLAG, MPV_FORMAT_STRING};

#[repr(C)]
struct DwmBlurBehind {
    flags: u32,
    enable: i32,
    blur_region: isize,
    transition_on_maximized: i32,
}

#[link(name = "dwmapi")]
extern "system" {
    fn DwmEnableBlurBehindWindow(hwnd: isize, bb: *const DwmBlurBehind) -> i32;
}

/// The dll ships beside the exe in packaged builds; in dev it lives in the
/// git-ignored `apps/desktop/vendor/mpv/` (see vendor/README.md for the pin).
fn find_libmpv() -> Result<std::path::PathBuf, String> {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let bundled = dir.join("libmpv-2.dll");
            if bundled.exists() {
                return Ok(bundled);
            }
        }
    }
    let dev = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../vendor/mpv/libmpv-2.dll");
    if dev.exists() {
        return Ok(dev);
    }
    Err("libmpv-2.dll not found beside the exe or in apps/desktop/vendor/mpv — see vendor/README.md".into())
}

/// Bundled subtitle fonts (committed in `apps/desktop/fonts/`, OFL-licensed).
/// Same resolution order as the dll: beside the exe in packaged builds
/// (packaging must copy the directory), repo path in dev. Missing dir is
/// non-fatal — mpv just falls back to system fonts.
fn find_fonts_dir() -> Option<std::path::PathBuf> {
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            let bundled = dir.join("fonts");
            if bundled.is_dir() {
                return Some(bundled);
            }
        }
    }
    let dev = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../fonts");
    dev.is_dir().then_some(dev)
}

struct PlayerState {
    mpv: Arc<Mpv>,
}

#[tauri::command]
fn mpv_cmd(state: tauri::State<PlayerState>, args: Vec<String>) -> Result<(), String> {
    state.mpv.cmd(&args)
}

#[tauri::command]
fn mpv_set(state: tauri::State<PlayerState>, name: String, value: String) -> Result<(), String> {
    state.mpv.set_str(&name, &value)
}

#[tauri::command]
fn mpv_get(state: tauri::State<PlayerState>, name: String) -> Result<Option<String>, String> {
    state.mpv.get_str(&name)
}

#[tauri::command]
fn mpv_observe(state: tauri::State<PlayerState>, name: String, format: String) -> Result<(), String> {
    let format = match format.as_str() {
        "double" => MPV_FORMAT_DOUBLE,
        "flag" => MPV_FORMAT_FLAG,
        "string" => MPV_FORMAT_STRING,
        other => return Err(format!("unsupported observe format: {other}")),
    };
    state.mpv.observe(&name, format)
}

#[tauri::command]
fn mpv_unobserve_all(state: tauri::State<PlayerState>) {
    state.mpv.unobserve_all()
}

/// Async + spawn_blocking: a plain (non-async) command would run ON the main
/// thread, and this one blocks until the browser redirects (or the 5-minute
/// timeout) — that froze the whole window. The dedicated blocking worker is
/// the correct home for a synchronous accept loop.
#[tauri::command]
async fn oauth_wait_callback() -> Result<String, String> {
    tauri::async_runtime::spawn_blocking(oauth::wait_for_callback)
        .await
        .map_err(|e| e.to_string())?
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            mpv_cmd,
            mpv_set,
            mpv_get,
            mpv_observe,
            mpv_unobserve_all,
            oauth_wait_callback
        ])
        .setup(|app| {
            let window = app.get_webview_window("main").expect("main window");
            let hwnd = window.hwnd()?.0 as isize;

            // `transparent: true` in tauri.conf.json is required so wry gives
            // the WebView2 a transparent background — but tao also blur-behinds
            // the top-level window, which makes DWM alpha-composite the
            // redirection surface and drop mpv's (alpha-less) pixels. Re-opaque
            // the window; the webview keeps its own transparency. (Spike trap #2.)
            let bb = DwmBlurBehind { flags: 0x1, enable: 0, blur_region: 0, transition_on_maximized: 0 };
            unsafe { DwmEnableBlurBehindWindow(hwnd, &bb) };

            // Spike trap #1: wid must be this top-level HWND. mpv creates its
            // own child inside it and tracks the window size natively — no
            // resize handling on our side.
            let dll = find_libmpv()?;
            let fonts = find_fonts_dir();
            let mpv = Arc::new(
                Mpv::load(&dll, hwnd, fonts.as_deref()).map_err(|e| format!("mpv init: {e}"))?,
            );

            let pump = mpv.clone();
            let events = app.handle().clone();
            std::thread::spawn(move || {
                pump.run_event_loop(|event| match event {
                    Event::Prop { name, value } => {
                        let _ = events.emit("mpv-prop", serde_json::json!({ "name": name, "value": value }));
                    }
                    Event::Lifecycle(kind) => {
                        let _ = events.emit("mpv-event", kind);
                    }
                    Event::Log(line) => {
                        let _ = events.emit("mpv-log", line);
                    }
                    Event::Shutdown => {}
                });
            });

            app.manage(PlayerState { mpv });
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("tauri run");
}
