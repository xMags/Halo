//! Device-local downloads. One worker transfers one job at a time, oldest
//! first, for the signed-in account only. The webview drives it through the
//! `downloads_*` commands and renders the `download-changed` and
//! `download-removed` events; it never sees a source URL or header, which
//! live DPAPI-encrypted in a per-job request vault rather than in the index.

mod containment;
mod naming;
mod rate;
mod redirect;
mod relocate;

use crate::secure_fs::{atomic_replace, os_error_code, protect, unprotect};
use containment::{is_within_approved_root, resolve_roots, same_path, strip_verbatim};
use futures_util::StreamExt;
use naming::{download_file_name, is_safe_file_name, safe_language, subtitle_extension};
use rate::RateWindow;
use redirect::{is_redirect_status, next_redirect_target};
use reqwest::header::{
    HeaderMap, HeaderName, HeaderValue, CONTENT_RANGE, ETAG, IF_RANGE, LAST_MODIFIED, LOCATION,
    RANGE,
};
use reqwest::Url;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::{Mutex, Notify};

const CHANGED_EVENT: &str = "download-changed";
const REMOVED_EVENT: &str = "download-removed";
const INDEX_FILE: &str = "downloads-index.json";
const CONFIG_FILE: &str = "downloads-config.json";
const DOWNLOAD_DIR: &str = "downloads";
const VAULT_DIR: &str = "download-requests";
const TRANSFER_ATTEMPTS: u32 = 3;
/// Progress reaches the UI this often.
const PROGRESS_INTERVAL: Duration = Duration::from_millis(250);
/// The data file is flushed and the index persisted only this often, because
/// both are synchronous disk work that stalls the socket when done on every
/// progress tick.
const DURABLE_INTERVAL: Duration = Duration::from_secs(2);
/// Throughput is averaged over this much history before it is reported.
const RATE_WINDOW: Duration = Duration::from_secs(3);
/// A read that yields nothing for this long is a failed connection.
const READ_TIMEOUT: Duration = Duration::from_secs(45);
/// How quickly a stalled read notices it was paused or cancelled.
const CANCEL_POLL: Duration = Duration::from_millis(250);
/// How long a pause, removal or replacement waits for the worker to let go.
const STOP_WAIT: Duration = Duration::from_secs(10);
const SPACE_RESERVE: u64 = 64 * 1024 * 1024;
/// Free space is rechecked after this many bytes even between progress ticks.
const SPACE_CHECK_BYTES: u64 = 8 * 1024 * 1024;
const MAX_SUBTITLE_BYTES: u64 = 32 * 1024 * 1024;
const MAX_APPROVED_ROOTS: usize = 32;
static JOB_COUNTER: AtomicU64 = AtomicU64::new(0);

const SIGN_IN_FIRST: &str = "Sign in before downloading.";
const ACCOUNT_CHANGED: &str = "The active account changed while starting the download.";
const STATE_CHANGED: &str = "Download state changed. Try again.";
const FOLDER_UNAVAILABLE: &str =
    "The download folder is unavailable. Choose another folder to continue.";
const NOT_ENOUGH_SPACE: &str = "There is not enough free space for this video.";
const OTHER_ACCOUNT: &str = "This download belongs to another account.";
const RETAINED_FOR_REPLACEMENT: &str =
    "The previous file is retained until its replacement finishes.";
const OUTSIDE_FOLDERS: &str = "This download is saved outside Halo's download folders. It was removed from the list and its files were left in place.";
const DELETE_FAILED: &str = "Halo could not delete every file for this download. It will try again the next time Halo starts.";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DownloadStatus {
    Queued,
    Downloading,
    Paused,
    Done,
    Failed,
}

