//! Halo's data for someone using it without an account: the addon list, the
//! library, watch history, settings and the profile that marks the mode is in
//! use. The webview owns each document's format (`DeviceBackend` in
//! `@halo/core`); this module keeps the bytes safe on disk.
//!
//! This is the only copy of that data, so it lives in the app's local data
//! folder rather than the WebView2 profile, which gets reset. Every write is
//! atomic. Documents are named by a fixed enum, so the webview can never
//! choose a path. The addon list is DPAPI-protected because addon URLs can
//! carry debrid API keys, the same reason download requests are.

use crate::secure_fs::{atomic_replace, os_error_code, protect, unprotect};
use serde::Deserialize;
use std::fs;
use std::io::ErrorKind;
use std::path::{Path, PathBuf};
use tokio::sync::Mutex;

/// Far above a heavy user's library and history; a write past it is a bug.
pub const MAX_DOCUMENT_BYTES: usize = 32 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum DeviceCollection {
    Profile,
    Addons,
    Library,
    WatchStates,
    Settings,
}

impl DeviceCollection {
    const ALL: [DeviceCollection; 5] = [
        DeviceCollection::Profile,
        DeviceCollection::Addons,
        DeviceCollection::Library,
        DeviceCollection::WatchStates,
        DeviceCollection::Settings,
    ];

    fn file_name(self) -> &'static str {
        match self {
            DeviceCollection::Profile => "profile.json",
            DeviceCollection::Addons => "addons.bin",
            DeviceCollection::Library => "library.json",
            DeviceCollection::WatchStates => "watch-states.json",
            DeviceCollection::Settings => "settings.json",
        }
    }

    fn is_protected(self) -> bool {
        matches!(self, DeviceCollection::Addons)
    }
}

/// One process's access to the device documents. Reads and writes are
/// serialized here; the webview additionally orders writes per collection.
pub struct DeviceStore {
    dir: PathBuf,
    lock: Mutex<()>,
}

impl DeviceStore {
    pub fn new(dir: PathBuf) -> Self {
        Self {
            dir,
            lock: Mutex::new(()),
        }
    }

    /// The document's text, or None when it was never written.
    pub async fn read(&self, collection: DeviceCollection) -> Result<Option<String>, String> {
        let _guard = self.lock.lock().await;
        read_document(&self.dir, collection)
    }

    pub async fn write(&self, collection: DeviceCollection, contents: &str) -> Result<(), String> {
        let _guard = self.lock.lock().await;
        write_document(&self.dir, collection, contents)
    }

    /// Removes every device document, after its contents moved into an account.
    pub async fn clear(&self) -> Result<(), String> {
        let _guard = self.lock.lock().await;
        for collection in DeviceCollection::ALL {
            match fs::remove_file(self.dir.join(collection.file_name())) {
                Ok(()) => {}
                Err(error) if error.kind() == ErrorKind::NotFound => {}
                Err(error) => return Err(format!("could not remove this PC's saved data: {error}")),
            }
        }
        Ok(())
    }
}

fn read_document(dir: &Path, collection: DeviceCollection) -> Result<Option<String>, String> {
    let raw = match fs::read(dir.join(collection.file_name())) {
        Ok(raw) => raw,
        Err(error) if error.kind() == ErrorKind::NotFound => return Ok(None),
        Err(error) => return Err(format!("could not read this PC's saved data: {error}")),
    };
    let clear = if collection.is_protected() {
        unprotect(&raw).map_err(|error| {
            format!(
                "Windows could not read this PC's saved addons (error {}).",
                os_error_code(&error)
            )
        })?
    } else {
        raw
    };
    String::from_utf8(clear)
        .map(Some)
        .map_err(|_| "this PC's saved data is not text".to_string())
}

fn write_document(dir: &Path, collection: DeviceCollection, contents: &str) -> Result<(), String> {
    if contents.len() > MAX_DOCUMENT_BYTES {
        return Err("this PC's saved data is too large to store".into());
    }
    fs::create_dir_all(dir).map_err(|error| format!("could not create the data folder: {error}"))?;
    let bytes = if collection.is_protected() {
        protect(contents.as_bytes()).map_err(|error| {
            format!(
                "Windows could not protect this PC's addons (error {}).",
                os_error_code(&error)
            )
        })?
    } else {
        contents.as_bytes().to_vec()
    };
    let path = dir.join(collection.file_name());
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes).map_err(|error| format!("could not save this PC's data: {error}"))?;
    atomic_replace(&tmp, &path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("halo-device-store-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn collections_are_named_by_the_webview_and_nothing_else_is_accepted() {
        let parsed: DeviceCollection = serde_json::from_str("\"watchStates\"").unwrap();
        assert_eq!(parsed, DeviceCollection::WatchStates);
        assert!(serde_json::from_str::<DeviceCollection>("\"../secrets\"").is_err());
        assert!(serde_json::from_str::<DeviceCollection>("\"watch_states\"").is_err());
    }

    #[tokio::test]
    async fn documents_round_trip_and_missing_ones_read_as_none() {
        let dir = temp_dir("round-trip");
        let store = DeviceStore::new(dir.clone());
        assert_eq!(store.read(DeviceCollection::Library).await.unwrap(), None);
        store
            .write(DeviceCollection::Library, r#"{"version":1,"items":[]}"#)
            .await
            .unwrap();
        assert_eq!(
            store.read(DeviceCollection::Library).await.unwrap().as_deref(),
            Some(r#"{"version":1,"items":[]}"#)
        );
        assert!(!dir.join("library.tmp").exists());
        fs::remove_dir_all(&dir).unwrap();
    }

    #[tokio::test]
    async fn oversized_writes_are_refused_and_leave_the_old_document() {
        let dir = temp_dir("oversized");
        let store = DeviceStore::new(dir.clone());
        store.write(DeviceCollection::Settings, "old").await.unwrap();
        let huge = "x".repeat(MAX_DOCUMENT_BYTES + 1);
        assert!(store.write(DeviceCollection::Settings, &huge).await.is_err());
        assert_eq!(
            store.read(DeviceCollection::Settings).await.unwrap().as_deref(),
            Some("old")
        );
        fs::remove_dir_all(&dir).unwrap();
    }

    #[tokio::test]
    async fn clear_removes_every_document() {
        let dir = temp_dir("clear");
        let store = DeviceStore::new(dir.clone());
        for collection in [DeviceCollection::Profile, DeviceCollection::Library, DeviceCollection::Settings] {
            store.write(collection, "{}").await.unwrap();
        }
        store.clear().await.unwrap();
        for collection in DeviceCollection::ALL {
            assert_eq!(store.read(collection).await.unwrap(), None);
        }
        // Clearing again finds nothing to remove and still succeeds.
        store.clear().await.unwrap();
        fs::remove_dir_all(&dir).unwrap();
    }

    #[tokio::test]
    #[ignore = "requires a Windows process with the interactive user profile loaded"]
    async fn the_addon_list_is_encrypted_on_disk() {
        let dir = temp_dir("protected");
        let store = DeviceStore::new(dir.clone());
        let contents = r#"{"version":1,"addons":[{"transportUrl":"https://addon.test/secret-key/manifest.json"}]}"#;
        store.write(DeviceCollection::Addons, contents).await.unwrap();
        let on_disk = fs::read(dir.join("addons.bin")).unwrap();
        assert!(!String::from_utf8_lossy(&on_disk).contains("secret-key"));
        assert_eq!(
            store.read(DeviceCollection::Addons).await.unwrap().as_deref(),
            Some(contents)
        );
        fs::remove_dir_all(&dir).unwrap();
    }
}
