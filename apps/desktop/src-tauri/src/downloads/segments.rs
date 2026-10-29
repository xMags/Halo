//! The segment table of one download: which byte ranges exist, how far each
//! has arrived, which connection serves it, and the sidecar that carries the
//! table from one run to the next. Pure bookkeeping with no network or disk,
//! so every rule here is tested on its own.
//!
//! The planning rules are Last Download Manager's: a free connection takes the
//! unassigned segment with the most bytes left, and otherwise halves what
//! remains of the busiest one.

use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// The most connections one download opens at once.
pub const MAX_CONNECTIONS: usize = 8;
/// A segment is split only when this much remains, so both halves are worth
/// a connection. Each connection costs the source a request, which a debrid
/// service counts, so this is larger than a general download manager's.
pub const MIN_SPLIT_BYTES: u64 = 4 * 1024 * 1024;
/// One more connection is allowed this often until the limit is reached.
pub const RAMP_INTERVAL: Duration = Duration::from_millis(250);
/// Bytes a connection reads past its segment's end, compared with what the
/// next segment's own connection received there.
pub const OVERLAP_BYTES: usize = 64;
/// The end of a segment whose source never stated the file's size.
pub const UNBOUNDED: u64 = u64::MAX;
const MAX_RETRY_AFTER: Duration = Duration::from_secs(60);
const TABLE_VERSION: u32 = 1;
/// A table is rebuilt from a file anyone can edit; this bounds what it costs.
const MAX_SAVED_SEGMENTS: usize = 4096;

/// The connection strategy's tunable values. Tests shorten them.
#[derive(Debug, Clone, Copy)]
pub struct Tuning {
    pub max_connections: usize,
    pub min_split_bytes: u64,
    pub ramp_interval: Duration,
}

impl Default for Tuning {
    fn default() -> Self {
        Self {
            max_connections: MAX_CONNECTIONS,
            min_split_bytes: MIN_SPLIT_BYTES,
            ramp_interval: RAMP_INTERVAL,
        }
    }
}

/// The planner's view of one segment.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlanSegment {
    pub start: u64,
    /// Exclusive.
    pub end: u64,
    /// The next byte to receive.
    pub position: u64,
    /// A connection serves it, or it waits to be retried.
    pub assigned: bool,
}

impl PlanSegment {
    pub fn remaining(&self) -> u64 {
        self.end.saturating_sub(self.position)
    }
}

/// Work for one free connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlanStep {
    /// Serve the unassigned segment at `index` from its position.
    Resume { index: usize },
    /// End the segment at `index` at `at`; the new connection takes the rest.
    Split { index: usize, at: u64 },
}

/// Chooses work for one newly available connection: an unassigned, incomplete
/// segment, largest remaining first; otherwise the segment with the most
/// bytes left, split at the middle of what remains when that is at least
/// `min_split_bytes`; otherwise nothing, because another connection would not
/// help. Ties go to the earlier segment.
pub fn choose_work(segments: &[PlanSegment], min_split_bytes: u64) -> Option<PlanStep> {
    let mut best_idle: Option<usize> = None;
    let mut best_busy: Option<usize> = None;
    for (index, segment) in segments.iter().enumerate() {
        if segment.remaining() == 0 {
            continue;
        }
        let best = if segment.assigned {
            &mut best_busy
        } else {
            &mut best_idle
        };
        if best.is_none_or(|current| segment.remaining() > segments[current].remaining()) {
            *best = Some(index);
        }
    }
    if let Some(index) = best_idle {
        return Some(PlanStep::Resume { index });
    }
    let index = best_busy?;
    let busy = &segments[index];
    let remaining = busy.remaining();
    (remaining >= min_split_bytes).then_some(PlanStep::Split {
        index,
        at: busy.position + remaining / 2,
    })
}

