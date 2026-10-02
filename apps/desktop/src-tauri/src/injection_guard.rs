//! Keeps other programs' hook DLLs out of this process.
//!
//! On-screen overlays such as RivaTuner Statistics Server (MSI Afterburner's
//! FPS counter) load a DLL into every program that draws with the GPU through
//! a global window hook, then patch its Direct3D calls. mpv draws every video
//! frame in this process, so such a hook runs on each frame, and a fault in it
//! takes the whole app down in the middle of playback. Windows lets a process
//! refuse these legacy extension points (global window-hook DLLs, AppInit
//! DLLs and Winsock LSPs); Chromium refuses them in its sandboxed processes
//! the same way.
//!
//! The policy only stops DLLs that have not loaded yet, and a hook DLL loads
//! the first time this process handles window messages, so it must be set
//! before Tauri creates a window. It cannot reach WebView2's processes: the
//! Edge runtime starts those, not this process.
//!
//! What it costs: legacy (pre-TSF) input methods and tools that work by
//! injecting a hook DLL cannot load into this process. Text is typed into the
//! webview, which runs in WebView2's own processes, and current Windows input
//! methods use TSF, so typing is unaffected.

use std::ffi::c_void;
use std::mem::size_of;

use windows::Win32::System::SystemServices::{
    PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY, PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY_0,
};
use windows::Win32::System::Threading::{ProcessExtensionPointDisablePolicy, SetProcessMitigationPolicy};

/// The policy's DisableExtensionPoints bit.
const DISABLE_EXTENSION_POINTS: u32 = 1;

/// Refuses extension-point DLLs for the rest of this process's life; Windows
/// does not let a process turn the policy off again. A failure leaves the
/// process as every earlier build ran, so it is reported and startup goes on.
pub fn refuse_extension_points() {
    let policy = PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY {
        Anonymous: PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY_0 { Flags: DISABLE_EXTENSION_POINTS },
    };
    let result = unsafe {
        SetProcessMitigationPolicy(
            ProcessExtensionPointDisablePolicy,
            std::ptr::from_ref(&policy).cast::<c_void>(),
            size_of::<PROCESS_MITIGATION_EXTENSION_POINT_DISABLE_POLICY>(),
        )
    };
    if let Err(error) = result {
        eprintln!("could not refuse extension-point DLLs: {error}");
    }
}