impl DownloadStatus {
    fn is_active(&self) -> bool {
        matches!(self, Self::Queued | Self::Downloading)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DownloadFailureCode {
    SourceExpired,
    StorageFull,
    InvalidRange,
    MissingFile,
    Network,
    ServerUnavailable,
    SourceRejected,
    ProtectedRequestCorrupt,
    Unknown,
}

impl DownloadFailureCode {
    pub fn message(&self) -> &'static str {
        match self {
            Self::SourceExpired => "This source has expired. Choose a source again to continue.",
            Self::StorageFull => "The device ran out of storage while downloading.",
            Self::InvalidRange => "The source could not safely resume this download.",
            Self::MissingFile => "This download is no longer on the device.",
            Self::Network => "The download could not continue after repeated network failures.",
            Self::ServerUnavailable => "The source is still unavailable after repeated retries.",
            Self::SourceRejected => "The source refused this download.",
            Self::ProtectedRequestCorrupt => {
                "The protected download request could not be read. Choose the source again."
            }
            Self::Unknown => "This download could not be completed.",
        }
    }
    /// Failures a retry of the same request cannot fix. A missing file has no
    /// request left to retry: the vault entry went when the file finished.
    pub fn requires_new_source(&self) -> bool {
        matches!(
            self,
            Self::SourceExpired
                | Self::ProtectedRequestCorrupt
                | Self::InvalidRange
                | Self::MissingFile
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadMedia {
    pub video_id: String,
    pub item_id: String,
    pub media_type: String,
    pub meta_id: Option<String>,
    pub title: String,
    pub show_name: Option<String>,
    pub episode_label: Option<String>,
    pub poster: Option<String>,
    /// An episode still or title backdrop for the row thumbnail, looked up by
    /// the app after the download starts; never taken from the start request.
    #[serde(default)]
    pub landscape_artwork: Option<String>,
    pub addon_id: Option<String>,
    pub binge_group: Option<String>,
    pub filename: Option<String>,
    pub video_size: Option<u64>,
    pub video_hash: Option<String>,
    pub stream_name: Option<String>,
    pub stream_title: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadSubtitleRequest {
    pub url: String,
    pub lang: String,
    pub id: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadStartRequest {
    pub media: DownloadMedia,
    pub url: String,
    #[serde(default)]
    pub headers: HashMap<String, String>,
    pub subtitle: Option<DownloadSubtitleRequest>,
    #[serde(default)]
    pub replace_existing: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DownloadView {
    pub job_id: String,
    pub media: DownloadMedia,
    pub file_name: String,
    pub subtitle_file_name: Option<String>,
    pub subtitle_lang: Option<String>,
    pub status: DownloadStatus,
    pub total_bytes: u64,
    pub downloaded_bytes: u64,
    pub bytes_per_second: u64,
    pub source_fingerprint: String,
    pub failure: Option<DownloadFailureCode>,
    pub explicit_pause: bool,
    pub created_at: u64,
    pub updated_at: u64,
}

/// What asking for a download did. Swapping the saved source of a video is
/// never implicit: the caller has to ask again with `replace_existing`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "outcome", rename_all = "snake_case")]
pub enum StartOutcome {
    Started { download: DownloadView },
    AlreadyExists { download: DownloadView },
    ReplacementRequired,
}

/// The download a replacement will supersede. It stays in the index, hidden,
/// until the replacement finishes, so cancelling the replacement brings it
/// back and a failed replacement never costs the file that already played.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct ReplacementBackup {
    job_id: String,
    root_path: String,
    file_name: String,
    subtitle_file_name: Option<String>,
}

impl ReplacementBackup {
    fn of(record: &DownloadRecord) -> Self {
        Self {
            job_id: record.job_id.clone(),
            root_path: record.root_path.clone(),
            file_name: record.file_name.clone(),
            subtitle_file_name: record.subtitle_file_name.clone(),
        }
    }
    fn files(&self) -> FileSet {
        FileSet::new(
            &self.root_path,
            &self.file_name,
            self.subtitle_file_name.as_deref(),
        )
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct DownloadRecord {
    job_id: String,
    account_key: String,
    media: DownloadMedia,
    file_name: String,
    subtitle_file_name: Option<String>,
    subtitle_lang: Option<String>,
    root_path: String,
    status: DownloadStatus,
    total_bytes: u64,
    downloaded_bytes: u64,
    validator: Option<String>,
    source_fingerprint: String,
    failure: Option<DownloadFailureCode>,
    explicit_pause: bool,
    created_at: u64,
    updated_at: u64,
    bytes_per_second: u64,
    #[serde(default)]
    replacement: Option<ReplacementBackup>,
    /// A tombstone: the files are being removed, and a record that survives a
    /// crash in this state finishes its removal on the next launch.
    #[serde(default)]
    pending_deletion: bool,
}

impl DownloadRecord {
    fn view(&self) -> DownloadView {
        DownloadView {
            job_id: self.job_id.clone(),
            media: self.media.clone(),
            file_name: self.file_name.clone(),
            subtitle_file_name: self.subtitle_file_name.clone(),
            subtitle_lang: self.subtitle_lang.clone(),
            status: self.status.clone(),
            total_bytes: self.total_bytes,
            downloaded_bytes: self.downloaded_bytes,
            bytes_per_second: self.bytes_per_second,
            source_fingerprint: self.source_fingerprint.clone(),
            failure: self.failure.clone(),
            explicit_pause: self.explicit_pause,
            created_at: self.created_at,
            updated_at: self.updated_at,
        }
    }
    fn target(&self) -> PathBuf {
        PathBuf::from(&self.root_path).join(&self.file_name)
    }
    fn part(&self) -> PathBuf {
        part_path(&self.target())
    }
    fn subtitle_path(&self) -> Option<PathBuf> {
        self.subtitle_file_name
            .as_deref()
            .filter(|name| is_safe_file_name(name))
            .map(|name| PathBuf::from(&self.root_path).join(name))
    }
    fn files(&self) -> FileSet {
        FileSet::new(
            &self.root_path,
            &self.file_name,
            self.subtitle_file_name.as_deref(),
        )
    }
}

/// Every file one download may own on disk.
struct FileSet {
    part: PathBuf,
    subtitle: Option<PathBuf>,
    target: PathBuf,
}

impl FileSet {
    fn new(root: &str, file_name: &str, subtitle: Option<&str>) -> Self {
        let target = PathBuf::from(root).join(file_name);
        Self {
            part: part_path(&target),
            subtitle: subtitle
                .filter(|name| is_safe_file_name(name))
                .map(|name| PathBuf::from(root).join(name)),
            target,
        }
    }
    fn contains(&self, path: &Path) -> bool {
        same_path(&self.target, path)
            || same_path(&self.part, path)
            || self
                .subtitle
                .as_deref()
                .is_some_and(|subtitle| same_path(subtitle, path))
    }
}

fn part_path(target: &Path) -> PathBuf {
    let mut value = target.as_os_str().to_owned();
    value.push(".part");
    PathBuf::from(value)
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct PersistedIndex {
    version: u32,
    entries: Vec<DownloadRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct PersistedConfig {
    /// The folder the user chose; absent means the default under app data.
    #[serde(default)]
    directory: Option<String>,
    /// Every folder this device has used as a download root, newest first.
    /// Seeded once from the index, then only grows when a folder is chosen.
    #[serde(default)]
    approved_roots: Option<Vec<String>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct ProtectedRequest {
    url: String,
    headers: HashMap<String, String>,
    subtitle: Option<DownloadSubtitleRequest>,
}

#[derive(Debug)]
enum TransferError {
    Permanent(DownloadFailureCode),
    Retryable(DownloadFailureCode),
    Paused,
}

struct Transferred {
    bytes: u64,
    total: u64,
    validator: Option<String>,
    subtitle: Option<(String, String)>,
}

struct OpenedResponse {
    response: reqwest::Response,
    /// Whether a redirect left the original origin on the way here.
    crossed_origin: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct ContentRange {
    start: u64,
    end: u64,
    total: u64,
}

#[derive(Debug, PartialEq, Eq)]
enum RemoveError {
    /// A path resolved outside every approved root; nothing was deleted.
    Containment,
    Io,
}

struct Inner {
    entries: HashMap<String, DownloadRecord>,
    active_account: Option<String>,
    active_job: Option<String>,
    cancel: HashMap<String, Arc<AtomicBool>>,
    directory: PathBuf,
    approved_roots: Vec<PathBuf>,
}

impl Inner {
    fn is_visible(&self, record: &DownloadRecord, account: &str) -> bool {
        record.account_key == account
            && !record.pending_deletion
            && !is_hidden_backup(&self.entries, &record.job_id)
    }
    fn visible_for_video(&self, account: &str, video_id: &str) -> Option<DownloadRecord> {
        self.entries
            .values()
            .find(|entry| entry.media.video_id == video_id && self.is_visible(entry, account))
            .cloned()
    }
    fn visible_view(&self, job_id: &str) -> Option<DownloadView> {
        let account = self.active_account.as_deref()?;
        self.entries
            .get(job_id)
            .filter(|entry| self.is_visible(entry, account))
            .map(DownloadRecord::view)
    }
    fn visible_views(&self) -> Vec<DownloadView> {
        let Some(account) = self.active_account.as_deref() else {
            return Vec::new();
        };
        let mut views: Vec<_> = self
            .entries
            .values()
            .filter(|entry| self.is_visible(entry, account))
            .map(DownloadRecord::view)
            .collect();
        views.sort_by(|a, b| (a.created_at, &a.job_id).cmp(&(b.created_at, &b.job_id)));
        views
    }
}

/// What moving one partition's downloads into another does.
#[derive(Debug, Default, PartialEq, Eq)]
struct AdoptionPlan {
    /// Records that change owner.
    rekey: Vec<String>,
    /// Duplicates removed, files included.
    discard: Vec<String>,
}

/// Every record of `from` moves to `to`, except where `to` already shows the
/// same video: there a finished download beats an unfinished one, and when
/// both are finished (or neither is) the one already in `to` stays. A losing
/// record's replacement backup goes with it, so it cannot resurface as a
/// second copy.
fn plan_adoption(entries: &HashMap<String, DownloadRecord>, from: &str, to: &str) -> AdoptionPlan {
    if from == to {
        return AdoptionPlan::default();
    }
    let visible = |entry: &DownloadRecord, account: &str| {
        entry.account_key == account
            && !entry.pending_deletion
            && !is_hidden_backup(entries, &entry.job_id)
    };
    let mut discard: HashSet<String> = HashSet::new();
    for moving in entries.values().filter(|entry| visible(entry, from)) {
        let Some(existing) = entries
            .values()
            .find(|entry| visible(entry, to) && entry.media.video_id == moving.media.video_id)
        else {
            continue;
        };
        let moving_wins =
            moving.status == DownloadStatus::Done && existing.status != DownloadStatus::Done;
        let loser = if moving_wins { existing } else { moving };
        discard.insert(loser.job_id.clone());
        if let Some(backup) = loser.replacement.as_ref() {
            discard.insert(backup.job_id.clone());
        }
    }
    let mut rekey: Vec<String> = entries
        .values()
        .filter(|entry| entry.account_key == from && !discard.contains(&entry.job_id))
        .map(|entry| entry.job_id.clone())
        .collect();
    rekey.sort();
    let mut discard: Vec<String> = discard.into_iter().collect();
    discard.sort();
    AdoptionPlan { rekey, discard }
}

/// A record that some live record names as its replacement backup.
fn is_hidden_backup(entries: &HashMap<String, DownloadRecord>, job_id: &str) -> bool {
    entries.values().any(|entry| {
        !entry.pending_deletion
            && entry
                .replacement
                .as_ref()
                .is_some_and(|backup| backup.job_id == job_id)
    })
}

/// Refuses a second start for a video while the first is still being
/// prepared, instead of letting both create a record.
struct PreparingGuard<'a> {
    set: &'a std::sync::Mutex<HashSet<String>>,
    key: String,
}

impl<'a> PreparingGuard<'a> {
    fn acquire(
        set: &'a std::sync::Mutex<HashSet<String>>,
        account: &str,
        video_id: &str,
    ) -> Result<Self, String> {
        let key = format!("{account}\n{video_id}");
        let mut pending = set
            .lock()
            .map_err(|_| "Download state is unavailable.".to_string())?;
        if !pending.insert(key.clone()) {
            return Err("This video is already being prepared for download.".into());
        }
        Ok(Self { set, key })
    }
}

impl Drop for PreparingGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut pending) = self.set.lock() {
            pending.remove(&self.key);
        }
    }
}

pub struct DownloadManager {
    app: AppHandle,
    index_path: PathBuf,
    config_path: PathBuf,
    vault_dir: PathBuf,
    client: reqwest::Client,
    inner: Mutex<Inner>,
    /// Serialises snapshot-and-write so an older snapshot of the index can
    /// never land on disk after a newer one. Always taken before `inner`.
    persist_gate: Mutex<()>,
    /// Signalled whenever the worker lets go of a job.
    job_released: Notify,
    preparing: std::sync::Mutex<HashSet<String>>,
}

impl DownloadManager {
    pub fn new(app: AppHandle, data_dir: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&data_dir).map_err(|e| format!("download state unavailable: {e}"))?;
        let index_path = data_dir.join(INDEX_FILE);
        let config_path = data_dir.join(CONFIG_FILE);
        let vault_dir = data_dir.join(VAULT_DIR);
        fs::create_dir_all(&vault_dir).map_err(|e| format!("download vault unavailable: {e}"))?;
        let default_directory = data_dir.join(DOWNLOAD_DIR);
        let mut entries = load_index(&index_path)?;
        let config = load_config(&config_path).unwrap_or_default();
        let configured = config
            .directory
            .as_ref()
            .map(|directory| strip_verbatim(PathBuf::from(directory)));
        let directory = configured
            .clone()
            .unwrap_or_else(|| default_directory.clone());
        if configured.is_none() {
            fs::create_dir_all(&directory)
                .map_err(|e| format!("download directory unavailable: {e}"))?;
        }
        let mut approved_roots = match config.approved_roots.as_ref() {
            Some(saved) => saved.iter().map(PathBuf::from).collect(),
            None => seed_roots(&entries),
        };
        remember_root(&mut approved_roots, &default_directory);
        remember_root(&mut approved_roots, &directory);

        for entry in entries.values_mut() {
            let recovered = recovered_status(&entry.status, entry.explicit_pause);
            if recovered != entry.status {
                entry.status = recovered;
                entry.bytes_per_second = 0;
                entry.updated_at = now_ms();
            }
        }
        finish_pending_deletions(&mut entries, &resolve_roots(&approved_roots), &vault_dir);

        let manager = Self {
            app,
            index_path,
            config_path,
            vault_dir,
            client: reqwest::Client::builder()
                // Redirects are followed by hand so the source's headers stop
                // at the first hop that leaves its origin (see open_get).
                .redirect(reqwest::redirect::Policy::none())
                .connect_timeout(Duration::from_secs(30))
                .build()
                .map_err(|e| format!("HTTP client unavailable: {e}"))?,
            inner: Mutex::new(Inner {
                entries,
                active_account: None,
                active_job: None,
                cancel: HashMap::new(),
                directory,
                approved_roots: approved_roots.clone(),
            }),
            persist_gate: Mutex::new(()),
            job_released: Notify::new(),
            preparing: std::sync::Mutex::new(HashSet::new()),
        };
        manager.persist_blocking()?;
        // The roots are re-seeded next launch if this write fails, so it does
        // not stop the app from starting.
        let _ = write_json(
            &manager.config_path,
            &config_document(configured.as_deref(), &approved_roots),
        );
        Ok(manager)
    }

    pub async fn set_account(
        self: &Arc<Self>,
        account_key: String,
    ) -> Result<Vec<DownloadView>, String> {
        let account_key = fingerprint(&account_key);
        let views = {
            let mut inner = self.inner.lock().await;
            if inner
                .active_account
                .as_ref()
                .is_some_and(|active| active != &account_key)
            {
                for flag in inner.cancel.values() {
                    flag.store(true, Ordering::SeqCst);
                }
                if let Some(previous_account) = inner.active_account.clone() {
                    for entry in inner.entries.values_mut().filter(|entry| {
                        entry.account_key == previous_account && entry.status.is_active()
                    }) {
                        entry.status = DownloadStatus::Paused;
                        entry.explicit_pause = true;
                        entry.bytes_per_second = 0;
                        entry.updated_at = now_ms();
                    }
                }
            }
            inner.active_account = Some(account_key);
            inner.visible_views()
        };
        self.persist().await?;
        self.kick();
        Ok(views)
    }

    pub async fn clear_account(&self) -> Result<(), String> {
        let mut inner = self.inner.lock().await;
        for flag in inner.cancel.values() {
            flag.store(true, Ordering::SeqCst);
        }
        if let Some(account) = inner.active_account.take() {
            for entry in inner
                .entries
                .values_mut()
                .filter(|entry| entry.account_key == account && entry.status.is_active())
            {
                entry.status = DownloadStatus::Paused;
                entry.explicit_pause = true;
                entry.bytes_per_second = 0;
                entry.updated_at = now_ms();
            }
        }
        drop(inner);
        self.persist().await
    }

    /// Moves the downloads made without an account (`from`) to the account
    /// they now belong to (`to`), when someone signs in. Files and request
    /// vault entries stay where they are; only the owner changes, so
    /// transfers move as they were (paused, since the partition switch
    /// paused them). Where both already hold the same video, one copy stays
    /// (see `plan_adoption`) and the other is removed like any download.
    pub async fn adopt_account(
        self: &Arc<Self>,
        from: String,
        to: String,
    ) -> Result<Vec<DownloadView>, String> {
        let from = fingerprint(&from);
        let to = fingerprint(&to);
        let losers = {
            let mut inner = self.inner.lock().await;
            let plan = plan_adoption(&inner.entries, &from, &to);
            let now = now_ms();
            for job_id in &plan.rekey {
                if let Some(entry) = inner.entries.get_mut(job_id) {
                    entry.account_key = to.clone();
                    entry.updated_at = now;
                }
            }
            plan.discard
                .iter()
                .filter_map(|job_id| inner.entries.get(job_id).cloned())
                .collect::<Vec<_>>()
        };
        self.persist().await?;
        for loser in losers {
            self.stop_job(&loser.job_id).await;
            // A file that cannot be deleted now is retried on the next launch
            // (the record stays a tombstone), so adoption itself still holds.
            let _ = self.discard(&loser, None).await;
            self.emit_removed(&loser.job_id);
        }
        self.kick();
        Ok(self.inner.lock().await.visible_views())
    }

    pub async fn list(&self) -> Vec<DownloadView> {
        self.reconcile_missing_files().await;
        self.inner.lock().await.visible_views()
    }

    pub async fn start(
        self: &Arc<Self>,
        mut request: DownloadStartRequest,
    ) -> Result<StartOutcome, String> {
        let account = { self.inner.lock().await.active_account.clone() }
            .ok_or_else(|| SIGN_IN_FIRST.to_string())?;
        validate_media(&request.media)?;
        validate_url(&request.url)?;
        validate_headers(&request.headers)?;
        if let Some(subtitle) = request.subtitle.as_ref() {
            validate_subtitle(subtitle)?;
        }
        request.media.landscape_artwork = None;
        let _preparing =
            PreparingGuard::acquire(&self.preparing, &account, &request.media.video_id)?;
        let fingerprint = fingerprint(&request.url);
        let (existing, directory) = {
            let inner = self.inner.lock().await;
            if inner.active_account.as_deref() != Some(account.as_str()) {
                return Err(ACCOUNT_CHANGED.into());
            }
            (
                inner.visible_for_video(&account, &request.media.video_id),
                inner.directory.clone(),
            )
        };
        if let Some(existing) = existing.as_ref() {
            if existing.source_fingerprint == fingerprint
                && existing.status != DownloadStatus::Failed
            {
                return Ok(StartOutcome::AlreadyExists {
                    download: existing.view(),
                });
            }
            let needs_new_source = existing
                .failure
                .as_ref()
                .is_some_and(DownloadFailureCode::requires_new_source);
            if !request.replace_existing
                && (existing.source_fingerprint != fingerprint || needs_new_source)
            {
                return Ok(StartOutcome::ReplacementRequired);
            }
            if !request.replace_existing {
                // The same source failed for a reason a retry can fix.
                self.resume(&existing.job_id).await?;
                return self
                    .inner
                    .lock()
                    .await
                    .entries
                    .get(&existing.job_id)
                    .map(|entry| StartOutcome::Started {
                        download: entry.view(),
                    })
                    .ok_or_else(|| STATE_CHANGED.into());
            }
        }
        if !directory.is_dir() {
            return Err(FOLDER_UNAVAILABLE.into());
        }
        if let (Some(size), Some(free)) = (request.media.video_size, available_bytes(&directory)) {
            if !has_sufficient_space(free, size) {
                return Err(NOT_ENOUGH_SPACE.into());
            }
        }

        let job_id = new_job_id();
        let file_name = {
            let inner = self.inner.lock().await;
            choose_file_name(
                &inner.entries,
                &request.media,
                &fingerprint,
                &account,
                &directory,
            )?
        };
        let protected = ProtectedRequest {
            url: request.url,
            headers: request.headers,
            subtitle: request.subtitle,
        };
        self.write_request(&job_id, &protected)?;

        let mut superseded = Vec::new();
        let backup = match existing.as_ref() {
            Some(existing) => match self.prepare_replacement(existing).await {
                Ok((backup, discarded)) => {
                    superseded.push(existing.job_id.clone());
                    superseded.extend(discarded);
                    Some(backup)
                }
                Err(error) => {
                    self.delete_request(&job_id);
                    return Err(error);
                }
            },
            None => None,
        };

        let now = now_ms();
        let entry = DownloadRecord {
            job_id: job_id.clone(),
            account_key: account.clone(),
            media: request.media,
            file_name,
            subtitle_file_name: None,
            subtitle_lang: None,
            root_path: directory.to_string_lossy().into_owned(),
            status: DownloadStatus::Queued,
            total_bytes: 0,
            downloaded_bytes: 0,
            validator: None,
            source_fingerprint: fingerprint,
            failure: None,
            explicit_pause: false,
            created_at: now,
            updated_at: now,
            bytes_per_second: 0,
            replacement: backup,
            pending_deletion: false,
        };
        {
            let mut inner = self.inner.lock().await;
            if inner.active_account.as_deref() != Some(account.as_str()) {
                drop(inner);
                self.delete_request(&job_id);
                return Err(ACCOUNT_CHANGED.into());
            }
            inner.entries.insert(job_id.clone(), entry.clone());
        }
        if let Err(error) = self.persist().await {
            self.inner.lock().await.entries.remove(&job_id);
            self.delete_request(&job_id);
            return Err(error);
        }
        // The superseded download is hidden now; the replacement stands in.
        for id in &superseded {
            self.emit_removed(id);
        }
        self.emit_changed(entry.view());
        self.kick();
        Ok(StartOutcome::Started {
            download: entry.view(),
        })
    }

    /// Stops `existing` and decides what the replacement keeps as its backup.
    /// Returns that backup and the ids of any record discarded on the way.
    async fn prepare_replacement(
        &self,
        existing: &DownloadRecord,
    ) -> Result<(ReplacementBackup, Vec<String>), String> {
        self.stop_job(&existing.job_id).await;
        let current = {
            let inner = self.inner.lock().await;
            inner
                .entries
                .get(&existing.job_id)
                .filter(|entry| entry.source_fingerprint == existing.source_fingerprint)
                .cloned()
        }
        .ok_or_else(|| STATE_CHANGED.to_string())?;
        if let Some(carried) = current.replacement.clone() {
            // `current` is itself an unfinished replacement. The download it
            // was replacing is the one worth keeping; the unfinished one goes.
            let _ = self.discard(&current, Some(&carried.files())).await;
            return Ok((carried, vec![current.job_id]));
        }
        if current.status.is_active() {
            let mut inner = self.inner.lock().await;
            if let Some(entry) = inner.entries.get_mut(&current.job_id) {
                entry.status = DownloadStatus::Paused;
                entry.explicit_pause = true;
                entry.bytes_per_second = 0;
                entry.updated_at = now_ms();
            }
        }
        Ok((ReplacementBackup::of(&current), Vec::new()))
    }

    pub async fn pause(&self, job_id: &str) -> Result<(), String> {
        let view = {
            let mut inner = self.inner.lock().await;
            if is_hidden_backup(&inner.entries, job_id) {
                return Ok(());
            }
            let account = inner.active_account.clone();
            let flag = inner.cancel.get(job_id).cloned();
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return Ok(());
            };
            if account.as_deref() != Some(entry.account_key.as_str()) {
                return Err(OTHER_ACCOUNT.into());
            }
            if entry.status == DownloadStatus::Done || entry.pending_deletion {
                return Ok(());
            }
            entry.status = DownloadStatus::Paused;
            entry.explicit_pause = true;
            entry.bytes_per_second = 0;
            entry.updated_at = now_ms();
            if let Some(flag) = flag {
                flag.store(true, Ordering::SeqCst);
            }
            entry.view()
        };
        self.persist().await?;
        self.emit_changed(view);
        Ok(())
    }

    pub async fn resume(self: &Arc<Self>, job_id: &str) -> Result<(), String> {
        let view = {
            let mut inner = self.inner.lock().await;
            if is_hidden_backup(&inner.entries, job_id) {
                return Err(RETAINED_FOR_REPLACEMENT.into());
            }
            let account = inner.active_account.clone();
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return Ok(());
            };
            if account.as_deref() != Some(entry.account_key.as_str()) {
                return Err(OTHER_ACCOUNT.into());
            }
            if entry.status == DownloadStatus::Done || entry.pending_deletion {
                return Ok(());
            }
            if entry
                .failure
                .as_ref()
                .is_some_and(DownloadFailureCode::requires_new_source)
            {
                return Err("Choose the source again before retrying this download.".into());
            }
            entry.status = DownloadStatus::Queued;
            entry.explicit_pause = false;
            entry.failure = None;
            entry.bytes_per_second = 0;
            entry.updated_at = now_ms();
            entry.view()
        };
        self.persist().await?;
        self.emit_changed(view);
        self.kick();
        Ok(())
    }

    /// Cancels a transfer or deletes a finished download, files included.
    pub async fn remove(&self, job_id: &str) -> Result<(), String> {
        let record = {
            let inner = self.inner.lock().await;
            if is_hidden_backup(&inner.entries, job_id) {
                return Err(RETAINED_FOR_REPLACEMENT.into());
            }
            let Some(entry) = inner.entries.get(job_id) else {
                return Ok(());
            };
            if inner.active_account.as_deref() != Some(entry.account_key.as_str()) {
                return Err(OTHER_ACCOUNT.into());
            }
            entry.clone()
        };
        self.stop_job(job_id).await;
        let result = self.discard(&record, None).await;
        self.emit_removed(job_id);
        // Removing an unfinished replacement brings back what it replaced.
        if let Some(backup) = record.replacement.as_ref() {
            let restored = self.inner.lock().await.visible_view(&backup.job_id);
            if let Some(view) = restored {
                self.emit_changed(view);
            }
        }
        match result {
            Ok(()) => Ok(()),
            Err(RemoveError::Containment) => Err(OUTSIDE_FOLDERS.into()),
            Err(RemoveError::Io) => Err(DELETE_FAILED.into()),
        }
    }

    /// Tombstones a record, deletes its request and files (except any the
    /// `preserve` set owns), then forgets it. A containment failure forgets the
    /// record without touching its files; an I/O failure keeps the tombstone
    /// so the next launch retries.
    async fn discard(
        &self,
        record: &DownloadRecord,
        preserve: Option<&FileSet>,
    ) -> Result<(), RemoveError> {
        let (current, roots) = {
            let mut inner = self.inner.lock().await;
            let current = match inner.entries.get_mut(&record.job_id) {
                Some(entry) => {
                    entry.pending_deletion = true;
                    entry.status = DownloadStatus::Paused;
                    entry.explicit_pause = true;
                    entry.bytes_per_second = 0;
                    entry.updated_at = now_ms();
                    entry.clone()
                }
                None => record.clone(),
            };
            (current, inner.approved_roots.clone())
        };
        let _ = self.persist().await;
        self.delete_request(&current.job_id);
        let result = remove_files(&current.files(), &resolve_roots(&roots), preserve);
        if result != Err(RemoveError::Io) {
            self.inner.lock().await.entries.remove(&current.job_id);
            let _ = self.persist().await;
        }
        result
    }

    /// Asks the worker to drop `job_id` and waits until it has.
    async fn stop_job(&self, job_id: &str) {
        loop {
            let released = self.job_released.notified();
            {
                let inner = self.inner.lock().await;
                if let Some(flag) = inner.cancel.get(job_id) {
                    flag.store(true, Ordering::SeqCst);
                }
                if inner.active_job.as_deref() != Some(job_id) {
                    return;
                }
            }
            // A stalled read notices the flag within CANCEL_POLL; the timeout
            // only guards against a worker that never reports back.
            if tokio::time::timeout(STOP_WAIT, released).await.is_err() {
                return;
            }
        }
    }

    pub async fn attach_subtitle(
        self: &Arc<Self>,
        job_id: &str,
        subtitle: DownloadSubtitleRequest,
    ) -> Result<(), String> {
        validate_subtitle(&subtitle)?;
        let completed_target = {
            let inner = self.inner.lock().await;
            let entry = inner
                .active_account
                .as_deref()
                .and_then(|account| {
                    inner
                        .entries
                        .get(job_id)
                        .filter(|entry| inner.is_visible(entry, account))
                })
                .ok_or_else(|| "Download not found.".to_string())?;
            if entry.subtitle_file_name.is_some() {
                return Ok(());
            }
            (entry.status == DownloadStatus::Done).then(|| entry.target())
        };

        if let Some(target) = completed_target {
            return self
                .save_completed_subtitle(job_id, &subtitle, &target)
                .await;
        }

        let mut protected = match self.read_request(job_id) {
            Ok(protected) => protected,
            Err(_) => {
                // The transfer may have finished, and its request gone, since
                // the check above.
                let target = self
                    .inner
                    .lock()
                    .await
                    .entries
                    .get(job_id)
                    .filter(|entry| entry.status == DownloadStatus::Done)
                    .map(DownloadRecord::target);
                if let Some(target) = target {
                    return self
                        .save_completed_subtitle(job_id, &subtitle, &target)
                        .await;
                }
                return Err("The protected download request could not be read.".into());
            }
        };
        protected.subtitle = Some(subtitle);
        self.write_request(job_id, &protected)?;
        Ok(())
    }

    pub async fn set_directory(&self, directory: String) -> Result<String, String> {
        let path = canonical_directory(Path::new(&directory))?;
        let mut roots = self.inner.lock().await.approved_roots.clone();
        remember_root(&mut roots, &path);
        write_json(&self.config_path, &config_document(Some(&path), &roots))?;
        let mut inner = self.inner.lock().await;
        inner.directory = path.clone();
        inner.approved_roots = roots;
        Ok(path.to_string_lossy().into_owned())
    }

    pub async fn directory_info(&self) -> Result<DirectoryInfo, String> {
        let path = self.inner.lock().await.directory.clone();
        Ok(DirectoryInfo {
            path: path.to_string_lossy().into_owned(),
            exists: path.is_dir(),
            free_bytes: available_bytes(&path),
        })
    }

    /// Stores the landscape artwork the app looked up for a download's row.
    /// A stale answer (another account, a removed download) is ignored.
    pub async fn set_landscape_artwork(&self, job_id: &str, artwork: String) -> Result<(), String> {
        validate_artwork_url(&artwork)?;
        let view = {
            let mut inner = self.inner.lock().await;
            let Some(account) = inner.active_account.clone() else {
                return Ok(());
            };
            if !inner
                .entries
                .get(job_id)
                .is_some_and(|entry| inner.is_visible(entry, &account))
            {
                return Ok(());
            }
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return Ok(());
            };
            if entry.media.landscape_artwork.as_deref() == Some(artwork.as_str()) {
                return Ok(());
            }
            entry.media.landscape_artwork = Some(artwork);
            entry.updated_at = now_ms();
            entry.view()
        };
        self.persist().await?;
        self.emit_changed(view);
        Ok(())
    }

    pub async fn playback_path(&self, job_id: &str) -> Result<String, String> {
        Ok(self.playback_files(job_id).await?.video_path)
    }

    pub async fn playback_files(&self, job_id: &str) -> Result<PlaybackFiles, String> {
        self.reconcile_missing_files().await;
        let (entry, roots) = {
            let inner = self.inner.lock().await;
            let account = inner
                .active_account
                .as_deref()
                .ok_or_else(|| "Sign in before opening downloads.".to_string())?;
            let entry = inner
                .entries
                .get(job_id)
                .filter(|entry| inner.is_visible(entry, account))
                .cloned()
                .ok_or_else(|| "Download not found.".to_string())?;
            (entry, inner.approved_roots.clone())
        };
        // The index names the file to open, so the rule that confines
        // deletion also decides what the player may load.
        let roots = resolve_roots(&roots);
        let target = entry.target();
        if !is_safe_record_path(&entry)
            || entry.status != DownloadStatus::Done
            || !is_within_approved_root(&target, &roots)
            || !target.is_file()
        {
            return Err(DownloadFailureCode::MissingFile.message().into());
        }
        Ok(PlaybackFiles {
            video_path: target.to_string_lossy().into_owned(),
            subtitle_path: entry
                .subtitle_path()
                .filter(|path| is_within_approved_root(path, &roots) && path.is_file())
                .map(|path| path.to_string_lossy().into_owned()),
        })
    }

    /// Marks finished downloads whose file has gone from disk, so the list
    /// offers a new source instead of a Play button that cannot work.
    async fn reconcile_missing_files(&self) {
        let candidates: Vec<(String, PathBuf)> = {
            let inner = self.inner.lock().await;
            inner
                .entries
                .values()
                .filter(|entry| entry.status == DownloadStatus::Done && !entry.pending_deletion)
                .map(|entry| (entry.job_id.clone(), entry.target()))
                .collect()
        };
        let missing: Vec<String> = candidates
            .into_iter()
            .filter(|(_, target)| !target.is_file())
            .map(|(job_id, _)| job_id)
            .collect();
        if missing.is_empty() {
            return;
        }
        let views: Vec<DownloadView> = {
            let mut inner = self.inner.lock().await;
            for job_id in &missing {
                if let Some(entry) = inner
                    .entries
                    .get_mut(job_id)
                    .filter(|entry| entry.status == DownloadStatus::Done)
                {
                    entry.status = DownloadStatus::Failed;
                    entry.failure = Some(DownloadFailureCode::MissingFile);
                    entry.bytes_per_second = 0;
                    entry.updated_at = now_ms();
                }
            }
            missing
                .iter()
                .filter_map(|id| inner.visible_view(id))
                .collect()
        };
        let _ = self.persist().await;
        for view in views {
            self.emit_changed(view);
        }
    }

    fn kick(self: &Arc<Self>) {
        let manager = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            manager.pump_once().await;
        });
    }

    async fn pump_once(self: &Arc<Self>) {
        let (job_id, view) = {
            let mut inner = self.inner.lock().await;
            if inner.active_job.is_some() {
                return;
            }
            let Some(account) = inner.active_account.clone() else {
                return;
            };
            let Some(job_id) = next_queued_job(&inner.entries, &account) else {
                return;
            };
            let flag = Arc::new(AtomicBool::new(false));
            inner.active_job = Some(job_id.clone());
            inner.cancel.insert(job_id.clone(), flag);
            let view = inner.entries.get_mut(&job_id).map(|entry| {
                entry.status = DownloadStatus::Downloading;
                entry.updated_at = now_ms();
                entry.view()
            });
            (job_id, view)
        };
        let _ = self.persist().await;
        if let Some(view) = view {
            self.emit_changed(view);
        }
        let manager = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            manager.run(job_id).await;
        });
    }

