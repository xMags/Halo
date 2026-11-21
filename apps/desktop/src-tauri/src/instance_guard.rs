//! One Halo per Windows session, beyond what tauri-plugin-single-instance
//! guarantees.
//!
//! Two processes would share the download index, request vault and part
//! files, each overwriting the other's state. The plugin hands a second launch
//! over to the running window, but it lets the second process carry on in two
//! cases: when the first Halo runs elevated (its mutex then answers "access
//! denied", not "already exists"), and when the mutex exists but the first
//! Halo's hidden message window does not yet (it is still starting). This
//! claim is the backstop: a process that does not own it stops before it sets
//! anything up.

use windows::core::PCWSTR;
use windows::Win32::Foundation::{
    CloseHandle, GetLastError, ERROR_ACCESS_DENIED, ERROR_ALREADY_EXISTS, WAIT_ABANDONED, WAIT_OBJECT_0,
};
use windows::Win32::System::Threading::{CreateMutexW, WaitForSingleObject};

/// Named after the app identifier in tauri.conf.json, so a debug and a
/// release build (which share their data folder) also exclude each other.
const MUTEX_NAME: &str = "Local\\moe.ditto.halo.desktop-instance";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Claim {
    /// This process is the one Halo; the mutex stays held until it exits.
    Owned,
    /// Another Halo holds it, at this privilege level or a higher one.
    HeldElsewhere,
    /// Windows refused for another reason. Startup goes on, as every build
    /// before this guard did.
    Unknown,
}

/// How long a recovery restart waits for the Halo it replaces to end. That
/// process spawns this one and then exits, so it holds the claim for a moment.
const RESTART_WAIT_MS: u32 = 10_000;

/// Claims the one-Halo mutex. A recovery restart (`after_restart`) waits for
/// the process it replaces to let go; when that process ends Windows hands
/// the claim over, abandoned. Any other launch that finds it held gives up at
/// once, as the single-instance plugin has already handed it over.
pub fn claim(after_restart: bool) -> Claim {
    claim_named(MUTEX_NAME, if after_restart { RESTART_WAIT_MS } else { 0 })
}

fn claim_named(mutex_name: &str, wait_ms: u32) -> Claim {
    let name: Vec<u16> = mutex_name.encode_utf16().chain(std::iter::once(0)).collect();
    match unsafe { CreateMutexW(None, true, PCWSTR(name.as_ptr())) } {
        Ok(handle) => {
            if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
                if wait_ms > 0 {
                    let waited = unsafe { WaitForSingleObject(handle, wait_ms) };
                    if waited == WAIT_OBJECT_0 || waited == WAIT_ABANDONED {
                        // Ours now, held for the life of the process.
                        return Claim::Owned;
                    }
                }
                let _ = unsafe { CloseHandle(handle) };
                Claim::HeldElsewhere
            } else {
                // Held for the life of the process on purpose: Windows
                // releases it when the process ends, however it ends.
                Claim::Owned
            }
        }
        Err(error) if error.code() == ERROR_ACCESS_DENIED.to_hresult() => Claim::HeldElsewhere,
        Err(_) => Claim::Unknown,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_first_claim_owns_it_and_a_second_finds_it_held() {
        let name = format!(r"Local\halo-instance-guard-test-{}", std::process::id());
        assert_eq!(claim_named(&name, 0), Claim::Owned);
        assert_eq!(claim_named(&name, 0), Claim::HeldElsewhere);
    }

    /// A restart's predecessor: it holds the claim, then ends (here a thread
    /// that ends, which abandons the mutex the same way a process does).
    #[test]
    fn a_restart_takes_the_claim_once_its_predecessor_ends() {
        let name = format!(r"Local\halo-instance-guard-restart-{}", std::process::id());
        let (claimed, wait_for_claim) = std::sync::mpsc::channel();
        let holder_name = name.clone();
        let holder = std::thread::spawn(move || {
            assert_eq!(claim_named(&holder_name, 0), Claim::Owned);
            claimed.send(()).unwrap();
            std::thread::sleep(std::time::Duration::from_millis(300));
        });
        wait_for_claim.recv().unwrap();
        assert_eq!(claim_named(&name, 0), Claim::HeldElsewhere);
        assert_eq!(claim_named(&name, 5_000), Claim::Owned);
        holder.join().unwrap();
    }
}
