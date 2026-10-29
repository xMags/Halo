//! Runs one transfer over as many as eight HTTP connections. Debrid CDNs cap
//! each connection's speed, so a download that can be fetched in ranges is
//! fetched in several at once, the way Last Download Manager does it.
//!
//! The first connection is the request the engine always made, with the same
//! redirect, header, validator and resume rules. More connections join only
//! once the size is stated, the source gave a validator, and some answer in
//! this run proved it serves ranges (a 206 with the expected Content-Range).
//! One joins every ramp interval: it resumes the unassigned segment with the
//! most left, or splits the busiest one. A refusal while others still receive
//! fixes the run's connection ceiling. Every connection reads a little past
//! its segment's end, and those bytes must match what the next segment's own
//! connection received, or the source served two different objects.
//!
//! The coordinator owns the file, the table and every response body, and is
//! the only code that touches them; bodies are polled side by side, so no
//! lock is held while any connection waits on the network. The table goes to
//! a sidecar beside the part, written only after the part is synced, so a
//! later run resumes each segment and never trusts a byte that was not on
//! disk. A part without a sidecar is read the way it always was: its length
//! is what arrived.

use super::rate::RateWindow;
use super::segments::{self, ConnectionBudget, SegmentTable, Tuning, UNBOUNDED};
use super::{
    available_bytes, classify_http_status, has_sufficient_space, open_get, parse_content_range,
    DownloadFailureCode, OpenedResponse, TransferError, CANCEL_POLL, DURABLE_INTERVAL,
    PROGRESS_INTERVAL, RATE_WINDOW, READ_TIMEOUT, SPACE_CHECK_BYTES,
};
use crate::secure_fs::atomic_replace;
use bytes::Bytes;
use futures_util::future::{AbortHandle, Abortable, Aborted, BoxFuture, FutureExt};
use futures_util::stream::{BoxStream, FuturesUnordered, StreamExt};
use reqwest::header::{
    HeaderMap, ACCEPT_RANGES, CONTENT_ENCODING, CONTENT_RANGE, ETAG, LAST_MODIFIED, RETRY_AFTER,
};
use std::collections::HashMap;
use std::fs::{self, File, OpenOptions};
use std::future::Future;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

type Body = BoxStream<'static, reqwest::Result<Bytes>>;

/// What one transfer of one download needs to know.
pub(super) struct TransferJob<'a> {
    pub client: &'a reqwest::Client,
    pub url: &'a str,
    pub headers: &'a HashMap<String, String>,
    pub part: &'a Path,
    pub root: &'a Path,
    /// The size an earlier response stated, or 0.
    pub known_total: u64,
    /// The validator an earlier response gave.
    pub validator: Option<&'a str>,
    /// The size the addon reported, used only when the source states none.
    pub size_hint: Option<u64>,
}

/// The part is complete and synced, and its sidecar is gone.
pub(super) struct Finished {
    pub bytes: u64,
    pub total: u64,
    pub validator: Option<String>,
}

/// Where a transfer reports to: the index record of the download.
pub(super) trait TransferHost {
    fn response_metadata(
        &self,
        validator: Option<String>,
        total: u64,
    ) -> impl Future<Output = Result<(), String>> + Send;
    fn progress(
        &self,
        bytes: u64,
        total: u64,
        rate: u64,
        persist: bool,
    ) -> impl Future<Output = ()> + Send;
}

pub(super) async fn download<H: TransferHost + Sync>(
    job: &TransferJob<'_>,
    cancel: &AtomicBool,
    host: &H,
) -> Result<Finished, TransferError> {
    download_with(job, cancel, host, Tuning::default()).await
}

/// What an earlier run left on disk.
enum Resume {
    Fresh,
    /// A part written front to back, by one connection or by a build from
    /// before segment tables.
    Contiguous(u64),
    Table(SegmentTable),
    /// Every byte arrived; only the final rename is missing.
    Complete,
}

async fn download_with<H: TransferHost + Sync>(
    job: &TransferJob<'_>,
    cancel: &AtomicBool,
    host: &H,
    tuning: Tuning,
) -> Result<Finished, TransferError> {
    let table_path = segments::table_path(job.part);
    let (table, first_start) = match load_resume(job, &table_path)? {
        Resume::Complete => return finish_existing(job, &table_path),
        Resume::Fresh => (None, None),
        Resume::Contiguous(done) => (None, Some(done)),
        Resume::Table(table) => {
            // The first connection resumes what the planner would hand any
            // free connection: the segment with the most left.
            let start = table
                .choose(Instant::now(), u64::MAX)
                .and_then(|step| match step {
                    segments::PlanStep::Resume { index } => Some(table.segments()[index].position),
                    segments::PlanStep::Split { .. } => None,
                })
                .ok_or(TransferError::Permanent(DownloadFailureCode::InvalidRange))?;
            (Some(table), Some(start))
        }
    };

    let first = open_first(job, first_start).await?;
    // A whole-file answer to a resume starts the download again.
    let table = table.filter(|_| first.start.is_some());
    host.response_metadata(first.response_validator.clone(), first.total)
        .await
        .map_err(|_| TransferError::Permanent(DownloadFailureCode::Unknown))?;

    fs::create_dir_all(job.root).map_err(|_| storage_full())?;
    let resumed = table.is_some() || first.start.is_some();
    let file = if resumed {
        OpenOptions::new().write(true).open(job.part)
    } else {
        OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(job.part)
    }
    .map_err(|_| storage_full())?;
    if !resumed {
        // Only after the part is empty: a full-length part without its table
        // would pass for a finished download.
        remove_if_present(&table_path).map_err(|_| storage_full())?;
    }

    let segmented = table.is_some();
    let start = first.start.unwrap_or(0);
    let table = table.unwrap_or_else(|| {
        let end = if first.total_stated && first.total > 0 {
            first.total
        } else {
            UNBOUNDED
        };
        SegmentTable::contiguous(start, end)
    });
    let first_key = table
        .segments()
        .iter()
        .find(|segment| segment.position == start && !segment.is_complete())
        .map(|segment| segment.key)
        .ok_or(TransferError::Permanent(DownloadFailureCode::InvalidRange))?;

    let mut run = Run::new(job, cancel, host, tuning, file, &first, table);
    if segmented {
        run.table_started = true;
        run.size_file()?;
    }
    run.check_initial_space()?;
    run.table.begin(first_key, 0);
    run.connections.insert(
        0,
        Connection {
            segment: first_key,
            started_at: start,
            probe: false,
            state: ConnectionState::Opening,
            overlap: Vec::new(),
            last_activity: Instant::now(),
        },
    );
    run.arm(0, first.body);

    let outcome = match run.drive().await {
        Ok(()) if run.cancelled() => Err(TransferError::Paused),
        other => other,
    };
    match outcome {
        Ok(()) => run.finish(),
        Err(error) => {
            run.abandon();
            Err(error)
        }
    }
}

fn storage_full() -> TransferError {
    TransferError::Permanent(DownloadFailureCode::StorageFull)
}

