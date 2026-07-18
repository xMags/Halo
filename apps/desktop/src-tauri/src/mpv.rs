//! Runtime-loaded libmpv binding — the minimal FFI surface behind the generic
//! player channel. Loaded with `libloading` on purpose: the mpv dev packages
//! ship no MSVC import lib, and a runtime-resolved dll keeps libmpv trivially
//! replaceable (LGPL dynamic-link requirement).
//!
//! Thread contract (documented by libmpv): commands and property access are
//! safe from any thread; `mpv_wait_event` must only ever run on one thread —
//! `run_event_loop` below owns it.

use std::ffi::{c_char, c_int, c_void, CStr, CString};

pub const MPV_FORMAT_STRING: c_int = 1;
pub const MPV_FORMAT_FLAG: c_int = 3;
pub const MPV_FORMAT_INT64: c_int = 4;
pub const MPV_FORMAT_DOUBLE: c_int = 5;

const MPV_EVENT_SHUTDOWN: c_int = 1;
const MPV_EVENT_LOG_MESSAGE: c_int = 2;
const MPV_EVENT_START_FILE: c_int = 6;
const MPV_EVENT_END_FILE: c_int = 7;
const MPV_EVENT_FILE_LOADED: c_int = 8;
const MPV_EVENT_SEEK: c_int = 20;
const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;
const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

type MpvHandle = *mut c_void;

#[repr(C)]
struct MpvEvent {
    event_id: c_int,
    error: c_int,
    reply_userdata: u64,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventProperty {
    name: *const c_char,
    format: c_int,
    data: *mut c_void,
}

#[repr(C)]
struct MpvEventLogMessage {
    prefix: *const c_char,
    level: *const c_char,
    text: *const c_char,
    log_level: c_int,
}

pub struct Mpv {
    _lib: libloading::Library,
    handle: MpvHandle,
    command: unsafe extern "C" fn(MpvHandle, *const *const c_char) -> c_int,
    set_property_string: unsafe extern "C" fn(MpvHandle, *const c_char, *const c_char) -> c_int,
    get_property_string: unsafe extern "C" fn(MpvHandle, *const c_char) -> *mut c_char,
    free: unsafe extern "C" fn(*mut c_void),
    observe_property: unsafe extern "C" fn(MpvHandle, u64, *const c_char, c_int) -> c_int,
    unobserve_property: unsafe extern "C" fn(MpvHandle, u64) -> c_int,
    wait_event: unsafe extern "C" fn(MpvHandle, f64) -> *mut MpvEvent,
    error_string: unsafe extern "C" fn(c_int) -> *const c_char,
}

// Safety: per libmpv's documented thread guarantees (see module docs).
unsafe impl Send for Mpv {}
unsafe impl Sync for Mpv {}

/// A lifecycle or property event surfaced to the UI layer.
pub enum Event {
    Lifecycle(&'static str),
    Prop { name: String, value: serde_json::Value },
    Log(String),
    Shutdown,
}

impl Mpv {
    /// Loads the dll, creates the core, applies embedding options, initializes.
    /// `wid` must be the TOP-LEVEL window handle — an intermediate child window
    /// renders but is invisible under the transparent webview (Windows spike,
    /// 2026-07-18).
    pub fn load(dll_path: &std::path::Path, wid: isize) -> Result<Self, String> {
        unsafe {
            let lib = libloading::Library::new(dll_path)
                .map_err(|e| format!("load {}: {e}", dll_path.display()))?;
            macro_rules! sym {
                ($name:literal) => {
                    *lib.get($name)
                        .map_err(|e| format!("{}: {e}", String::from_utf8_lossy($name)))?
                };
            }
            let create: unsafe extern "C" fn() -> MpvHandle = sym!(b"mpv_create");
            let initialize: unsafe extern "C" fn(MpvHandle) -> c_int = sym!(b"mpv_initialize");
            let set_option: unsafe extern "C" fn(MpvHandle, *const c_char, c_int, *mut c_void) -> c_int =
                sym!(b"mpv_set_option");
            let set_option_string: unsafe extern "C" fn(MpvHandle, *const c_char, *const c_char) -> c_int =
                sym!(b"mpv_set_option_string");
            let request_log: unsafe extern "C" fn(MpvHandle, *const c_char) -> c_int =
                sym!(b"mpv_request_log_messages");
            let command = sym!(b"mpv_command");
            let set_property_string = sym!(b"mpv_set_property_string");
            let get_property_string = sym!(b"mpv_get_property_string");
            let free = sym!(b"mpv_free");
            let observe_property = sym!(b"mpv_observe_property");
            let unobserve_property = sym!(b"mpv_unobserve_property");
            let wait_event = sym!(b"mpv_wait_event");
            let error_string = sym!(b"mpv_error_string");

            let handle = create();
            if handle.is_null() {
                return Err("mpv_create returned null".into());
            }

            let opt = |k: &str, v: &str| {
                let k = CString::new(k).unwrap();
                let v = CString::new(v).unwrap();
                set_option_string(handle, k.as_ptr(), v.as_ptr());
            };
            // wid must be set before mpv_initialize.
            let wid_key = CString::new("wid").unwrap();
            let mut wid_val: i64 = wid as i64;
            let rc = set_option(handle, wid_key.as_ptr(), MPV_FORMAT_INT64, &mut wid_val as *mut i64 as *mut c_void);
            if rc < 0 {
                return Err(format!("set wid failed rc={rc}"));
            }
            // The UI owns all input and OSD; mpv is a pure render/decode engine.
            opt("force-window", "yes");
            opt("keep-open", "yes");
            opt("input-default-bindings", "no");
            opt("input-vo-keyboard", "no");
            opt("osc", "no");
            opt("osd-level", "0");
            opt("hwdec", "auto-safe");
            opt("terminal", "no");
            let warn = CString::new("warn").unwrap();
            request_log(handle, warn.as_ptr());

            let rc = initialize(handle);
            if rc < 0 {
                return Err(format!("mpv_initialize failed rc={rc}"));
            }

            Ok(Mpv {
                _lib: lib,
                handle,
                command,
                set_property_string,
                get_property_string,
                free,
                observe_property,
                unobserve_property,
                wait_event,
                error_string,
            })
        }
    }