    async fn run(self: Arc<Self>, job_id: String) {
        let result = self.transfer_with_retries(&job_id).await;
        let mut superseded: Option<DownloadRecord> = None;
        {
            let mut inner = self.inner.lock().await;
            let backup_id = inner.entries.get(&job_id).and_then(|entry| {
                entry
                    .replacement
                    .as_ref()
                    .map(|backup| backup.job_id.clone())
            });
            if let Some(entry) = inner.entries.get_mut(&job_id) {
                // A pause, resume or account switch while the transfer wound
                // down already wrote the state the user asked for.
                let still_running = entry.status == DownloadStatus::Downloading;
                entry.bytes_per_second = 0;
                entry.updated_at = now_ms();
                match &result {
                    Ok(done) => {
                        entry.status = DownloadStatus::Done;
                        entry.downloaded_bytes = done.bytes;
                        entry.total_bytes = done.total;
                        entry.validator = done.validator.clone();
                        entry.failure = None;
                        entry.explicit_pause = false;
                        if let Some((name, lang)) = done.subtitle.clone() {
                            entry.subtitle_file_name = Some(name);
                            entry.subtitle_lang = Some(lang);
                        }
                    }
                    Err(TransferError::Paused) => {
                        if still_running {
                            entry.status = DownloadStatus::Paused;
                        }
                    }
                    Err(TransferError::Permanent(code) | TransferError::Retryable(code)) => {
                        if still_running {
                            entry.status = DownloadStatus::Failed;
                            entry.failure = Some(code.clone());
                        }
                    }
                }
            }
            if result.is_ok() {
                if let Some(backup) = backup_id.and_then(|id| inner.entries.get_mut(&id)) {
                    backup.pending_deletion = true;
                    backup.status = DownloadStatus::Paused;
                    backup.explicit_pause = true;
                    backup.bytes_per_second = 0;
                    backup.updated_at = now_ms();
                    superseded = Some(backup.clone());
                    // Keep the replacement non-terminal on disk until the old
                    // files are gone or deliberately kept behind their
                    // tombstone; a crash in between finishes on next launch.
                    if let Some(entry) = inner.entries.get_mut(&job_id) {
                        entry.status = DownloadStatus::Downloading;
                    }
                }
            }
            inner.cancel.remove(&job_id);
            inner.active_job = None;
        }
        let _ = self.persist().await;
        let request_spent = match &result {
            Ok(_) => true,
            Err(TransferError::Permanent(code) | TransferError::Retryable(code)) => {
                code.requires_new_source()
            }
            Err(TransferError::Paused) => false,
        };
        if request_spent {
            self.delete_request(&job_id);
        }
        if let Some(old) = superseded {
            let (roots, owner_files) = {
                let inner = self.inner.lock().await;
                (
                    inner.approved_roots.clone(),
                    inner.entries.get(&job_id).map(DownloadRecord::files),
                )
            };
            let outcome = remove_files(&old.files(), &resolve_roots(&roots), owner_files.as_ref());
            self.delete_request(&old.job_id);
            {
                let mut inner = self.inner.lock().await;
                if outcome != Err(RemoveError::Io) {
                    inner.entries.remove(&old.job_id);
                }
                if let Some(entry) = inner.entries.get_mut(&job_id) {
                    entry.status = DownloadStatus::Done;
                    entry.replacement = None;
                    entry.updated_at = now_ms();
                }
            }
            let _ = self.persist().await;
        }
        let view = self.inner.lock().await.visible_view(&job_id);
        if let Some(view) = view {
            self.emit_changed(view);
        }
        self.job_released.notify_waiters();
        self.kick();
    }

