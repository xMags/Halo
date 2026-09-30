//! Discord Rich Presence, ported from the native WinUI Halo Desktop
//! (`Services/DiscordPresence.cpp`) so both clients show the same activity.
//!
//! The webview reports what is playing (`set_media`) and every playback change
//! (`update`); this module decides what Discord should show, throttles
//! repeats, and a worker thread delivers it over Discord's local IPC pipe,
//! retrying while Discord is closed. Only display text and a public poster URL
//! ever cross into Discord: no stream URL, addon, header or server address.

use serde::Deserialize;
use serde_json::{json, Map, Value};
use std::net::{Ipv4Addr, Ipv6Addr};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::JoinHandle;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

/// The native app's Discord application. Application IDs are public
/// identifiers; Rich Presence uses no Discord token or credential.
pub const APPLICATION_ID: &str = "1544266293249712128";
/// The artwork uploaded to that application, shown when no poster is usable.
pub const ARTWORK_KEY: &str = "halo";

/// Discord rejects activity strings longer than this many UTF-8 bytes.
const MAXIMUM_TEXT_BYTES: usize = 128;
/// While playing, an unchanged activity is re-sent this often so Discord's
/// elapsed/remaining timer does not drift from the real position.
const REFRESH_INTERVAL: Duration = Duration::from_secs(15);
/// How long the worker waits before retrying a failed delivery.
pub const RETRY_DELAY: Duration = Duration::from_secs(5);

/// What is playing, as the player knows it.
#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PresenceMedia {
    pub title: String,
    pub show_name: String,
    pub episode_label: String,
    pub media_type: String,
    pub poster_url: String,
}

/// The player's state at one moment. `file_serial` is zero until mpv has
/// loaded the file; `seek_serial` changes whenever playback restarts at a new
/// position, which is what re-anchors Discord's timer.
#[derive(Clone, Copy, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct PlaybackSnapshot {
    pub file_serial: u64,
    pub seek_serial: u64,
    pub ended: bool,
    pub buffering: bool,
    pub paused: bool,
    pub position_seconds: f64,
    pub duration_seconds: f64,
    pub speed: f64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlaybackKind {
    Playing,
    Paused,
    Buffering,
}

#[derive(Clone, Debug)]
pub struct Activity {
    pub details: String,
    pub state: String,
    pub artwork_url: String,
    pub playback: PlaybackKind,
    pub position_seconds: f64,
    pub duration_seconds: f64,
    pub playback_rate: f64,
    pub file_serial: u64,
    pub seek_serial: u64,
    pub captured_at: SystemTime,
}

impl Activity {
    /// Whether two activities would look the same in Discord, ignoring when
    /// they were captured.
    fn same_identity(&self, other: &Activity) -> bool {
        self.details == other.details
            && self.state == other.state
            && self.artwork_url == other.artwork_url
            && self.playback == other.playback
            && self.file_serial == other.file_serial
            && self.seek_serial == other.seek_serial
            && (self.playback_rate - other.playback_rate).abs() < 0.001
    }
}

// ── Policy ──────────────────────────────────────────────────────────────────

/// Control characters become spaces, then outer spaces go.
fn clean_text(value: &str) -> String {
    let cleaned: String = value
        .chars()
        .map(|c| if (c as u32) < 0x20 || c == '\u{7f}' { ' ' } else { c })
        .collect();
    cleaned.trim_matches(' ').to_string()
}

/// Cleaned and cut to Discord's byte limit on a character boundary.
fn truncate_text(value: &str) -> String {
    let mut text = clean_text(value);
    while text.len() > MAXIMUM_TEXT_BYTES {
        text.pop();
    }
    text
}

fn join_episode(media: &PresenceMedia) -> String {
    let episode = clean_text(&media.episode_label);
    let title = clean_text(&media.title);
    match (episode.is_empty(), title.is_empty()) {
        (true, _) => title,
        (false, true) => episode,
        (false, false) => format!("{episode} · {title}"),
    }
}

fn is_series(media: &PresenceMedia) -> bool {
    clean_text(&media.media_type).to_lowercase() == "series"
}

fn is_public_ipv4(address: Ipv4Addr) -> bool {
    let [first, second, ..] = address.octets();
    first != 0
        && first != 10
        && first != 127
        && first < 224
        && !(first == 100 && (64..=127).contains(&second))
        && !(first == 169 && second == 254)
        && !(first == 172 && (16..=31).contains(&second))
        && !(first == 192 && (second == 0 || second == 2 || second == 168))
        && !(first == 198 && (second == 18 || second == 19 || second == 51))
        && !(first == 203 && second == 0)
}

fn is_public_ipv6(address: Ipv6Addr) -> bool {
    let first = address.octets()[0];
    let link_local = (address.segments()[0] & 0xffc0) == 0xfe80;
    !(address.is_loopback() || address.is_unspecified() || link_local || address.is_multicast())
        && (first & 0xfe) != 0xfc
}