    fn err(&self, rc: c_int) -> String {
        unsafe { CStr::from_ptr((self.error_string)(rc)).to_string_lossy().into_owned() }
    }

    pub fn cmd(&self, args: &[String]) -> Result<(), String> {
        let cstrs: Vec<CString> = args
            .iter()
            .map(|a| CString::new(a.as_str()).map_err(|e| e.to_string()))
            .collect::<Result<_, _>>()?;
        let mut ptrs: Vec<*const c_char> = cstrs.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        let rc = unsafe { (self.command)(self.handle, ptrs.as_ptr()) };
        if rc < 0 {
            return Err(format!("{} (cmd={args:?})", self.err(rc)));
        }
        Ok(())
    }

    pub fn set_str(&self, name: &str, value: &str) -> Result<(), String> {
        let n = CString::new(name).map_err(|e| e.to_string())?;
        let v = CString::new(value).map_err(|e| e.to_string())?;
        let rc = unsafe { (self.set_property_string)(self.handle, n.as_ptr(), v.as_ptr()) };
        if rc < 0 {
            return Err(format!("{} (set {name})", self.err(rc)));
        }
        Ok(())
    }

    /// One-off read with mpv's string formatting; None when the property is unavailable.
    pub fn get_str(&self, name: &str) -> Result<Option<String>, String> {
        let n = CString::new(name).map_err(|e| e.to_string())?;
        let ptr = unsafe { (self.get_property_string)(self.handle, n.as_ptr()) };
        if ptr.is_null() {
            return Ok(None);
        }
        let value = unsafe { CStr::from_ptr(ptr).to_string_lossy().into_owned() };
        unsafe { (self.free)(ptr as *mut c_void) };
        Ok(Some(value))
    }

    pub fn observe(&self, name: &str, format: c_int) -> Result<(), String> {
        let n = CString::new(name).map_err(|e| e.to_string())?;
        let rc = unsafe { (self.observe_property)(self.handle, 0, n.as_ptr(), format) };
        if rc < 0 {
            return Err(format!("{} (observe {name})", self.err(rc)));
        }
        Ok(())
    }

    /// Drops every observer (all are registered under userdata 0) — the player
    /// screen re-registers its set on each mount, so unmount clears the slate
    /// instead of stacking duplicate observers.
    pub fn unobserve_all(&self) {
        unsafe { (self.unobserve_property)(self.handle, 0) };
    }

    /// Blocks on mpv's event queue and translates each event; call from the
    /// single dedicated event thread only.
    pub fn run_event_loop(&self, mut on_event: impl FnMut(Event)) {
        loop {
            let ev = unsafe { &*(self.wait_event)(self.handle, -1.0) };
            match ev.event_id {
                0 => continue, // MPV_EVENT_NONE (timeout)
                MPV_EVENT_SHUTDOWN => {
                    on_event(Event::Shutdown);
                    break;
                }
                MPV_EVENT_PROPERTY_CHANGE => {
                    let prop = unsafe { &*(ev.data as *const MpvEventProperty) };
                    let name = unsafe { CStr::from_ptr(prop.name).to_string_lossy().into_owned() };
                    let value: serde_json::Value = unsafe {
                        match prop.format {
                            MPV_FORMAT_DOUBLE => (*(prop.data as *const f64)).into(),
                            MPV_FORMAT_FLAG => (*(prop.data as *const c_int) != 0).into(),
                            MPV_FORMAT_INT64 => (*(prop.data as *const i64)).into(),
                            MPV_FORMAT_STRING => {
                                let s = *(prop.data as *const *const c_char);
                                if s.is_null() {
                                    serde_json::Value::Null
                                } else {
                                    CStr::from_ptr(s).to_string_lossy().into_owned().into()
                                }
                            }
                            _ => serde_json::Value::Null,
                        }
                    };
                    on_event(Event::Prop { name, value });
                }
                MPV_EVENT_LOG_MESSAGE => {
                    let log = unsafe { &*(ev.data as *const MpvEventLogMessage) };
                    let prefix = unsafe { CStr::from_ptr(log.prefix).to_string_lossy() };
                    let text = unsafe { CStr::from_ptr(log.text).to_string_lossy() };
                    on_event(Event::Log(format!("[{prefix}] {}", text.trim_end())));
                }
                MPV_EVENT_START_FILE => on_event(Event::Lifecycle("start-file")),
                MPV_EVENT_END_FILE => on_event(Event::Lifecycle("end-file")),
                MPV_EVENT_FILE_LOADED => on_event(Event::Lifecycle("file-loaded")),
                MPV_EVENT_SEEK => on_event(Event::Lifecycle("seek")),
                MPV_EVENT_PLAYBACK_RESTART => on_event(Event::Lifecycle("playback-restart")),
                _ => {}
            }
        }
    }
}
