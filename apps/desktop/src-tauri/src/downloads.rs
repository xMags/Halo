use futures_util::StreamExt;
use reqwest::header::{
    HeaderMap, HeaderName, HeaderValue, CONTENT_RANGE, ETAG, IF_RANGE, LAST_MODIFIED, RANGE,
};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::collections::{HashMap, VecDeque};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};
use tokio::sync::Mutex;

const EVENT_NAME: &str = "download-changed";
const INDEX_FILE: &str = "downloads-index.json";
const CONFIG_FILE: &str = "downloads-config.json";
const DOWNLOAD_DIR: &str = "downloads";
const RETRIES: usize = 3;
const PROGRESS_INTERVAL: Duration = Duration::from_millis(250);
const SPACE_RESERVE: u64 = 64 * 1024 * 1024;
static JOB_COUNTER: AtomicU64 = AtomicU64::new(0);

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
    pub fn requires_new_source(&self) -> bool {
        matches!(
            self,
            Self::SourceExpired | Self::ProtectedRequestCorrupt | Self::InvalidRange
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
        PathBuf::from(format!("{}.part", self.target().display()))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
struct PersistedIndex {
    version: u32,
    entries: Vec<DownloadRecord>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct PersistedConfig {
    directory: String,
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

struct Inner {
    entries: HashMap<String, DownloadRecord>,
    active_account: Option<String>,
    active_job: Option<String>,
    cancel: HashMap<String, Arc<AtomicBool>>,
    queue: VecDeque<String>,
    directory: PathBuf,
}

pub struct DownloadManager {
    app: AppHandle,
    index_path: PathBuf,
    config_path: PathBuf,
    vault_dir: PathBuf,
    client: reqwest::Client,
    inner: Mutex<Inner>,
}

impl DownloadManager {
    pub fn new(app: AppHandle, data_dir: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&data_dir).map_err(|e| format!("download state unavailable: {e}"))?;
        let index_path = data_dir.join(INDEX_FILE);
        let config_path = data_dir.join(CONFIG_FILE);
        let vault_dir = data_dir.join("download-requests");
        fs::create_dir_all(&vault_dir).map_err(|e| format!("download vault unavailable: {e}"))?;
        let entries = load_index(&index_path)?;
        let configured_directory = load_config(&config_path);
        let directory = configured_directory
            .clone()
            .unwrap_or_else(|| data_dir.join(DOWNLOAD_DIR));
        if configured_directory.is_none() {
            fs::create_dir_all(&directory)
                .map_err(|e| format!("download directory unavailable: {e}"))?;
        }
        let mut entries = entries;
        for entry in entries.values_mut() {
            let recovered = recovered_status(&entry.status, entry.explicit_pause);
            if recovered != entry.status {
                entry.status = recovered;
                entry.bytes_per_second = 0;
                entry.updated_at = now_ms();
            }
        }
        let manager = Self {
            app,
            index_path,
            config_path,
            vault_dir,
            client: reqwest::Client::builder()
                .redirect(reqwest::redirect::Policy::limited(5))
                .connect_timeout(Duration::from_secs(30))
                .build()
                .map_err(|e| format!("HTTP client unavailable: {e}"))?,
            inner: Mutex::new(Inner {
                entries,
                active_account: None,
                active_job: None,
                cancel: HashMap::new(),
                queue: VecDeque::new(),
                directory,
            }),
        };
        manager.persist_blocking()?;
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
            inner
                .entries
                .values()
                .filter(|e| e.account_key == inner.active_account.clone().unwrap())
                .map(DownloadRecord::view)
                .collect::<Vec<_>>()
        };
        self.persist().await?;
        self.kick();
        Ok(views)
    }

    pub async fn clear_account(&self) -> Result<(), String> {
        let active = {
            self.inner
                .lock()
                .await
                .cancel
                .values()
                .cloned()
                .collect::<Vec<_>>()
        };
        for flag in active {
            flag.store(true, Ordering::SeqCst);
        }
        let mut inner = self.inner.lock().await;
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

    pub async fn list(&self) -> Vec<DownloadView> {
        let inner = self.inner.lock().await;
        let Some(account) = inner.active_account.as_ref() else {
            return Vec::new();
        };
        let mut views: Vec<_> = inner
            .entries
            .values()
            .filter(|e| &e.account_key == account)
            .map(DownloadRecord::view)
            .collect();
        views.sort_by_key(|e| (e.created_at, e.job_id.clone()));
        views
    }

    pub async fn start(
        self: &Arc<Self>,
        request: DownloadStartRequest,
    ) -> Result<DownloadView, String> {
        let account = { self.inner.lock().await.active_account.clone() }
            .ok_or_else(|| "Sign in before downloading.".to_string())?;
        validate_url(&request.url)?;
        validate_headers(&request.headers)?;
        if let Some(subtitle) = request.subtitle.as_ref() {
            validate_url(&subtitle.url)?;
            validate_headers(&subtitle.headers)?;
        }
        let fingerprint = fingerprint(&request.url);
        let (existing, directory) = {
            let inner = self.inner.lock().await;
            (
                inner
                    .entries
                    .values()
                    .find(|e| {
                        e.account_key == account && e.media.video_id == request.media.video_id
                    })
                    .cloned(),
                inner.directory.clone(),
            )
        };
        if !directory.is_dir() {
            return Err(
                "The download folder is unavailable. Choose another folder to continue.".into(),
            );
        }
        if let Some(ref existing) = existing {
            if !request.replace_existing && existing.source_fingerprint != fingerprint {
                return Err("A different source is already saved for this video. Confirm replacement first.".into());
            }
            if !request.replace_existing && existing.status != DownloadStatus::Failed {
                return Ok(existing.view());
            }
            if !request.replace_existing
                && existing
                    .failure
                    .as_ref()
                    .is_none_or(|failure| !failure.requires_new_source())
            {
                self.resume(&existing.job_id).await?;
                let inner = self.inner.lock().await;
                return inner
                    .entries
                    .get(&existing.job_id)
                    .map(DownloadRecord::view)
                    .ok_or_else(|| "Download state changed. Try again.".into());
            }
        }
        if let Some(size) = request.media.video_size {
            if let Some(free) = available_bytes(&directory) {
                if free < size.saturating_add(SPACE_RESERVE) {
                    return Err("There is not enough free space for this video.".into());
                }
            }
        }
        let job_id = new_job_id();
        let file_name = unique_file_name(
            &request.media,
            &request.url,
            &request.media.video_id,
            &directory,
        )?;
        let protected = ProtectedRequest {
            url: request.url,
            headers: request.headers,
            subtitle: request.subtitle,
        };
        self.write_request(&job_id, &protected)?;
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
        };
        {
            let mut inner = self.inner.lock().await;
            if let Some(old) = existing.clone() {
                if request.replace_existing {
                    if let Some(flag) = inner.cancel.remove(&old.job_id) {
                        flag.store(true, Ordering::SeqCst);
                    }
                    let _ = fs::remove_file(old.target());
                    let _ = fs::remove_file(old.part());
                    let _ = self.delete_request(&old.job_id);
                    inner.entries.remove(&old.job_id);
                }
            }
            inner.queue.push_back(job_id.clone());
            inner.entries.insert(job_id.clone(), entry.clone());
        }
        self.persist().await?;
        self.emit(&entry).await;
        self.kick();
        Ok(entry.view())
    }

    pub async fn pause(&self, job_id: &str) -> Result<(), String> {
        let mut inner = self.inner.lock().await;
        let flag = inner.cancel.get(job_id).cloned();
        let Some(entry) = inner.entries.get_mut(job_id) else {
            return Ok(());
        };
        if entry.status == DownloadStatus::Done {
            return Ok(());
        }
        entry.status = DownloadStatus::Paused;
        entry.explicit_pause = true;
        entry.bytes_per_second = 0;
        entry.updated_at = now_ms();
        let view = entry.view();
        if let Some(flag) = flag {
            flag.store(true, Ordering::SeqCst);
        }
        drop(inner);
        self.persist().await?;
        let _ = self.app.emit(EVENT_NAME, view);
        Ok(())
    }

    pub async fn resume(self: &Arc<Self>, job_id: &str) -> Result<(), String> {
        let mut inner = self.inner.lock().await;
        let Some(entry) = inner.entries.get_mut(job_id) else {
            return Ok(());
        };
        if entry.status == DownloadStatus::Done {
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
        entry.updated_at = now_ms();
        let view = entry.view();
        if !inner.queue.iter().any(|id| id == job_id) {
            inner.queue.push_back(job_id.to_string());
        }
        drop(inner);
        self.persist().await?;
        let _ = self.app.emit(EVENT_NAME, view);
        self.kick();
        Ok(())
    }

    pub async fn remove(&self, job_id: &str) -> Result<(), String> {
        let entry = {
            let mut inner = self.inner.lock().await;
            let entry = inner.entries.remove(job_id);
            if let Some(flag) = inner.cancel.remove(job_id) {
                flag.store(true, Ordering::SeqCst);
            }
            inner.queue.retain(|id| id != job_id);
            entry
        };
        if let Some(entry) = entry {
            if is_safe_record_path(&entry) {
                let _ = fs::remove_file(entry.target());
                let _ = fs::remove_file(entry.part());
                if let Some(sub) = entry.subtitle_file_name {
                    if is_safe_file_name(&sub) {
                        let _ = fs::remove_file(PathBuf::from(&entry.root_path).join(sub));
                    }
                }
            }
            self.delete_request(job_id)?;
            self.persist().await?;
        }
        Ok(())
    }

    pub async fn attach_subtitle(
        self: &Arc<Self>,
        job_id: &str,
        subtitle: DownloadSubtitleRequest,
    ) -> Result<(), String> {
        validate_url(&subtitle.url)?;
        validate_headers(&subtitle.headers)?;
        let completed_target = {
            let inner = self.inner.lock().await;
            let account = inner
                .active_account
                .as_ref()
                .ok_or_else(|| "Sign in before updating downloads.".to_string())?;
            let entry = inner
                .entries
                .get(job_id)
                .filter(|entry| &entry.account_key == account)
                .ok_or_else(|| "Download not found.".to_string())?;
            if entry.subtitle_file_name.is_some() {
                return Ok(());
            }
            (entry.status == DownloadStatus::Done).then(|| entry.target())
        };

        if let Some(target) = completed_target {
            self.save_completed_subtitle(job_id, &subtitle, &target)
                .await?;
            return Ok(());
        }

        let mut protected = match self.read_request(job_id) {
            Ok(protected) => protected,
            Err(_) => {
                let target = {
                    let inner = self.inner.lock().await;
                    inner
                        .entries
                        .get(job_id)
                        .filter(|entry| entry.status == DownloadStatus::Done)
                        .map(DownloadRecord::target)
                };
                if let Some(target) = target {
                    self.save_completed_subtitle(job_id, &subtitle, &target)
                        .await?;
                    return Ok(());
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
        {
            let mut inner = self.inner.lock().await;
            inner.directory = path.clone();
        }
        let config = PersistedConfig {
            directory: path.to_string_lossy().into_owned(),
        };
        write_json(&self.config_path, &config)?;
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

    pub async fn playback_path(&self, job_id: &str) -> Result<String, String> {
        let inner = self.inner.lock().await;
        let account = inner
            .active_account
            .as_ref()
            .ok_or_else(|| "Sign in before opening downloads.".to_string())?;
        let entry = inner
            .entries
            .get(job_id)
            .filter(|e| &e.account_key == account)
            .ok_or_else(|| "Download not found.".to_string())?;
        if !is_safe_record_path(entry)
            || entry.status != DownloadStatus::Done
            || !entry.target().is_file()
        {
            return Err(DownloadFailureCode::MissingFile.message().into());
        }
        Ok(entry.target().to_string_lossy().into_owned())
    }

    pub async fn playback_files(&self, job_id: &str) -> Result<PlaybackFiles, String> {
        let inner = self.inner.lock().await;
        let account = inner
            .active_account
            .as_ref()
            .ok_or_else(|| "Sign in before opening downloads.".to_string())?;
        let entry = inner
            .entries
            .get(job_id)
            .filter(|e| &e.account_key == account)
            .ok_or_else(|| "Download not found.".to_string())?;
        if !is_safe_record_path(entry)
            || entry.status != DownloadStatus::Done
            || !entry.target().is_file()
        {
            return Err(DownloadFailureCode::MissingFile.message().into());
        }
        Ok(PlaybackFiles {
            video_path: entry.target().to_string_lossy().into_owned(),
            subtitle_path: entry.subtitle_file_name.as_ref().map(|name| {
                PathBuf::from(&entry.root_path)
                    .join(name)
                    .to_string_lossy()
                    .into_owned()
            }),
        })
    }

    fn kick(self: &Arc<Self>) {
        let manager = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            manager.pump_once().await;
        });
    }

    async fn pump_once(self: &Arc<Self>) {
        let job_id = {
            let mut inner = self.inner.lock().await;
            if inner.active_job.is_some() {
                return;
            }
            let account = match inner.active_account.clone() {
                Some(a) => a,
                None => return,
            };
            let next = next_queued_job(&inner.entries, &account);
            let Some(job_id) = next else {
                return;
            };
            let flag = Arc::new(AtomicBool::new(false));
            inner.active_job = Some(job_id.clone());
            inner.cancel.insert(job_id.clone(), flag);
            if let Some(entry) = inner.entries.get_mut(&job_id) {
                entry.status = DownloadStatus::Downloading;
                entry.updated_at = now_ms();
            }
            job_id
        };
        let manager = Arc::clone(self);
        tauri::async_runtime::spawn(async move {
            manager.run(job_id).await;
        });
        let _ = self.persist().await;
    }

    async fn run(self: Arc<Self>, job_id: String) {
        let result = self.transfer_with_retries(&job_id).await;
        match result {
            Ok((bytes, total, validator, subtitle)) => {
                let mut inner = self.inner.lock().await;
                if let Some(entry) = inner.entries.get_mut(&job_id) {
                    entry.status = DownloadStatus::Done;
                    entry.downloaded_bytes = bytes;
                    entry.total_bytes = total;
                    entry.validator = validator;
                    entry.bytes_per_second = 0;
                    if let Some((name, lang)) = subtitle {
                        entry.subtitle_file_name = Some(name);
                        entry.subtitle_lang = Some(lang);
                    }
                    entry.failure = None;
                    entry.updated_at = now_ms();
                }
            }
            Err(TransferError::Paused) => {
                let mut inner = self.inner.lock().await;
                if let Some(entry) = inner.entries.get_mut(&job_id) {
                    entry.status = DownloadStatus::Paused;
                    entry.bytes_per_second = 0;
                    entry.updated_at = now_ms();
                }
            }
            Err(TransferError::Permanent(code)) => {
                let mut inner = self.inner.lock().await;
                if let Some(entry) = inner.entries.get_mut(&job_id) {
                    entry.status = DownloadStatus::Failed;
                    entry.failure = Some(code);
                    entry.bytes_per_second = 0;
                    entry.updated_at = now_ms();
                }
            }
            Err(TransferError::Retryable(code)) => {
                let mut inner = self.inner.lock().await;
                if let Some(entry) = inner.entries.get_mut(&job_id) {
                    entry.status = DownloadStatus::Failed;
                    entry.failure = Some(code);
                    entry.bytes_per_second = 0;
                    entry.updated_at = now_ms();
                }
            }
        }
        let view = {
            let mut inner = self.inner.lock().await;
            inner.cancel.remove(&job_id);
            inner.active_job = None;
            inner.entries.get(&job_id).map(DownloadRecord::view)
        };
        let _ = self.persist().await;
        if let Some(view) = view {
            let _ = self.app.emit(EVENT_NAME, view);
        }
        let should_delete_request = {
            let inner = self.inner.lock().await;
            inner.entries.get(&job_id).is_some_and(|entry| {
                entry.status == DownloadStatus::Done
                    || entry
                        .failure
                        .as_ref()
                        .is_some_and(DownloadFailureCode::requires_new_source)
            })
        };
        if should_delete_request {
            let _ = self.delete_request(&job_id);
        }
        self.kick();
    }

    async fn transfer_with_retries(
        &self,
        job_id: &str,
    ) -> Result<(u64, u64, Option<String>, Option<(String, String)>), TransferError> {
        let request = self
            .read_request(job_id)
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::ProtectedRequestCorrupt))?;
        for attempt in 0..=RETRIES {
            match self.transfer_once(job_id, &request).await {
                Ok(result) => return Ok(result),
                Err(TransferError::Paused) => return Err(TransferError::Paused),
                Err(TransferError::Permanent(code)) => return Err(TransferError::Permanent(code)),
                Err(TransferError::Retryable(_code)) if attempt < RETRIES => {
                    tokio::time::sleep(Duration::from_millis(250 * 2u64.pow(attempt as u32))).await;
                }
                Err(TransferError::Retryable(code)) => return Err(TransferError::Retryable(code)),
            }
        }
        Err(TransferError::Retryable(DownloadFailureCode::Network))
    }

    async fn transfer_once(
        &self,
        job_id: &str,
        request: &ProtectedRequest,
    ) -> Result<(u64, u64, Option<String>, Option<(String, String)>), TransferError> {
        let (part, target, declared, validator, cancel) = {
            let inner = self.inner.lock().await;
            let entry = inner
                .entries
                .get(job_id)
                .ok_or(TransferError::Permanent(DownloadFailureCode::Unknown))?;
            (
                entry.part(),
                entry.target(),
                entry.media.video_size.unwrap_or(0),
                entry.validator.clone(),
                inner
                    .cancel
                    .get(job_id)
                    .cloned()
                    .ok_or(TransferError::Paused)?,
            )
        };
        let mut partial = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
        if partial > 0 && validator.is_none() {
            // Without ETag or Last-Modified there is no safe If-Range value.
            // Restarting loses bytes, appending could silently corrupt a film.
            partial = 0;
        }
        let mut header_map = HeaderMap::new();
        for (key, value) in &request.headers {
            header_map.insert(
                HeaderName::from_bytes(key.as_bytes())
                    .map_err(|_| TransferError::Permanent(DownloadFailureCode::SourceRejected))?,
                HeaderValue::from_str(value)
                    .map_err(|_| TransferError::Permanent(DownloadFailureCode::SourceRejected))?,
            );
        }
        let mut builder = self.client.get(&request.url).headers(header_map);
        if partial > 0 {
            builder = builder.header(RANGE, format!("bytes={partial}-"));
            if let Some(value) = validator.as_ref() {
                builder = builder.header(IF_RANGE, value);
            }
        }
        let response = builder
            .send()
            .await
            .map_err(|_| TransferError::Retryable(DownloadFailureCode::Network))?;
        let status = response.status().as_u16();
        classify_http_status(status)?;
        if partial > 0 && status == 200 {
            partial = 0;
        }
        if partial > 0 && status != 206 {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        if partial > 0 {
            let Some((start, end, total)) =
                parse_content_range(response.headers().get(CONTENT_RANGE))
            else {
                return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
            };
            if start != partial || end < start || end >= total {
                return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
            }
        }
        let response_validator = response
            .headers()
            .get(ETAG)
            .or_else(|| response.headers().get(LAST_MODIFIED))
            .and_then(|v| v.to_str().ok())
            .map(str::to_owned);
        if partial > 0
            && validator.is_some()
            && response_validator.is_some()
            && response_validator != validator
        {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        if response_validator.is_some() {
            let mut inner = self.inner.lock().await;
            if let Some(entry) = inner.entries.get_mut(job_id) {
                entry.validator = response_validator.clone();
                entry.updated_at = now_ms();
            }
            drop(inner);
            self.persist()
                .await
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::Unknown))?;
        }
        let total = if status == 206 {
            parse_content_range_total(response.headers().get(CONTENT_RANGE))
                .unwrap_or(partial + response.content_length().unwrap_or(0))
        } else {
            response.content_length().unwrap_or(declared)
        };
        if partial == 0 {
            if let Some(parent) = part.parent() {
                fs::create_dir_all(parent)
                    .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
            }
        }
        let mut file = if partial == 0 {
            OpenOptions::new()
                .create(true)
                .write(true)
                .truncate(true)
                .open(&part)
        } else {
            OpenOptions::new().create(true).append(true).open(&part)
        }
        .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        let mut written = partial;
        let mut last_progress = SystemTime::now();
        let mut last_bytes = written;
        let mut stream = response.bytes_stream();
        loop {
            let next = tokio::time::timeout(Duration::from_secs(45), stream.next())
                .await
                .map_err(|_| TransferError::Retryable(DownloadFailureCode::Network))?;
            let Some(chunk) = next else { break };
            if cancel.load(Ordering::SeqCst) {
                return Err(TransferError::Paused);
            }
            let chunk =
                chunk.map_err(|_| TransferError::Retryable(DownloadFailureCode::Network))?;
            file.write_all(&chunk)
                .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
            written += chunk.len() as u64;
            let elapsed = last_progress.elapsed().unwrap_or_default();
            if elapsed >= PROGRESS_INTERVAL {
                let rate = ((written.saturating_sub(last_bytes)) as f64
                    / elapsed.as_secs_f64().max(0.001)) as u64;
                self.update_progress(job_id, written, total, rate).await;
                last_progress = SystemTime::now();
                last_bytes = written;
            }
        }
        if cancel.load(Ordering::SeqCst) {
            return Err(TransferError::Paused);
        }
        file.flush()
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        if total > 0 && written < total {
            return Err(TransferError::Retryable(DownloadFailureCode::Network));
        }
        if total > 0 && written > total {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        if target.exists() {
            let _ = fs::remove_file(&target);
        }
        fs::rename(&part, &target)
            .map_err(|_| TransferError::Permanent(DownloadFailureCode::StorageFull))?;
        let latest_request = self.read_request(job_id).ok();
        let subtitle = self
            .download_subtitle(
                latest_request
                    .as_ref()
                    .and_then(|latest| latest.subtitle.as_ref()),
                &target,
                &cancel,
            )
            .await;
        Ok((written, total.max(written), response_validator, subtitle))
    }

    async fn download_subtitle(
        &self,
        subtitle: Option<&DownloadSubtitleRequest>,
        target: &Path,
        cancel: &Arc<AtomicBool>,
    ) -> Option<(String, String)> {
        let subtitle = subtitle?;
        if cancel.load(Ordering::SeqCst) {
            return None;
        }
        let mut headers = HeaderMap::new();
        for (key, value) in &subtitle.headers {
            headers.insert(
                HeaderName::from_bytes(key.as_bytes()).ok()?,
                HeaderValue::from_str(value).ok()?,
            );
        }
        let response = self
            .client
            .get(&subtitle.url)
            .headers(headers)
            .send()
            .await
            .ok()?;
        if !response.status().is_success() {
            return None;
        }
        let extension = subtitle
            .url
            .split('?')
            .next()
            .and_then(|s| Path::new(s).extension())
            .and_then(|s| s.to_str())
            .filter(|s| matches!(*s, "srt" | "vtt" | "ass" | "ssa"))
            .unwrap_or("srt");
        let name = format!(
            "{}.{}.{}",
            target.file_stem()?.to_string_lossy(),
            subtitle.lang,
            extension
        );
        let path = target.parent()?.join(&name);
        let bytes = response.bytes().await.ok()?;
        fs::write(&path, bytes).ok()?;
        Some((name, subtitle.lang.clone()))
    }

    async fn save_completed_subtitle(
        &self,
        job_id: &str,
        subtitle: &DownloadSubtitleRequest,
        target: &Path,
    ) -> Result<(), String> {
        let cancel = Arc::new(AtomicBool::new(false));
        let Some((name, lang)) = self
            .download_subtitle(Some(subtitle), target, &cancel)
            .await
        else {
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
            entry.view()
        };
        self.persist().await?;
        let _ = self.app.emit(EVENT_NAME, view);
        let _ = self.delete_request(job_id);
        Ok(())
    }

    async fn update_progress(&self, job_id: &str, bytes: u64, total: u64, rate: u64) {
        let view = {
            let mut inner = self.inner.lock().await;
            let Some(entry) = inner.entries.get_mut(job_id) else {
                return;
            };
            entry.downloaded_bytes = bytes;
            entry.total_bytes = total;
            entry.bytes_per_second = rate;
            entry.updated_at = now_ms();
            entry.view()
        };
        let _ = self.persist().await;
        let _ = self.app.emit(EVENT_NAME, view);
    }
    async fn persist(&self) -> Result<(), String> {
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
    fn request_path(&self, job_id: &str) -> PathBuf {
        self.vault_dir.join(format!("{job_id}.bin"))
    }
    fn write_request(&self, job_id: &str, request: &ProtectedRequest) -> Result<(), String> {
        let raw = serde_json::to_vec(request).map_err(|e| e.to_string())?;
        let encrypted = protect(&raw)?;
        let path = self.request_path(job_id);
        let tmp = path.with_extension("tmp");
        fs::write(&tmp, encrypted).map_err(|e| e.to_string())?;
        atomic_replace(&tmp, &path)
    }
    fn read_request(&self, job_id: &str) -> Result<ProtectedRequest, String> {
        let raw = fs::read(self.request_path(job_id)).map_err(|e| e.to_string())?;
        let clear = unprotect(&raw)?;
        serde_json::from_slice(&clear).map_err(|e| e.to_string())
    }
    fn delete_request(&self, job_id: &str) -> Result<(), String> {
        let _ = fs::remove_file(self.request_path(job_id));
        Ok(())
    }
    async fn emit(&self, entry: &DownloadRecord) {
        let _ = self.app.emit(EVENT_NAME, entry.view());
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

pub fn load_manager(app: &AppHandle) -> Result<DownloadManager, String> {
    let data_dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
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
        })
        .min_by_key(|entry| (entry.created_at, entry.job_id.clone()))
        .map(|entry| entry.job_id.clone())
}
fn classify_http_status(status: u16) -> Result<(), TransferError> {
    match status {
        200 | 206 => Ok(()),
        401 | 403 | 404 => Err(TransferError::Permanent(DownloadFailureCode::SourceExpired)),
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
fn validate_url(url: &str) -> Result<(), String> {
    let parsed =
        reqwest::Url::parse(url).map_err(|_| "Choose a valid HTTP(S) source.".to_string())?;
    if !matches!(parsed.scheme(), "http" | "https") || parsed.host_str().is_none() {
        return Err("Choose a valid HTTP(S) source.".into());
    }
    Ok(())
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
fn parse_content_range_total(value: Option<&HeaderValue>) -> Option<u64> {
    parse_content_range(value).map(|(_, _, total)| total)
}
fn parse_content_range(value: Option<&HeaderValue>) -> Option<(u64, u64, u64)> {
    let raw = value?.to_str().ok()?.strip_prefix("bytes ")?;
    let (range, total) = raw.split_once('/')?;
    let (start, end) = range.split_once('-')?;
    Some((start.parse().ok()?, end.parse().ok()?, total.parse().ok()?))
}
fn unique_file_name(
    media: &DownloadMedia,
    url: &str,
    video_id: &str,
    directory: &Path,
) -> Result<String, String> {
    let raw = media.filename.as_deref().unwrap_or(video_id);
    let stem = raw.rsplit('/').next().unwrap_or(raw).replace(
        |c: char| !c.is_ascii_alphanumeric() && c != '.' && c != '-' && c != '_',
        "_",
    );
    let mut stem = stem.trim_matches('.').to_string();
    if stem.is_empty() {
        stem = "video".into();
    }
    let ext = Path::new(&stem)
        .extension()
        .and_then(|e| e.to_str())
        .filter(|e| matches!(*e, "mp4" | "mkv" | "webm" | "avi" | "m4v" | "mov" | "ts"))
        .unwrap_or("mkv");
    let base = Path::new(&stem)
        .file_stem()
        .and_then(|s| s.to_str())
        .unwrap_or("video");
    let name = format!(
        "{}-{}.{}",
        base.chars().take(80).collect::<String>(),
        &fingerprint(url)[..12],
        ext
    );
    if directory.join(&name).exists() {
        return Ok(format!(
            "{}-{}.{}",
            base.chars().take(70).collect::<String>(),
            &fingerprint(&format!("{url}:{video_id}"))[..12],
            ext
        ));
    }
    Ok(name)
}
fn canonical_directory(path: &Path) -> Result<PathBuf, String> {
    fs::create_dir_all(path).map_err(|e| format!("Could not create that folder: {e}"))?;
    fs::canonicalize(path).map_err(|e| format!("Could not use that folder: {e}"))
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

#[cfg(windows)]
fn atomic_replace(source: &Path, target: &Path) -> Result<(), String> {
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
        return Err(std::io::Error::last_os_error().to_string());
    }
    Ok(())
}

#[cfg(not(windows))]
fn atomic_replace(source: &Path, target: &Path) -> Result<(), String> {
    fs::rename(source, target).map_err(|error| error.to_string())
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
fn load_config(path: &Path) -> Option<PathBuf> {
    let raw = fs::read(path).ok()?;
    let parsed: PersistedConfig = serde_json::from_slice(&raw).ok()?;
    Some(PathBuf::from(parsed.directory))
}

fn is_safe_file_name(name: &str) -> bool {
    !name.is_empty()
        && Path::new(name)
            .components()
            .all(|component| matches!(component, std::path::Component::Normal(_)))
}

fn is_safe_record_path(entry: &DownloadRecord) -> bool {
    Path::new(&entry.root_path).is_absolute() && is_safe_file_name(&entry.file_name)
}

#[cfg(windows)]
fn protect(bytes: &[u8]) -> Result<Vec<u8>, String> {
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
        return Err(format!(
            "Windows could not protect the download request (error {}).",
            unsafe { GetLastError() }
        ));
    }
    let result = unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        LocalFree(output.pbData as *mut std::ffi::c_void);
    }
    Ok(result)
}
#[cfg(windows)]
fn unprotect(bytes: &[u8]) -> Result<Vec<u8>, String> {
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
        return Err(format!(
            "Windows could not read the protected download request (error {}).",
            unsafe { GetLastError() }
        ));
    }
    let result = unsafe { slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec() };
    unsafe {
        LocalFree(output.pbData as *mut std::ffi::c_void);
    }
    Ok(result)
}
#[cfg(not(windows))]
fn protect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    Ok(bytes.to_vec())
}
#[cfg(not(windows))]
fn unprotect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    Ok(bytes.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn record(root_path: String) -> DownloadRecord {
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
        }
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
    fn sanitizes_file_names_and_keeps_extension_whitelist() {
        let media = DownloadMedia {
            video_id: "movie:1".into(),
            item_id: "movie:1".into(),
            media_type: "movie".into(),
            meta_id: None,
            title: "Test".into(),
            show_name: None,
            episode_label: None,
            poster: None,
            addon_id: None,
            binge_group: None,
            filename: Some("../../bad name.exe".into()),
            video_size: None,
            video_hash: None,
            stream_name: None,
            stream_title: None,
        };
        let name =
            unique_file_name(&media, "https://example.test/a", "movie:1", Path::new(".")).unwrap();
        assert!(!name.contains(".."));
        assert!(name.ends_with(".mkv"));
    }
    #[test]
    fn content_range_total_is_parsed() {
        let value = HeaderValue::from_static("bytes 10-99/100");
        assert_eq!(parse_content_range_total(Some(&value)), Some(100));
        assert_eq!(parse_content_range(Some(&value)), Some((10, 99, 100)));
        let invalid = HeaderValue::from_static("bytes 10-x/100");
        assert_eq!(parse_content_range(Some(&invalid)), None);
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
        assert!(validate_headers(&HashMap::from([("Range".into(), "bytes=0-1".into(),)])).is_err());
        assert!(validate_headers(&HashMap::from([(
            "Authorization".into(),
            "Bearer source-token".into(),
        )]))
        .is_ok());
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
    fn http_failures_are_sanitized_by_retry_policy() {
        assert!(matches!(
            classify_http_status(403),
            Err(TransferError::Permanent(DownloadFailureCode::SourceExpired)),
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
    fn durable_json_writes_replace_existing_windows_files() {
        let directory = std::env::temp_dir().join(format!("halo-download-test-{}", new_job_id()));
        fs::create_dir_all(&directory).unwrap();
        let path = directory.join("index.json");
        write_json(&path, &serde_json::json!({ "version": 1 })).unwrap();
        write_json(&path, &serde_json::json!({ "version": 2 })).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
        assert_eq!(value["version"], 2);
        let _ = fs::remove_file(path);
        let _ = fs::remove_dir(directory);
    }
}