/// The poster, only when Discord can fetch it without learning anything about
/// the viewer's network: HTTPS, a public host, and no credentials, query or
/// fragment (addon image URLs can carry tokens there).
fn public_artwork_url(value: &str) -> String {
    if value.is_empty() || value.len() > 2048 {
        return String::new();
    }
    let Ok(url) = url::Url::parse(value) else {
        return String::new();
    };
    if url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return String::new();
    }
    let public = match url.host() {
        Some(url::Host::Domain(domain)) => {
            let host = domain.to_lowercase();
            !(host.is_empty()
                || host == "localhost"
                || host.ends_with(".localhost")
                || host.ends_with(".local")
                || host.ends_with(".internal")
                || host.ends_with(".lan"))
        }
        Some(url::Host::Ipv4(address)) => is_public_ipv4(address),
        Some(url::Host::Ipv6(address)) => is_public_ipv6(address),
        None => false,
    };
    if public {
        url.to_string()
    } else {
        String::new()
    }
}

fn playback_kind(snapshot: &PlaybackSnapshot) -> Option<PlaybackKind> {
    if snapshot.file_serial == 0 || snapshot.ended {
        return None;
    }
    if snapshot.buffering {
        return Some(PlaybackKind::Buffering);
    }
    if snapshot.paused {
        return Some(PlaybackKind::Paused);
    }
    Some(PlaybackKind::Playing)
}

fn positive_or(value: f64, fallback: f64) -> f64 {
    if value.is_finite() && value > 0.0 {
        value
    } else {
        fallback
    }
}

/// What Discord should show for this moment of playback, or `None` when
/// nothing should be shown (no file yet, or playback ended).
///
/// A film shows its title and "Movie"; an episode shows the series and
/// "S01E06 · Episode title". Pausing and buffering are appended to that line
/// and stop the timer.
pub fn build_activity(
    media: &PresenceMedia,
    snapshot: &PlaybackSnapshot,
    now: SystemTime,
) -> Option<Activity> {
    let kind = playback_kind(snapshot)?;
    let series = is_series(media);
    let mut details = if series { clean_text(&media.show_name) } else { clean_text(&media.title) };
    let episode = if series { join_episode(media) } else { "Movie".to_string() };
    if details.is_empty() && series {
        details = clean_text(&media.title);
    }
    let details = truncate_text(&details);
    let mut episode = truncate_text(&episode);
    if details.is_empty() {
        return None;
    }
    let suffix = match kind {
        PlaybackKind::Paused => Some("Paused"),
        PlaybackKind::Buffering => Some("Buffering"),
        PlaybackKind::Playing => None,
    };
    if let Some(suffix) = suffix {
        episode = if episode.is_empty() { suffix.to_string() } else { format!("{episode} · {suffix}") };
    }
    Some(Activity {
        details,
        state: truncate_text(&episode),
        artwork_url: public_artwork_url(&media.poster_url),
        playback: kind,
        position_seconds: positive_or(snapshot.position_seconds, 0.0),
        duration_seconds: positive_or(snapshot.duration_seconds, 0.0),
        playback_rate: positive_or(snapshot.speed, 1.0),
        file_serial: snapshot.file_serial,
        seek_serial: snapshot.seek_serial,
        captured_at: now,
    })
}

fn next_nonce(process_id: u32) -> String {
    static SEQUENCE: AtomicU64 = AtomicU64::new(0);
    format!("{process_id}:{}", SEQUENCE.fetch_add(1, Ordering::Relaxed) + 1)
}