fn remove_if_present(path: &Path) -> io::Result<()> {
    match fs::remove_file(path) {
        Err(error) if error.kind() != io::ErrorKind::NotFound => Err(error),
        _ => Ok(()),
    }
}

/// A sidecar decides the resume whenever it exists, readable or not: a part
/// that once had one may be full length with holes, so its length says
/// nothing. Without one, the part's length is what arrived.
fn load_resume(job: &TransferJob<'_>, table_path: &Path) -> Result<Resume, TransferError> {
    let part_len = fs::metadata(job.part).map(|meta| meta.len()).unwrap_or(0);
    match fs::read(table_path) {
        Ok(raw) => {
            let table = segments::decode(&raw).and_then(|saved| {
                SegmentTable::restore(&saved, job.known_total, job.validator, part_len)
            });
            return Ok(match table {
                Some(table) if table.is_complete() => Resume::Complete,
                Some(table) => Resume::Table(table),
                None => Resume::Fresh,
            });
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {}
        Err(_) => return Ok(Resume::Fresh),
    }
    if job.known_total > 0 && part_len > job.known_total {
        return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
    }
    if part_len > 0 && part_len == job.known_total {
        return Ok(Resume::Complete);
    }
    if part_len > 0 && job.validator.is_none() {
        // Without ETag or Last-Modified there is no safe If-Range value.
        // Restarting loses bytes, appending could silently corrupt a film.
        return Ok(Resume::Fresh);
    }
    Ok(if part_len > 0 {
        Resume::Contiguous(part_len)
    } else {
        Resume::Fresh
    })
}

/// Every byte arrived in an earlier run, which stopped before the rename.
fn finish_existing(job: &TransferJob<'_>, table_path: &Path) -> Result<Finished, TransferError> {
    remove_if_present(table_path).map_err(|_| storage_full())?;
    Ok(Finished {
        bytes: job.known_total,
        total: job.known_total,
        validator: job.validator.map(str::to_owned),
    })
}

struct FirstResponse {
    body: Body,
    /// Where the body starts when it continues earlier progress.
    start: Option<u64>,
    total: u64,
    /// The source stated the size, rather than the addon guessing it.
    total_stated: bool,
    response_validator: Option<String>,
    /// The If-Range value for every later range request.
    validator: Option<String>,
    ranges_proven: bool,
    ranges_refused: bool,
}

/// The engine's one request, as it always was: a resume asks for the rest
/// with If-Range, a whole-file answer restarts, and a ranged answer from
/// another origin is not trusted to describe the same object.
async fn open_first(
    job: &TransferJob<'_>,
    mut start: Option<u64>,
) -> Result<FirstResponse, TransferError> {
    let mut opened = open_get(job.client, job.url, job.headers, start, job.validator).await?;
    let (content_range, response_validator) = loop {
        let response = &opened.response;
        let status = response.status().as_u16();
        classify_http_status(status)?;
        if start.is_some() && status == 200 {
            start = None;
        }
        let mut content_range = None;
        if status == 206 {
            let range = parse_content_range(response.headers().get(CONTENT_RANGE))
                .ok_or(TransferError::Permanent(DownloadFailureCode::InvalidRange))?;
            let length_matches = response
                .content_length()
                .is_none_or(|length| length == range.end - range.start + 1);
            if range.start != start.unwrap_or(0)
                || (job.known_total > 0 && range.total != job.known_total)
                || !length_matches
            {
                return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
            }
            content_range = Some(range);
        }
        let response_validator = validator_of(response.headers());
        if start.is_some() {
            if let (Some(saved), Some(fresh)) = (job.validator, response_validator.as_deref()) {
                if saved != fresh {
                    return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
                }
            }
        }
        if start.is_some() && status == 206 && opened.crossed_origin {
            // A ranged answer from another origin may describe a different
            // object. Take the whole file from the original URL instead.
            opened = open_get(job.client, job.url, job.headers, None, None).await?;
            start = None;
            continue;
        }
        break (content_range, response_validator);
    };
    let response = opened.response;
    let headers = response.headers();
    let encoded = headers
        .get(CONTENT_ENCODING)
        .is_some_and(|value| value.as_bytes() != b"identity");
    let ranges_refused = encoded
        || headers
            .get(ACCEPT_RANGES)
            .is_some_and(|value| value.as_bytes().eq_ignore_ascii_case(b"none"));
    let (total, total_stated) = match (content_range, response.content_length()) {
        (Some(range), _) => (range.total, true),
        (None, Some(length)) => (length, true),
        (None, None) => (job.size_hint.unwrap_or(0), false),
    };
    let validator = response_validator
        .clone()
        .or_else(|| start.and(job.validator.map(str::to_owned)))
        .filter(|value| !value.is_empty());
    Ok(FirstResponse {
        start,
        total,
        total_stated,
        validator,
        ranges_proven: content_range.is_some() && start.is_some(),
        ranges_refused,
        response_validator,
        body: response.bytes_stream().boxed(),
    })
}

fn validator_of(headers: &HeaderMap) -> Option<String> {
    headers
        .get(ETAG)
        .or_else(|| headers.get(LAST_MODIFIED))
        .and_then(|value| value.to_str().ok())
        .map(str::to_owned)
}

fn retry_after_seconds(headers: &HeaderMap) -> Option<u64> {
    headers.get(RETRY_AFTER)?.to_str().ok()?.trim().parse().ok()
}

struct Connection {
    segment: u32,
    started_at: u64,
    /// Opened before the source proved it serves ranges.
    probe: bool,
    state: ConnectionState,
    /// Bytes read past the segment's end, for the overlap check.
    overlap: Vec<u8>,
    last_activity: Instant,
}

enum ConnectionState {
    Opening,
    Reading(AbortHandle),
    /// Reached its segment's end before the source proved it serves ranges.
    /// It holds what it read past the end and waits: if the next segment's
    /// connection is refused, it takes that range back and reads on.
    Parked {
        body: Body,
        held: Bytes,
    },
}

struct Opened {
    id: u64,
    requested: u64,
    result: Result<OpenedResponse, TransferError>,
}

struct ReadDone {
    id: u64,
    item: Option<reqwest::Result<Bytes>>,
    body: Body,
}

enum Event {
    Opened(Opened),
    Read(Result<ReadDone, Aborted>),
    Tick,
}

/// What a connection does after a chunk.
enum Flow {
    Continue,
    Park(Bytes),
    Stop,
}

struct Run<'a, H> {
    job: &'a TransferJob<'a>,
    cancel: &'a AtomicBool,
    host: &'a H,
    tuning: Tuning,
    file: File,
    table_path: PathBuf,
    table: SegmentTable,
    /// The size of the file; 0 while unknown.
    total: u64,
    response_validator: Option<String>,
    validator: Option<String>,
    /// More than one connection may serve this run.
    multi_allowed: bool,
    ranges_proven: bool,
    /// The sidecar exists and the part has its full length.
    table_started: bool,
    /// The full length is allocated, so writes take no further space.
    preallocated: bool,
    /// Two connections disagreed about the same bytes.
    corrupt: bool,
    budget: ConnectionBudget,
    connections: HashMap<u64, Connection>,
    next_connection: u64,
    opens: FuturesUnordered<BoxFuture<'a, Opened>>,
    reads: FuturesUnordered<Abortable<BoxFuture<'static, ReadDone>>>,
    rate: RateWindow,
    last_progress: Instant,
    last_durable: Instant,
    last_space_check: Instant,
    unchecked_bytes: u64,
}

