//! Keeps the app alive when a WebView2 process dies under it.
//!
//! The UI runs in WebView2's own processes, which other software can crash:
//! RivaTuner's overlay hook once took down the WebView2 browser process, and
//! injection_guard.rs cannot keep such hooks out of processes the Edge runtime
//! starts. Neither wry nor Tauri handles WebView2's ProcessFailed event, so
//! the window was left showing a dead page. Here every failure is written to
//! the crash log; a dead page process gets a fresh page, and a dead browser
//! process, which cannot be revived in place, restarts the app. Playback stops
//! either way: the page that drove it is gone, and mpv would otherwise go on
//! playing behind a new page that knows nothing about it.
//!
//! A failure that comes straight back is not recovered the same way twice: a
//! page that dies again soon after a reload escalates to a restart, and a
//! failure soon after a recovery restart closes the app with a message rather
//! than restarting in a loop.

use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use tauri::{AppHandle, Manager, WebviewWindow};
use webview2_com::Microsoft::Web::WebView2::Win32::{
    ICoreWebView2Controller, ICoreWebView2ProcessFailedEventArgs, ICoreWebView2ProcessFailedEventArgs2,
    COREWEBVIEW2_PROCESS_FAILED_KIND, COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED,
    COREWEBVIEW2_PROCESS_FAILED_KIND_FRAME_RENDER_PROCESS_EXITED,
    COREWEBVIEW2_PROCESS_FAILED_KIND_GPU_PROCESS_EXITED,
    COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED,
    COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE,
    COREWEBVIEW2_PROCESS_FAILED_KIND_UTILITY_PROCESS_EXITED, COREWEBVIEW2_PROCESS_FAILED_REASON,
    COREWEBVIEW2_PROCESS_FAILED_REASON_CRASHED, COREWEBVIEW2_PROCESS_FAILED_REASON_LAUNCH_FAILED,
    COREWEBVIEW2_PROCESS_FAILED_REASON_OUT_OF_MEMORY, COREWEBVIEW2_PROCESS_FAILED_REASON_PROFILE_DELETED,
    COREWEBVIEW2_PROCESS_FAILED_REASON_TERMINATED, COREWEBVIEW2_PROCESS_FAILED_REASON_UNRESPONSIVE,
};
use webview2_com::{take_pwstr, ProcessFailedEventHandler};
use windows::core::{Interface, HSTRING, PWSTR};
use windows::Win32::UI::WindowsAndMessaging::{MessageBoxW, MB_ICONERROR, MB_OK, MB_SETFOREGROUND};

use crate::crash_log::CrashLog;
use crate::mpv::Mpv;

