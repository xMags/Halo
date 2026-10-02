//! A local record of what took the app down or had to be recovered: Rust
//! panics and WebView2 process failures (webview_recovery.rs).
//!
//! Release builds have no console, so before this a panic or a dead WebView2
//! left no trace anywhere. The file stays on this machine, in the app's log
//! folder, and holds no URLs or account data: only what failed, where and
//! when.

use std::fs::{self, OpenOptions};
use std::io::Write;
use std::panic;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

/// Past this size the log becomes `crash.old.log`, replacing the previous
/// one, and starts over, so it never grows without bound.
const ROTATE_AT_BYTES: u64 = 512 * 1024;

pub struct CrashLog {
    path: PathBuf,
    old_path: PathBuf,
}

impl CrashLog {
    pub fn new(dir: &Path) -> Self {
        Self {
            path: dir.join("crash.log"),
            old_path: dir.join("crash.old.log"),
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Appends one timestamped line. Best-effort: a log that cannot be
    /// written must never take down the app it is recording.
    pub fn record(&self, message: &str) {
        let _ = self.append(SystemTime::now(), message);
    }

    fn append(&self, at: SystemTime, message: &str) -> std::io::Result<()> {
        if let Some(dir) = self.path.parent() {
            fs::create_dir_all(dir)?;
        }
        if fs::metadata(&self.path).is_ok_and(|meta| meta.len() >= ROTATE_AT_BYTES) {
            // Replaces the previous old log; a failed move only means this
            // file keeps growing until the next try.
            let _ = fs::rename(&self.path, &self.old_path);
        }
        // One write per line keeps lines from different threads whole.
        let line = format!("{} {}\n", utc_timestamp(at), message.replace(['\r', '\n'], " "));
        OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)?
            .write_all(line.as_bytes())
    }
}

/// Records every panic, on any thread, then hands it on to the previous hook,
/// which prints it in debug builds. A panic on the main thread ends the app;
/// one on a background thread, such as mpv's event pump, leaves the app open
/// but the player stuck, so both are worth a line.
pub fn record_panics(log: Arc<CrashLog>) {
    let previous = panic::take_hook();
    panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current();
        let location = info
            .location()
            .map(|place| format!("{}:{}", place.file(), place.line()))
            .unwrap_or_else(|| "an unknown location".to_owned());
        let payload = info.payload();
        let message = payload
            .downcast_ref::<&str>()
            .copied()
            .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
            .unwrap_or("(no message)");
        log.record(&format!(
            "panic in thread '{}' at {location}: {message}",
            thread.name().unwrap_or("unnamed")
        ));
        previous(info);
    }));
}

/// `2026-10-02T16:34:05Z`, so the log needs no date crate.
fn utc_timestamp(at: SystemTime) -> String {
    let seconds = at.duration_since(UNIX_EPOCH).map(|since| since.as_secs()).unwrap_or(0);
    let (days, of_day) = (seconds / 86_400, seconds % 86_400);
    let (year, month, day) = civil_from_days(days);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        of_day / 3600,
        of_day % 3600 / 60,
        of_day % 60
    )
}

/// Days since 1970-01-01 to a Gregorian (year, month, day), by Howard
/// Hinnant's `civil_from_days`: shift to eras starting on 1 March, so the
/// leap day ends each 400-year era and month lengths repeat every 5 months.
fn civil_from_days(days: u64) -> (u64, u64, u64) {
    let shifted = days + 719_468;
    let era = shifted / 146_097;
    let day_of_era = shifted % 146_097;
    let year_of_era = (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_from_march = (5 * day_of_year + 2) / 153;
    let day = day_of_year - (153 * month_from_march + 2) / 5 + 1;
    let month = if month_from_march < 10 { month_from_march + 3 } else { month_from_march - 9 };
    let year = year_of_era + era * 400 + u64::from(month <= 2);
    (year, month, day)
}

#[cfg(test)]
mod tests {
    use super::{utc_timestamp, CrashLog, ROTATE_AT_BYTES};
    use std::fs;
    use std::path::PathBuf;
    use std::time::{Duration, UNIX_EPOCH};

    fn at(seconds: u64) -> String {
        utc_timestamp(UNIX_EPOCH + Duration::from_secs(seconds))
    }

    fn scratch_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("halo-crash-log-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn timestamps_are_utc_calendar_dates() {
        assert_eq!(at(0), "1970-01-01T00:00:00Z");
        assert_eq!(at(951_782_400), "2000-02-29T00:00:00Z");
        assert_eq!(at(1_700_000_000), "2023-11-14T22:13:20Z");
        assert_eq!(at(1_735_689_599), "2024-12-31T23:59:59Z");
        assert_eq!(at(1_735_689_600), "2025-01-01T00:00:00Z");
        assert_eq!(at(4_107_542_400), "2100-03-01T00:00:00Z");
    }

    #[test]
    fn each_record_is_one_line_in_a_folder_it_creates() {
        let dir = scratch_dir("lines");
        let log = CrashLog::new(&dir.join("logs"));
        log.record("first");
        log.record("second\r\nstill second");
        let text = fs::read_to_string(log.path()).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines.len(), 2);
        assert!(lines[0].ends_with("Z first"));
        assert!(lines[1].ends_with("Z second  still second"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_full_log_moves_aside_and_starts_over() {
        let dir = scratch_dir("rotate");
        let log = CrashLog::new(&dir);
        fs::create_dir_all(&dir).unwrap();
        fs::write(log.path(), vec![b'x'; ROTATE_AT_BYTES as usize]).unwrap();
        log.record("fresh");
        assert!(fs::read_to_string(log.path()).unwrap().ends_with("Z fresh\n"));
        assert_eq!(fs::metadata(dir.join("crash.old.log")).unwrap().len(), ROTATE_AT_BYTES);
        let _ = fs::remove_dir_all(&dir);
    }
}