impl<'a, H: TransferHost + Sync> Run<'a, H> {
    fn new(
        job: &'a TransferJob<'a>,
        cancel: &'a AtomicBool,
        host: &'a H,
        tuning: Tuning,
        file: File,
        first: &FirstResponse,
        table: SegmentTable,
    ) -> Self {
        let now = Instant::now();
        let multi_allowed = first.total_stated
            && first.total > 0
            && first.validator.is_some()
            && !first.ranges_refused
            && tuning.max_connections > 1;
        let received = table.received();
        Self {
            job,
            cancel,
            host,
            tuning,
            file,
            table_path: segments::table_path(job.part),
            table,
            total: first.total,
            response_validator: first.response_validator.clone(),
            validator: first.validator.clone(),
            multi_allowed,
            ranges_proven: first.ranges_proven,
            table_started: false,
            preallocated: false,
            corrupt: false,
            budget: ConnectionBudget::new(tuning.max_connections, tuning.ramp_interval, now),
            connections: HashMap::new(),
            next_connection: 1,
            opens: FuturesUnordered::new(),
            reads: FuturesUnordered::new(),
            rate: RateWindow::new(RATE_WINDOW, now, received),
            last_progress: now,
            last_durable: now,
            last_space_check: now,
            unchecked_bytes: 0,
        }
    }

    fn cancelled(&self) -> bool {
        self.cancel.load(Ordering::SeqCst)
    }

    async fn drive(&mut self) -> Result<(), TransferError> {
        loop {
            if self.cancelled() {
                return Err(TransferError::Paused);
            }
            let now = Instant::now();
            self.check_stalls(now)?;
            self.resolve_parked()?;
            // Done only once no connection is left: one still reading past its
            // segment's end has an overlap check to finish, and the last one
            // has the end of the body to reach.
            if self.table.is_complete() && self.connections.is_empty() {
                return Ok(());
            }
            self.fill(now)?;
            if self.connections.is_empty()
                && (!self.multi_allowed || self.table.next_retry(now).is_none())
            {
                // Nothing serves the rest and nothing will: the run is over.
                return Err(TransferError::Retryable(DownloadFailureCode::Network));
            }
            let wait = self.next_wake(now);
            let event = tokio::select! {
                Some(opened) = self.opens.next(), if !self.opens.is_empty() => Event::Opened(opened),
                Some(read) = self.reads.next(), if !self.reads.is_empty() => Event::Read(read),
                () = tokio::time::sleep(wait) => Event::Tick,
            };
            let now = Instant::now();
            match event {
                Event::Opened(opened) => self.on_open(opened, now)?,
                Event::Read(Ok(read)) => self.on_read(read, now)?,
                // A connection cut at its segment's end.
                Event::Read(Err(Aborted)) | Event::Tick => {}
            }
            self.report(now).await?;
        }
    }

    fn next_wake(&self, now: Instant) -> std::time::Duration {
        let mut wake = now + CANCEL_POLL;
        if self.multi_allowed {
            if let Some(ramp) = self.budget.next_ramp().filter(|at| *at > now) {
                wake = wake.min(ramp);
            }
        }
        if let Some(retry) = self.table.next_retry(now) {
            wake = wake.min(retry);
        }
        wake.saturating_duration_since(now)
    }

    /// Lets one more connection in when the budget allows, and gives every
    /// free slot work. Until a 206 shows the source serves ranges, only one
    /// connection asks, so a source that ignores ranges costs one request.
    fn fill(&mut self, now: Instant) -> Result<(), TransferError> {
        if !self.multi_allowed {
            return Ok(());
        }
        self.budget.ramp(now);
        let limit = if self.ranges_proven {
            self.budget.allowed()
        } else {
            self.budget.allowed().min(2)
        };
        while self.connections.len() < limit {
            let Some(step) = self.table.choose(now, self.tuning.min_split_bytes) else {
                break;
            };
            self.start_table()?;
            let Some(key) = self.table.take_step(step) else {
                break;
            };
            self.start_connection(key, now);
        }
        Ok(())
    }

    /// Turns the run into a segmented one before its first extra connection:
    /// the table reaches disk first, then the part takes its full length, so
    /// a full-length part never exists without the table that explains it.
    fn start_table(&mut self) -> Result<(), TransferError> {
        if self.table_started {
            return Ok(());
        }
        self.file.sync_data().map_err(|_| storage_full())?;
        self.write_table()?;
        self.table_started = true;
        self.size_file()
    }

    /// Gives the part its full length, sparse where the volume allows it, so
    /// a write far into the file does not make Windows zero-fill the gap.
    fn size_file(&mut self) -> Result<(), TransferError> {
        let length = self.file.metadata().map_err(|_| storage_full())?.len();
        if length != self.total {
            mark_sparse(&self.file);
            self.file.set_len(self.total).map_err(|_| storage_full())?;
        }
        self.preallocated = !is_sparse(&self.file);
        Ok(())
    }

    fn write_table(&self) -> Result<(), TransferError> {
        let validator = self.validator.as_deref().ok_or_else(storage_full)?;
        let raw = segments::encode(&self.table.saved(self.total, validator))
            .map_err(|_| storage_full())?;
        let temporary = segments::table_temp_path(&self.table_path);
        let written = File::create(&temporary).and_then(|mut file| {
            file.write_all(&raw)?;
            file.sync_all()
        });
        if written.is_err() {
            let _ = fs::remove_file(&temporary);
            return Err(storage_full());
        }
        atomic_replace(&temporary, &self.table_path).map_err(|_| storage_full())
    }

    fn start_connection(&mut self, key: u32, now: Instant) {
        let Some(requested) = self.table.get(key).map(|segment| segment.position) else {
            return;
        };
        let id = self.next_connection;
        self.next_connection += 1;
        self.table.begin(key, id);
        self.connections.insert(
            id,
            Connection {
                segment: key,
                started_at: requested,
                probe: !self.ranges_proven,
                state: ConnectionState::Opening,
                overlap: Vec::new(),
                last_activity: now,
            },
        );
        let job = self.job;
        let validator = self.validator.clone();
        self.opens.push(
            async move {
                let result = tokio::time::timeout(
                    READ_TIMEOUT,
                    open_get(
                        job.client,
                        job.url,
                        job.headers,
                        Some(requested),
                        validator.as_deref(),
                    ),
                )
                .await
                .unwrap_or(Err(TransferError::Retryable(DownloadFailureCode::Network)));
                Opened {
                    id,
                    requested,
                    result,
                }
            }
            .boxed(),
        );
    }

