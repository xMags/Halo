// Halo desktop shell. Architecture (settled by the 2026-07-18 Windows spike —
// see DESKTOP-HANDOFF.md in the memory repo for the full findings):
// mpv renders into the top-level window (`wid` embedding); the transparent
// WebView2 composites the React UI above it; JS drives mpv over a generic
// command/property channel. The UI keeps an opaque background except on the
// player screen, so mpv's surface only shows where the UI opens a hole.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio_session;
mod crash_log;
mod device_store;
mod discord;
mod downloads;
mod injection_guard;
mod mpv;
mod oauth;
mod scrub_preview;
mod secure_fs;
mod webview_recovery;
mod window_icon;
mod window_menu;

use std::sync::Arc;
use tauri::{Emitter, Manager, State};
use tauri_plugin_dialog::DialogExt;

use mpv::{Event, Mpv, MPV_FORMAT_DOUBLE, MPV_FORMAT_FLAG, MPV_FORMAT_STRING};

#[repr(C)]
struct DwmBlurBehind {
    flags: u32,
    enable: i32,
    blur_region: isize,
    transition_on_maximized: i32,
}

/// Windows 11 DWM attributes used by Halo's app-drawn frame.
const DWMWA_WINDOW_CORNER_PREFERENCE: u32 = 33;
const DWMWA_BORDER_COLOR: u32 = 34;
const DWMWCP_DONOTROUND: u32 = 1;
const DWMWCP_ROUND: u32 = 2;
const DWMWA_COLOR_NONE: u32 = 0xFFFF_FFFE;

