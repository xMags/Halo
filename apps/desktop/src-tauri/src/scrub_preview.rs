//! Seek-bar thumbnails: single frames decoded at arbitrary times by a second,
//! hidden libmpv instance, so the playing one is never disturbed. A port of the
//! native Halo Desktop's `MpvScrubPreviewSource`.
//!
//! Lifecycle: `open` only records the source. The worker thread, its mpv
//! instance and the second connection to the origin are created on the first
//! `request`, so a session where nobody scrubs costs nothing. `open` for a new
//! file and `close` both retire the running worker; it notices within one poll
//! interval and tears its instance down on its own thread.
//!
//! Requests: the latest one wins. A request arriving while a frame decodes
//! replaces the queued one, and a finished decode for a request that is no
//! longer the latest is dropped rather than delivered.

use std::ffi::{c_char, c_int, c_void, CStr, CString};
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

use tokio::sync::oneshot;

const MPV_FORMAT_STRING: c_int = 1;
const MPV_FORMAT_INT64: c_int = 4;
const MPV_FORMAT_NODE_MAP: c_int = 8;
const MPV_FORMAT_BYTE_ARRAY: c_int = 9;

const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_EVENT_FILE_LOADED: c_int = 8;
const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;

/// How often a waiting worker re-checks whether it has been retired.
const POLL: Duration = Duration::from_millis(100);
/// A remote open or keyframe seek that takes longer than this is abandoned.
const OPEN_TIMEOUT: Duration = Duration::from_secs(20);
const SEEK_TIMEOUT: Duration = Duration::from_secs(8);
/// Frames are scaled to this width inside mpv; the guard below averages down
/// anything wider if the scale filter was unavailable.
const PREVIEW_WIDTH: u32 = 320;
const MAX_SOURCE_DIMENSION: i64 = 8192;

/// The preview instance's configuration: no output, no audio or subtitles,
/// software decoding (one downscaled keyframe is cheaper than a second GPU
/// context and readback), keep-open so previewing the last seconds does not
/// unload the file, and a small cache so it never competes with playback.
const OPTIONS: &[(&str, &str)] = &[
    ("vo", "null"),
    ("ao", "null"),
    ("audio", "no"),
    ("sid", "no"),
    ("sub-auto", "no"),
    ("idle", "yes"),
    ("terminal", "no"),
    ("force-window", "no"),
    ("pause", "yes"),
    ("ytdl", "no"),
    ("hwdec", "no"),
    ("keep-open", "yes"),
    ("demuxer-max-bytes", "8MiB"),
    ("demuxer-readahead-secs", "1"),
    ("cache-on-disk", "no"),
];
/// Tried in order: mpv's own scale alias, then the explicit libavfilter form.
const SCALE_FILTERS: &[&str] = &["scale=320:-2", "lavfi=[scale=320:-2]"];

/// One decoded preview, RGBA and opaque.
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub rgba: Vec<u8>,
}

struct Pending {
    id: u64,
    seconds: f64,
    reply: oneshot::Sender<Option<Frame>>,
}

#[derive(Default)]
struct State {
    source: Option<String>,
    /// Bumped by every open and close; a worker from an older generation retires.
    generation: u64,
    /// The generation whose worker is running, if one is.
    worker: Option<u64>,
    /// A generation whose source could not be opened. Its requests fail at
    /// once instead of reconnecting on every pointer move.
    failed: Option<u64>,
    latest_id: u64,
    pending: Option<Pending>,
}

pub struct ScrubPreview {
    shared: Arc<Shared>,
}

struct Shared {
    dll: PathBuf,
    state: Mutex<State>,
    wake: Condvar,
}

impl ScrubPreview {
    pub fn new(dll: PathBuf) -> Self {
        ScrubPreview {
            shared: Arc::new(Shared {
                dll,
                state: Mutex::new(State::default()),
                wake: Condvar::new(),
            }),
        }
    }