    fn on_open(&mut self, opened: Opened, now: Instant) -> Result<(), TransferError> {
        let Opened {
            id,
            requested,
            result,
        } = opened;
        if !self.connections.contains_key(&id) {
            return Ok(());
        }
        let response = match result {
            Ok(opened) => opened.response,
            Err(error) => return self.fail(id, error, None, now),
        };
        let status = response.status().as_u16();
        if status == 200 {
            return self.range_refused(id, now);
        }
        if let Err(error) = classify_http_status(status) {
            let retry_after = retry_after_seconds(response.headers());
            // Some hosts turn away one connection too many with 403 or 404.
            // While others still receive, that is a limit, not an expired
            // link; the last connection meeting it still ends the download.
            let receiving = self.connections.iter().any(|(other, state)| {
                *other != id && !matches!(state.state, ConnectionState::Opening)
            });
            let error = match (status, error) {
                (403 | 404, TransferError::Permanent(_)) if receiving => {
                    TransferError::Retryable(DownloadFailureCode::ServerUnavailable)
                }
                (_, error) => error,
            };
            return self.fail(id, error, retry_after, now);
        }
        // A 206 must continue the same object exactly where it was asked to.
        let range = parse_content_range(response.headers().get(CONTENT_RANGE))
            .ok_or(TransferError::Permanent(DownloadFailureCode::InvalidRange))?;
        let length_matches = response
            .content_length()
            .is_none_or(|length| length == range.end - range.start + 1);
        let validator_matches = validator_of(response.headers())
            .is_none_or(|fresh| self.validator.as_deref() == Some(fresh.as_str()));
        if range.start != requested
            || range.total != self.total
            || !length_matches
            || !validator_matches
        {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        self.ranges_proven = true;
        self.arm(id, response.bytes_stream().boxed());
        Ok(())
    }

    /// An extra connection got the whole file instead of its range. Before
    /// any 206 that means the source ignores ranges: the range goes back to
    /// the connection before it, which reads on, and the run stays on one
    /// connection. After a 206 it is a refusal like any other.
    fn range_refused(&mut self, id: u64, now: Instant) -> Result<(), TransferError> {
        if self.ranges_proven {
            return self.fail(
                id,
                TransferError::Retryable(DownloadFailureCode::ServerUnavailable),
                None,
                now,
            );
        }
        let Some(connection) = self.connections.remove(&id) else {
            return Ok(());
        };
        self.table.release(connection.segment);
        self.multi_allowed = false;
        let previous = self
            .table
            .previous_of(connection.segment)
            .map(|segment| segment.key);
        if !previous.is_some_and(|key| self.table.absorb_next(key)) {
            return Err(TransferError::Retryable(
                DownloadFailureCode::ServerUnavailable,
            ));
        }
        Ok(())
    }

    /// Ends a connection that failed. With others still receiving, after the
    /// source proved it serves ranges (or for the connection that was asking
    /// whether it does), the run keeps the connections it has: the ceiling
    /// drops to them and the segment waits to be asked for again. Otherwise
    /// the failure ends the run, and the engine's own retries take over.
    fn fail(
        &mut self,
        id: u64,
        error: TransferError,
        retry_after: Option<u64>,
        now: Instant,
    ) -> Result<(), TransferError> {
        let Some(connection) = self.connections.remove(&id) else {
            return Ok(());
        };
        if let ConnectionState::Reading(abort) = &connection.state {
            abort.abort();
        }
        self.table.release(connection.segment);
        let TransferError::Retryable(code) = error else {
            return Err(error);
        };
        if self
            .table
            .get(connection.segment)
            .is_some_and(|segment| segment.is_complete())
        {
            // Its segment is all on disk; only the overlap past it is lost.
            return Ok(());
        }
        let receiving = self
            .connections
            .values()
            .filter(|other| !matches!(other.state, ConnectionState::Opening))
            .count();
        if receiving == 0 || !(self.ranges_proven || connection.probe) {
            return Err(TransferError::Retryable(code));
        }
        self.budget.refuse(receiving);
        if let Some(segment) = self.table.get_mut(connection.segment) {
            if segment.position > connection.started_at {
                // Data arrived first: not one failure in a row after another.
                segment.attempts = 0;
            }
            segment.attempts += 1;
            segment.retry_at = Some(now + segments::retry_delay(segment.attempts, retry_after));
        }
        Ok(())
    }

    fn release(&mut self, id: u64) {
        if let Some(connection) = self.connections.remove(&id) {
            if let ConnectionState::Reading(abort) = &connection.state {
                abort.abort();
            }
            self.table.release(connection.segment);
            if let Some(segment) = self.table.get_mut(connection.segment) {
                segment.attempts = 0;
            }
        }
    }

    /// Reads the next chunk of `body` for connection `id`.
    fn arm(&mut self, id: u64, mut body: Body) {
        let Some(connection) = self.connections.get_mut(&id) else {
            return;
        };
        let (abort, registration) = AbortHandle::new_pair();
        connection.state = ConnectionState::Reading(abort);
        connection.last_activity = Instant::now();
        let read = async move {
            let item = body.next().await;
            ReadDone { id, item, body }
        }
        .boxed();
        self.reads.push(Abortable::new(read, registration));
    }

    fn on_read(&mut self, read: ReadDone, now: Instant) -> Result<(), TransferError> {
        let ReadDone { id, item, body } = read;
        if !self.connections.contains_key(&id) {
            return Ok(());
        }
        match item {
            Some(Ok(chunk)) => {
                self.check_space(chunk.len() as u64, now)?;
                let flow = self.consume(id, chunk)?;
                self.apply(id, flow, body);
                Ok(())
            }
            Some(Err(_)) => self.fail(
                id,
                TransferError::Retryable(DownloadFailureCode::Network),
                None,
                now,
            ),
            None => self.on_end_of_body(id, now),
        }
    }

    fn apply(&mut self, id: u64, flow: Flow, body: Body) {
        match flow {
            Flow::Continue => self.arm(id, body),
            Flow::Park(held) => {
                if let Some(connection) = self.connections.get_mut(&id) {
                    connection.state = ConnectionState::Parked { body, held };
                }
            }
            Flow::Stop => self.release(id),
        }
    }

    fn on_end_of_body(&mut self, id: u64, now: Instant) -> Result<(), TransferError> {
        let Some(key) = self
            .connections
            .get(&id)
            .map(|connection| connection.segment)
        else {
            return Ok(());
        };
        let Some(segment) = self.table.get_mut(key) else {
            return Ok(());
        };
        if segment.end == UNBOUNDED {
            // A body of unstated size ends where the source ends it, and is
            // then held to the size the addon reported, if any.
            let arrived = segment.position;
            if self.total > 0 && arrived > self.total {
                return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
            }
            if self.total > 0 && arrived < self.total {
                return self.fail(
                    id,
                    TransferError::Retryable(DownloadFailureCode::Network),
                    None,
                    now,
                );
            }
            segment.end = arrived;
            self.total = arrived;
            self.release(id);
            return Ok(());
        }
        if segment.position >= segment.end {
            self.release(id);
            return Ok(());
        }
        // The source closed the body before the segment was complete.
        self.fail(
            id,
            TransferError::Retryable(DownloadFailureCode::Network),
            None,
            now,
        )
    }

    /// Writes what belongs to the connection's segment and decides what the
    /// connection does next. Bytes past the end are never written: they are
    /// the overlap check's, or a parked connection's to hold.
    fn consume(&mut self, id: u64, mut data: Bytes) -> Result<Flow, TransferError> {
        let key = self
            .connections
            .get(&id)
            .map(|connection| connection.segment)
            .ok_or(TransferError::Permanent(DownloadFailureCode::Unknown))?;
        loop {
            let (position, end) = self
                .table
                .get(key)
                .map(|segment| (segment.position, segment.end))
                .ok_or(TransferError::Permanent(DownloadFailureCode::Unknown))?;
            if position < end {
                if data.is_empty() {
                    return Ok(Flow::Continue);
                }
                let take =
                    usize::try_from(end - position).map_or(data.len(), |left| left.min(data.len()));
                let piece = data.split_to(take);
                self.write_at(position, &piece)?;
                if self.table.record(key, &piece).is_err() {
                    return Err(self.mismatch());
                }
                continue;
            }
            if self.table.next_of(key).is_none() {
                if !data.is_empty() {
                    // More than the whole file.
                    return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
                }
                return Ok(Flow::Continue);
            }
            if !self.ranges_proven {
                return Ok(if data.is_empty() {
                    Flow::Continue
                } else {
                    Flow::Park(data)
                });
            }
            let wanted = self.table.overlap_wanted(key, self.total);
            let Some(connection) = self.connections.get_mut(&id) else {
                return Ok(Flow::Stop);
            };
            let take = wanted
                .saturating_sub(connection.overlap.len())
                .min(data.len());
            connection.overlap.extend_from_slice(&data[..take]);
            if connection.overlap.len() < wanted {
                return Ok(Flow::Continue);
            }
            let overlap = std::mem::take(&mut connection.overlap);
            if !overlap.is_empty() && self.table.hand_over_overlap(key, overlap).is_err() {
                return Err(self.mismatch());
            }
            return Ok(Flow::Stop);
        }
    }

    fn mismatch(&mut self) -> TransferError {
        self.corrupt = true;
        TransferError::Permanent(DownloadFailureCode::InvalidRange)
    }

    /// Lets parked connections go once the question they wait on is
    /// answered: ranges are proven, or the next segment has no connection
    /// and nothing of it arrived, so its range comes back to them.
    fn resolve_parked(&mut self) -> Result<(), TransferError> {
        let parked: Vec<u64> = self
            .connections
            .iter()
            .filter(|(_, connection)| matches!(connection.state, ConnectionState::Parked { .. }))
            .map(|(id, _)| *id)
            .collect();
        for id in parked {
            let Some(key) = self
                .connections
                .get(&id)
                .map(|connection| connection.segment)
            else {
                continue;
            };
            let free = self.ranges_proven
                || self.table.next_of(key).is_none()
                || self.table.absorb_next(key);
            if !free {
                continue;
            }
            let Some(connection) = self.connections.get_mut(&id) else {
                continue;
            };
            let ConnectionState::Parked { body, held } =
                std::mem::replace(&mut connection.state, ConnectionState::Opening)
            else {
                continue;
            };
            let flow = self.consume(id, held)?;
            self.apply(id, flow, body);
        }
        Ok(())
    }

    /// A connection that delivers nothing for READ_TIMEOUT has failed. A
    /// parked one is waiting on purpose; an opening one has its own timeout.
    fn check_stalls(&mut self, now: Instant) -> Result<(), TransferError> {
        let stalled: Vec<u64> = self
            .connections
            .iter()
            .filter(|(_, connection)| {
                matches!(connection.state, ConnectionState::Reading(_))
                    && now.duration_since(connection.last_activity) >= READ_TIMEOUT
            })
            .map(|(id, _)| *id)
            .collect();
        for id in stalled {
            self.fail(
                id,
                TransferError::Retryable(DownloadFailureCode::Network),
                None,
                now,
            )?;
        }
        Ok(())
    }

    fn check_initial_space(&self) -> Result<(), TransferError> {
        let needed = self.needed_space(0);
        if available_bytes(self.job.root).is_some_and(|free| !has_sufficient_space(free, needed)) {
            return Err(storage_full());
        }
        Ok(())
    }

    fn needed_space(&self, chunk: u64) -> u64 {
        if self.preallocated {
            return chunk;
        }
        self.total.saturating_sub(self.table.received()).max(chunk)
    }

    /// Rechecked every few megabytes rather than on every chunk: the reserve
    /// absorbs what lands in between, and a response that never states its
    /// size still cannot eat into the reserve.
    fn check_space(&mut self, chunk: u64, now: Instant) -> Result<(), TransferError> {
        self.unchecked_bytes += chunk;
        if self.unchecked_bytes < SPACE_CHECK_BYTES
            && now.duration_since(self.last_space_check) < PROGRESS_INTERVAL
        {
            return Ok(());
        }
        let needed = self.needed_space(chunk);
        if available_bytes(self.job.root).is_some_and(|free| !has_sufficient_space(free, needed)) {
            return Err(storage_full());
        }
        self.unchecked_bytes = 0;
        self.last_space_check = now;
        Ok(())
    }

    fn write_at(&mut self, offset: u64, data: &[u8]) -> Result<(), TransferError> {
        if self.cancelled() {
            return Err(TransferError::Paused);
        }
        write_all_at(&self.file, offset, data).map_err(|_| storage_full())
    }

    async fn report(&mut self, now: Instant) -> Result<(), TransferError> {
        if now.duration_since(self.last_progress) < PROGRESS_INTERVAL {
            return Ok(());
        }
        let received = self.table.received();
        let durable = now.duration_since(self.last_durable) >= DURABLE_INTERVAL;
        if durable {
            self.make_durable()?;
            self.last_durable = now;
        }
        // Persisting only after a flush keeps the stored byte count, and the
        // table, at or below what is actually on disk.
        let rate = self.rate.record(now, received);
        self.host
            .progress(received, self.total, rate, durable)
            .await;
        self.last_progress = now;
        Ok(())
    }

    fn make_durable(&mut self) -> Result<(), TransferError> {
        self.file.sync_data().map_err(|_| storage_full())?;
        if self.table_started {
            self.write_table()?;
        }
        Ok(())
    }

    fn finish(mut self) -> Result<Finished, TransferError> {
        self.file.sync_all().map_err(|_| storage_full())?;
        let length = self.file.metadata().map_err(|_| storage_full())?.len();
        if length != self.total {
            return Err(TransferError::Permanent(DownloadFailureCode::InvalidRange));
        }
        // Every byte is synced, so the bare part is now the truth.
        remove_if_present(&self.table_path).map_err(|_| storage_full())?;
        self.connections.clear();
        Ok(Finished {
            bytes: self.total,
            total: self.total,
            validator: self.response_validator.take(),
        })
    }

    /// Leaves what a later run can resume: the table as of a fresh sync. A
    /// mismatch leaves nothing, part first, so a full-length part never
    /// outlives its table.
    fn abandon(self) {
        if self.corrupt {
            let part = self.job.part;
            let table_path = self.table_path.clone();
            drop(self);
            if fs::remove_file(part).is_ok() {
                let _ = fs::remove_file(&table_path);
            }
            return;
        }
        // A removal that stopped waiting for this run may have deleted the
        // part already; a table must not reappear beside nothing.
        if self.table_started && self.job.part.exists() && self.file.sync_data().is_ok() {
            // Best effort: the table already on disk is still a safe one.
            let _ = self.write_table();
        }
    }
}

/// Writes all of `data` at `offset`, however many calls that takes.
fn write_all_at(file: &File, offset: u64, data: &[u8]) -> io::Result<()> {
    #[cfg(windows)]
    {
        use std::os::windows::fs::FileExt;
        let mut written = 0usize;
        while written < data.len() {
            let count = file.seek_write(&data[written..], offset + written as u64)?;
            if count == 0 {
                return Err(io::Error::from(io::ErrorKind::WriteZero));
            }
            written += count;
        }
        Ok(())
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::FileExt;
        file.write_all_at(data, offset)
    }
}

/// Volumes without sparse files (FAT, exFAT) refuse; the part is then
/// extended normally.
#[cfg(windows)]
fn mark_sparse(file: &File) {
    use std::os::windows::io::AsRawHandle;
    use windows_sys::Win32::System::Ioctl::FSCTL_SET_SPARSE;
    use windows_sys::Win32::System::IO::DeviceIoControl;
    let mut returned = 0u32;
    unsafe {
        DeviceIoControl(
            file.as_raw_handle(),
            FSCTL_SET_SPARSE,
            std::ptr::null(),
            0,
            std::ptr::null_mut(),
            0,
            &mut returned,
            std::ptr::null_mut(),
        );
    }
}

#[cfg(not(windows))]
fn mark_sparse(_file: &File) {}

#[cfg(windows)]
fn is_sparse(file: &File) -> bool {
    use std::os::windows::fs::MetadataExt;
    use windows_sys::Win32::Storage::FileSystem::FILE_ATTRIBUTE_SPARSE_FILE;
    file.metadata()
        .is_ok_and(|meta| meta.file_attributes() & FILE_ATTRIBUTE_SPARSE_FILE != 0)
}

/// Extending a file leaves a hole on the file systems these builds meet.
#[cfg(not(windows))]
fn is_sparse(_file: &File) -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::net::{TcpListener, TcpStream};
    use std::sync::atomic::AtomicUsize;
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    const ETAG_VALUE: &str = "\"v1\"";

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum Mode {
        /// Honours Range and If-Range.
        Ranges,
        /// Always sends the whole file.
        IgnoresRanges,
        /// Answers the given status to any connection past this many at once.
        Limit(usize, u16),
        /// Ranged answers come from a different object under the same ETag.
        Inconsistent,
    }

