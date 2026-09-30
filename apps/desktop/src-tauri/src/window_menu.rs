//! The window menu (Restore, Move, Size, Minimize, Maximize, Close) on a
//! right-click in the app-drawn title bar, as every Windows title bar offers it.
//!
//! The window is undecorated, so Windows never opens this menu itself. Opening
//! it with TrackPopupMenu skips the pass Windows makes over its own caption's
//! menu to grey out what does not apply, so the item states are set here
//! first, as Windows Terminal does for its custom title bar. The chosen command
//! goes back to the window as WM_SYSCOMMAND, so it is handled exactly as it
//! would be from the caption.

use std::ffi::c_void;

use windows::Win32::Foundation::{HWND, LPARAM, POINT, WPARAM};
use windows::Win32::UI::WindowsAndMessaging::{
    EnableMenuItem, GetCursorPos, GetSystemMenu, IsZoomed, PostMessageW, SetMenuDefaultItem,
    TrackPopupMenu, HMENU, MF_BYCOMMAND, MF_ENABLED, MF_GRAYED, SC_CLOSE, SC_MAXIMIZE, SC_MINIMIZE,
    SC_MOVE, SC_RESTORE, SC_SIZE, TPM_RETURNCMD, TPM_RIGHTBUTTON, WM_SYSCOMMAND,
};

/// Which items apply in the window's current state. A maximized window can
/// only be restored; a restored one can be moved, sized and maximized.
fn item_states(maximized: bool) -> [(u32, bool); 6] {
    [
        (SC_RESTORE, maximized),
        (SC_MOVE, !maximized),
        (SC_SIZE, !maximized),
        (SC_MINIMIZE, true),
        (SC_MAXIMIZE, !maximized),
        (SC_CLOSE, true),
    ]
}

/// Opens the menu at the pointer and carries out whatever is chosen. Runs on
/// the thread that owns the window, which TrackPopupMenu requires. A failure
/// only means no menu appears, so it is not reported.
pub fn show_at_cursor(hwnd: isize) {
    let hwnd = HWND(hwnd as *mut c_void);
    let menu = unsafe { GetSystemMenu(hwnd, false) };
    if menu.is_invalid() {
        return;
    }
    let maximized = unsafe { IsZoomed(hwnd) }.as_bool();
    for (command, enabled) in item_states(maximized) {
        set_enabled(menu, command, enabled);
    }
    // Close in bold, as the caption's own menu draws it.
    let _ = unsafe { SetMenuDefaultItem(menu, SC_CLOSE, 0) };

    let mut cursor = POINT::default();
    if unsafe { GetCursorPos(&mut cursor) }.is_err() {
        return;
    }
    // With TPM_RETURNCMD the return value is the chosen command, 0 for none.
    let command = unsafe {
        TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON, cursor.x, cursor.y, None, hwnd, None)
    };
    if command.0 == 0 {
        return;
    }
    let _ = unsafe {
        PostMessageW(Some(hwnd), WM_SYSCOMMAND, WPARAM(command.0 as usize), LPARAM(0))
    };
}

/// A command the menu does not carry is simply skipped by EnableMenuItem.
fn set_enabled(menu: HMENU, command: u32, enabled: bool) {
    let state = if enabled { MF_ENABLED } else { MF_GRAYED };
    let _ = unsafe { EnableMenuItem(menu, command, MF_BYCOMMAND | state) };
}

#[cfg(test)]
mod tests {
    use super::*;

    fn enabled(maximized: bool, command: u32) -> bool {
        item_states(maximized)
            .into_iter()
            .find(|(item, _)| *item == command)
            .map(|(_, enabled)| enabled)
            .expect("every caption command has a state")
    }

    #[test]
    fn a_maximized_window_can_only_be_restored_minimized_or_closed() {
        assert!(enabled(true, SC_RESTORE));
        assert!(!enabled(true, SC_MOVE));
        assert!(!enabled(true, SC_SIZE));
        assert!(!enabled(true, SC_MAXIMIZE));
        assert!(enabled(true, SC_MINIMIZE));
        assert!(enabled(true, SC_CLOSE));
    }

    #[test]
    fn a_restored_window_offers_everything_but_restore() {
        assert!(!enabled(false, SC_RESTORE));
        assert!(enabled(false, SC_MOVE));
        assert!(enabled(false, SC_SIZE));
        assert!(enabled(false, SC_MAXIMIZE));
        assert!(enabled(false, SC_MINIMIZE));
        assert!(enabled(false, SC_CLOSE));
    }
}