    /// Records the source for later requests. No network work happens here.
    pub fn open(&self, source: String) {
        let mut state = self.shared.lock();
        state.generation += 1;
        state.source = Some(source);
        fail_pending(&mut state);
        self.shared.wake.notify_all();
    }

    pub fn close(&self) {
        let mut state = self.shared.lock();
        state.generation += 1;
        state.source = None;
        fail_pending(&mut state);
        self.shared.wake.notify_all();
    }

    /// Queues a decode at `seconds`, replacing any queued one, and returns
    /// where its frame (or None, when superseded or failed) will arrive.
    pub fn request(&self, seconds: f64) -> oneshot::Receiver<Option<Frame>> {
        let (reply, receiver) = oneshot::channel();
        let mut state = self.shared.lock();
        let Some(source) = state.source.clone() else {
            let _ = reply.send(None);
            return receiver;
        };
        if !seconds.is_finite() || seconds < 0.0 {
            let _ = reply.send(None);
            return receiver;
        }
        if state.failed == Some(state.generation) {
            let _ = reply.send(None);
            return receiver;
        }
        fail_pending(&mut state);
        state.latest_id += 1;
        state.pending = Some(Pending {
            id: state.latest_id,
            seconds,
            reply,
        });
        let generation = state.generation;
        if state.worker != Some(generation) {
            state.worker = Some(generation);
            let shared = self.shared.clone();
            std::thread::spawn(move || run_worker(shared, generation, source));
        }
        self.shared.wake.notify_all();
        receiver
    }
}

impl Shared {
    fn lock(&self) -> std::sync::MutexGuard<'_, State> {
        // A panicking worker must not take previews down for the whole session.
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn retired(&self, generation: u64) -> bool {
        self.lock().generation != generation
    }

    /// Waits for the next request of this generation; None once retired.
    fn next_request(&self, generation: u64) -> Option<Pending> {
        let mut state = self.lock();
        loop {
            if state.generation != generation {
                return None;
            }
            if let Some(pending) = state.pending.take() {
                return Some(pending);
            }
            state = self
                .wake
                .wait_timeout(state, POLL)
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .0;
        }
    }

    fn is_latest(&self, generation: u64, id: u64) -> bool {
        let state = self.lock();
        state.generation == generation && state.latest_id == id
    }

    fn worker_exited(&self, generation: u64, failed: bool) {
        let mut state = self.lock();
        if failed {
            state.failed = Some(generation);
        }
        if state.worker == Some(generation) {
            state.worker = None;
            // A request that raced the exit would otherwise wait forever.
            if state.generation == generation {
                fail_pending(&mut state);
            }
        }
    }
}

fn fail_pending(state: &mut State) {
    if let Some(pending) = state.pending.take() {
        let _ = pending.reply.send(None);
    }
}

fn run_worker(shared: Arc<Shared>, generation: u64, source: String) {
    let mut opened = false;
    if let Ok(instance) = Instance::create(&shared.dll) {
        opened = instance.load(&source, &shared, generation);
        if opened {
            while let Some(request) = shared.next_request(generation) {
                let frame = instance.decode(request.seconds, &shared, generation, request.id);
                let current = shared.is_latest(generation, request.id);
                let _ = request.reply.send(if current { frame } else { None });
            }
        }
        // Dropping the instance quits and destroys it here, off every other thread.
    }
    // A retired worker's failed open says nothing about the next source.
    let failed = !opened && !shared.retired(generation);
    shared.worker_exited(generation, failed);
}

// The FFI structs mirror libmpv's layout, so some fields exist only to keep
// the offsets right and are never read.
#[allow(dead_code)]
#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[allow(dead_code)]
#[repr(C)]
#[derive(Clone, Copy)]
union MpvNodeValue {
    string: *mut c_char,
    flag: c_int,
    int64: i64,
    double: f64,
    list: *mut MpvNodeList,
    bytes: *mut MpvByteArray,
}