/// How long a refused segment waits before it is asked for again: what the
/// source's Retry-After said, capped, or else 1, 2 and then 4 seconds.
pub fn retry_delay(attempt: u32, retry_after_seconds: Option<u64>) -> Duration {
    if let Some(seconds) = retry_after_seconds {
        return Duration::from_secs(seconds).min(MAX_RETRY_AFTER);
    }
    Duration::from_secs(1 << attempt.saturating_sub(1).min(2))
}

/// How many connections a run may have open. It starts at one, grows by one
/// every ramp interval, and once the source refuses a connection while others
/// still receive, never grows past what it took at that moment again.
#[derive(Debug)]
pub struct ConnectionBudget {
    max: usize,
    interval: Duration,
    allowed: usize,
    ceiling: usize,
    next_ramp: Instant,
}

impl ConnectionBudget {
    pub fn new(max: usize, interval: Duration, now: Instant) -> Self {
        let max = max.max(1);
        Self {
            max,
            interval,
            allowed: 1,
            ceiling: max,
            next_ramp: now + interval,
        }
    }
    fn cap(&self) -> usize {
        self.max.min(self.ceiling)
    }
    pub fn ramp(&mut self, now: Instant) {
        if self.allowed < self.cap() && now >= self.next_ramp {
            self.allowed += 1;
            self.next_ramp = now + self.interval;
        }
    }
    pub fn allowed(&self) -> usize {
        self.allowed
    }
    /// The source refused a connection while `live` others kept receiving.
    pub fn refuse(&mut self, live: usize) {
        self.ceiling = self.ceiling.min(live.max(1));
        self.allowed = self.allowed.min(self.ceiling);
    }
    /// When the budget grows next, if it still can.
    pub fn next_ramp(&self) -> Option<Instant> {
        (self.allowed < self.cap()).then_some(self.next_ramp)
    }
}

/// Raised when two connections delivered different bytes for the same
/// offsets: the source served two different objects under one validator.
#[derive(Debug, PartialEq, Eq)]
pub struct OverlapMismatch;

#[derive(Debug, Clone)]
pub struct Segment {
    pub key: u32,
    pub start: u64,
    /// Exclusive; [`UNBOUNDED`] while the file's size is unknown.
    pub end: u64,
    /// The next byte to receive; everything before it is written.
    pub position: u64,
    pub connection: Option<u64>,
    pub retry_at: Option<Instant>,
    pub attempts: u32,
    /// The first bytes of the segment as its own connection received them.
    head: Vec<u8>,
    head_from_start: bool,
    /// Bytes the previous segment's connection read past its end, not yet
    /// all compared with `head`.
    overlap_from_previous: Vec<u8>,
}

impl Segment {
    fn new(key: u32, start: u64, end: u64, position: u64) -> Self {
        Self {
            key,
            start,
            end,
            position,
            connection: None,
            retry_at: None,
            attempts: 0,
            head: Vec::new(),
            head_from_start: false,
            overlap_from_previous: Vec::new(),
        }
    }
    pub fn is_complete(&self) -> bool {
        self.end != UNBOUNDED && self.position >= self.end
    }
    fn verify_overlap(&mut self) -> Result<(), OverlapMismatch> {
        if self.overlap_from_previous.is_empty() || !self.head_from_start {
            return Ok(());
        }
        let compared = self.head.len().min(self.overlap_from_previous.len());
        if self.head[..compared] != self.overlap_from_previous[..compared] {
            return Err(OverlapMismatch);
        }
        if compared == self.overlap_from_previous.len() {
            self.overlap_from_previous.clear();
        }
        Ok(())
    }
}

/// The table as the sidecar stores it. `done` counts the bytes from `start`
/// that were on disk, synced, when the table was written.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedTable {
    pub version: u32,
    pub total: u64,
    pub validator: String,
    pub segments: Vec<SavedSegment>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct SavedSegment {
    pub start: u64,
    pub end: u64,
    pub done: u64,
}

pub fn encode(table: &SavedTable) -> serde_json::Result<Vec<u8>> {
    serde_json::to_vec(table)
}

pub fn decode(raw: &[u8]) -> Option<SavedTable> {
    serde_json::from_slice(raw).ok()
}

