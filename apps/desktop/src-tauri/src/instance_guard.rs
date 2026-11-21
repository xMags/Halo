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
use windows::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ACCESS_DENIED, ERROR_ALREADY_EXISTS};
use windows::Win32::System::Threading::CreateMutexW;

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

pub fn claim() -> Claim {
    claim_named(MUTEX_NAME)
}

fn claim_named(mutex_name: &str) -> Claim {
    let name: Vec<u16> = mutex_name.encode_utf16().chain(std::iter::once(0)).collect();
    match unsafe { CreateMutexW(None, true, PCWSTR(name.as_ptr())) } {
        Ok(handle) => {
            if unsafe { GetLastError() } == ERROR_ALREADY_EXISTS {
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
        assert_eq!(claim_named(&name), Claim::Owned);
        assert_eq!(claim_named(&name), Claim::HeldElsewhere);
    }
}