#[repr(C)]
struct MpvNode {
    value: MpvNodeValue,
    format: c_int,
}

#[repr(C)]
struct MpvNodeList {
    num: c_int,
    values: *mut MpvNode,
    keys: *mut *mut c_char,
}

#[repr(C)]
struct MpvByteArray {
    data: *mut c_void,
    size: usize,
}

type Handle = *mut c_void;

/// One preview mpv instance. Used only from the worker thread that created it.
struct Instance {
    _lib: libloading::Library,
    handle: Handle,
    command: unsafe extern "C" fn(Handle, *const *const c_char) -> c_int,
    command_ret: unsafe extern "C" fn(Handle, *const *const c_char, *mut MpvNode) -> c_int,
    free_node_contents: unsafe extern "C" fn(*mut MpvNode),
    wait_event: unsafe extern "C" fn(Handle, f64) -> *mut MpvEvent,
    wakeup: unsafe extern "C" fn(Handle),
    terminate_destroy: unsafe extern "C" fn(Handle),
}

impl Instance {
    fn create(dll: &std::path::Path) -> Result<Self, String> {
        unsafe {
            let lib = libloading::Library::new(dll).map_err(|error| error.to_string())?;
            macro_rules! sym {
                ($name:literal) => {
                    *lib.get($name).map_err(|error| error.to_string())?
                };
            }
            let create: unsafe extern "C" fn() -> Handle = sym!(b"mpv_create");
            let initialize: unsafe extern "C" fn(Handle) -> c_int = sym!(b"mpv_initialize");
            let set_option_string: unsafe extern "C" fn(Handle, *const c_char, *const c_char) -> c_int =
                sym!(b"mpv_set_option_string");
            let command = sym!(b"mpv_command");
            let command_ret = sym!(b"mpv_command_ret");
            let free_node_contents = sym!(b"mpv_free_node_contents");
            let wait_event = sym!(b"mpv_wait_event");
            let wakeup = sym!(b"mpv_wakeup");
            let terminate_destroy: unsafe extern "C" fn(Handle) = sym!(b"mpv_terminate_destroy");

            let handle = create();
            if handle.is_null() {
                return Err("mpv_create returned null".into());
            }
            let set = |name: &str, value: &str| -> c_int {
                let (Ok(name), Ok(value)) = (CString::new(name), CString::new(value)) else {
                    return -1;
                };
                set_option_string(handle, name.as_ptr(), value.as_ptr())
            };
            for (name, value) in OPTIONS {
                if set(name, value) < 0 {
                    terminate_destroy(handle);
                    return Err(format!("preview option {name} rejected"));
                }
            }
            // Not fatal: without it every grab is full size and averaged down below.
            for filter in SCALE_FILTERS {
                if set("vf", filter) >= 0 {
                    break;
                }
            }
            if initialize(handle) < 0 {
                terminate_destroy(handle);
                return Err("mpv_initialize failed".into());
            }
            Ok(Instance {
                _lib: lib,
                handle,
                command,
                command_ret,
                free_node_contents,
                wait_event,
                wakeup,
                terminate_destroy,
            })
        }
    }

    fn run(&self, args: &[&str]) -> bool {
        let Ok(owned) = args.iter().map(|arg| CString::new(*arg)).collect::<Result<Vec<_>, _>>() else {
            return false;
        };
        let mut pointers: Vec<*const c_char> = owned.iter().map(|arg| arg.as_ptr()).collect();
        pointers.push(std::ptr::null());
        unsafe { (self.command)(self.handle, pointers.as_ptr()) >= 0 }
    }