    struct Logged {
        range: Option<u64>,
        if_range: Option<String>,
        status: u16,
    }

    /// A plain HTTP/1.1 file server on loopback, one thread per connection.
    struct Server {
        body: Vec<u8>,
        mode: Mode,
        etag: Option<&'static str>,
        /// Pause between 16 KiB writes, so a transfer lasts long enough to
        /// ramp up.
        pace: Duration,
        /// How long a ranged request waits for its answer.
        ranged_delay: Duration,
        active: AtomicUsize,
        max_active: AtomicUsize,
        log: Mutex<Vec<Logged>>,
    }

    impl Server {
        fn start(
            body: Vec<u8>,
            mode: Mode,
            etag: Option<&'static str>,
            pace: Duration,
        ) -> (Arc<Self>, String) {
            Self::start_delayed(body, mode, etag, pace, Duration::ZERO)
        }

        fn start_delayed(
            body: Vec<u8>,
            mode: Mode,
            etag: Option<&'static str>,
            pace: Duration,
            ranged_delay: Duration,
        ) -> (Arc<Self>, String) {
            let server = Arc::new(Self {
                body,
                mode,
                etag,
                pace,
                ranged_delay,
                active: AtomicUsize::new(0),
                max_active: AtomicUsize::new(0),
                log: Mutex::new(Vec::new()),
            });
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let url = format!("http://{}/movie.mkv", listener.local_addr().unwrap());
            let shared = Arc::clone(&server);
            std::thread::spawn(move || {
                for stream in listener.incoming().flatten() {
                    let server = Arc::clone(&shared);
                    std::thread::spawn(move || server.serve(stream));
                }
            });
            (server, url)
        }