    async fn transfer_with_retries(&self, job_id: &str) -> Result<Transferred, TransferError> {
        let request = self
            .read_request(job_id)
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::ProtectedRequestCorrupt))?;
        let cancel =
            { self.inner.lock().await.cancel.get(job_id).cloned() }.ok_or(TransferError::Paused)?;
        let mut attempt = 0;
        loop {
            match self.transfer_once(job_id, &request, &cancel).await {
                Err(TransferError::Retryable(code)) => {
                    attempt += 1;
                    if attempt >= TRANSFER_ATTEMPTS {
                        return Err(TransferError::Retryable(code));
                    }
                    wait_unless_cancelled(&cancel, Duration::from_millis(250 << (attempt - 1)))
                        .await?;
                }
                other => return other,
            }
        }
    }

    async fn transfer_once(
        &self,
        job_id: &str,
        request: &ProtectedRequest,
        cancel: &Arc<AtomicBool>,
    ) -> Result<Transferred, TransferError> {
        let record = { self.inner.lock().await.entries.get(job_id).cloned() }
            .ok_or(TransferError::Permanent(DownloadFailureCode::Unknown))?;
        let part = record.part();
        let target = record.target();
        let root = PathBuf::from(&record.root_path);
        let known_total = record.total_bytes;
        let mut partial = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
        if known_total > 0 && partial > known_total {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        if partial > 0 && partial == known_total {
            // Every byte arrived but the final rename did not happen.
            atomic_replace(&part, &target)
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
            let subtitle = self.fetch_latest_subtitle(job_id, &target, cancel).await;
            return Ok(Transferred {
                bytes: partial,
                total: known_total,
                validator: record.validator.clone(),
                subtitle,
            });
        }
        if partial > 0 && record.validator.is_none() {
            // Without ETag or Last-Modified there is no safe If-Range value.
            // Restarting loses bytes, appending could silently corrupt a film.
            partial = 0;
        }

        let mut opened = self
            .open_get(
                &request.url,
                &request.headers,
                (partial > 0).then_some(partial),
                record.validator.as_deref(),
            )
            .await?;
        let (content_range, response_validator) = loop {
            let response = &opened.response;
            let status = response.status().as_u16();
            classify_http_status(status)?;
            if partial > 0 && status == 200 {
                partial = 0;
            }
            let mut content_range = None;
            if status == 206 {
                let range = parse_content_range(response.headers().get(CONTENT_RANGE))
                    .ok_or(TransferError::Permanent(DownloadFailureCode::InvalidRange))?;
                let length_matches = response
                    .content_length()
                    .is_none_or(|length| length == range.end - range.start + 1);
                if range.start != partial
                    || (known_total > 0 && range.total != known_total)
                    || !length_matches
                {
                    return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
                }
                content_range = Some(range);
            }
            let response_validator = response
                .headers()
                .get(ETAG)
                .or_else(|| response.headers().get(LAST_MODIFIED))
                .and_then(|v| v.to_str().ok())
                .map(str::to_owned);
            if partial > 0 {
                if let (Some(saved), Some(fresh)) =
                    (record.validator.as_ref(), response_validator.as_ref())
                {
                    if saved != fresh {
                        return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
                    }
                }
            }
            if partial > 0 && status == 206 && opened.crossed_origin {
                // A ranged answer from another origin may describe a different
                // object. Take the whole file from the original URL instead.
                opened = self
                    .open_get(&request.url, &request.headers, None, None)
                    .await?;
                partial = 0;
                continue;
            }
            break (content_range, response_validator);
        };
        let response = opened.response;
        let total = match content_range {
            Some(range) => range.total,
            None => response
                .content_length()
                .unwrap_or(record.media.video_size.unwrap_or(0)),
        };
        self.update_response_metadata(job_id, response_validator.clone(), total)
            .await
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::Unknown))?;

        fs::create_dir_all(&root)
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        if available_bytes(&root)
            .is_some_and(|free| !has_sufficient_space(free, total.saturating_sub(partial)))
        {
            return Err(TransferError::Permanent(DownloadFailureCode::StorageFull));
        }
        let mut file = if partial == 0 {
            OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(true)
                .open(&part)
        } else {
            OpenOptions::new().write(true).open(&part)
        }
        .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        if partial > 0 {
            file.seek(SeekFrom::Start(partial))
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        }

        let started = Instant::now();
        let mut rate = RateWindow::new(RATE_WINDOW, started, partial);
        let mut written = partial;
        let mut last_progress = started;
        let mut last_durable = started;
        let mut last_space_check = started;
        let mut unchecked_bytes = 0u64;
        let mut idle = Duration::ZERO;
        let mut stream = response.bytes_stream();
        loop {
            if cancel.load(Ordering::SeqCst) {
                return Err(TransferError::Paused);
            }
            let next = match tokio::time::timeout(CANCEL_POLL, stream.next()).await {
                Ok(next) => next,
                Err(_) => {
                    idle += CANCEL_POLL;
                    if idle >= READ_TIMEOUT {
                        return Err(TransferError::Retryable(DownloadFailureCode::Network));
                    }
                    continue;
                }
            };
            idle = Duration::ZERO;
            let Some(chunk) = next else { break };
            let chunk =
                chunk.map_err(|_| TransferError::Retryable(DownloadFailureCode::Network))?;
            let now = Instant::now();
            unchecked_bytes += chunk.len() as u64;
            // Rechecked every few megabytes rather than on every chunk: the
            // reserve absorbs what lands in between, and a response that never
            // states its size still cannot eat into the reserve.
            if unchecked_bytes >= SPACE_CHECK_BYTES
                || now.duration_since(last_space_check) >= PROGRESS_INTERVAL
            {
                let needed = total.saturating_sub(written).max(chunk.len() as u64);
                if available_bytes(&root).is_some_and(|free| !has_sufficient_space(free, needed)) {
                    return Err(TransferError::Permanent(DownloadFailureCode::StorageFull));
                }
                unchecked_bytes = 0;
                last_space_check = now;
            }
            if cancel.load(Ordering::SeqCst) {
                return Err(TransferError::Paused);
            }
            file.write_all(&chunk)
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
            written += chunk.len() as u64;
            if now.duration_since(last_progress) >= PROGRESS_INTERVAL {
                let durable = now.duration_since(last_durable) >= DURABLE_INTERVAL;
                if durable {
                    file.sync_data()
                        .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
                    last_durable = now;
                }
                // Persisting only after a flush keeps the stored byte count at
                // or below what is actually on disk.
                self.update_progress(job_id, written, total, rate.record(now, written), durable)
                    .await;
                last_progress = now;
            }
        }
        if cancel.load(Ordering::SeqCst) {
            return Err(TransferError::Paused);
        }
        file.sync_all()
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        drop(file);
        if total > 0 && written < total {
            return Err(TransferError::Retryable(DownloadFailureCode::Network));
        }
        if total > 0 && written > total {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        atomic_replace(&part, &target)
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        let subtitle = self.fetch_latest_subtitle(job_id, &target, cancel).await;
        Ok(Transferred {
            bytes: written,
            total: total.max(written),
            validator: response_validator,
            subtitle,
        })
    }

    /// GET with the source's headers, following redirects by hand: at most
    /// five hops, never out of https, and the protected headers stop at the
    /// first hop that leaves the original origin.
    async fn open_get(
        &self,
        url: &str,
        headers: &HashMap<String, String>,
        range_start: Option<u64>,
        validator: Option<&str>,
    ) -> Result<OpenedResponse, TransferError> {
        let mut current = Url::parse(url)
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::SourceRejected))?;
        let protected = header_map(headers)?;
        let mut forward_headers = true;
        let mut crossed_origin = false;
        let mut hops = 0;
        loop {
            let mut builder = self.client.get(current.clone());
            if forward_headers {
                builder = builder.headers(protected.clone());
            }
            if let Some(start) = range_start {
                builder = builder.header(RANGE, format!("bytes={start}-"));
                if let Some(value) = validator {
                    builder = builder.header(IF_RANGE, value);
                }
            }
            let response = builder
                .send()
                .await
                .map_err(|_| TransferError::Retryable(DownloadFailureCode::Network))?;
            if !is_redirect_status(response.status().as_u16()) {
                return Ok(OpenedResponse {
                    response,
                    crossed_origin,
                });
            }
            let target = response
                .headers()
                .get(LOCATION)
                .and_then(|value| value.to_str().ok())
                .and_then(|location| next_redirect_target(&current, location, hops))
                .ok_or(TransferError::Permanent(
                    DownloadFailureCode::SourceRejected,
                ))?;
            if !target.same_origin {
                forward_headers = false;
                crossed_origin = true;
            }
            current = target.url;
            hops += 1;
        }
    }

    /// The subtitle the request carries now: it may have been attached after
    /// the transfer started.
    async fn fetch_latest_subtitle(
        &self,
        job_id: &str,
        target: &Path,
        cancel: &Arc<AtomicBool>,
    ) -> Option<(String, String)> {
        if cancel.load(Ordering::SeqCst) {
            return None;
        }
        let subtitle = self.read_request(job_id).ok()?.subtitle?;
        self.download_subtitle(&subtitle, target, cancel).await
    }

    /// Saves a sidecar beside `target` as `<video stem>.<lang>.<ext>`, through
    /// a `.part` file so a failed fetch never leaves half a subtitle behind.
    async fn download_subtitle(
        &self,
        subtitle: &DownloadSubtitleRequest,
        target: &Path,
        cancel: &Arc<AtomicBool>,
    ) -> Option<(String, String)> {
        let language = safe_language(&subtitle.lang);
        let stem = target.file_stem()?.to_string_lossy().into_owned();
        let name = format!("{stem}.{language}.{}", subtitle_extension(&subtitle.url));
        if !is_safe_file_name(&name) {
            return None;
        }
        let path = target.parent()?.join(&name);
        let temporary = part_path(&path);
        let saved = self
            .write_subtitle(subtitle, &temporary, cancel)
            .await
            .is_some()
            && atomic_replace(&temporary, &path).is_ok();
        if !saved {
            let _ = fs::remove_file(&temporary);
            return None;
        }
        Some((name, language))
    }

    async fn write_subtitle(
        &self,
        subtitle: &DownloadSubtitleRequest,
        temporary: &Path,
        cancel: &Arc<AtomicBool>,
    ) -> Option<()> {
        let response = self
            .open_get(&subtitle.url, &subtitle.headers, None, None)
            .await
            .ok()?
            .response;
        if response.status().as_u16() != 200
            || response
                .content_length()
                .is_some_and(|length| length > MAX_SUBTITLE_BYTES)
        {
            return None;
        }
        let mut file = fs::File::create(temporary).ok()?;
        let mut written = 0u64;
        let mut stream = response.bytes_stream();
        while let Some(chunk) = tokio::time::timeout(READ_TIMEOUT, stream.next())
            .await
            .ok()?
        {
            if cancel.load(Ordering::SeqCst) {
                return None;
            }
            let chunk = chunk.ok()?;
            written += chunk.len() as u64;
            if written > MAX_SUBTITLE_BYTES {
                return None;
            }
            file.write_all(&chunk).ok()?;
        }
        file.sync_all().ok()
    }

    async fn save_completed_subtitle(
        &self,
        job_id: &str,
        subtitle: &DownloadSubtitleRequest,
        target: &Path,
    ) -> Result<(), String> {
        let cancel = Arc::new(AtomicBool::new(false));
        let Some((name, lang)) = self.download_subtitle(subtitle, target, &cancel).await else {
            return Ok(());
        };
        let view = {
            let mut inner = self.inner.lock().await;
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return Ok(());
            };
            entry.subtitle_file_name = Some(name);
            entry.subtitle_lang = Some(lang);
            entry.updated_at = now_ms();
            inner.visible_view(job_id)
        };
        self.persist().await?;
        if let Some(view) = view {
            self.emit_changed(view);
        }
        self.delete_request(job_id);
        Ok(())
    }

    async fn update_progress(
        &self,
        job_id: &str,
        bytes: u64,
        total: u64,
        rate: u64,
        persist: bool,
    ) {
        let view = {
            let mut inner = self.inner.lock().await;
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return;
            };
            entry.downloaded_bytes = bytes;
            if total > 0 {
                entry.total_bytes = total;
            }
            entry.bytes_per_second = rate;
            entry.updated_at = now_ms();
            // A transfer winding down after an account switch still reports
            // once; that report must not reach the new account's list.
            inner.visible_view(job_id)
        };
        if persist {
            let _ = self.persist().await;
        }
        if let Some(view) = view {
            self.emit_changed(view);
        }
    }

    async fn update_response_metadata(
        &self,
        job_id: &str,
        validator: Option<String>,
        total: u64,
    ) -> Result<(), String> {
        {
            let mut inner = self.inner.lock().await;
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return Ok(());
            };
            if validator.is_some() {
                entry.validator = validator;
            }
            if total > 0 {
                entry.total_bytes = total;
            }
            entry.updated_at = now_ms();
        }
        self.persist().await
    }

    async fn persist(&self) -> Result<(), String> {
        let _gate = self.persist_gate.lock().await;
        let entries = {
            self.inner
                .lock()
                .await
                .entries
                .values()
                .cloned()
                .collect::<Vec<_>>()
        };
        write_json(
            &self.index_path,
            &PersistedIndex {
                version: 1,
                entries,
            },
        )
    }
    fn persist_blocking(&self) -> Result<(), String> {
        let entries = self
            .inner
            .blocking_lock()
            .entries
            .values()
            .cloned()
            .collect::<Vec<_>>();
        write_json(
            &self.index_path,
            &PersistedIndex {
                version: 1,
                entries,
            },
        )
    }
    fn write_request(&self, job_id: &str, request: &ProtectedRequest) -> Result<(), String> {
        let raw = serde_json::to_vec(request).map_err(|e| e.to_string())?;
        let encrypted = protect(&raw).map_err(|e| {
            format!(
                "Windows could not protect the download request (error {}).",
                os_error_code(&e)
            )
        })?;
        let path = request_path(&self.vault_dir, job_id);
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, encrypted).map_err(|e| e.to_string())?;
        atomic_replace(&tmp, &path)
    }
    fn read_request(&self, job_id: &str) -> Result<ProtectedRequest, String> {
        let raw = fs::read(request_path(&self.vault_dir, job_id)).map_err(|e| e.to_string())?;
        let clear = unprotect(&raw).map_err(|e| {
            format!(
                "Windows could not read the protected download request (error {}).",
                os_error_code(&e)
            )
        })?;
        serde_json::from_slice(&clear).map_err(|e| e.to_string())
    }
    fn delete_request(&self, job_id: &str) {
        delete_request_file(&self.vault_dir, job_id);
    }
    fn emit_changed(&self, view: DownloadView) {
        let _ = self.app.emit(CHANGED_EVENT, view);
    }
    fn emit_removed(&self, job_id: &str) {
        let _ = self
            .app
            .emit(REMOVED_EVENT, serde_json::json!({ "job_id": job_id }));
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct DirectoryInfo {
    pub path: String,
    pub exists: bool,
    pub free_bytes: Option<u64>,
}

#[derive(Debug, Clone, Serialize)]
pub struct PlaybackFiles {
    pub video_path: String,
    pub subtitle_path: Option<String>,
}

/// Download state lives in local app data, beside the webview's own data.
/// Earlier builds kept it in roaming app data, so that is moved over first.
pub fn load_manager(app: &AppHandle) -> Result<DownloadManager, String> {
    let data_dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    let roaming = app.path().app_data_dir().map_err(|e| e.to_string())?;
    if let Err(error) = relocate::move_roaming_state(&roaming, &data_dir) {
        // Everything is still usable where it was, so keep using it; a later
        // launch tries the move again.
        eprintln!(
            "download state stays in {} for now: {error}",
            roaming.display()
        );
        return DownloadManager::new(app.clone(), roaming);
    }
    DownloadManager::new(app.clone(), data_dir)
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn recovered_status(status: &DownloadStatus, explicit_pause: bool) -> DownloadStatus {
    match status {
        DownloadStatus::Downloading if explicit_pause => DownloadStatus::Paused,
        DownloadStatus::Downloading => DownloadStatus::Queued,
        _ => status.clone(),
    }
}
fn next_queued_job(entries: &HashMap<String, DownloadRecord>, account: &str) -> Option<String> {
    entries
        .values()
        .filter(|entry| {
            entry.account_key == account
                && entry.status == DownloadStatus::Queued
                && !entry.explicit_pause
                && !entry.pending_deletion
                && !is_hidden_backup(entries, &entry.job_id)
        })
        .min_by_key(|entry| (entry.created_at, entry.job_id.clone()))
        .map(|entry| entry.job_id.clone())
}

/// Completes removals a previous run tombstoned but did not finish. A backup
/// is only tombstoned after its replacement's file is complete, so the
/// replacement that names it is finalised as done here too.
fn finish_pending_deletions(
    entries: &mut HashMap<String, DownloadRecord>,
    resolved_roots: &[PathBuf],
    vault_dir: &Path,
) {
    let pending: Vec<String> = entries
        .values()
        .filter(|entry| entry.pending_deletion)
        .map(|entry| entry.job_id.clone())
        .collect();
    for job_id in pending {
        let Some(record) = entries.get(&job_id).cloned() else {
            continue;
        };
        let owner_id = entries
            .values()
            .find(|entry| {
                entry
                    .replacement
                    .as_ref()
                    .is_some_and(|backup| backup.job_id == job_id)
            })
            .map(|entry| entry.job_id.clone());
        let owner_files = owner_id
            .as_ref()
            .and_then(|id| entries.get(id))
            .map(DownloadRecord::files);
        if let Some(owner) = owner_id.as_ref().and_then(|id| entries.get_mut(id)) {
            owner.status = DownloadStatus::Done;
            owner.failure = None;
            owner.explicit_pause = false;
            owner.bytes_per_second = 0;
            owner.replacement = None;
        }
        match remove_files(&record.files(), resolved_roots, owner_files.as_ref()) {
            // A containment failure forgets the record and leaves its files:
            // retrying every launch would never succeed, and deleting outside
            // the download folders is exactly what must not happen.
            Ok(()) | Err(RemoveError::Containment) => {
                delete_request_file(vault_dir, &job_id);
                entries.remove(&job_id);
            }
            Err(RemoveError::Io) => {}
        }
    }
}

/// Deletes a download's files, the playable one last so a failed auxiliary
/// cleanup still leaves the content in place. Paths `preserve` owns are
/// skipped; every other path must resolve inside an approved root.
fn remove_files(
    files: &FileSet,
    resolved_roots: &[PathBuf],
    preserve: Option<&FileSet>,
) -> Result<(), RemoveError> {
    let paths = std::iter::once(&files.part)
        .chain(files.subtitle.as_ref())
        .chain(std::iter::once(&files.target));
    for path in paths {
        if preserve.is_some_and(|owner| owner.contains(path)) {
            continue;
        }
        containment::ensure_within_roots(path, resolved_roots)
            .map_err(|_| RemoveError::Containment)?;
        match fs::remove_file(path) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(RemoveError::Io),
        }
    }
    Ok(())
}

async fn wait_unless_cancelled(cancel: &AtomicBool, delay: Duration) -> Result<(), TransferError> {
    let slice = Duration::from_millis(25);
    let mut waited = Duration::ZERO;
    while waited < delay {
        if cancel.load(Ordering::SeqCst) {
            return Err(TransferError::Paused);
        }
        tokio::time::sleep(slice).await;
        waited += slice;
    }
    Ok(())
}

fn classify_http_status(status: u16) -> Result<(), TransferError> {
    match status {
        200 | 206 => Ok(()),
        // A debrid link that stopped resolving answers 404 as often as 403,
        // and a retry of it can never succeed.
        401 | 403 | 404 => Err(TransferError::Permanent(DownloadFailureCode::SourceExpired)),
        416 => Err(TransferError::Permanent(DownloadFailureCode::InvalidRange)),
        408 | 425 | 429 | 500..=599 => Err(TransferError::Retryable(
            DownloadFailureCode::ServerUnavailable,
        )),
        _ => Err(TransferError::Permanent(
            DownloadFailureCode::SourceRejected,
        )),
    }
}
fn new_job_id() -> String {
    let sequence = JOB_COUNTER.fetch_add(1, Ordering::Relaxed);
    fingerprint(&format!(
        "{}:{}:{}",
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos(),
        std::process::id(),
        sequence,
    ))[..32]
        .to_string()
}
fn fingerprint(url: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(url.as_bytes());
    format!("{:x}", hasher.finalize())
}

/// A file name no other record and no file on disk already uses.
fn choose_file_name(
    entries: &HashMap<String, DownloadRecord>,
    media: &DownloadMedia,
    source_fingerprint: &str,
    account_key: &str,
    directory: &Path,
) -> Result<String, String> {
    for attempt in 1..=99 {
        let name = download_file_name(
            media.filename.as_deref(),
            &media.video_id,
            source_fingerprint,
            account_key,
            attempt,
        );
        if !is_safe_file_name(&name) {
            break;
        }
        let target = directory.join(&name);
        let taken = target.exists()
            || part_path(&target).exists()
            || entries
                .values()
                .any(|entry| same_path(&entry.target(), &target));
        if !taken {
            return Ok(name);
        }
    }
    Err("A safe download filename could not be created.".into())
}

fn validate_media(media: &DownloadMedia) -> Result<(), String> {
    if media.video_id.is_empty()
        || media.video_id.len() > 1024
        || media.item_id.is_empty()
        || media.item_id.len() > 1024
        || media.title.is_empty()
    {
        return Err("Complete media metadata is required for a download.".into());
    }
    Ok(())
}
fn validate_url(url: &str) -> Result<(), String> {
    if url.is_empty() || url.len() > 32_768 || url.contains('\0') {
        return Err("Choose a valid HTTP(S) source.".into());
    }
    let parsed = Url::parse(url).map_err(|_| "Choose a valid HTTP(S) source.".to_string())?;
    if !matches!(parsed.scheme(), "http" | "https") || parsed.host_str().is_none() {
        return Err("Choose a valid HTTP(S) source.".into());
    }
    Ok(())
}
fn validate_subtitle(subtitle: &DownloadSubtitleRequest) -> Result<(), String> {
    validate_url(&subtitle.url)?;
    validate_headers(&subtitle.headers)?;
    if subtitle.lang.is_empty() || subtitle.lang.chars().count() > 32 {
        return Err("The subtitle language is invalid.".into());
    }
    Ok(())
}
fn validate_artwork_url(url: &str) -> Result<(), String> {
    let valid = url.len() <= 8_192
        && Url::parse(url).is_ok_and(|parsed| {
            matches!(parsed.scheme(), "http" | "https") && parsed.host_str().is_some()
        });
    if valid {
        Ok(())
    } else {
        Err("The artwork address is invalid.".into())
    }
}
fn validate_headers(headers: &HashMap<String, String>) -> Result<(), String> {
    if headers.len() > 64 {
        return Err("The source supplied too many request headers.".into());
    }
    for (key, value) in headers {
        let name = HeaderName::from_bytes(key.as_bytes())
            .map_err(|_| "The source headers are invalid.".to_string())?;
        if key.len() > 128
            || value.len() > 8_192
            || matches!(
                name.as_str(),
                "connection"
                    | "content-length"
                    | "host"
                    | "if-range"
                    | "keep-alive"
                    | "proxy-authenticate"
                    | "proxy-authorization"
                    | "proxy-connection"
                    | "range"
                    | "te"
                    | "trailer"
                    | "transfer-encoding"
                    | "upgrade"
            )
        {
            return Err("The source headers are not safe for downloading.".into());
        }
        HeaderValue::from_str(value).map_err(|_| "The source headers are invalid.".to_string())?;
    }
    Ok(())
}
fn header_map(headers: &HashMap<String, String>) -> Result<HeaderMap, TransferError> {
    let mut map = HeaderMap::new();
    for (key, value) in headers {
        map.insert(
            HeaderName::from_bytes(key.as_bytes())
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::SourceRejected))?,
            HeaderValue::from_str(value)
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::SourceRejected))?,
        );
    }
    Ok(map)
}
fn has_sufficient_space(available: u64, needed: u64) -> bool {
    needed
        .checked_add(SPACE_RESERVE)
        .is_some_and(|required| available >= required)
}
fn parse_content_range(value: Option<&HeaderValue>) -> Option<ContentRange> {
    let raw = value?.to_str().ok()?.strip_prefix("bytes ")?;
    let (range, total) = raw.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    let range = ContentRange {
        start: start.parse().ok()?,
        end: end.parse().ok()?,
        total: total.parse().ok()?,
    };
    (range.end >= range.start && range.total > 0 && range.end < range.total).then_some(range)
}
fn canonical_directory(path: &Path) -> Result<PathBuf, String> {
    fs::create_dir_all(path).map_err(|e| format!("Could not create that folder: {e}"))?;
    fs::canonicalize(path)
        .map(strip_verbatim)
        .map_err(|e| format!("Could not use that folder: {e}"))
}
/// Moves `path` to the front of the approved roots, once.
fn remember_root(roots: &mut Vec<PathBuf>, path: &Path) {
    if !path.is_absolute() {
        return;
    }
    roots.retain(|root| !same_path(root, path));
    roots.insert(0, path.to_path_buf());
    roots.truncate(MAX_APPROVED_ROOTS);
}
/// The roots an index written before roots were tracked already uses.
fn seed_roots(entries: &HashMap<String, DownloadRecord>) -> Vec<PathBuf> {
    let mut roots = Vec::new();
    for entry in entries.values() {
        remember_root(&mut roots, Path::new(&entry.root_path));
        if let Some(backup) = entry.replacement.as_ref() {
            remember_root(&mut roots, Path::new(&backup.root_path));
        }
    }
    roots
}
fn config_document(directory: Option<&Path>, roots: &[PathBuf]) -> PersistedConfig {
    PersistedConfig {
        directory: directory.map(|path| path.to_string_lossy().into_owned()),
        approved_roots: Some(
            roots
                .iter()
                .map(|root| root.to_string_lossy().into_owned())
                .collect(),
        ),
    }
}
fn request_path(vault_dir: &Path, job_id: &str) -> PathBuf {
    vault_dir.join(format!("{job_id}.bin"))
}
fn delete_request_file(vault_dir: &Path, job_id: &str) {
    let _ = fs::remove_file(request_path(vault_dir, job_id));
}
fn available_bytes(path: &Path) -> Option<u64> {
    #[cfg(windows)]
    {
        use std::os::windows::ffi::OsStrExt;
        use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
        let text: Vec<u16> = path
            .as_os_str()
            .encode_wide()
            .chain(std::iter::once(0))
            .collect();
        let mut free = 0u64;
        let ok = unsafe {
            GetDiskFreeSpaceExW(
                text.as_ptr(),
                &mut free,
                std::ptr::null_mut(),
                std::ptr::null_mut(),
            )
        };
        if ok != 0 {
            Some(free)
        } else {
            None
        }
    }
    #[cfg(not(windows))]
    {
        let _ = path;
        None
    }
}
fn write_json<T: Serialize>(path: &Path, value: &T) -> Result<(), String> {
    let tmp = path.with_extension("tmp");
    let raw = serde_json::to_vec_pretty(value).map_err(|e| e.to_string())?;
    fs::write(&tmp, raw).map_err(|e| e.to_string())?;
    atomic_replace(&tmp, path)
}