/// The sidecar beside a part file.
pub fn table_path(part: &Path) -> PathBuf {
    let mut value = part.as_os_str().to_owned();
    value.push(".segments");
    PathBuf::from(value)
}

/// Where the sidecar is written before it replaces the old one.
pub fn table_temp_path(table: &Path) -> PathBuf {
    let mut value = table.as_os_str().to_owned();
    value.push(".tmp");
    PathBuf::from(value)
}

/// Segments in file order, contiguous from 0 to the end of the file.
#[derive(Debug, Clone)]
pub struct SegmentTable {
    segments: Vec<Segment>,
    next_key: u32,
}

impl SegmentTable {
    /// The table of a part written front to back: `done` bytes received, the
    /// rest one segment up to `end`.
    pub fn contiguous(done: u64, end: u64) -> Self {
        let mut table = Self {
            segments: Vec::new(),
            next_key: 0,
        };
        if done > 0 {
            table.push(0, done, done);
        }
        if end > done {
            table.push(done, end, done);
        }
        table
    }

    /// The table a sidecar describes, when it still describes this download:
    /// same size, same validator, segments that cover the file exactly, and
    /// no claim to bytes past the end of the part on disk. Neighbours behind
    /// a finished segment are folded into it so tables do not grow from run
    /// to run.
    pub fn restore(
        saved: &SavedTable,
        total: u64,
        validator: Option<&str>,
        part_len: u64,
    ) -> Option<Self> {
        let validator = validator.filter(|value| !value.is_empty())?;
        if saved.version != TABLE_VERSION
            || total == 0
            || saved.total != total
            || saved.validator != validator
            || part_len > total
            || saved.segments.is_empty()
            || saved.segments.len() > MAX_SAVED_SEGMENTS
        {
            return None;
        }
        let mut folded: Vec<SavedSegment> = Vec::with_capacity(saved.segments.len());
        let mut expected = 0u64;
        for segment in &saved.segments {
            let length = segment.end.checked_sub(segment.start)?;
            if segment.start != expected
                || length == 0
                || segment.done > length
                || segment.start + segment.done > part_len
            {
                return None;
            }
            expected = segment.end;
            match folded.last_mut() {
                Some(previous) if previous.done == previous.end - previous.start => {
                    previous.done += segment.done;
                    previous.end = segment.end;
                }
                _ => folded.push(*segment),
            }
        }
        if expected != total {
            return None;
        }
        let mut table = Self {
            segments: Vec::with_capacity(folded.len()),
            next_key: 0,
        };
        for segment in folded {
            table.push(segment.start, segment.end, segment.start + segment.done);
        }
        Some(table)
    }

    /// The table as it stands, for the sidecar. The caller writes it only
    /// after syncing the part, so every `done` byte is on disk.
    pub fn saved(&self, total: u64, validator: &str) -> SavedTable {
        SavedTable {
            version: TABLE_VERSION,
            total,
            validator: validator.to_string(),
            segments: self
                .segments
                .iter()
                .map(|segment| SavedSegment {
                    start: segment.start,
                    end: segment.end,
                    done: segment.position.min(segment.end) - segment.start,
                })
                .collect(),
        }
    }

    fn push(&mut self, start: u64, end: u64, position: u64) {
        let key = self.next_key;
        self.next_key += 1;
        self.segments.push(Segment::new(key, start, end, position));
    }

    pub fn segments(&self) -> &[Segment] {
        &self.segments
    }

    fn index_of(&self, key: u32) -> Option<usize> {
        self.segments.iter().position(|segment| segment.key == key)
    }

    pub fn get(&self, key: u32) -> Option<&Segment> {
        self.segments.iter().find(|segment| segment.key == key)
    }

    pub fn get_mut(&mut self, key: u32) -> Option<&mut Segment> {
        self.segments.iter_mut().find(|segment| segment.key == key)
    }