        fn serve(&self, stream: TcpStream) {
            let mut reader = BufReader::new(stream.try_clone().unwrap());
            let mut range = None;
            let mut if_range = None;
            let mut line = String::new();
            loop {
                line.clear();
                if reader.read_line(&mut line).unwrap_or(0) == 0 {
                    return;
                }
                let trimmed = line.trim_end();
                if trimmed.is_empty() {
                    break;
                }
                let Some((name, value)) = trimmed.split_once(':') else {
                    continue;
                };
                let value = value.trim();
                match name.to_ascii_lowercase().as_str() {
                    "range" => {
                        range = value
                            .strip_prefix("bytes=")
                            .and_then(|rest| rest.strip_suffix('-'))
                            .and_then(|start| start.parse::<u64>().ok());
                    }
                    "if-range" => if_range = Some(value.to_string()),
                    _ => {}
                }
            }
            if range.is_some() {
                std::thread::sleep(self.ranged_delay);
            }
            let active = self.active.fetch_add(1, Ordering::SeqCst) + 1;
            let refusal = match self.mode {
                Mode::Limit(limit, status) if active > limit => Some(status),
                _ => None,
            };
            let status = if let Some(status) = refusal {
                status
            } else {
                self.max_active.fetch_max(active, Ordering::SeqCst);
                let honours = self.mode != Mode::IgnoresRanges
                    && if_range
                        .as_deref()
                        .is_none_or(|value| Some(value) == self.etag);
                if range.is_some() && honours {
                    206
                } else {
                    200
                }
            };
            self.log.lock().unwrap().push(Logged {
                range,
                if_range,
                status,
            });
            let _ = self.respond(stream, status, range.unwrap_or(0));
            self.active.fetch_sub(1, Ordering::SeqCst);
        }