fn load_index(path: &Path) -> Result<HashMap<String, DownloadRecord>, String> {
    if !path.exists() {
        return Ok(HashMap::new());
    }
    let raw = fs::read(path).map_err(|e| e.to_string())?;
    let parsed: PersistedIndex =
        serde_json::from_slice(&raw).map_err(|e| format!("download index is unreadable: {e}"))?;
    if parsed.version != 1 {
        return Err("download index version is unsupported".into());
    }
    Ok(parsed
        .entries
        .into_iter()
        .filter(is_safe_record_path)
        .map(|e| (e.job_id.clone(), e))
        .collect())
}
fn load_config(path: &Path) -> Option<PersistedConfig> {
    let raw = fs::read(path).ok()?;
    serde_json::from_slice(&raw).ok()
}

fn is_safe_record_path(entry: &DownloadRecord) -> bool {
    Path::new(&entry.root_path).is_absolute() && is_safe_file_name(&entry.file_name)
}


#[cfg(test)]
mod tests {
    use super::*;

    pub(super) fn record(root_path: String) -> DownloadRecord {
        DownloadRecord {
            job_id: "job".into(),
            account_key: fingerprint("server:user"),
            media: DownloadMedia {
                video_id: "movie:1".into(),
                item_id: "movie:1".into(),
                media_type: "movie".into(),
                meta_id: Some("1".into()),
                title: "Test".into(),
                show_name: None,
                episode_label: None,
                poster: None,
                landscape_artwork: None,
                addon_id: Some("addon".into()),
                binge_group: None,
                filename: Some("movie.mkv".into()),
                video_size: Some(100),
                video_hash: None,
                stream_name: None,
                stream_title: None,
            },
            file_name: "movie.mkv".into(),
            subtitle_file_name: None,
            subtitle_lang: None,
            root_path,
            status: DownloadStatus::Queued,
            total_bytes: 0,
            downloaded_bytes: 0,
            validator: None,
            source_fingerprint: fingerprint("https://source.example/movie"),
            failure: None,
            explicit_pause: false,
            created_at: 1,
            updated_at: 1,
            bytes_per_second: 0,
            replacement: None,
            pending_deletion: false,
        }
    }

