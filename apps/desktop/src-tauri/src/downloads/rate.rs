//! Averages one transfer's throughput over a sliding time window. A single
//! progress slice exaggerates TCP bursts and disk stalls; the window reports
//! what the transfer actually sustained.

use std::collections::VecDeque;
use std::time::{Duration, Instant};

pub struct RateWindow {
    window: Duration,
    samples: VecDeque<(Instant, u64)>,
}

impl RateWindow {
    /// Starts measuring from `bytes` already on disk at `now`.
    pub fn new(window: Duration, now: Instant, bytes: u64) -> Self {
        let mut rate = Self {
            window,
            samples: VecDeque::new(),
        };
        rate.reset(now, bytes);
        rate
    }

    /// Forgets all history and starts again from this byte count.
    pub fn reset(&mut self, now: Instant, bytes: u64) {
        self.samples.clear();
        self.samples.push_back((now, bytes));
    }

    /// Records the running byte count of the transfer and returns the average
    /// bytes per second over the retained window. Returns 0 until a second
    /// sample exists.
    pub fn record(&mut self, now: Instant, bytes: u64) -> u64 {
        let Some(&(last_time, last_bytes)) = self.samples.back() else {
            self.samples.push_back((now, bytes));
            return 0;
        };
        if bytes < last_bytes || now < last_time {
            // The byte count went backwards or the clock did. Either way the
            // history no longer describes this transfer.
            self.reset(now, bytes);
            return 0;
        }
        self.samples.push_back((now, bytes));
        // Drop the oldest samples while the next one still spans the full
        // window, so the average covers the window and not much more.
        while self.samples.len() > 1 && now.duration_since(self.samples[1].0) >= self.window {
            self.samples.pop_front();
        }
        let (oldest_time, oldest_bytes) = self.samples[0];
        let seconds = now.duration_since(oldest_time).as_secs_f64();
        if seconds <= 0.0 {
            return 0;
        }
        ((bytes - oldest_bytes) as f64 / seconds) as u64
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WINDOW: Duration = Duration::from_secs(3);

    #[test]
    fn a_steady_transfer_reports_its_steady_rate() {
        let start = Instant::now();
        let mut rate = RateWindow::new(WINDOW, start, 0);
        let mut last = 0;
        for tick in 1..=20u64 {
            last = rate.record(start + Duration::from_millis(250 * tick), 250_000 * tick);
        }
        assert_eq!(last, 1_000_000);
    }

    #[test]
    fn a_burst_is_averaged_over_the_window_rather_than_reported_raw() {
        let start = Instant::now();
        let mut rate = RateWindow::new(WINDOW, start, 0);
        rate.record(start + Duration::from_secs(1), 1_000_000);
        rate.record(start + Duration::from_secs(2), 2_000_000);
        // A 5 MB burst in the last quarter second reads as 40 MB/s raw.
        let averaged = rate.record(start + Duration::from_millis(2_250), 7_000_000);
        assert!(averaged < 4_000_000, "burst leaked through: {averaged}");
    }

    #[test]
    fn old_samples_leave_the_window() {
        let start = Instant::now();
        let mut rate = RateWindow::new(WINDOW, start, 0);
        // Fast for ten seconds, then stalled: the stall must dominate.
        for second in 1..=10u64 {
            rate.record(start + Duration::from_secs(second), 10_000_000 * second);
        }
        let mut value = 0;
        for second in 11..=14u64 {
            value = rate.record(start + Duration::from_secs(second), 100_000_000);
        }
        assert_eq!(value, 0);
    }

    #[test]
    fn going_backwards_restarts_the_measurement() {
        let start = Instant::now();
        let mut rate = RateWindow::new(WINDOW, start, 5_000);
        assert_eq!(rate.record(start + Duration::from_secs(1), 1_000), 0);
        assert_eq!(rate.record(start + Duration::from_secs(2), 3_000), 2_000);
    }
}