    /// The segment that starts where `key` ends.
    pub fn next_of(&self, key: u32) -> Option<&Segment> {
        let end = self.get(key)?.end;
        self.segments.iter().find(|segment| segment.start == end)
    }

    /// The segment that ends where `key` starts.
    pub fn previous_of(&self, key: u32) -> Option<&Segment> {
        let start = self.get(key)?.start;
        self.segments
            .iter()
            .find(|segment| segment.end == start && segment.end != UNBOUNDED)
    }

    /// Work for one free connection, with segments that wait to be retried
    /// counted as taken. Nothing while the file's size is unknown.
    pub fn choose(&self, now: Instant, min_split_bytes: u64) -> Option<PlanStep> {
        if self.segments.iter().any(|segment| segment.end == UNBOUNDED) {
            return None;
        }
        let plan: Vec<PlanSegment> = self
            .segments
            .iter()
            .map(|segment| PlanSegment {
                start: segment.start,
                end: segment.end,
                position: segment.position,
                assigned: segment.connection.is_some()
                    || segment.retry_at.is_some_and(|at| at > now),
            })
            .collect();
        choose_work(&plan, min_split_bytes)
    }

    /// The key of the segment a step serves, splitting first when it says so.
    pub fn take_step(&mut self, step: PlanStep) -> Option<u32> {
        match step {
            PlanStep::Resume { index } => self.segments.get(index).map(|segment| segment.key),
            PlanStep::Split { index, at } => self.split(index, at),
        }
    }

    /// Ends the segment at `index` at `at` and inserts the rest after it.
    fn split(&mut self, index: usize, at: u64) -> Option<u32> {
        let segment = self.segments.get_mut(index)?;
        if at <= segment.position || at >= segment.end {
            return None;
        }
        let end = segment.end;
        segment.end = at;
        let key = self.next_key;
        self.next_key += 1;
        self.segments
            .insert(index + 1, Segment::new(key, at, end, at));
        Some(key)
    }

    /// Gives the segment after `key` back to it, when no connection serves
    /// that one and nothing of it arrived yet. Returns whether it did.
    pub fn absorb_next(&mut self, key: u32) -> bool {
        let Some(index) = self.index_of(key) else {
            return false;
        };
        let end = self.segments[index].end;
        let Some(next) = self
            .segments
            .iter()
            .position(|segment| segment.start == end && end != UNBOUNDED)
        else {
            return false;
        };
        let taken = &self.segments[next];
        if taken.connection.is_some() || taken.position != taken.start {
            return false;
        }
        let new_end = taken.end;
        self.segments.remove(next);
        self.segments[index].end = new_end;
        true
    }

    /// `connection` starts serving `key` from its position. Starting at the
    /// segment's first byte, it records the head the overlap check needs.
    pub fn begin(&mut self, key: u32, connection: u64) {
        if let Some(segment) = self.get_mut(key) {
            segment.connection = Some(connection);
            segment.retry_at = None;
            if segment.position == segment.start {
                segment.head.clear();
                segment.head_from_start = true;
            }
        }
    }

    /// The connection serving `key` stopped.
    pub fn release(&mut self, key: u32) {
        if let Some(segment) = self.get_mut(key) {
            segment.connection = None;
        }
    }

    /// `bytes` were written at `key`'s position.
    pub fn record(&mut self, key: u32, bytes: &[u8]) -> Result<(), OverlapMismatch> {
        let Some(segment) = self.get_mut(key) else {
            return Ok(());
        };
        let into = segment.position - segment.start;
        if segment.head_from_start
            && into < OVERLAP_BYTES as u64
            && into == segment.head.len() as u64
        {
            let take = (OVERLAP_BYTES - segment.head.len()).min(bytes.len());
            segment.head.extend_from_slice(&bytes[..take]);
            segment.verify_overlap()?;
        }
        segment.position += bytes.len() as u64;
        Ok(())
    }