        fn respond(&self, mut stream: TcpStream, status: u16, start: u64) -> io::Result<()> {
            let mut head = String::new();
            if status == 503 || status == 403 {
                let reason = if status == 503 {
                    "Service Unavailable"
                } else {
                    "Forbidden"
                };
                head.push_str(&format!("HTTP/1.1 {status} {reason}\r\n"));
                head.push_str("Retry-After: 0\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                return stream.write_all(head.as_bytes());
            }
            let total = self.body.len() as u64;
            let start = if status == 206 { start } else { 0 };
            if status == 206 {
                head.push_str("HTTP/1.1 206 Partial Content\r\n");
                head.push_str(&format!(
                    "Content-Range: bytes {start}-{}/{total}\r\n",
                    total - 1
                ));
            } else {
                head.push_str("HTTP/1.1 200 OK\r\n");
            }
            head.push_str(&format!("Content-Length: {}\r\n", total - start));
            if let Some(etag) = self.etag {
                head.push_str(&format!("ETag: {etag}\r\n"));
            }
            head.push_str("Connection: close\r\n\r\n");
            stream.write_all(head.as_bytes())?;
            let flip = status == 206 && self.mode == Mode::Inconsistent;
            for chunk in self.body[start as usize..].chunks(16 * 1024) {
                if flip {
                    let flipped: Vec<u8> = chunk.iter().map(|byte| byte ^ 0xFF).collect();
                    stream.write_all(&flipped)?;
                } else {
                    stream.write_all(chunk)?;
                }
                std::thread::sleep(self.pace);
            }
            stream.flush()
        }

        fn requests(&self) -> usize {
            self.log.lock().unwrap().len()
        }
    }

    #[derive(Default)]
    struct FakeHost {
        metadata: Mutex<Option<(Option<String>, u64)>>,
    }

    impl TransferHost for FakeHost {
        async fn response_metadata(
            &self,
            validator: Option<String>,
            total: u64,
        ) -> Result<(), String> {
            *self.metadata.lock().unwrap() = Some((validator, total));
            Ok(())
        }
        async fn progress(&self, _bytes: u64, _total: u64, _rate: u64, _persist: bool) {}
    }

    /// A pattern in which no two nearby offsets agree by accident.
    fn content(length: usize) -> Vec<u8> {
        let mut state = 0x2545_f491_u32;
        (0..length)
            .map(|_| {
                state ^= state << 13;
                state ^= state >> 17;
                state ^= state << 5;
                (state >> 24) as u8
            })
            .collect()
    }

    fn quick() -> Tuning {
        Tuning {
            max_connections: segments::MAX_CONNECTIONS,
            min_split_bytes: 256 * 1024,
            ramp_interval: Duration::from_millis(20),
        }
    }

    struct Fixture {
        root: PathBuf,
        part: PathBuf,
        url: String,
        headers: HashMap<String, String>,
        client: reqwest::Client,
    }

    impl Fixture {
        fn new(name: &str, url: String) -> Self {
            let root = crate::downloads::tests::scratch_dir(name);
            Self {
                part: root.join("movie.mkv.part"),
                root,
                url,
                headers: HashMap::new(),
                client: reqwest::Client::builder()
                    .redirect(reqwest::redirect::Policy::none())
                    .build()
                    .unwrap(),
            }
        }
        fn job<'a>(&'a self, known_total: u64, validator: Option<&'a str>) -> TransferJob<'a> {
            TransferJob {
                client: &self.client,
                url: &self.url,
                headers: &self.headers,
                part: &self.part,
                root: &self.root,
                known_total,
                validator,
                size_hint: None,
            }
        }
        fn table(&self) -> PathBuf {
            segments::table_path(&self.part)
        }
        fn part_matches(&self, body: &[u8]) -> bool {
            fs::read(&self.part).is_ok_and(|on_disk| on_disk == body)
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.root);
        }
    }

    #[tokio::test]
    async fn several_connections_deliver_the_exact_file() {
        let body = content(6 * 1024 * 1024);
        let (server, url) = Server::start(
            body.clone(),
            Mode::Ranges,
            Some(ETAG_VALUE),
            Duration::from_millis(2),
        );
        let fixture = Fixture::new("segmented", url);
        let cancel = AtomicBool::new(false);
        let finished = download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await
        .unwrap();
        assert_eq!(finished.total, body.len() as u64);
        assert_eq!(finished.validator.as_deref(), Some(ETAG_VALUE));
        assert!(
            fixture.part_matches(&body),
            "the part differs from the source"
        );
        assert!(!fixture.table().exists(), "the table outlived the download");
        assert!(
            server.max_active.load(Ordering::SeqCst) > 1,
            "only one connection ran"
        );
        let log = server.log.lock().unwrap();
        assert!(log[0].range.is_none());
        for extra in &log[1..] {
            assert!(extra.range.is_some_and(|start| start > 0));
            assert_eq!(extra.if_range.as_deref(), Some(ETAG_VALUE));
            assert_eq!(extra.status, 206);
        }
    }

    #[tokio::test]
    async fn a_source_that_ignores_ranges_is_read_through_on_one_connection() {
        let body = content(2 * 1024 * 1024);
        let (server, url) = Server::start(
            body.clone(),
            Mode::IgnoresRanges,
            Some(ETAG_VALUE),
            Duration::from_millis(2),
        );
        let fixture = Fixture::new("ignores-ranges", url);
        let cancel = AtomicBool::new(false);
        download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await
        .unwrap();
        assert!(fixture.part_matches(&body));
        // The first request and one probe; its 200 ends the asking.
        assert_eq!(server.requests(), 2);
        assert!(!fixture.table().exists());
    }

    /// The first connection reaches the split point long before the probe is
    /// answered, so it must wait there instead of stopping: when the answer
    /// is a 206 it stops after the overlap, and when it is a 200 it takes the
    /// range back and reads on.
    #[tokio::test]
    async fn the_first_connection_waits_at_the_split_for_the_probes_answer() {
        for mode in [Mode::Ranges, Mode::IgnoresRanges] {
            let body = content(2 * 1024 * 1024);
            let (server, url) = Server::start_delayed(
                body.clone(),
                mode,
                Some(ETAG_VALUE),
                Duration::from_millis(1),
                Duration::from_millis(600),
            );
            let fixture = Fixture::new("parked", url);
            let cancel = AtomicBool::new(false);
            let mut tuning = quick();
            tuning.max_connections = 2;
            download_with(&fixture.job(0, None), &cancel, &FakeHost::default(), tuning)
                .await
                .unwrap();
            assert!(fixture.part_matches(&body));
            if mode == Mode::IgnoresRanges {
                assert_eq!(server.requests(), 2);
            }
            assert!(!fixture.table().exists());
        }
    }

    #[tokio::test]
    async fn a_refused_probe_keeps_the_run_on_one_connection() {
        let body = content(2 * 1024 * 1024);
        let (server, url) = Server::start(
            body.clone(),
            Mode::Limit(1, 503),
            Some(ETAG_VALUE),
            Duration::from_millis(2),
        );
        let fixture = Fixture::new("refused-probe", url);
        let cancel = AtomicBool::new(false);
        download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await
        .unwrap();
        assert!(fixture.part_matches(&body));
        assert_eq!(server.max_active.load(Ordering::SeqCst), 1);
        assert_eq!(server.requests(), 2, "the ceiling stopped further asking");
    }