/// Set on a recovery restart, read by the new process: when it was asked for.
const RESTARTED_AT_VAR: &str = "HALO_RECOVERY_RESTARTED_AT";
/// A page that dies again this soon after a reload is not reloaded again.
const RELOAD_GRACE: Duration = Duration::from_secs(30);
/// A failure this soon after a recovery restart is not restarted again.
const RESTART_GRACE: Duration = Duration::from_secs(60);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Failure {
    /// The browser process is gone; the webview cannot be used again.
    BrowserExited,
    /// The page's render process is gone; a reload starts a new one.
    PageExited,
    /// The page stopped answering but still exists; it may recover by itself.
    PageUnresponsive,
    /// A helper (GPU, utility, iframe) process WebView2 restarts on its own.
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Action {
    Record,
    Reload,
    Restart,
    GiveUp,
}

/// What to do about `failure`, given how long ago this process reloaded the
/// page and how long ago the app was restarted for a failure, if ever.
fn action_for(failure: Failure, since_reload: Option<Duration>, since_restart: Option<Duration>) -> Action {
    let within = |since: Option<Duration>, grace: Duration| since.is_some_and(|elapsed| elapsed < grace);
    match failure {
        Failure::PageUnresponsive | Failure::Other => Action::Record,
        Failure::PageExited if !within(since_reload, RELOAD_GRACE) => Action::Reload,
        Failure::PageExited | Failure::BrowserExited if within(since_restart, RESTART_GRACE) => Action::GiveUp,
        Failure::PageExited | Failure::BrowserExited => Action::Restart,
    }
}

fn failure_of(kind: COREWEBVIEW2_PROCESS_FAILED_KIND) -> Failure {
    match kind {
        COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED => Failure::BrowserExited,
        COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED => Failure::PageExited,
        COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE => Failure::PageUnresponsive,
        _ => Failure::Other,
    }
}

fn kind_name(kind: COREWEBVIEW2_PROCESS_FAILED_KIND) -> String {
    match kind {
        COREWEBVIEW2_PROCESS_FAILED_KIND_BROWSER_PROCESS_EXITED => "browser process exited".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_EXITED => "page process exited".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_KIND_RENDER_PROCESS_UNRESPONSIVE => "page process unresponsive".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_KIND_FRAME_RENDER_PROCESS_EXITED => "frame process exited".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_KIND_GPU_PROCESS_EXITED => "GPU process exited".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_KIND_UTILITY_PROCESS_EXITED => "utility process exited".to_owned(),
        other => format!("process failure kind {}", other.0),
    }
}

fn reason_name(reason: COREWEBVIEW2_PROCESS_FAILED_REASON) -> String {
    match reason {
        COREWEBVIEW2_PROCESS_FAILED_REASON_CRASHED => "crashed".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_REASON_LAUNCH_FAILED => "launch failed".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_REASON_OUT_OF_MEMORY => "out of memory".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_REASON_PROFILE_DELETED => "profile deleted".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_REASON_TERMINATED => "terminated".to_owned(),
        COREWEBVIEW2_PROCESS_FAILED_REASON_UNRESPONSIVE => "unresponsive".to_owned(),
        other => format!("reason {}", other.0),
    }
}

/// One log line: the kind, plus the reason, exit code and process name when
/// the runtime offers them (ICoreWebView2ProcessFailedEventArgs2).
fn describe(kind: COREWEBVIEW2_PROCESS_FAILED_KIND, args: &ICoreWebView2ProcessFailedEventArgs) -> String {
    let mut line = format!("webview2 {}", kind_name(kind));
    let Ok(details) = args.cast::<ICoreWebView2ProcessFailedEventArgs2>() else {
        return line;
    };
    let mut reason = COREWEBVIEW2_PROCESS_FAILED_REASON::default();
    if unsafe { details.Reason(&mut reason) }.is_ok() {
        line.push_str(&format!(", {}", reason_name(reason)));
    }
    let mut exit_code = 0i32;
    if unsafe { details.ExitCode(&mut exit_code) }.is_ok() {
        // Windows exit codes read as NTSTATUS values, e.g. 0xC0000005.
        line.push_str(&format!(", exit code 0x{:08X}", exit_code as u32));
    }
    let mut description = PWSTR::null();
    if unsafe { details.ProcessDescription(&mut description) }.is_ok() {
        let description = take_pwstr(description);
        if !description.is_empty() {
            line.push_str(&format!(", process \"{description}\""));
        }
    }
    line
}

/// When this process was started by a recovery restart, if it was.
fn restarted_at() -> Option<SystemTime> {
    let seconds = std::env::var(RESTARTED_AT_VAR).ok()?.parse::<u64>().ok()?;
    Some(UNIX_EPOCH + Duration::from_secs(seconds))
}

/// Starts watching the main webview's WebView2 processes. Runs the
/// subscription on the UI thread, where WebView2 also raises the event.
pub fn watch(window: &WebviewWindow, mpv: Arc<Mpv>, log: Arc<CrashLog>) -> tauri::Result<()> {
    let app = window.app_handle().clone();
    let restarted_at = restarted_at();
    window.with_webview(move |platform| {
        if let Err(error) = subscribe(&platform.controller(), app, mpv, log.clone(), restarted_at) {
            log.record(&format!("could not watch WebView2 processes: {error}"));
        }
    })
}

fn subscribe(
    controller: &ICoreWebView2Controller,
    app: AppHandle,
    mpv: Arc<Mpv>,
    log: Arc<CrashLog>,
    restarted_at: Option<SystemTime>,
) -> windows::core::Result<()> {
    let webview = unsafe { controller.CoreWebView2() }?;
    let mut last_reload: Option<Instant> = None;
    // Once the app is going, later failures (one dead browser process raises
    // several) are only recorded.
    let mut leaving = false;
    let handler = ProcessFailedEventHandler::create(Box::new(move |sender, args| {
        let Some(args) = args else { return Ok(()) };
        let mut kind = COREWEBVIEW2_PROCESS_FAILED_KIND::default();
        unsafe { args.ProcessFailedKind(&mut kind) }?;
        log.record(&describe(kind, &args));
        if leaving {
            return Ok(());
        }
        let since_restart = restarted_at.and_then(|at| SystemTime::now().duration_since(at).ok());
        match action_for(failure_of(kind), last_reload.map(|at| at.elapsed()), since_restart) {
            Action::Record => {}
            Action::Reload => {
                stop_playback(&mpv, &log);
                last_reload = Some(Instant::now());
                match sender {
                    Some(page) => match unsafe { page.Reload() } {
                        Ok(()) => log.record("recovered: reloaded the page"),
                        Err(error) => log.record(&format!("reload failed: {error}")),
                    },
                    None => log.record("reload skipped: the event named no webview"),
                }
            }
            Action::Restart => {
                leaving = true;
                stop_playback(&mpv, &log);
                log.record("recovering: restarting Halo");
                let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|since| since.as_secs()).unwrap_or(0);
                // The new process inherits this environment (tauri::process::restart).
                std::env::set_var(RESTARTED_AT_VAR, now.to_string());
                app.request_restart();
            }
            Action::GiveUp => {
                leaving = true;
                stop_playback(&mpv, &log);
                log.record("giving up: the failure came back right after a restart, closing Halo");
                give_up(&app, &log);
            }
        }
        Ok(())
    }));
    let mut token = 0i64;
    unsafe { webview.add_ProcessFailed(&handler, &mut token) }
}