    /// Waits for `wanted`, giving up on an end-of-file, a shutdown, a timeout,
    /// or retirement. Returns whether `wanted` arrived.
    fn await_event(&self, wanted: c_int, timeout: Duration, shared: &Shared, generation: u64) -> bool {
        let started = Instant::now();
        loop {
            if shared.retired(generation) || started.elapsed() > timeout {
                return false;
            }
            let event = unsafe { &*(self.wait_event)(self.handle, POLL.as_secs_f64()) };
            match event.event_id {
                id if id == wanted => return true,
                MPV_EVENT_END_FILE | MPV_EVENT_SHUTDOWN => return false,
                _ => {}
            }
        }
    }

    fn load(&self, source: &str, shared: &Shared, generation: u64) -> bool {
        self.run(&["loadfile", source]) && self.await_event(MPV_EVENT_FILE_LOADED, OPEN_TIMEOUT, shared, generation)
    }

    fn decode(&self, seconds: f64, shared: &Shared, generation: u64, id: u64) -> Option<Frame> {
        // Anything still queued belongs to an earlier request; a leftover restart
        // would end this wait before this request's own frame exists.
        loop {
            let event = unsafe { &*(self.wait_event)(self.handle, 0.0) };
            if event.event_id == 0 {
                break;
            }
            if event.event_id == MPV_EVENT_SHUTDOWN {
                return None;
            }
        }
        // Keyframe seeks: a preview needs no frame accuracy, and an exact seek
        // decodes a whole group of pictures for one thumbnail.
        let target = format!("{seconds:.3}");
        if !self.run(&["seek", &target, "absolute+keyframes"]) {
            return None;
        }
        if !self.await_event(MPV_EVENT_PLAYBACK_RESTART, SEEK_TIMEOUT, shared, generation)
            || !shared.is_latest(generation, id)
        {
            return None;
        }
        self.grab()
    }

    fn grab(&self) -> Option<Frame> {
        let args: [*const c_char; 3] = [c"screenshot-raw".as_ptr(), c"video".as_ptr(), std::ptr::null()];
        let mut result = MpvNode {
            value: MpvNodeValue { int64: 0 },
            format: 0,
        };
        if unsafe { (self.command_ret)(self.handle, args.as_ptr(), &mut result) } < 0 {
            return None;
        }
        let frame = unsafe { frame_from_node(&result) };
        unsafe { (self.free_node_contents)(&mut result) };
        frame
    }
}

impl Drop for Instance {
    fn drop(&mut self) {
        self.run(&["quit"]);
        // quit is asynchronous; an open network demuxer could otherwise hold
        // the teardown below for a long time.
        unsafe {
            (self.wakeup)(self.handle);
            (self.terminate_destroy)(self.handle);
        }
    }
}

/// Reads `screenshot-raw`'s map (w, h, stride, format, data) into an RGBA frame.
unsafe fn frame_from_node(node: &MpvNode) -> Option<Frame> {
    if node.format != MPV_FORMAT_NODE_MAP || node.value.list.is_null() {
        return None;
    }
    let list = &*node.value.list;
    let mut width = 0i64;
    let mut height = 0i64;
    let mut stride = 0i64;
    let mut format_ok = false;
    let mut data: Option<&[u8]> = None;
    for index in 0..usize::try_from(list.num).ok()? {
        let key = CStr::from_ptr(*list.keys.add(index)).to_bytes();
        let value = &*list.values.add(index);
        match (key, value.format) {
            (b"w", MPV_FORMAT_INT64) => width = value.value.int64,
            (b"h", MPV_FORMAT_INT64) => height = value.value.int64,
            (b"stride", MPV_FORMAT_INT64) => stride = value.value.int64,
            (b"format", MPV_FORMAT_STRING) if !value.value.string.is_null() => {
                let format = CStr::from_ptr(value.value.string).to_bytes();
                format_ok = format == b"bgr0" || format == b"bgra";
            }
            (b"data", MPV_FORMAT_BYTE_ARRAY) if !value.value.bytes.is_null() => {
                let bytes = &*value.value.bytes;
                if !bytes.data.is_null() {
                    data = Some(std::slice::from_raw_parts(bytes.data as *const u8, bytes.size));
                }
            }
            _ => {}
        }
    }
    if !format_ok
        || !(1..=MAX_SOURCE_DIMENSION).contains(&width)
        || !(1..=MAX_SOURCE_DIMENSION).contains(&height)
        || stride < width * 4
    {
        return None;
    }
    let data = data?;
    if (data.len() as i64) < stride * height {
        return None;
    }
    Some(scale_to_rgba(data, width as usize, height as usize, stride as usize))
}

