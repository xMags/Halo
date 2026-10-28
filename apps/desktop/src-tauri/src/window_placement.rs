//! Moving between maximized and fullscreen without a visible restore.
//!
//! tao's fullscreen keeps a maximized window's maximized flag, and Windows
//! holds a maximized window to the monitor's work area, so fullscreen entered
//! from maximized stops short of the taskbar. The window has to leave
//! maximized first, but an ordinary restore shows the window at its normal
//! size for a frame on the way in, and the re-maximize does the same on the
//! way out.
//!
//! So the restore happens in place: the window's normal rectangle is set to
//! the work area it already fills before the maximized flag is cleared, and
//! nothing on screen changes. Leaving fullscreen lands back on that same
//! rectangle, and the window is marked maximized again in place, with the
//! normal rectangle it had before put back for its next real restore.

use std::sync::Mutex;

use windows::Win32::Foundation::{HWND, RECT};
use windows::Win32::Graphics::Gdi::{GetMonitorInfoW, MonitorFromWindow, MONITORINFO, MONITOR_DEFAULTTONEAREST};
use windows::Win32::UI::WindowsAndMessaging::{
    GetWindowPlacement, IsZoomed, SetWindowPlacement, SW_SHOWMAXIMIZED, SW_SHOWNORMAL, WINDOWPLACEMENT,
};

/// The window's own normal rectangle, held while fullscreen borrowed it.
#[derive(Default)]
pub struct FullscreenPlacement {
    saved_normal: Mutex<Option<RECT>>,
}

impl FullscreenPlacement {
    /// Clears the maximized flag without moving the window. Returns whether it
    /// was maximized (and so should be put back by [`Self::remaximize`]).
    pub fn unmaximize_in_place(&self, hwnd: isize) -> Result<bool, String> {
        let hwnd = HWND(hwnd as *mut std::ffi::c_void);
        if !unsafe { IsZoomed(hwnd) }.as_bool() {
            return Ok(false);
        }
        let mut placement = read_placement(hwnd)?;
        let work = work_area_size(hwnd)?;
        let saved = placement.rcNormalPosition;
        // Workspace coordinates start at the work area's corner, so the area
        // the maximized window fills is (0, 0)-(width, height).
        placement.rcNormalPosition = RECT { left: 0, top: 0, right: work.0, bottom: work.1 };
        placement.showCmd = SW_SHOWNORMAL.0 as u32;
        unsafe { SetWindowPlacement(hwnd, &placement) }.map_err(|error| error.to_string())?;
        *self.saved_normal.lock().map_err(|_| "placement lock poisoned".to_string())? = Some(saved);
        Ok(true)
    }

    /// Marks the window maximized again where it stands, with its own normal
    /// rectangle back. A no-op when nothing was saved.
    pub fn remaximize(&self, hwnd: isize) -> Result<(), String> {
        let saved = self.saved_normal.lock().map_err(|_| "placement lock poisoned".to_string())?.take();
        let Some(saved) = saved else { return Ok(()) };
        let hwnd = HWND(hwnd as *mut std::ffi::c_void);
        let mut placement = read_placement(hwnd)?;
        placement.rcNormalPosition = saved;
        placement.showCmd = SW_SHOWMAXIMIZED.0 as u32;
        unsafe { SetWindowPlacement(hwnd, &placement) }.map_err(|error| error.to_string())
    }
}

fn read_placement(hwnd: HWND) -> Result<WINDOWPLACEMENT, String> {
    let mut placement = WINDOWPLACEMENT {
        length: std::mem::size_of::<WINDOWPLACEMENT>() as u32,
        ..Default::default()
    };
    unsafe { GetWindowPlacement(hwnd, &mut placement) }.map_err(|error| error.to_string())?;
    Ok(placement)
}

/// The size of the work area of the monitor the window is on.
fn work_area_size(hwnd: HWND) -> Result<(i32, i32), String> {
    let monitor = unsafe { MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST) };
    let mut info = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
    if !unsafe { GetMonitorInfoW(monitor, &mut info) }.as_bool() {
        return Err("monitor information is unavailable".to_string());
    }
    let work = info.rcWork;
    Ok((work.right - work.left, work.bottom - work.top))
}