    /// How many bytes past its end the connection serving `key` should read
    /// for the overlap check: none when no next segment can still be
    /// compared (one resumed from an earlier run has no head from its start).
    pub fn overlap_wanted(&self, key: u32, total: u64) -> usize {
        let (Some(segment), Some(next)) = (self.get(key), self.next_of(key)) else {
            return 0;
        };
        if !next.head_from_start && next.position != next.start {
            return 0;
        }
        let left = total.saturating_sub(segment.end);
        usize::try_from(left).map_or(OVERLAP_BYTES, |left| left.min(OVERLAP_BYTES))
    }

    /// Hands the bytes read past `key`'s end to the next segment, comparing
    /// what its own connection already received.
    pub fn hand_over_overlap(&mut self, key: u32, overlap: Vec<u8>) -> Result<(), OverlapMismatch> {
        let Some(end) = self.get(key).map(|segment| segment.end) else {
            return Ok(());
        };
        let Some(next) = self
            .segments
            .iter_mut()
            .find(|segment| segment.start == end)
        else {
            return Ok(());
        };
        next.overlap_from_previous = overlap;
        next.verify_overlap()
    }

    /// Bytes written across every segment.
    pub fn received(&self) -> u64 {
        self.segments
            .iter()
            .map(|segment| segment.position.min(segment.end) - segment.start)
            .sum()
    }

    pub fn is_complete(&self) -> bool {
        self.segments.iter().all(Segment::is_complete)
    }