    #[tokio::test]
    async fn a_refusal_after_ranges_are_proven_sets_the_ceiling() {
        let body = content(6 * 1024 * 1024);
        let (server, url) = Server::start(
            body.clone(),
            Mode::Limit(2, 503),
            Some(ETAG_VALUE),
            Duration::from_millis(2),
        );
        let fixture = Fixture::new("ceiling", url);
        let cancel = AtomicBool::new(false);
        download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await
        .unwrap();
        assert!(fixture.part_matches(&body));
        let log = server.log.lock().unwrap();
        let refused = log.iter().filter(|entry| entry.status == 503).count();
        assert!(refused >= 1, "the server never refused");
        // Without the ceiling every ramp step would ask again and be refused.
        assert!(refused <= 4, "{refused} refusals");
    }

    #[tokio::test]
    async fn a_403_to_an_extra_connection_is_a_limit_not_an_expired_link() {
        let body = content(6 * 1024 * 1024);
        let (server, url) = Server::start(
            body.clone(),
            Mode::Limit(2, 403),
            Some(ETAG_VALUE),
            Duration::from_millis(2),
        );
        let fixture = Fixture::new("ceiling-403", url);
        let cancel = AtomicBool::new(false);
        download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await
        .unwrap();
        assert!(fixture.part_matches(&body));
        let log = server.log.lock().unwrap();
        let refused = log.iter().filter(|entry| entry.status == 403).count();
        assert!(refused >= 1, "the server never refused");
        assert!(refused <= 4, "{refused} refusals");
    }

    #[tokio::test]
    async fn different_bytes_from_two_connections_fail_and_leave_nothing() {
        let body = content(2 * 1024 * 1024);
        let (_server, url) = Server::start(
            body,
            Mode::Inconsistent,
            Some(ETAG_VALUE),
            Duration::from_millis(2),
        );
        let fixture = Fixture::new("inconsistent", url);
        let cancel = AtomicBool::new(false);
        let result = download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await;
        assert!(
            matches!(
                result,
                Err(TransferError::Permanent(DownloadFailureCode::InvalidRange))
            ),
            "{:?}",
            result.err()
        );
        assert!(!fixture.part.exists(), "the corrupt part was kept");
        assert!(!fixture.table().exists());
    }

    #[tokio::test]
    async fn a_source_without_a_validator_stays_on_one_connection() {
        let body = content(2 * 1024 * 1024);
        let (server, url) =
            Server::start(body.clone(), Mode::Ranges, None, Duration::from_millis(1));
        let fixture = Fixture::new("no-validator", url);
        let cancel = AtomicBool::new(false);
        download_with(
            &fixture.job(0, None),
            &cancel,
            &FakeHost::default(),
            quick(),
        )
        .await
        .unwrap();
        assert!(fixture.part_matches(&body));
        assert_eq!(server.requests(), 1);
        assert!(!fixture.table().exists());
    }

    #[tokio::test]
    async fn a_paused_segmented_download_resumes_every_segment() {
        let body = content(6 * 1024 * 1024);
        let (server, url) = Server::start(
            body.clone(),
            Mode::Ranges,
            Some(ETAG_VALUE),
            Duration::from_millis(25),
        );
        let fixture = Fixture::new("resume", url);
        let host = FakeHost::default();
        let cancel = AtomicBool::new(false);
        let pause = async {
            tokio::time::sleep(Duration::from_millis(500)).await;
            cancel.store(true, Ordering::SeqCst);
        };
        let job = fixture.job(0, None);
        let (result, ()) = tokio::join!(download_with(&job, &cancel, &host, quick()), pause);
        assert!(
            matches!(result, Err(TransferError::Paused)),
            "{:?}",
            result.err()
        );
        let saved = segments::decode(&fs::read(fixture.table()).unwrap()).unwrap();
        assert_eq!(saved.total, body.len() as u64);
        assert!(saved.segments.len() > 1, "the run never split");
        let done: u64 = saved.segments.iter().map(|segment| segment.done).sum();
        assert!(done > 0 && done < body.len() as u64, "{done} bytes saved");
        let before = server.requests();

        let (validator, total) = host.metadata.lock().unwrap().clone().unwrap();
        cancel.store(false, Ordering::SeqCst);
        let job = fixture.job(total, validator.as_deref());
        download_with(&job, &cancel, &host, quick()).await.unwrap();
        assert!(fixture.part_matches(&body));
        assert!(!fixture.table().exists());
        let log = server.log.lock().unwrap();
        let resumed = &log[before];
        assert!(
            resumed.range.is_some(),
            "the resume did not ask for a range"
        );
        assert_eq!(resumed.status, 206);
    }

    #[tokio::test]
    async fn a_part_from_before_tables_resumes_from_its_length() {
        let body = content(1024 * 1024);
        let (server, url) =
            Server::start(body.clone(), Mode::Ranges, Some(ETAG_VALUE), Duration::ZERO);
        let fixture = Fixture::new("legacy", url);
        fs::write(&fixture.part, &body[..300_000]).unwrap();
        let cancel = AtomicBool::new(false);
        let job = fixture.job(body.len() as u64, Some(ETAG_VALUE));
        let mut tuning = quick();
        tuning.max_connections = 1;
        download_with(&job, &cancel, &FakeHost::default(), tuning)
            .await
            .unwrap();
        assert!(fixture.part_matches(&body));
        let log = server.log.lock().unwrap();
        assert_eq!(log[0].range, Some(300_000));
        assert_eq!(log.len(), 1);
    }

    #[tokio::test]
    async fn an_unreadable_table_restarts_from_zero() {
        let body = content(1024 * 1024);
        let (server, url) =
            Server::start(body.clone(), Mode::Ranges, Some(ETAG_VALUE), Duration::ZERO);
        let fixture = Fixture::new("bad-table", url);
        // A full-length part with holes: its length must not pass for progress.
        fs::write(&fixture.part, vec![0u8; body.len()]).unwrap();
        fs::write(fixture.table(), b"{ torn").unwrap();
        let cancel = AtomicBool::new(false);
        let job = fixture.job(body.len() as u64, Some(ETAG_VALUE));
        download_with(&job, &cancel, &FakeHost::default(), quick())
            .await
            .unwrap();
        assert!(fixture.part_matches(&body));
        assert!(server.log.lock().unwrap()[0].range.is_none());
        assert!(!fixture.table().exists());
    }

    #[test]
    fn retry_after_is_read_as_seconds() {
        let mut headers = HeaderMap::new();
        assert_eq!(retry_after_seconds(&headers), None);
        headers.insert(RETRY_AFTER, "12".parse().unwrap());
        assert_eq!(retry_after_seconds(&headers), Some(12));
        headers.insert(
            RETRY_AFTER,
            "Wed, 21 Oct 2026 07:28:00 GMT".parse().unwrap(),
        );
        assert_eq!(retry_after_seconds(&headers), None);
    }
}