/// BGR(A) to opaque RGBA, averaging blocks down to about PREVIEW_WIDTH when
/// the frame arrived wider (no scale filter). bgr0's fourth byte is undefined,
/// so alpha is asserted rather than copied.
fn scale_to_rgba(source: &[u8], width: usize, height: usize, stride: usize) -> Frame {
    let factor = (width / PREVIEW_WIDTH as usize).max(1);
    let out_width = width / factor;
    let out_height = height / factor;
    let samples = (factor * factor) as u32;
    let mut rgba = vec![0u8; out_width * out_height * 4];
    for y in 0..out_height {
        for x in 0..out_width {
            let (mut blue, mut green, mut red) = (0u32, 0u32, 0u32);
            for dy in 0..factor {
                let row = (y * factor + dy) * stride;
                for dx in 0..factor {
                    let pixel = row + (x * factor + dx) * 4;
                    blue += u32::from(source[pixel]);
                    green += u32::from(source[pixel + 1]);
                    red += u32::from(source[pixel + 2]);
                }
            }
            let out = (y * out_width + x) * 4;
            rgba[out] = (red / samples) as u8;
            rgba[out + 1] = (green / samples) as u8;
            rgba[out + 2] = (blue / samples) as u8;
            rgba[out + 3] = 255;
        }
    }
    Frame {
        width: out_width as u32,
        height: out_height as u32,
        rgba,
    }
}

/// The frame as the webview receives it: width and height as little-endian
/// u32s, then the RGBA bytes. Empty means no frame.
pub fn encode(frame: Option<Frame>) -> Vec<u8> {
    let Some(frame) = frame else {
        return Vec::new();
    };
    let mut bytes = Vec::with_capacity(8 + frame.rgba.len());
    bytes.extend_from_slice(&frame.width.to_le_bytes());
    bytes.extend_from_slice(&frame.height.to_le_bytes());
    bytes.extend_from_slice(&frame.rgba);
    bytes
}

#[cfg(test)]
mod tests {
    use super::{encode, scale_to_rgba, Frame};

    #[test]
    fn swaps_bgr_to_rgba_and_asserts_opacity() {
        // One 2x1 bgr0 row with a padded stride.
        let source = [10, 20, 30, 0, 40, 50, 60, 0, 99, 99];
        let frame = scale_to_rgba(&source, 2, 1, 10);
        assert_eq!((frame.width, frame.height), (2, 1));
        assert_eq!(frame.rgba, vec![30, 20, 10, 255, 60, 50, 40, 255]);
    }

    #[test]
    fn averages_wide_frames_down_to_the_preview_width() {
        let width = 640;
        let source = vec![100u8; width * 2 * 4];
        let frame = scale_to_rgba(&source, width, 2, width * 4);
        assert_eq!((frame.width, frame.height), (320, 1));
        assert!(frame.rgba.chunks(4).all(|pixel| pixel == [100, 100, 100, 255]));
    }

    #[test]
    fn encodes_dimensions_ahead_of_pixels_and_nothing_for_no_frame() {
        let bytes = encode(Some(Frame { width: 2, height: 1, rgba: vec![1; 8] }));
        assert_eq!(&bytes[..8], &[2, 0, 0, 0, 1, 0, 0, 0]);
        assert_eq!(bytes.len(), 16);
        assert!(encode(None).is_empty());
    }
}