    /// The earliest moment a waiting segment may be asked for again.
    pub fn next_retry(&self, now: Instant) -> Option<Instant> {
        self.segments
            .iter()
            .filter(|segment| segment.connection.is_none())
            .filter_map(|segment| segment.retry_at)
            .filter(|at| *at > now)
            .min()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIB: u64 = 1024 * 1024;

    fn plan(start: u64, end: u64, position: u64, assigned: bool) -> PlanSegment {
        PlanSegment {
            start,
            end,
            position,
            assigned,
        }
    }

    fn saved(total: u64, segments: &[(u64, u64, u64)]) -> SavedTable {
        SavedTable {
            version: TABLE_VERSION,
            total,
            validator: "\"v1\"".into(),
            segments: segments
                .iter()
                .map(|&(start, end, done)| SavedSegment { start, end, done })
                .collect(),
        }
    }

    #[test]
    fn an_unassigned_segment_with_the_most_left_is_resumed_first() {
        let segments = [
            plan(0, 10 * MIB, 9 * MIB, false),
            plan(10 * MIB, 20 * MIB, 10 * MIB, true),
            plan(20 * MIB, 30 * MIB, 25 * MIB, false),
        ];
        assert_eq!(
            choose_work(&segments, MIN_SPLIT_BYTES),
            Some(PlanStep::Resume { index: 2 })
        );
    }

    #[test]
    fn ties_go_to_the_earlier_segment() {
        let segments = [plan(0, 10, 5, false), plan(10, 20, 15, false)];
        assert_eq!(
            choose_work(&segments, 1),
            Some(PlanStep::Resume { index: 0 })
        );
    }

    #[test]
    fn the_busiest_segment_is_split_at_the_middle_of_what_remains() {
        let segments = [
            plan(0, 10 * MIB, 2 * MIB, true),
            plan(10 * MIB, 40 * MIB, 20 * MIB, true),
            plan(40 * MIB, 50 * MIB, 50 * MIB, false),
        ];
        assert_eq!(
            choose_work(&segments, MIN_SPLIT_BYTES),
            Some(PlanStep::Split {
                index: 1,
                at: 30 * MIB
            })
        );
    }

    #[test]
    fn a_segment_below_the_split_threshold_is_left_alone() {
        let below = [plan(0, 100 * MIB, 100 * MIB - (MIN_SPLIT_BYTES - 1), true)];
        assert_eq!(choose_work(&below, MIN_SPLIT_BYTES), None);
        let at = [plan(0, 100 * MIB, 100 * MIB - MIN_SPLIT_BYTES, true)];
        assert_eq!(
            choose_work(&at, MIN_SPLIT_BYTES),
            Some(PlanStep::Split {
                index: 0,
                at: 100 * MIB - MIN_SPLIT_BYTES / 2
            })
        );
    }

    #[test]
    fn finished_segments_get_no_work() {
        let segments = [plan(0, 10, 10, false), plan(10, 20, 20, true)];
        assert_eq!(choose_work(&segments, 1), None);
    }

    #[test]
    fn a_split_shortens_the_busy_segment_and_starts_the_rest_unassigned() {
        let mut table = SegmentTable::contiguous(0, 32 * MIB);
        let first = table.segments()[0].key;
        table.begin(first, 1);
        let now = Instant::now();
        let step = table.choose(now, MIN_SPLIT_BYTES).unwrap();
        let added = table.take_step(step).unwrap();
        assert_eq!(table.get(first).unwrap().end, 16 * MIB);
        let second = table.get(added).unwrap();
        assert_eq!(
            (second.start, second.end, second.position),
            (16 * MIB, 32 * MIB, 16 * MIB)
        );
        assert!(second.connection.is_none());
        assert_eq!(table.next_of(first).unwrap().key, added);
        assert_eq!(table.previous_of(added).unwrap().key, first);
    }

    #[test]
    fn a_segment_waiting_to_retry_counts_as_taken_until_its_time() {
        let mut table = SegmentTable::contiguous(0, 32 * MIB);
        let now = Instant::now();
        let key = table.segments()[0].key;
        table.get_mut(key).unwrap().retry_at = Some(now + Duration::from_secs(1));
        assert!(matches!(
            table.choose(now, MIN_SPLIT_BYTES),
            Some(PlanStep::Split { .. })
        ));
        assert_eq!(
            table.choose(now + Duration::from_secs(2), MIN_SPLIT_BYTES),
            Some(PlanStep::Resume { index: 0 })
        );
        assert_eq!(table.next_retry(now), Some(now + Duration::from_secs(1)));
    }

    #[test]
    fn an_unknown_size_is_never_split() {
        let mut table = SegmentTable::contiguous(0, UNBOUNDED);
        let key = table.segments()[0].key;
        table.begin(key, 1);
        assert_eq!(table.choose(Instant::now(), 1), None);
    }

    #[test]
    fn an_unstarted_segment_is_given_back_to_the_one_before_it() {
        let mut table = SegmentTable::contiguous(0, 32 * MIB);
        let first = table.segments()[0].key;
        table.begin(first, 1);
        let added = table.split(0, 16 * MIB).unwrap();
        table.begin(added, 2);
        assert!(!table.absorb_next(first), "a served segment stays");
        table.release(added);
        assert!(table.absorb_next(first));
        assert_eq!(table.segments().len(), 1);
        assert_eq!(table.get(first).unwrap().end, 32 * MIB);
    }

    #[test]
    fn progress_is_the_sum_of_every_segments_bytes() {
        let mut table = SegmentTable::contiguous(4, 100);
        let key = table.segments()[1].key;
        table.record(key, &[0; 10]).unwrap();
        let added = table.split(1, 50).unwrap();
        table.record(added, &[0; 5]).unwrap();
        assert_eq!(table.received(), 19);
        assert!(!table.is_complete());
    }

    #[test]
    fn the_sidecar_round_trips_and_resumes_each_segment() {
        let mut table = SegmentTable::contiguous(0, 100);
        let first = table.segments()[0].key;
        table.begin(first, 1);
        table.record(first, &[1; 10]).unwrap();
        let second = table.split(0, 60).unwrap();
        table.record(second, &[2; 5]).unwrap();
        let raw = encode(&table.saved(100, "\"v1\"")).unwrap();
        let decoded = decode(&raw).unwrap();
        assert_eq!(decoded, saved(100, &[(0, 60, 10), (60, 100, 5)]));
        let restored = SegmentTable::restore(&decoded, 100, Some("\"v1\""), 100).unwrap();
        let positions: Vec<_> = restored
            .segments()
            .iter()
            .map(|segment| (segment.start, segment.end, segment.position))
            .collect();
        assert_eq!(positions, vec![(0, 60, 10), (60, 100, 65)]);
        assert!(restored
            .segments()
            .iter()
            .all(|segment| segment.connection.is_none()));
    }

    #[test]
    fn finished_neighbours_fold_into_one_segment_on_restore() {
        let table = saved(100, &[(0, 20, 20), (20, 50, 30), (50, 80, 7), (80, 100, 0)]);
        let restored = SegmentTable::restore(&table, 100, Some("\"v1\""), 100).unwrap();
        let shape: Vec<_> = restored
            .segments()
            .iter()
            .map(|segment| (segment.start, segment.end, segment.position))
            .collect();
        assert_eq!(shape, vec![(0, 80, 57), (80, 100, 80)]);
        assert_eq!(restored.received(), 57);
    }

    #[test]
    fn a_sidecar_that_no_longer_fits_the_download_is_refused() {
        let good = saved(100, &[(0, 50, 50), (50, 100, 10)]);
        assert!(SegmentTable::restore(&good, 100, Some("\"v1\""), 100).is_some());
        // Another size, another validator, or none at all.
        assert!(SegmentTable::restore(&good, 101, Some("\"v1\""), 100).is_none());
        assert!(SegmentTable::restore(&good, 100, Some("\"v2\""), 100).is_none());
        assert!(SegmentTable::restore(&good, 100, None, 100).is_none());
        // A claim to bytes past the end of the part, or a part longer than the file.
        assert!(SegmentTable::restore(&good, 100, Some("\"v1\""), 59).is_none());
        assert!(SegmentTable::restore(&good, 100, Some("\"v1\""), 101).is_none());
        // Gaps, overlaps, a short cover, overstated progress, an unknown version.
        for broken in [
            saved(100, &[(0, 40, 0), (50, 100, 0)]),
            saved(100, &[(0, 60, 0), (50, 100, 0)]),
            saved(100, &[(0, 50, 0), (50, 90, 0)]),
            saved(100, &[(0, 50, 51), (50, 100, 0)]),
            saved(100, &[(10, 100, 0)]),
            saved(100, &[]),
        ] {
            assert!(
                SegmentTable::restore(&broken, 100, Some("\"v1\""), 100).is_none(),
                "{broken:?}"
            );
        }
        let mut future = good;
        future.version = 2;
        assert!(SegmentTable::restore(&future, 100, Some("\"v1\""), 100).is_none());
        assert!(decode(b"{ not json").is_none());
    }

    #[test]
    fn a_part_from_before_sidecars_is_one_finished_segment() {
        let table = SegmentTable::contiguous(40, 100);
        let shape: Vec<_> = table
            .segments()
            .iter()
            .map(|segment| (segment.start, segment.end, segment.position))
            .collect();
        assert_eq!(shape, vec![(0, 40, 40), (40, 100, 40)]);
        assert_eq!(table.received(), 40);
        let fresh = SegmentTable::contiguous(0, 100);
        assert_eq!(fresh.segments().len(), 1);
        assert_eq!(fresh.received(), 0);
    }

    #[test]
    fn matching_overlap_bytes_pass_in_either_order() {
        // The next segment's head arrives first.
        let mut table = SegmentTable::contiguous(0, 200);
        let first = table.segments()[0].key;
        let second = table.split(0, 100).unwrap();
        table.begin(second, 2);
        table.record(second, &[7; 80]).unwrap();
        assert_eq!(table.overlap_wanted(first, 200), OVERLAP_BYTES);
        assert_eq!(
            table.hand_over_overlap(first, vec![7; OVERLAP_BYTES]),
            Ok(())
        );
        // The overlap arrives first and is compared as the head grows.
        let mut table = SegmentTable::contiguous(0, 200);
        let first = table.segments()[0].key;
        let second = table.split(0, 100).unwrap();
        table.begin(second, 2);
        assert_eq!(
            table.hand_over_overlap(first, vec![7; OVERLAP_BYTES]),
            Ok(())
        );
        table.record(second, &[7; 10]).unwrap();
        table.record(second, &[7; 70]).unwrap();
    }

    #[test]
    fn different_overlap_bytes_are_a_mismatch() {
        let mut table = SegmentTable::contiguous(0, 200);
        let first = table.segments()[0].key;
        let second = table.split(0, 100).unwrap();
        table.begin(second, 2);
        table.record(second, &[7; 80]).unwrap();
        let mut overlap = vec![7; OVERLAP_BYTES];
        overlap[OVERLAP_BYTES - 1] = 8;
        assert_eq!(
            table.hand_over_overlap(first, overlap),
            Err(OverlapMismatch)
        );

        let mut table = SegmentTable::contiguous(0, 200);
        let first = table.segments()[0].key;
        let second = table.split(0, 100).unwrap();
        table.begin(second, 2);
        table
            .hand_over_overlap(first, vec![7; OVERLAP_BYTES])
            .unwrap();
        assert_eq!(table.record(second, &[9; 4]), Err(OverlapMismatch));
    }

    #[test]
    fn the_overlap_stops_at_the_end_of_the_file_and_skips_resumed_segments() {
        let mut table = SegmentTable::contiguous(0, 130);
        let first = table.segments()[0].key;
        table.split(0, 100).unwrap();
        assert_eq!(table.overlap_wanted(first, 130), 30);
        let resumed = SegmentTable::restore(
            &saved(130, &[(0, 100, 10), (100, 130, 5)]),
            130,
            Some("\"v1\""),
            130,
        )
        .unwrap();
        let first = resumed.segments()[0].key;
        assert_eq!(resumed.overlap_wanted(first, 130), 0);
        let last = resumed.segments()[1].key;
        assert_eq!(resumed.overlap_wanted(last, 130), 0);
    }

    #[test]
    fn the_budget_ramps_one_at_a_time_and_keeps_its_ceiling() {
        let start = Instant::now();
        let mut budget = ConnectionBudget::new(MAX_CONNECTIONS, RAMP_INTERVAL, start);
        assert_eq!(budget.allowed(), 1);
        budget.ramp(start);
        assert_eq!(budget.allowed(), 1, "no growth before the first interval");
        let mut now = start;
        for expected in 2..=MAX_CONNECTIONS {
            now += RAMP_INTERVAL;
            budget.ramp(now);
            budget.ramp(now);
            assert_eq!(budget.allowed(), expected);
        }
        now += RAMP_INTERVAL;
        budget.ramp(now);
        assert_eq!(budget.allowed(), MAX_CONNECTIONS);
        assert_eq!(budget.next_ramp(), None);
        budget.refuse(3);
        assert_eq!(budget.allowed(), 3);
        for _ in 0..10 {
            now += RAMP_INTERVAL;
            budget.ramp(now);
        }
        assert_eq!(budget.allowed(), 3, "never past the ceiling again");
        budget.refuse(0);
        assert_eq!(budget.allowed(), 1);
    }

    #[test]
    fn retry_delays_honour_retry_after_and_otherwise_back_off() {
        assert_eq!(retry_delay(1, None), Duration::from_secs(1));
        assert_eq!(retry_delay(2, None), Duration::from_secs(2));
        assert_eq!(retry_delay(3, None), Duration::from_secs(4));
        assert_eq!(retry_delay(9, None), Duration::from_secs(4));
        assert_eq!(retry_delay(1, Some(7)), Duration::from_secs(7));
        assert_eq!(retry_delay(1, Some(3600)), MAX_RETRY_AFTER);
    }

    #[test]
    fn sidecar_paths_sit_beside_the_part() {
        let table = table_path(Path::new(r"C:\Halo\movie.mkv.part"));
        assert_eq!(table, PathBuf::from(r"C:\Halo\movie.mkv.part.segments"));
        assert_eq!(
            table_temp_path(&table),
            PathBuf::from(r"C:\Halo\movie.mkv.part.segments.tmp")
        );
    }
}
