//! Halo's own session in the Windows Volume Mixer. libmpv plays through a
//! shared-mode WASAPI session owned by this process; driving that session's
//! volume (rather than mpv's software gain) keeps the player's slider and the
//! mixer's Halo slider the same control, and Windows remembers the level
//! between launches. The endpoint volume other apps use is never touched.
//! (A port of the native Halo Desktop's `WindowsAudioSession`.)

use windows::core::Interface;
use windows::Win32::Media::Audio::{
    eMultimedia, eRender, IAudioSessionControl2, IAudioSessionManager2, IMMDeviceEnumerator,
    ISimpleAudioVolume, MMDeviceEnumerator,
};
use windows::Win32::System::Com::{
    CoCreateInstance, CoInitializeEx, CoUninitialize, CLSCTX_INPROC_SERVER, COINIT_MULTITHREADED,
};
use windows::Win32::System::Threading::GetCurrentProcessId;

#[derive(serde::Serialize, Clone, Copy, Debug, PartialEq)]
pub struct SessionVolume {
    /// 0.0 to 1.0.
    pub volume: f32,
    pub muted: bool,
}

/// COM for the current thread, balanced on drop. A thread already in another
/// apartment reports RPC_E_CHANGED_MODE; COM is still usable there, but that
/// call must not be balanced with an uninitialize.
struct ComScope {
    initialized: bool,
}

impl ComScope {
    fn enter() -> Self {
        let result = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
        ComScope { initialized: result.is_ok() }
    }
}

impl Drop for ComScope {
    fn drop(&mut self) {
        if self.initialized {
            unsafe { CoUninitialize() };
        }
    }
}

/// Runs `each` against every session on the default output device that this
/// process owns, returning whether any of them accepted it. There is usually
/// one; there are none until mpv has opened its audio output.
fn for_process_sessions(mut each: impl FnMut(&ISimpleAudioVolume) -> bool) -> bool {
    let _com = ComScope::enter();
    unsafe {
        let Ok(enumerator) =
            CoCreateInstance::<_, IMMDeviceEnumerator>(&MMDeviceEnumerator, None, CLSCTX_INPROC_SERVER)
        else {
            return false;
        };
        let Ok(device) = enumerator.GetDefaultAudioEndpoint(eRender, eMultimedia) else {
            return false;
        };
        let Ok(manager) = device.Activate::<IAudioSessionManager2>(CLSCTX_INPROC_SERVER, None) else {
            return false;
        };
        let Ok(sessions) = manager.GetSessionEnumerator() else {
            return false;
        };
        let Ok(count) = sessions.GetCount() else {
            return false;
        };
        let process = GetCurrentProcessId();
        let mut matched = false;
        for index in 0..count {
            let Ok(control) = sessions.GetSession(index) else {
                continue;
            };
            let Ok(owned) = control.cast::<IAudioSessionControl2>() else {
                continue;
            };
            if owned.GetProcessId().ok() != Some(process) {
                continue;
            }
            let Ok(volume) = control.cast::<ISimpleAudioVolume>() else {
                continue;
            };
            matched = each(&volume) || matched;
        }
        matched
    }
}

/// The session's current level, or None while this process has no session.
pub fn read() -> Option<SessionVolume> {
    let mut result = None;
    for_process_sessions(|session| unsafe {
        let (Ok(volume), Ok(muted)) = (session.GetMasterVolume(), session.GetMute()) else {
            return false;
        };
        result = Some(SessionVolume {
            volume: volume.clamp(0.0, 1.0),
            muted: muted.as_bool(),
        });
        true
    });
    result
}

/// Sets the session level; `unmute` also clears a mute set from the mixer.
/// False when there is no session to set, so the caller can fall back.
pub fn set_volume(volume: f32, unmute: bool) -> bool {
    let level = volume.clamp(0.0, 1.0);
    for_process_sessions(|session| unsafe {
        if session.SetMasterVolume(level, std::ptr::null()).is_err() {
            return false;
        }
        !unmute || session.SetMute(false, std::ptr::null()).is_ok()
    })
}
