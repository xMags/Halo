//! The main window's title-bar and taskbar icons.
//!
//! Tauri gives tao one image for the window, the first entry of
//! `icons/icon.ico`, which is the 16px one, and tao sets it as the small icon
//! only. With no large icon, the taskbar and Alt+Tab stretch that 16px image,
//! which is why they looked blurry. tauri-build embeds the whole .ico in the
//! exe, so loading from there at the sizes Windows draws picks the image made
//! for each size. The native Halo Desktop sets its icon the same way.

use std::ffi::c_void;

use windows::core::PCWSTR;
use windows::Win32::Foundation::{HANDLE, HWND, LPARAM, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::WindowsAndMessaging::{
    GetSystemMetrics, LoadImageW, SendMessageW, ICON_BIG, ICON_SMALL, IMAGE_FLAGS, IMAGE_ICON,
    LR_DEFAULTCOLOR, LR_DEFAULTSIZE, SM_CXSMICON, SM_CYSMICON, WM_SETICON,
};

/// tauri-build embeds `icons/icon.ico` under IDI_APPLICATION's number.
const APP_ICON_RESOURCE_ID: u16 = 32512;

/// Sets both icons on `hwnd`. A missing icon is cosmetic, so a failed load
/// leaves Tauri's own icon in place rather than failing startup.
pub fn apply(hwnd: isize) {
    let hwnd = HWND(hwnd as *mut c_void);
    // Large (taskbar, Alt+Tab): the system icon size, as the native app loads it.
    if let Some(icon) = load(0, 0, LR_DEFAULTSIZE) {
        set(hwnd, ICON_BIG, icon);
    }
    // Small (title bar, window menu): the small-icon size for this DPI.
    let (cx, cy) = unsafe { (GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON)) };
    if let Some(icon) = load(cx, cy, LR_DEFAULTCOLOR) {
        set(hwnd, ICON_SMALL, icon);
    }
}

/// Deliberately not LR_SHARED: a shared load hands back the first cached image
/// for the resource whatever size is asked for, so the second size would get
/// the first one's picture. The handles are never destroyed, because the
/// window uses them until the process exits.
fn load(cx: i32, cy: i32, flags: IMAGE_FLAGS) -> Option<HANDLE> {
    let module = unsafe { GetModuleHandleW(PCWSTR::null()) }.ok()?;
    // MAKEINTRESOURCEW: a resource number travels in the name pointer.
    let name = PCWSTR(usize::from(APP_ICON_RESOURCE_ID) as *const u16);
    unsafe { LoadImageW(Some(module.into()), name, IMAGE_ICON, cx, cy, flags) }.ok()
}

/// The icon this replaces belongs to tao, which frees it with the window, so
/// the previous handle WM_SETICON returns is left alone.
fn set(hwnd: HWND, which: u32, icon: HANDLE) {
    unsafe {
        SendMessageW(
            hwnd,
            WM_SETICON,
            Some(WPARAM(which as usize)),
            Some(LPARAM(icon.0 as isize)),
        )
    };
}