fn stop_playback(mpv: &Mpv, log: &CrashLog) {
    if let Err(error) = mpv.cmd(&["stop".to_owned()]) {
        log.record(&format!("could not stop playback: {error}"));
    }
}

/// Says why the app is closing, then closes it. Runs after the event handler
/// returns, so the message box's own message loop never runs inside it.
fn give_up(app: &AppHandle, log: &CrashLog) {
    let text = HSTRING::from(format!(
        "Halo's display stopped working again right after restarting, so Halo will close now.\n\n\
         Details are in:\n{}",
        log.path().display()
    ));
    let closing = app.clone();
    let scheduled = app.run_on_main_thread(move || {
        unsafe { MessageBoxW(None, &text, &HSTRING::from("Halo"), MB_OK | MB_ICONERROR | MB_SETFOREGROUND) };
        closing.exit(1);
    });
    if scheduled.is_err() {
        app.exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::{action_for, Action, Failure, RELOAD_GRACE, RESTART_GRACE};
    use std::time::Duration;

    const SOON: Duration = Duration::from_secs(5);
    const LONG_AGO: Duration = Duration::from_secs(3600);

    #[test]
    fn helper_and_hung_page_failures_are_only_recorded() {
        for failure in [Failure::Other, Failure::PageUnresponsive] {
            assert_eq!(action_for(failure, None, None), Action::Record);
            assert_eq!(action_for(failure, Some(SOON), Some(SOON)), Action::Record);
        }
    }

    #[test]
    fn a_dead_page_is_reloaded_unless_it_just_was() {
        assert_eq!(action_for(Failure::PageExited, None, None), Action::Reload);
        assert_eq!(action_for(Failure::PageExited, Some(LONG_AGO), None), Action::Reload);
        assert_eq!(action_for(Failure::PageExited, Some(RELOAD_GRACE), None), Action::Reload);
        // Straight back after a reload: escalate to a restart.
        assert_eq!(action_for(Failure::PageExited, Some(SOON), None), Action::Restart);
    }

    #[test]
    fn a_dead_browser_restarts_the_app_unless_a_restart_just_happened() {
        assert_eq!(action_for(Failure::BrowserExited, None, None), Action::Restart);
        assert_eq!(action_for(Failure::BrowserExited, None, Some(LONG_AGO)), Action::Restart);
        assert_eq!(action_for(Failure::BrowserExited, None, Some(RESTART_GRACE)), Action::Restart);
        assert_eq!(action_for(Failure::BrowserExited, None, Some(SOON)), Action::GiveUp);
    }

    #[test]
    fn a_page_that_keeps_dying_after_a_recovery_restart_closes_the_app() {
        assert_eq!(action_for(Failure::PageExited, Some(SOON), Some(SOON)), Action::GiveUp);
        // A first page failure after a recent restart still gets its reload.
        assert_eq!(action_for(Failure::PageExited, None, Some(SOON)), Action::Reload);
    }
}