fn unix_seconds(value: SystemTime) -> i64 {
    value.duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

/// The `SET_ACTIVITY` command for an activity.
pub fn serialize_set_activity(activity: &Activity, process_id: u32) -> String {
    let use_poster = !activity.artwork_url.is_empty();
    let mut presence = Map::new();
    presence.insert("details".into(), json!(activity.details));
    if !activity.state.is_empty() {
        presence.insert("state".into(), json!(activity.state));
    }
    presence.insert(
        "assets".into(),
        json!({
            "large_image": if use_poster { activity.artwork_url.as_str() } else { ARTWORK_KEY },
            "large_text": if use_poster { activity.details.as_str() } else { "Halo" },
        }),
    );
    presence.insert("instance".into(), json!(false));
    // Discord activity type 3 is Watching; omitting it defaults to Playing.
    presence.insert("type".into(), json!(3));
    if activity.playback == PlaybackKind::Playing {
        let now = unix_seconds(activity.captured_at);
        let rate = activity.playback_rate;
        let mut timestamps = Map::new();
        timestamps.insert("start".into(), json!(now - (activity.position_seconds / rate) as i64));
        if activity.duration_seconds > activity.position_seconds {
            let remaining = (activity.duration_seconds - activity.position_seconds) / rate;
            timestamps.insert("end".into(), json!(now + remaining as i64));
        }
        presence.insert("timestamps".into(), Value::Object(timestamps));
    }
    json!({
        "cmd": "SET_ACTIVITY",
        "nonce": next_nonce(process_id),
        "args": { "pid": process_id, "activity": Value::Object(presence) },
    })
    .to_string()
}

/// The `SET_ACTIVITY` command that removes this process's activity.
pub fn serialize_clear_activity(process_id: u32) -> String {
    json!({
        "cmd": "SET_ACTIVITY",
        "nonce": next_nonce(process_id),
        "args": { "pid": process_id, "activity": Value::Null },
    })
    .to_string()
}

/// The same command with the poster swapped for the Halo artwork, or with no
/// artwork at all when the Halo artwork was already the one Discord refused.
fn with_fallback_artwork(payload: &str) -> Option<String> {
    let mut root: Value = serde_json::from_str(payload).ok()?;
    let activity = root.get_mut("args")?.get_mut("activity")?.as_object_mut()?;
    let assets = activity.get_mut("assets")?.as_object_mut()?;
    if assets.get("large_image").and_then(Value::as_str) == Some(ARTWORK_KEY) {
        activity.remove("assets");
    } else {
        assets.insert("large_image".into(), json!(ARTWORK_KEY));
        assets.insert("large_text".into(), json!("Halo"));
    }
    root.as_object_mut()?.insert("nonce".into(), json!(next_nonce(std::process::id())));
    Some(root.to_string())
}

// ── Service ─────────────────────────────────────────────────────────────────

/// Delivers one command to Discord. Returns false when it did not arrive.
pub trait Transport: Send {
    fn send(&mut self, application_id: &str, payload: &str) -> bool;
}

enum Pending {
    Activity(Activity),
    Clear,
}

#[derive(Default)]
struct State {
    media: Option<PresenceMedia>,
    last_activity: Option<Activity>,
    pending: Option<Pending>,
    enabled: bool,
    has_published: bool,
    stopping: bool,
}

impl State {
    /// Whether Discord may be showing, or about to show, something of ours.
    fn has_anything_to_clear(&self) -> bool {
        self.has_published || self.last_activity.is_some() || self.pending.is_some()
    }
}

struct Shared {
    state: Mutex<State>,
    wake: Condvar,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, State> {
        // A panic while holding the lock leaves plain data behind; keep going.
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

/// The presence service: callers state what is playing and how it is going;
/// one worker thread owns the transport and delivers the latest command.
/// Only the newest pending command is kept, so a burst of changes while
/// Discord is slow or closed collapses to what is true now.
pub struct PresenceService {
    shared: Arc<Shared>,
    worker: Mutex<Option<JoinHandle<()>>>,
}

impl PresenceService {
    pub fn new(enabled: bool, transport: Box<dyn Transport>, retry_delay: Duration) -> Self {
        let shared = Arc::new(Shared {
            state: Mutex::new(State { enabled, ..State::default() }),
            wake: Condvar::new(),
        });
        let retry_delay = retry_delay.max(Duration::from_millis(1));
        let worker_shared = Arc::clone(&shared);
        let worker = std::thread::Builder::new()
            .name("discord-presence".into())
            .spawn(move || run_worker(worker_shared, transport, retry_delay))
            .ok();
        Self { shared, worker: Mutex::new(worker) }
    }

    /// Turns presence on or off. Off clears what Discord shows; on replays the
    /// activity playback last produced.
    pub fn set_enabled(&self, enabled: bool) {
        let mut state = self.shared.lock();
        if state.enabled == enabled {
            return;
        }
        state.enabled = enabled;
        if enabled {
            state.pending = state.last_activity.take().map(Pending::Activity);
        } else {
            let clear = state.has_anything_to_clear();
            state.pending = clear.then_some(Pending::Clear);
        }
        self.shared.wake.notify_all();
    }

    /// A new file is starting: remember what it is, forget the previous one.
    pub fn set_media(&self, media: PresenceMedia) {
        let mut state = self.shared.lock();
        state.media = Some(media);
        state.last_activity = None;
        state.pending = None;
    }

    /// Playback changed. Unchanged activity is not re-sent, except every 15s
    /// while playing so Discord's timer stays anchored to the real position.
    pub fn update(&self, snapshot: PlaybackSnapshot) {
        self.update_at(snapshot, SystemTime::now());
    }

    fn update_at(&self, snapshot: PlaybackSnapshot, now: SystemTime) {
        let mut state = self.shared.lock();
        let Some(media) = state.media.as_ref() else {
            return;
        };
        match build_activity(media, &snapshot, now) {
            Some(activity) => {
                if let Some(last) = state.last_activity.as_ref() {
                    let elapsed = now.duration_since(last.captured_at).unwrap_or_default();
                    if last.same_identity(&activity)
                        && (activity.playback != PlaybackKind::Playing || elapsed < REFRESH_INTERVAL)
                    {
                        return;
                    }
                }
                state.last_activity = Some(activity.clone());
                if !state.enabled {
                    return;
                }
                state.pending = Some(Pending::Activity(activity));
                self.shared.wake.notify_all();
            }
            None => {
                // Nothing to show right now, but the file is still this one.
                let clear = state.has_anything_to_clear();
                state.last_activity = None;
                state.pending = None;
                if clear {
                    state.pending = Some(Pending::Clear);
                    self.shared.wake.notify_all();
                }
            }
        }
    }

    /// Playback stopped: clear Discord and forget the media.
    pub fn clear(&self) {
        let mut state = self.shared.lock();
        let clear = state.has_anything_to_clear();
        state.media = None;
        state.last_activity = None;
        state.pending = clear.then_some(Pending::Clear);
        self.shared.wake.notify_all();
    }

    /// Clears Discord and stops the worker, waiting for it to finish. A
    /// failed delivery at this point is not retried, so the wait is bounded
    /// by one delivery attempt.
    pub fn shutdown(&self) {
        self.clear();
        self.shared.lock().stopping = true;
        self.shared.wake.notify_all();
        let worker = self.worker.lock().unwrap_or_else(|p| p.into_inner()).take();
        if let Some(worker) = worker {
            let _ = worker.join();
        }
    }
}

impl Drop for PresenceService {
    fn drop(&mut self) {
        self.shutdown();
    }
}

fn run_worker(shared: Arc<Shared>, mut transport: Box<dyn Transport>, retry_delay: Duration) {
    loop {
        let pending = {
            let mut state = shared
                .wake
                .wait_while(shared.lock(), |s| !s.stopping && s.pending.is_none())
                .unwrap_or_else(|p| p.into_inner());
            match state.pending.take() {
                Some(pending) => pending,
                None => return,
            }
        };
        let clear = matches!(pending, Pending::Clear);
        let payload = match &pending {
            Pending::Clear => serialize_clear_activity(std::process::id()),
            Pending::Activity(activity) => serialize_set_activity(activity, std::process::id()),
        };
        // Delivered without the lock: a slow Discord must not block callers.
        let sent = transport.send(APPLICATION_ID, &payload);
        let mut state = shared.lock();
        if sent {
            state.has_published = !clear;
            continue;
        }
        if state.stopping {
            return;
        }
        // Wait, then retry this command unless a newer one has arrived.
        let (mut state, _) = shared
            .wake
            .wait_timeout_while(state, retry_delay, |s| !s.stopping && s.pending.is_none())
            .unwrap_or_else(|p| p.into_inner());
        if !state.stopping && state.pending.is_none() {
            state.pending = Some(pending);
        }
    }
}

// ── Transport: Discord's local IPC pipe ─────────────────────────────────────

#[cfg(windows)]
pub use pipe::NamedPipeTransport;

#[cfg(windows)]
mod pipe {
    use super::{with_fallback_artwork, Transport};
    use serde_json::{json, Value};
    use std::fs::{File, OpenOptions};
    use std::io::{Read, Write};
    use std::os::windows::io::AsRawHandle;
    use std::time::{Duration, Instant};
    use windows_sys::Win32::System::Pipes::{PeekNamedPipe, WaitNamedPipeW};

    /// Discord listens on `discord-ipc-0` through `-9`, one per running client.
    const MAXIMUM_PIPE_ATTEMPTS: u32 = 10;
    const PIPE_WAIT_MILLISECONDS: u32 = 100;
    const RESPONSE_TIMEOUT: Duration = Duration::from_secs(2);
    const MAXIMUM_FRAME_BYTES: u32 = 1024 * 1024;
    const OP_HANDSHAKE: u32 = 0;
    const OP_FRAME: u32 = 1;
    const OP_PING: u32 = 3;
    const OP_PONG: u32 = 4;

    #[derive(PartialEq)]
    enum Reply {
        Success,
        Error,
        Disconnected,
    }

    /// Discord's IPC protocol over its named pipe: 8-byte little-endian
    /// headers (opcode, length) then JSON. Every read waits at most two
    /// seconds, so a hung Discord cannot stall the worker for good.
    pub struct NamedPipeTransport {
        pipe: Option<File>,
        application_id: String,
        prefix: String,
    }

    impl Default for NamedPipeTransport {
        fn default() -> Self {
            Self::with_prefix(r"\\.\pipe\discord-ipc-")
        }
    }

    impl NamedPipeTransport {
        /// A transport that looks for the pipes under another name (tests).
        pub fn with_prefix(prefix: &str) -> Self {
            Self { pipe: None, application_id: String::new(), prefix: prefix.to_string() }
        }

        fn ensure_connected(&mut self, application_id: &str) -> bool {
            if self.pipe.is_some() && self.application_id == application_id {
                return true;
            }
            self.pipe = None;
            self.application_id.clear();
            let handshake = json!({ "v": 1, "client_id": application_id }).to_string();
            for index in 0..MAXIMUM_PIPE_ATTEMPTS {
                let name = format!("{}{index}", self.prefix);
                let wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
                // SAFETY: `wide` is a NUL-terminated UTF-16 string that
                // outlives the call.
                if unsafe { WaitNamedPipeW(wide.as_ptr(), PIPE_WAIT_MILLISECONDS) } == 0 {
                    continue;
                }
                let Ok(file) = OpenOptions::new().read(true).write(true).open(&name) else {
                    continue;
                };
                self.pipe = Some(file);
                if self.write_frame(OP_HANDSHAKE, &handshake) && self.read_reply() == Reply::Success {
                    self.application_id = application_id.to_string();
                    return true;
                }
                self.pipe = None;
            }
            false
        }

        fn exchange(&mut self, payload: &str) -> Reply {
            if self.write_frame(OP_FRAME, payload) {
                self.read_reply()
            } else {
                Reply::Disconnected
            }
        }

        fn read_reply(&mut self) -> Reply {
            // Answer up to two pings before the reply itself.
            for _ in 0..3 {
                let Some((opcode, payload)) = self.read_frame() else {
                    return Reply::Disconnected;
                };
                if opcode == OP_PING {
                    if !self.write_raw(OP_PONG, &payload) {
                        return Reply::Disconnected;
                    }
                    continue;
                }
                if opcode != OP_FRAME {
                    return Reply::Disconnected;
                }
                let Ok(response) = serde_json::from_slice::<Value>(&payload) else {
                    return Reply::Disconnected;
                };
                return match response.get("evt") {
                    Some(Value::String(event)) if event == "ERROR" => Reply::Error,
                    _ => Reply::Success,
                };
            }
            Reply::Disconnected
        }

        /// Waits until `wanted` bytes can be read without blocking.
        fn wait_for_bytes(&self, wanted: u32) -> bool {
            let Some(pipe) = self.pipe.as_ref() else {
                return false;
            };
            let deadline = Instant::now() + RESPONSE_TIMEOUT;
            while Instant::now() < deadline {
                let mut available: u32 = 0;
                // SAFETY: the handle belongs to the open `File` borrowed above;
                // PeekNamedPipe writes only the count it is given a pointer to.
                let ok = unsafe {
                    PeekNamedPipe(
                        pipe.as_raw_handle(),
                        std::ptr::null_mut(),
                        0,
                        std::ptr::null_mut(),
                        &mut available,
                        std::ptr::null_mut(),
                    )
                };
                if ok == 0 {
                    return false;
                }
                if available >= wanted {
                    return true;
                }
                std::thread::sleep(Duration::from_millis(10));
            }
            false
        }

        fn read_frame(&mut self) -> Option<(u32, Vec<u8>)> {
            if !self.wait_for_bytes(8) {
                return None;
            }
            let pipe = self.pipe.as_mut()?;
            let mut header = [0u8; 8];
            pipe.read_exact(&mut header).ok()?;
            let opcode = u32::from_le_bytes(header[..4].try_into().ok()?);
            let length = u32::from_le_bytes(header[4..].try_into().ok()?);
            if length > MAXIMUM_FRAME_BYTES {
                return None;
            }
            let mut payload = vec![0u8; length as usize];
            if length > 0 {
                if !self.wait_for_bytes(length) {
                    return None;
                }
                self.pipe.as_mut()?.read_exact(&mut payload).ok()?;
            }
            Some((opcode, payload))
        }

        fn write_frame(&mut self, opcode: u32, payload: &str) -> bool {
            self.write_raw(opcode, payload.as_bytes())
        }

        fn write_raw(&mut self, opcode: u32, payload: &[u8]) -> bool {
            let Some(pipe) = self.pipe.as_mut() else {
                return false;
            };
            let Ok(length) = u32::try_from(payload.len()) else {
                return false;
            };
            let mut frame = Vec::with_capacity(8 + payload.len());
            frame.extend_from_slice(&opcode.to_le_bytes());
            frame.extend_from_slice(&length.to_le_bytes());
            frame.extend_from_slice(payload);
            pipe.write_all(&frame).is_ok()
        }
    }

    impl Transport for NamedPipeTransport {
        fn send(&mut self, application_id: &str, payload: &str) -> bool {
            if !self.ensure_connected(application_id) {
                return false;
            }
            match self.exchange(payload) {
                Reply::Success => return true,
                Reply::Error => {
                    // Discord refused the command, most often because it
                    // could not fetch the poster: retry with the Halo art.
                    if let Some(fallback) = with_fallback_artwork(payload) {
                        if self.exchange(&fallback) == Reply::Success {
                            return true;
                        }
                    }
                }
                Reply::Disconnected => {}
            }
            self.pipe = None;
            self.application_id.clear();
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    fn snapshot() -> PlaybackSnapshot {
        PlaybackSnapshot {
            file_serial: 7,
            seek_serial: 2,
            ended: false,
            buffering: false,
            paused: false,
            position_seconds: 120.0,
            duration_seconds: 3600.0,
            speed: 1.0,
        }
    }

    fn media(title: &str, show: &str, episode: &str, kind: &str, poster: &str) -> PresenceMedia {
        PresenceMedia {
            title: title.into(),
            show_name: show.into(),
            episode_label: episode.into(),
            media_type: kind.into(),
            poster_url: poster.into(),
        }
    }

    fn at(seconds: u64) -> SystemTime {
        UNIX_EPOCH + Duration::from_secs(seconds)
    }

    const ARRIVAL_POSTER: &str = "https://images.example.com/posters/arrival.jpg";

    #[test]
    fn a_playing_movie_shows_title_kind_poster_and_an_anchored_timer() {
        let movie = build_activity(&media("Arrival", "Arrival", "", "movie", ARRIVAL_POSTER), &snapshot(), at(2000))
            .expect("a playing movie produces presence");
        assert_eq!(movie.details, "Arrival");
        assert_eq!(movie.state, "Movie");
        let json = serialize_set_activity(&movie, 42);
        assert!(json.contains("\"start\":1880"), "{json}");
        assert!(json.contains("\"end\":5480"), "{json}");
        assert!(json.contains(ARRIVAL_POSTER), "{json}");
        assert!(json.contains("\"type\":3"), "{json}");
    }

    #[test]
    fn unsafe_posters_fall_back_to_the_halo_artwork() {
        for poster in [
            "http://images.example.com/poster.jpg",
            "https://user:password@images.example.com/poster.jpg",
            "https://images.example.com/poster.jpg?token=secret",
            "https://images.example.com/poster.jpg#fragment",
            "https://127.0.0.1/poster.jpg",
            "https://192.168.1.2/poster.jpg",
            "https://10.0.0.5/poster.jpg",
            "https://[::1]/poster.jpg",
            "https://[fd00::1]/poster.jpg",
            "https://poster.local/poster.jpg",
            "https://nas.lan/poster.jpg",
            "file:///C:/poster.jpg",
        ] {
            let activity = build_activity(&media("Arrival", "Arrival", "", "movie", poster), &snapshot(), at(2000))
                .expect("still presents");
            assert!(activity.artwork_url.is_empty(), "{poster} crossed the boundary");
            let json = serialize_set_activity(&activity, 42);
            assert!(json.contains("\"large_image\":\"halo\""), "{poster}: {json}");
        }
    }

    #[test]
    fn an_episode_shows_the_series_then_tag_and_title() {
        let episode = build_activity(
            &media("The Ones Who Live", "The Walking Dead", "S01E06", "series", ""),
            &snapshot(),
            at(2000),
        )
        .expect("a playing episode produces presence");
        assert_eq!(episode.details, "The Walking Dead");
        assert_eq!(episode.state, "S01E06 · The Ones Who Live");
    }

    #[test]
    fn paused_and_buffering_say_so_and_stop_the_timer() {
        let mut state = snapshot();
        state.paused = true;
        let paused = build_activity(&media("Arrival", "", "", "", ""), &state, at(2000)).unwrap();
        assert_eq!(paused.playback, PlaybackKind::Paused);
        let json = serialize_set_activity(&paused, 42);
        assert!(json.contains("Movie · Paused") && !json.contains("timestamps"), "{json}");

        state.paused = false;
        state.buffering = true;
        let buffering = build_activity(&media("Arrival", "", "", "", ""), &state, at(2000)).unwrap();
        let json = serialize_set_activity(&buffering, 42);
        assert!(json.contains("Buffering") && !json.contains("timestamps"), "{json}");
    }

    #[test]
    fn nothing_shows_before_the_file_loads_or_after_it_ends() {
        let arrival = media("Arrival", "", "", "", "");
        let mut state = snapshot();
        state.file_serial = 0;
        assert!(build_activity(&arrival, &state, at(2000)).is_none());
        state.file_serial = 7;
        state.ended = true;
        assert!(build_activity(&arrival, &state, at(2000)).is_none());
    }

    #[test]
    fn text_is_cleaned_and_cut_to_discords_byte_limit() {
        let mut title = "x".repeat(60);
        title.push('\n');
        title.push_str(&"é".repeat(60));
        let activity = build_activity(&media(&title, "", "", "", ""), &snapshot(), at(2000)).unwrap();
        assert!(activity.details.len() <= MAXIMUM_TEXT_BYTES);
        assert!(!activity.details.contains('\n'));
        assert!(serialize_clear_activity(42).contains("\"activity\":null"));
    }

    #[test]
    fn a_refused_poster_retries_with_the_halo_artwork_then_without_any() {
        let activity =
            build_activity(&media("Arrival", "", "", "", ARRIVAL_POSTER), &snapshot(), at(2000)).unwrap();
        let first = with_fallback_artwork(&serialize_set_activity(&activity, 42)).unwrap();
        assert!(first.contains("\"large_image\":\"halo\"") && first.contains("\"large_text\":\"Halo\""));
        let second = with_fallback_artwork(&first).unwrap();
        assert!(!second.contains("assets"), "{second}");
    }

    /// Records what reached "Discord"; can be told to fail the next sends.
    #[derive(Default)]
    struct Record {
        application_ids: Vec<String>,
        payloads: Vec<String>,
        failures_remaining: usize,
    }

    #[derive(Clone, Default)]
    struct FakeTransport(Arc<(StdMutex<Record>, Condvar)>);

    impl FakeTransport {
        fn wait_for_count(&self, count: usize) -> bool {
            let (lock, wake) = &*self.0;
            let guard = lock.lock().unwrap();
            let (guard, _) = wake
                .wait_timeout_while(guard, Duration::from_millis(500), |r| r.payloads.len() < count)
                .unwrap();
            guard.payloads.len() >= count
        }

        fn last(&self) -> String {
            self.0 .0.lock().unwrap().payloads.last().cloned().unwrap_or_default()
        }
    }

    impl Transport for FakeTransport {
        fn send(&mut self, application_id: &str, payload: &str) -> bool {
            let (lock, wake) = &*self.0;
            let mut record = lock.lock().unwrap();
            record.application_ids.push(application_id.to_string());
            record.payloads.push(payload.to_string());
            let ok = if record.failures_remaining > 0 {
                record.failures_remaining -= 1;
                false
            } else {
                true
            };
            wake.notify_all();
            ok
        }
    }

    #[test]
    fn the_switch_publishes_clears_and_replays_playback() {
        let transport = FakeTransport::default();
        let service = PresenceService::new(false, Box::new(transport.clone()), Duration::from_millis(5));
        service.set_media(media("Arrival", "", "", "", ""));
        service.update(snapshot());
        assert!(!transport.wait_for_count(1), "disabled presence published");

        service.set_enabled(true);
        assert!(transport.wait_for_count(1), "enabling did not publish current playback");
        assert_eq!(transport.0 .0.lock().unwrap().application_ids[0], APPLICATION_ID);

        service.set_enabled(false);
        assert!(transport.wait_for_count(2), "disabling did not clear");
        assert!(transport.last().contains("\"activity\":null"));

        service.set_enabled(true);
        service.update(snapshot());
        assert!(transport.wait_for_count(3), "re-enabling did not replay current playback");

        service.clear();
        assert!(transport.wait_for_count(4), "clearing did not reach Discord");
        assert!(transport.last().contains("\"activity\":null"));
    }

    #[test]
    fn a_local_file_keeps_its_media_until_it_loads() {
        let transport = FakeTransport::default();
        let service = PresenceService::new(true, Box::new(transport.clone()), Duration::from_millis(5));
        service.set_media(media(
            "Spider-Man: No Way Home",
            "Spider-Man: No Way Home",
            "",
            "movie",
            "https://images.example.com/posters/spider-man.jpg",
        ));
        let mut loading = snapshot();
        loading.file_serial = 0;
        loading.buffering = true;
        service.update(loading);
        assert!(!transport.wait_for_count(1), "pre-load playback published");
        service.update(snapshot());
        assert!(transport.wait_for_count(1));
        let last = transport.last();
        assert!(last.contains("Spider-Man: No Way Home"));
        assert!(last.contains("https://images.example.com/posters/spider-man.jpg"));
    }

    #[test]
    fn unchanged_activity_is_resent_only_to_refresh_a_running_timer() {
        let transport = FakeTransport::default();
        let service = PresenceService::new(true, Box::new(transport.clone()), Duration::from_millis(5));
        service.set_media(media("Arrival", "", "", "", ""));
        service.update_at(snapshot(), at(2000));
        assert!(transport.wait_for_count(1));
        service.update_at(snapshot(), at(2005));
        assert!(!transport.wait_for_count(2), "resent within 15 seconds");
        service.update_at(snapshot(), at(2016));
        assert!(transport.wait_for_count(2), "a running timer was not refreshed");

        let mut paused = snapshot();
        paused.paused = true;
        service.update_at(paused, at(2020));
        assert!(transport.wait_for_count(3));
        service.update_at(paused, at(2100));
        assert!(!transport.wait_for_count(4), "a paused activity was resent");
    }

    #[test]
    fn a_failed_delivery_is_retried_and_shutdown_stays_prompt() {
        let transport = FakeTransport::default();
        transport.0 .0.lock().unwrap().failures_remaining = 1;
        let started = std::time::Instant::now();
        {
            let service = PresenceService::new(true, Box::new(transport.clone()), Duration::from_millis(5));
            service.set_media(media("Arrival", "", "", "", ""));
            service.update(snapshot());
            assert!(transport.wait_for_count(2), "a failed delivery was not retried");
        }
        assert!(started.elapsed() < Duration::from_secs(2), "shutdown blocked");
    }

    /// The pipe transport against a fake Discord: handshake, a ping, a refused
    /// poster and the Halo-artwork retry, all over a real named pipe.
    #[cfg(windows)]
    #[test]
    fn the_pipe_transport_speaks_discords_ipc_protocol() {
        use std::fs::File;
        use std::io::{Read, Write};
        use std::os::windows::io::FromRawHandle;
        use windows_sys::Win32::Foundation::INVALID_HANDLE_VALUE;
        use windows_sys::Win32::Storage::FileSystem::PIPE_ACCESS_DUPLEX;
        use windows_sys::Win32::System::Pipes::{
            ConnectNamedPipe, CreateNamedPipeW, PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS,
            PIPE_TYPE_BYTE, PIPE_WAIT,
        };

        let prefix = format!(r"\\.\pipe\halo-test-discord-{}-", std::process::id());
        let name: Vec<u16> = format!("{prefix}0").encode_utf16().chain(Some(0)).collect();
        // SAFETY: `name` is NUL-terminated; the handle is owned by `File` below.
        let handle = unsafe {
            CreateNamedPipeW(
                name.as_ptr(),
                PIPE_ACCESS_DUPLEX,
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
                1,
                65536,
                65536,
                0,
                std::ptr::null(),
            )
        };
        assert_ne!(handle, INVALID_HANDLE_VALUE);
        let handle_value = handle as usize;

        let server = std::thread::spawn(move || {
            let handle = handle_value as windows_sys::Win32::Foundation::HANDLE;
            // SAFETY: the handle is a valid pipe instance created above.
            unsafe { ConnectNamedPipe(handle, std::ptr::null_mut()) };
            let mut pipe = unsafe { File::from_raw_handle(handle as _) };
            let read_frame = |pipe: &mut File| -> (u32, String) {
                let mut header = [0u8; 8];
                pipe.read_exact(&mut header).unwrap();
                let opcode = u32::from_le_bytes(header[..4].try_into().unwrap());
                let length = u32::from_le_bytes(header[4..].try_into().unwrap());
                let mut body = vec![0u8; length as usize];
                pipe.read_exact(&mut body).unwrap();
                (opcode, String::from_utf8(body).unwrap())
            };
            let write_frame = |pipe: &mut File, opcode: u32, body: &str| {
                let mut frame = opcode.to_le_bytes().to_vec();
                frame.extend_from_slice(&(body.len() as u32).to_le_bytes());
                frame.extend_from_slice(body.as_bytes());
                pipe.write_all(&frame).unwrap();
            };

            let (opcode, handshake) = read_frame(&mut pipe);
            assert_eq!(opcode, 0);
            assert!(handshake.contains(APPLICATION_ID), "{handshake}");
            write_frame(&mut pipe, 1, r#"{"cmd":"DISPATCH","evt":"READY"}"#);

            let (opcode, first) = read_frame(&mut pipe);
            assert_eq!(opcode, 1);
            // A ping before the reply must be answered with a pong.
            write_frame(&mut pipe, 3, "{}");
            let (pong, _) = read_frame(&mut pipe);
            assert_eq!(pong, 4);
            write_frame(&mut pipe, 1, r#"{"cmd":"SET_ACTIVITY","evt":"ERROR"}"#);

            let (_, retry) = read_frame(&mut pipe);
            write_frame(&mut pipe, 1, r#"{"cmd":"SET_ACTIVITY","evt":null}"#);
            (first, retry)
        });

        let activity =
            build_activity(&media("Arrival", "", "", "movie", ARRIVAL_POSTER), &snapshot(), at(2000)).unwrap();
        let mut transport = NamedPipeTransport::with_prefix(&prefix);
        assert!(transport.send(APPLICATION_ID, &serialize_set_activity(&activity, 42)));
        let (first, retry) = server.join().unwrap();
        assert!(first.contains(ARRIVAL_POSTER), "{first}");
        assert!(retry.contains("\"large_image\":\"halo\"") && !retry.contains(ARRIVAL_POSTER), "{retry}");
    }

    #[test]
    fn nothing_is_sent_to_a_discord_that_is_not_running() {
        #[cfg(windows)]
        {
            let mut transport = NamedPipeTransport::with_prefix(r"\\.\pipe\halo-test-no-discord-");
            let started = std::time::Instant::now();
            assert!(!transport.send(APPLICATION_ID, &serialize_clear_activity(42)));
            assert!(started.elapsed() < Duration::from_secs(3));
        }
    }
}