    pub(super) fn scratch_dir(name: &str) -> PathBuf {
        let directory = std::env::temp_dir().join(format!("halo-download-{name}-{}", new_job_id()));
        fs::create_dir_all(&directory).unwrap();
        directory
    }

    fn owned(job_id: &str, account: &str, video_id: &str, status: DownloadStatus) -> DownloadRecord {
        let mut entry = record("C:\\Downloads".into());
        entry.job_id = job_id.into();
        entry.account_key = account.into();
        entry.media.video_id = video_id.into();
        entry.status = status;
        entry
    }

    fn entries(records: Vec<DownloadRecord>) -> HashMap<String, DownloadRecord> {
        records.into_iter().map(|entry| (entry.job_id.clone(), entry)).collect()
    }

    #[test]
    fn adoption_moves_every_download_without_a_duplicate() {
        let plan = plan_adoption(
            &entries(vec![
                owned("d1", "device", "movie:1", DownloadStatus::Done),
                owned("d2", "device", "movie:2", DownloadStatus::Paused),
                owned("a1", "account", "movie:3", DownloadStatus::Done),
                owned("o1", "other", "movie:1", DownloadStatus::Done),
            ]),
            "device",
            "account",
        );
        assert_eq!(plan.rekey, vec!["d1".to_string(), "d2".to_string()]);
        assert!(plan.discard.is_empty());
    }