#[link(name = "dwmapi")]
extern "system" {
    fn DwmEnableBlurBehindWindow(hwnd: isize, bb: *const DwmBlurBehind) -> i32;
    fn DwmSetWindowAttribute(hwnd: isize, attribute: u32, value: *const u32, size: u32) -> i32;
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

fn corner_preference(fullscreen: bool) -> u32 {
    if fullscreen {
        DWMWCP_DONOTROUND
    } else {
        DWMWCP_ROUND
    }
}

fn set_corner_preference(hwnd: isize, fullscreen: bool) -> Result<(), String> {
    let corner = corner_preference(fullscreen);
    let result = unsafe {
        DwmSetWindowAttribute(
            hwnd,
            DWMWA_WINDOW_CORNER_PREFERENCE,
            &corner,
            std::mem::size_of::<u32>() as u32,
        )
    };
    if result < 0 {
        return Err(format!(
            "DWM corner preference failed (HRESULT {result:#x})"
        ));
    }
    Ok(())
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
fn mpv_observe(
    state: tauri::State<PlayerState>,
    name: String,
    format: String,
) -> Result<(), String> {
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

#[tauri::command]
fn window_set_fullscreen_style(app: tauri::AppHandle, fullscreen: bool) -> Result<(), String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "main window is unavailable".to_string())?;
    let hwnd = window.hwnd().map_err(|error| error.to_string())?.0 as isize;
    set_corner_preference(hwnd, fullscreen)
}

/// Right-click on the app-drawn title bar opens the Windows window menu
/// (window_menu.rs). The menu runs its own modal loop, so it is posted to the
/// event loop instead of running inside the webview's IPC dispatch, and this
/// command returns straight away.
#[tauri::command]
fn window_show_system_menu(app: tauri::AppHandle) -> Result<(), String> {
    let window = app
        .get_webview_window("main")
        .ok_or_else(|| "main window is unavailable".to_string())?;
    let hwnd = window.hwnd().map_err(|error| error.to_string())?.0 as isize;
    app.run_on_main_thread(move || window_menu::show_at_cursor(hwnd))
        .map_err(|error| error.to_string())
}

/// Discord Rich Presence (discord.rs). The service starts disabled; the
/// webview turns it on from the device-local preference at startup.
#[tauri::command]
fn discord_presence_set_enabled(presence: State<discord::PresenceService>, enabled: bool) {
    presence.set_enabled(enabled);
}

#[tauri::command]
fn discord_presence_set_media(presence: State<discord::PresenceService>, media: discord::PresenceMedia) {
    presence.set_media(media);
}

#[tauri::command]
fn discord_presence_update(presence: State<discord::PresenceService>, snapshot: discord::PlaybackSnapshot) {
    presence.update(snapshot);
}

#[tauri::command]
fn discord_presence_clear(presence: State<discord::PresenceService>) {
    presence.clear();
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

#[tauri::command]
async fn device_store_read(
    state: State<'_, device_store::DeviceStore>,
    collection: device_store::DeviceCollection,
) -> Result<Option<String>, String> {
    state.read(collection).await
}

#[tauri::command]
async fn device_store_write(
    state: State<'_, device_store::DeviceStore>,
    collection: device_store::DeviceCollection,
    contents: String,
) -> Result<(), String> {
    state.write(collection, &contents).await
}

#[tauri::command]
async fn device_store_clear(state: State<'_, device_store::DeviceStore>) -> Result<(), String> {
    state.clear().await
}

#[tauri::command]
async fn downloads_set_account(
    state: State<'_, Arc<downloads::DownloadManager>>,
    account_key: String,
) -> Result<Vec<downloads::DownloadView>, String> {
    state.set_account(account_key).await
}

#[tauri::command]
async fn downloads_clear_account(
    state: State<'_, Arc<downloads::DownloadManager>>,
) -> Result<(), String> {
    state.clear_account().await
}

#[tauri::command]
async fn downloads_adopt_account(
    state: State<'_, Arc<downloads::DownloadManager>>,
    from_account_key: String,
    to_account_key: String,
) -> Result<Vec<downloads::DownloadView>, String> {
    state.adopt_account(from_account_key, to_account_key).await
}

#[tauri::command]
async fn downloads_list(
    state: State<'_, Arc<downloads::DownloadManager>>,
) -> Result<Vec<downloads::DownloadView>, String> {
    Ok(state.list().await)
}

#[tauri::command]
async fn downloads_start(
    state: State<'_, Arc<downloads::DownloadManager>>,
    request: downloads::DownloadStartRequest,
) -> Result<downloads::StartOutcome, String> {
    state.start(request).await
}

#[tauri::command]
async fn downloads_pause(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
) -> Result<(), String> {
    state.pause(&job_id).await
}

#[tauri::command]
async fn downloads_resume(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
) -> Result<(), String> {
    state.resume(&job_id).await
}

#[tauri::command]
async fn downloads_remove(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
) -> Result<(), String> {
    state.remove(&job_id).await
}

#[tauri::command]
async fn downloads_attach_subtitle(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
    subtitle: downloads::DownloadSubtitleRequest,
) -> Result<(), String> {
    state.attach_subtitle(&job_id, subtitle).await
}

#[tauri::command]
async fn downloads_set_landscape_artwork(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
    artwork: String,
) -> Result<(), String> {
    state.set_landscape_artwork(&job_id, artwork).await
}

#[tauri::command]
async fn downloads_set_directory(
    state: State<'_, Arc<downloads::DownloadManager>>,
    directory: String,
) -> Result<String, String> {
    state.set_directory(directory).await
}

#[tauri::command]
async fn downloads_choose_directory(
    app: tauri::AppHandle,
    state: State<'_, Arc<downloads::DownloadManager>>,
) -> Result<Option<String>, String> {
    // Open on the folder downloads go to now. Without a starting folder
    // Windows opens the picker wherever it was last, Documents at first,
    // which reads as the current download location. A folder that no
    // longer exists is skipped by the dialog, which then opens as before.
    let current = state.directory_info().await?.path;
    let (sender, receiver) = tokio::sync::oneshot::channel();
    app.dialog()
        .file()
        .set_title("Choose Halo download folder")
        .set_directory(current)
        .pick_folder(move |selection| {
            let path = selection
                .map(std::path::PathBuf::try_from)
                .transpose()
                .map(|path| path.map(|value| value.to_string_lossy().into_owned()))
                .map_err(|error| error.to_string());
            let _ = sender.send(path);
        });
    receiver
        .await
        .map_err(|_| "The folder picker closed unexpectedly.".to_string())?
}

#[tauri::command]
async fn downloads_directory_info(
    state: State<'_, Arc<downloads::DownloadManager>>,
) -> Result<downloads::DirectoryInfo, String> {
    state.directory_info().await
}

/// Shows a saved download in Explorer, or the download folder itself when no
/// job is named. Driven from Rust rather than the frontend's opener plugin
/// because the folder is user-chosen at runtime, so no static capability scope
/// could cover it; the manager still owns which paths are legitimate.
#[tauri::command]
async fn downloads_open_folder(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: Option<String>,
) -> Result<(), String> {
    match job_id {
        Some(id) => {
            let path = state.playback_path(&id).await?;
            tauri_plugin_opener::reveal_item_in_dir(path).map_err(|error| error.to_string())
        }
        None => {
            let info = state.directory_info().await?;
            if !info.exists {
                return Err("The download folder is unavailable.".to_string());
            }
            tauri_plugin_opener::open_path(info.path, None::<&str>)
                .map_err(|error| error.to_string())
        }
    }
}

#[tauri::command]
async fn downloads_playback_path(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
) -> Result<String, String> {
    state.playback_path(&job_id).await
}

#[tauri::command]
async fn downloads_playback_files(
    state: State<'_, Arc<downloads::DownloadManager>>,
    job_id: String,
) -> Result<downloads::PlaybackFiles, String> {
    state.playback_files(&job_id).await
}

/// Halo's level in the Windows Volume Mixer; None until mpv has opened audio.
#[tauri::command]
async fn audio_session_read() -> Result<Option<audio_session::SessionVolume>, String> {
    tauri::async_runtime::spawn_blocking(audio_session::read)
        .await
        .map_err(|error| error.to_string())
}

/// Sets that level (0 to 1). False when there is no session yet, so the UI
/// falls back to mpv's own volume.
#[tauri::command]
async fn audio_session_set_volume(volume: f64, unmute: bool) -> Result<bool, String> {
    if !volume.is_finite() {
        return Err("volume must be a number".to_string());
    }
    let level = volume.clamp(0.0, 1.0) as f32;
    tauri::async_runtime::spawn_blocking(move || audio_session::set_volume(level, unmute))
        .await
        .map_err(|error| error.to_string())
}

/// Records the file seek-bar previews decode from; opens nothing yet.
#[tauri::command]
fn scrub_preview_open(state: State<'_, scrub_preview::ScrubPreview>, source: String) -> Result<(), String> {
    if source.is_empty() || source.len() > 8192 {
        return Err("invalid preview source".to_string());
    }
    state.open(source);
    Ok(())
}

#[tauri::command]
fn scrub_preview_close(state: State<'_, scrub_preview::ScrubPreview>) {
    state.close();
}

/// A preview frame at `seconds` as raw bytes (see scrub_preview::encode);
/// empty when superseded by a newer request or when decoding failed.
#[tauri::command]
async fn scrub_preview_request(
    state: State<'_, scrub_preview::ScrubPreview>,
    seconds: f64,
) -> Result<tauri::ipc::Response, String> {
    let frame = state.request(seconds).await.unwrap_or(None);
    Ok(tauri::ipc::Response::new(scrub_preview::encode(frame)))
}

fn main() {
    // Before any window exists, or an overlay's hook DLL is already in.
    injection_guard::refuse_extension_points();
    tauri::Builder::default()
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(discord::PresenceService::new(
            false,
            Box::new(discord::NamedPipeTransport::default()),
            discord::RETRY_DELAY,
        ))
        .invoke_handler(tauri::generate_handler![
            mpv_cmd,
            mpv_set,
            mpv_get,
            mpv_observe,
            mpv_unobserve_all,
            window_set_fullscreen_style,
            window_show_system_menu,
            oauth_wait_callback,
            discord_presence_set_enabled,
            discord_presence_set_media,
            discord_presence_update,
            discord_presence_clear,
            device_store_read,
            device_store_write,
            device_store_clear,
            downloads_set_account,
            downloads_clear_account,
            downloads_adopt_account,
            downloads_list,
            downloads_start,
            downloads_pause,
            downloads_resume,
            downloads_remove,
            downloads_attach_subtitle,
            downloads_set_landscape_artwork,
            downloads_set_directory,
            downloads_choose_directory,
            downloads_directory_info,
            downloads_open_folder,
            downloads_playback_path,
            downloads_playback_files,
            audio_session_read,
            audio_session_set_volume,
            scrub_preview_open,
            scrub_preview_close,
            scrub_preview_request
        ])
        .setup(|app| {
            // Release builds have no console, so panics and WebView2 process
            // failures are written to the app's log folder (crash_log.rs).
            let crash_log = Arc::new(crash_log::CrashLog::new(&app.path().app_log_dir()?));
            crash_log::record_panics(crash_log.clone());

            let window = app.get_webview_window("main").expect("main window");
            let hwnd = window.hwnd()?.0 as isize;

            // `transparent: true` in tauri.conf.json is required so wry gives
            // the WebView2 a transparent background — but tao also blur-behinds
            // the top-level window, which makes DWM alpha-composite the
            // redirection surface and drop mpv's (alpha-less) pixels. Re-opaque
            // the window; the webview keeps its own transparency. (Spike trap #2.)
            let bb = DwmBlurBehind {
                flags: 0x1,
                enable: 0,
                blur_region: 0,
                transition_on_maximized: 0,
            };
            unsafe { DwmEnableBlurBehindWindow(hwnd, &bb) };

            // The window is undecorated (the UI draws its own title bar), so
            // Windows does not round its corners for us. Ask DWM to, which
            // clips mpv's child surface along with everything else. Older
            // Windows rejects the attribute and keeps square corners — that is
            // a cosmetic difference, so the result is deliberately ignored.
            let _ = set_corner_preference(hwnd, false);

            // Tauri sets only a 16px small icon, which the taskbar stretches.
            window_icon::apply(hwnd);

            // Tauri's undecorated-window shadow asks DWM for a one-pixel
            // frame. That frame becomes a bright focus-colour line at the
            // monitor edge in fullscreen. Suppress only the border: DWM can
            // keep the windowed shadow and the rounded clipping requested
            // above. Windows versions before 11 reject this cosmetic hint.
            let border = DWMWA_COLOR_NONE;
            unsafe {
                DwmSetWindowAttribute(
                    hwnd,
                    DWMWA_BORDER_COLOR,
                    &border,
                    std::mem::size_of::<u32>() as u32,
                )
            };

            // Spike trap #1: wid must be this top-level HWND. mpv creates its
            // own child inside it and tracks the window size natively — no
            // resize handling on our side.
            let dll = find_libmpv()?;
            let fonts = find_fonts_dir();
            let mpv = Arc::new(
                Mpv::load(&dll, hwnd, fonts.as_deref()).map_err(|e| format!("mpv init: {e}"))?,
            );
            // A second, hidden instance for seek-bar thumbnails, created lazily.
            app.manage(scrub_preview::ScrubPreview::new(dll.clone()));

            let download_manager = Arc::new(downloads::load_manager(&app.handle())?);
            app.manage(download_manager);

            // Halo without an account keeps its data beside the downloads index.
            app.manage(device_store::DeviceStore::new(
                app.path().app_local_data_dir()?.join("device"),
            ));

            let pump = mpv.clone();
            let events = app.handle().clone();
            std::thread::spawn(move || {
                pump.run_event_loop(|event| match event {
                    Event::Prop { name, value } => {
                        let _ = events.emit(
                            "mpv-prop",
                            serde_json::json!({ "name": name, "value": value }),
                        );
                    }
                    Event::Lifecycle(kind) => {
                        let _ = events.emit("mpv-event", kind);
                    }
                    Event::EndFile { reason, error } => {
                        let _ = events.emit(
                            "mpv-end-file",
                            serde_json::json!({ "reason": reason, "error": error }),
                        );
                    }
                    Event::Log(line) => {
                        let _ = events.emit("mpv-log", line);
                    }
                    Event::Shutdown => {}
                });
            });

            // A WebView2 process that dies takes the UI with it; reload or
            // restart instead of leaving a dead window (webview_recovery.rs).
            if let Err(error) = webview_recovery::watch(&window, mpv.clone(), crash_log.clone()) {
                crash_log.record(&format!("could not watch WebView2 processes: {error}"));
            }

            app.manage(PlayerState { mpv });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("tauri build")
        .run(|app, event| {
            // Clear what Discord shows before the process goes, as native does
            // on close. Bounded: a failed delivery is not retried on exit.
            if let tauri::RunEvent::Exit = event {
                app.state::<discord::PresenceService>().shutdown();
            }
        });
}

#[cfg(test)]
mod window_style_tests {
    use super::{corner_preference, DWMWCP_DONOTROUND, DWMWCP_ROUND};

    #[test]
    fn fullscreen_corners_are_square_and_windowed_corners_are_round() {
        assert_eq!(corner_preference(true), DWMWCP_DONOTROUND);
        assert_eq!(corner_preference(false), DWMWCP_ROUND);
    }
}
