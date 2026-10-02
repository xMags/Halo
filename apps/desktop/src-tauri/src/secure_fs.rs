//! File primitives shared by the stores that keep the user's data on disk:
//! an atomic replace, so a crash mid-write leaves the old file rather than a
//! torn one, and Windows DPAPI protection for data that carries secrets
//! (download source URLs, addon URLs with debrid keys in them).

use std::io;
use std::path::Path;

/// Replaces `target` with `source` in one step, flushed through to disk.
#[cfg(windows)]
pub fn atomic_replace(source: &Path, target: &Path) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };
    let source_wide: Vec<u16> = source
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let target_wide: Vec<u16> = target
        .as_os_str()
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let result = unsafe {
        MoveFileExW(
            source_wide.as_ptr(),
            target_wide.as_ptr(),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    if result == 0 {
        return Err(io::Error::last_os_error().to_string());
    }
    Ok(())
}

#[cfg(not(windows))]
pub fn atomic_replace(source: &Path, target: &Path) -> Result<(), String> {
    std::fs::rename(source, target).map_err(|error| error.to_string())
}

/// Encrypts `bytes` to the current Windows user (DPAPI, no UI). The error
/// carries the Windows error code; callers say what they were protecting.
#[cfg(windows)]
pub fn protect(bytes: &[u8]) -> Result<Vec<u8>, io::Error> {
    use std::slice;
    use windows_sys::Win32::Foundation::{GetLastError, LocalFree};
    use windows_sys::Win32::Security::Cryptography::{CryptProtectData, CRYPT_INTEGER_BLOB};
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptProtectData(
            &input,
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            0x1,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(io::Error::from_raw_os_error(unsafe { GetLastError() } as i32));
    }
    let result = unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        LocalFree(output.pbData as *mut std::ffi::c_void);
    }
    Ok(result)
}

/// Decrypts what `protect` produced for the same Windows user.
#[cfg(windows)]
pub fn unprotect(bytes: &[u8]) -> Result<Vec<u8>, io::Error> {
    use std::slice;
    use windows_sys::Win32::Foundation::{GetLastError, LocalFree};
    use windows_sys::Win32::Security::Cryptography::{CryptUnprotectData, CRYPT_INTEGER_BLOB};
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB {
        cbData: 0,
        pbData: std::ptr::null_mut(),
    };
    let ok = unsafe {
        CryptUnprotectData(
            &input,
            std::ptr::null_mut(),
            std::ptr::null(),
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            0x1,
            &mut output,
        )
    };
    if ok == 0 {
        return Err(io::Error::from_raw_os_error(unsafe { GetLastError() } as i32));
    }
    let result = unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        LocalFree(output.pbData as *mut std::ffi::c_void);
    }
    Ok(result)
}

#[cfg(not(windows))]
pub fn protect(bytes: &[u8]) -> Result<Vec<u8>, io::Error> {
    Ok(bytes.to_vec())
}

#[cfg(not(windows))]
pub fn unprotect(bytes: &[u8]) -> Result<Vec<u8>, io::Error> {
    Ok(bytes.to_vec())
}

/// The Windows error code for a message, as the stores have always shown it.
pub fn os_error_code(error: &io::Error) -> i32 {
    error.raw_os_error().unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn atomic_replace_swaps_the_file_contents() {
        let dir = std::env::temp_dir().join(format!("halo-secure-fs-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let target = dir.join("doc.json");
        let tmp = dir.join("doc.tmp");
        std::fs::write(&target, b"old").unwrap();
        std::fs::write(&tmp, b"new").unwrap();
        atomic_replace(&tmp, &target).unwrap();
        assert_eq!(std::fs::read(&target).unwrap(), b"new");
        assert!(!tmp.exists());
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