    #[test]
    fn adoption_keeps_the_finished_copy_of_a_duplicate() {
        let plan = plan_adoption(
            &entries(vec![
                // The device finished, the account did not: the device copy wins.
                owned("d1", "device", "movie:1", DownloadStatus::Done),
                owned("a1", "account", "movie:1", DownloadStatus::Paused),
                // Both finished: the account keeps its own.
                owned("d2", "device", "movie:2", DownloadStatus::Done),
                owned("a2", "account", "movie:2", DownloadStatus::Done),
                // Neither finished: the account keeps its own.
                owned("d3", "device", "movie:3", DownloadStatus::Failed),
                owned("a3", "account", "movie:3", DownloadStatus::Queued),
            ]),
            "device",
            "account",
        );
        assert_eq!(plan.rekey, vec!["d1".to_string()]);
        assert_eq!(
            plan.discard,
            vec!["a1".to_string(), "d2".to_string(), "d3".to_string()]
        );
    }

    #[test]
    fn adoption_discards_a_losing_downloads_backup_with_it() {
        let mut replacing = owned("d1", "device", "movie:1", DownloadStatus::Paused);
        let backup = owned("d0", "device", "movie:1", DownloadStatus::Done);
        replacing.replacement = Some(ReplacementBackup::of(&backup));
        let plan = plan_adoption(
            &entries(vec![
                replacing,
                backup,
                owned("a1", "account", "movie:1", DownloadStatus::Done),
            ]),
            "device",
            "account",
        );
        assert!(plan.rekey.is_empty());
        assert_eq!(plan.discard, vec!["d0".to_string(), "d1".to_string()]);
    }

    #[test]
    fn adopting_into_the_same_partition_changes_nothing() {
        let plan = plan_adoption(
            &entries(vec![owned("d1", "device", "movie:1", DownloadStatus::Done)]),
            "device",
            "device",
        );
        assert_eq!(plan, AdoptionPlan::default());
    }

    #[test]
    fn source_fingerprint_is_stable_and_url_free() {
        let value = fingerprint("https://example.test/a");
        assert_eq!(value.len(), 64);
        assert!(!value.contains("example"));
    }
    #[test]
    fn rejects_non_http_sources() {
        assert!(validate_url("file:///tmp/movie.mkv").is_err());
        assert!(validate_url("https://example.test/movie.mkv").is_ok());
    }
    #[test]
    fn content_range_is_parsed_and_impossible_ranges_are_refused() {
        let value = HeaderValue::from_static("bytes 10-99/100");
        assert_eq!(
            parse_content_range(Some(&value)),
            Some(ContentRange {
                start: 10,
                end: 99,
                total: 100
            })
        );
        for invalid in [
            "bytes 10-x/100",
            "bytes 10-100/100",
            "bytes 50-10/100",
            "bytes 0-0/0",
        ] {
            let value = HeaderValue::from_static(invalid);
            assert_eq!(parse_content_range(Some(&value)), None, "{invalid}");
        }
    }

    #[test]
    fn index_never_serializes_the_protected_source() {
        let document = PersistedIndex {
            version: 1,
            entries: vec![record(r"C:\missing-drive\Halo".into())],
        };
        let raw = serde_json::to_string(&document).unwrap();
        assert!(!raw.contains("source.example"));
        assert!(!raw.contains("authorization"));
    }

    #[test]
    fn an_index_written_before_replacements_existed_still_loads() {
        let mut value = serde_json::to_value(record(r"C:\Halo".into())).unwrap();
        let object = value.as_object_mut().unwrap();
        object.remove("replacement");
        object.remove("pending_deletion");
        object["media"]
            .as_object_mut()
            .unwrap()
            .remove("landscape_artwork");
        let parsed: DownloadRecord = serde_json::from_value(value).unwrap();
        assert!(parsed.replacement.is_none());
        assert!(!parsed.pending_deletion);
        let config: PersistedConfig = serde_json::from_str(r#"{"directory":"D:\\Halo"}"#).unwrap();
        assert_eq!(config.directory.as_deref(), Some(r"D:\Halo"));
        assert!(config.approved_roots.is_none());
    }

    #[test]
    #[ignore = "requires a Windows process with the interactive user profile loaded"]
    fn protected_requests_round_trip_through_the_os_vault() {
        let request = ProtectedRequest {
            url: "https://source.example/movie".into(),
            headers: HashMap::from([("authorization".into(), "Bearer private".into())]),
            subtitle: None,
        };
        let raw = serde_json::to_vec(&request).unwrap();
        let encrypted = protect(&raw).unwrap();
        assert_ne!(encrypted, raw);
        assert_eq!(unprotect(&encrypted).unwrap(), raw);
    }

    #[test]
    fn caller_cannot_override_range_or_transport_headers() {
        for denied in ["Range", "Host", "Proxy-Authorization", "Keep-Alive"] {
            assert!(
                validate_headers(&HashMap::from([(denied.into(), "x".into())])).is_err(),
                "{denied}"
            );
        }
        assert!(validate_headers(&HashMap::from([(
            "Authorization".into(),
            "Bearer source-token".into(),
        )]))
        .is_ok());
        assert!(validate_headers(&HashMap::from([("X-Key".into(), "a\r\nb".into())])).is_err());
    }

    #[test]
    fn subtitle_requests_need_a_short_language() {
        let subtitle = |lang: &str| DownloadSubtitleRequest {
            url: "https://subs.test/a.srt".into(),
            lang: lang.into(),
            id: "1".into(),
            headers: HashMap::new(),
        };
        assert!(validate_subtitle(&subtitle("en")).is_ok());
        assert!(validate_subtitle(&subtitle("")).is_err());
        assert!(validate_subtitle(&subtitle(&"e".repeat(33))).is_err());
    }

    #[test]
    fn media_without_identity_is_refused() {
        let mut media = record(r"C:\Halo".into()).media;
        assert!(validate_media(&media).is_ok());
        media.title.clear();
        assert!(validate_media(&media).is_err());
    }

    #[test]
    fn job_ids_are_unique_and_do_not_expose_timestamps() {
        let first = new_job_id();
        let second = new_job_id();
        assert_ne!(first, second);
        assert_eq!(first.len(), 32);
        assert!(first.chars().all(|character| character.is_ascii_hexdigit()));
    }

    #[test]
    fn missing_download_roots_keep_safe_index_records() {
        let entry = record(r"C:\missing-drive\Halo".into());
        assert!(is_safe_record_path(&entry));
        let mut nested = entry.clone();
        nested.file_name = r"..\escape.mkv".into();
        assert!(!is_safe_record_path(&nested));
    }

    #[test]
    fn relaunch_requeues_only_non_explicit_active_work() {
        assert_eq!(
            recovered_status(&DownloadStatus::Downloading, false),
            DownloadStatus::Queued,
        );
        assert_eq!(
            recovered_status(&DownloadStatus::Downloading, true),
            DownloadStatus::Paused,
        );
        assert_eq!(
            recovered_status(&DownloadStatus::Done, false),
            DownloadStatus::Done,
        );
    }

    #[test]
    fn queue_is_account_scoped_and_oldest_first() {
        let mut newer = record(r"C:\Halo".into());
        newer.job_id = "newer".into();
        newer.created_at = 2;
        let mut older = record(r"C:\Halo".into());
        older.job_id = "older".into();
        older.created_at = 1;
        let mut other_account = record(r"C:\Halo".into());
        other_account.job_id = "other".into();
        other_account.account_key = "other-account".into();
        other_account.created_at = 0;
        let account = older.account_key.clone();
        let entries = HashMap::from([
            (newer.job_id.clone(), newer),
            (older.job_id.clone(), older),
            (other_account.job_id.clone(), other_account),
        ]);
        assert_eq!(
            next_queued_job(&entries, &account).as_deref(),
            Some("older")
        );
    }

    #[test]
    fn backups_and_tombstones_are_neither_queued_nor_listed() {
        let mut backup = record(r"C:\Halo".into());
        backup.job_id = "backup".into();
        backup.created_at = 1;
        let mut replacement = record(r"C:\Halo".into());
        replacement.job_id = "replacement".into();
        replacement.created_at = 3;
        replacement.status = DownloadStatus::Paused;
        replacement.replacement = Some(ReplacementBackup::of(&backup));
        let mut tombstone = record(r"C:\Halo".into());
        tombstone.job_id = "tombstone".into();
        tombstone.created_at = 2;
        tombstone.pending_deletion = true;
        let account = backup.account_key.clone();
        let inner = Inner {
            entries: HashMap::from([
                (backup.job_id.clone(), backup),
                (replacement.job_id.clone(), replacement),
                (tombstone.job_id.clone(), tombstone),
            ]),
            active_account: Some(account.clone()),
            active_job: None,
            cancel: HashMap::new(),
            directory: PathBuf::from(r"C:\Halo"),
            approved_roots: vec![PathBuf::from(r"C:\Halo")],
        };
        assert_eq!(next_queued_job(&inner.entries, &account), None);
        let listed: Vec<_> = inner
            .visible_views()
            .into_iter()
            .map(|view| view.job_id)
            .collect();
        assert_eq!(listed, vec!["replacement".to_string()]);
        assert_eq!(
            inner
                .visible_for_video(&account, "movie:1")
                .map(|record| record.job_id),
            Some("replacement".to_string())
        );
    }

    #[test]
    fn http_failures_are_sanitized_by_retry_policy() {
        assert!(matches!(
            classify_http_status(403),
            Err(TransferError::Permanent(DownloadFailureCode::SourceExpired)),
        ));
        assert!(matches!(
            classify_http_status(416),
            Err(TransferError::Permanent(DownloadFailureCode::InvalidRange)),
        ));
        assert!(matches!(
            classify_http_status(503),
            Err(TransferError::Retryable(
                DownloadFailureCode::ServerUnavailable
            )),
        ));
        assert!(matches!(
            classify_http_status(429),
            Err(TransferError::Retryable(
                DownloadFailureCode::ServerUnavailable
            )),
        ));
    }

    #[test]
    fn a_missing_file_needs_a_new_source_but_a_network_failure_does_not() {
        assert!(DownloadFailureCode::MissingFile.requires_new_source());
        assert!(DownloadFailureCode::InvalidRange.requires_new_source());
        assert!(!DownloadFailureCode::Network.requires_new_source());
        assert!(!DownloadFailureCode::StorageFull.requires_new_source());
    }

    #[test]
    fn free_space_keeps_the_reserve_and_never_overflows() {
        assert!(has_sufficient_space(SPACE_RESERVE + 10, 10));
        assert!(!has_sufficient_space(SPACE_RESERVE + 9, 10));
        assert!(!has_sufficient_space(u64::MAX, u64::MAX));
    }

    #[test]
    fn roots_are_remembered_once_newest_first_and_capped() {
        let mut roots = Vec::new();
        remember_root(&mut roots, Path::new(r"C:\A"));
        remember_root(&mut roots, Path::new(r"D:\B"));
        remember_root(&mut roots, Path::new(r"c:\a"));
        remember_root(&mut roots, Path::new("relative"));
        assert_eq!(roots, vec![PathBuf::from(r"c:\a"), PathBuf::from(r"D:\B")]);
        for index in 0..40 {
            remember_root(&mut roots, &PathBuf::from(format!(r"E:\{index}")));
        }
        assert_eq!(roots.len(), MAX_APPROVED_ROOTS);
    }

    #[test]
    fn file_names_skip_any_name_another_record_holds() {
        let directory = scratch_dir("names");
        let existing = record(directory.to_string_lossy().into_owned());
        let mut media = existing.media.clone();
        media.filename = Some("film.mkv".into());
        let first = choose_file_name(
            &HashMap::new(),
            &media,
            "a".repeat(64).as_str(),
            "b".repeat(64).as_str(),
            &directory,
        )
        .unwrap();
        let mut holder = existing;
        holder.file_name = first.clone();
        let entries = HashMap::from([(holder.job_id.clone(), holder)]);
        let second = choose_file_name(
            &entries,
            &media,
            "a".repeat(64).as_str(),
            "b".repeat(64).as_str(),
            &directory,
        )
        .unwrap();
        assert_ne!(first, second);
        assert!(second.ends_with("-2.mkv"));
        let _ = fs::remove_dir_all(directory);
    }

    #[test]
    fn deletion_outside_the_approved_roots_touches_nothing() {
        let approved = scratch_dir("approved");
        let outside = scratch_dir("outside");
        let victim = outside.join("keep.mkv");
        fs::write(&victim, b"precious").unwrap();
        let mut entry = record(outside.to_string_lossy().into_owned());
        entry.file_name = "keep.mkv".into();
        let result = remove_files(
            &entry.files(),
            &resolve_roots(std::slice::from_ref(&approved)),
            None,
        );
        assert_eq!(result, Err(RemoveError::Containment));
        assert!(victim.is_file());
        let _ = fs::remove_dir_all(approved);
        let _ = fs::remove_dir_all(outside);
    }

    #[test]
    fn an_interrupted_replacement_finishes_on_the_next_launch() {
        let root = scratch_dir("replacement");
        let root_text = root.to_string_lossy().into_owned();
        let mut old = record(root_text.clone());
        old.job_id = "old".into();
        old.file_name = "old.mkv".into();
        old.subtitle_file_name = Some("old.en.srt".into());
        old.status = DownloadStatus::Paused;
        old.pending_deletion = true;
        let mut new = record(root_text);
        new.job_id = "new".into();
        new.file_name = "new.mkv".into();
        new.status = DownloadStatus::Queued;
        new.replacement = Some(ReplacementBackup::of(&old));
        for name in ["old.mkv", "old.en.srt", "new.mkv"] {
            fs::write(root.join(name), b"x").unwrap();
        }
        let vault = scratch_dir("vault");
        fs::write(request_path(&vault, "old"), b"sealed").unwrap();
        let mut entries = HashMap::from([(old.job_id.clone(), old), (new.job_id.clone(), new)]);
        finish_pending_deletions(
            &mut entries,
            &resolve_roots(std::slice::from_ref(&root)),
            &vault,
        );
        assert!(!entries.contains_key("old"));
        let finished = &entries["new"];
        assert_eq!(finished.status, DownloadStatus::Done);
        assert!(finished.replacement.is_none());
        assert!(!root.join("old.mkv").exists());
        assert!(!root.join("old.en.srt").exists());
        assert!(root.join("new.mkv").is_file());
        assert!(!request_path(&vault, "old").exists());
        let _ = fs::remove_dir_all(root);
        let _ = fs::remove_dir_all(vault);
    }

    #[test]
    fn deleting_a_backup_spares_every_file_its_replacement_owns() {
        let root = scratch_dir("shared");
        let root_text = root.to_string_lossy().into_owned();
        let mut backup = record(root_text.clone());
        backup.file_name = "shared.mkv".into();
        let owner = record(root_text);
        fs::write(root.join("shared.mkv"), b"x").unwrap();
        let mut owner = owner;
        owner.file_name = "shared.mkv".into();
        let result = remove_files(
            &backup.files(),
            &resolve_roots(std::slice::from_ref(&root)),
            Some(&owner.files()),
        );
        assert_eq!(result, Ok(()));
        assert!(root.join("shared.mkv").is_file());
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn durable_json_writes_replace_existing_windows_files() {
        let directory = scratch_dir("json");
        let path = directory.join("index.json");
        write_json(&path, &serde_json::json!({ "version": 1 })).unwrap();
        write_json(&path, &serde_json::json!({ "version": 2 })).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(value["version"], 2);
        let _ = fs::remove_dir_all(directory);
    }
}
