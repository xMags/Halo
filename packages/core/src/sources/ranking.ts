import type { Stream } from '../addon/types'
import type { StreamsResult } from '../api/types'
import { parseStreamInfo, qualityRank, type Quality } from './streamInfo'

/**
 * Which source plays best on this device: the one rule every Halo client and
 * the Halo API order sources by. Pure, with no I/O, so the server's ranking
 * and a client's own ranking of the same streams cannot differ.
 *
 * The old order (cached, then resolution, then the largest file) made the
 * best pick on a 4K title the heaviest remux, which stalls on an ordinary
 * line. This one still prefers a file that plays at once, but only ranks the
 * biggest picture first when the line can carry it and the screen can show it.
 *
 * The Kotlin app carries a copy of this rule; both are held to the cases in
 * `test/vectors/source-ranking.json`. Change a rule in both, with a case.
 */

/**
 * The line speed assumed before anything has measured it, in megabits per
 * second: an ordinary broadband line, so a first run neither trusts every
 * remux nor writes off every 4K file.
 */
export const ASSUMED_LINE_MBPS = 40

/**
 * The share of the line a stream may use and still be called safe. A line
 * is never all available (other traffic, the debrid host's own pace), and a
 * file needing exactly the line's speed would buffer at every busy scene.
 */
export const LINE_HEADROOM = 0.7

/**
 * How far a picture may outgrow the screen before its extra lines count as
 * wasted. A 1440p file on a 1200-pixel screen still looks sharper; a 2160p
 * file on a 1080p screen mostly costs bandwidth.
 */
export const SCREEN_MARGIN = 1.25

/** Bounds of a kept line reading, in megabits per second. */
export const MIN_LINE_MBPS = 0.5
export const MAX_LINE_MBPS = 100_000

/**
 * Largest accepted ranking inputs. Anything above is not a real device or
 * title, and is treated as unknown rather than trusted.
 */
export const MAX_SCREEN_HEIGHT = 100_000
/** A week: longer than any title, short enough to reject nonsense. */
export const MAX_RUNTIME_SECONDS = 604_800

/** Nominal picture height of each quality, in pixels. */
const QUALITY_HEIGHT: Record<Quality, number> = {
  '2160p': 2160,
  '1440p': 1440,
  '1080p': 1080,
  '720p': 720,
  '480p': 480,
  SD: 480,
}

/**
 * How a source sits on this device: it `fits`, its picture is far larger than
 * the screen (`exceedsScreen`), or it needs more than the line's safe share
 * (`overBudget`, which may buffer). A source whose bitrate is unknown fits.
 */
export type SourceFit = 'fits' | 'exceedsScreen' | 'overBudget'

const FIT_TIER: Record<SourceFit, number> = { fits: 0, exceedsScreen: 1, overBudget: 2 }

/** What the device says about itself. Zero means unknown in both fields. */
export interface RankingDevice {
  /** Measured line speed in megabits per second; 0 = not measured yet. */
  lineMbps: number
  /** Physical pixels of the screen's shorter side; 0 = unknown, so no cap. */
  screenHeight: number
}

/** A device plus the title's runtime, which turns file sizes into bitrates. */
export interface StreamRankingInput extends RankingDevice {
  /** Runtime of the video in seconds; 0 = unknown, so every bitrate is unknown. */
  runtimeSeconds: number
}

/** The facts of one source the rule reads. */
export interface RankableSource {
  /** True when already on the debrid; false and null rank the same. */
  cached: boolean | null
  quality: Quality | null
  sizeBytes: number | null
  /** Megabits per second the file needs; 0 when its size or runtime is unknown. */
  neededMbps: number
}

/** A source's place in the best-first order (0 is the pick) and its fit. */
export interface SourceRank {
  rank: number
  fit: SourceFit
}

/** The share of the line, in megabits per second, a source may need and still fit. */
export function budgetMbps(lineMbps: number): number {
  return (lineMbps > 0 ? lineMbps : ASSUMED_LINE_MBPS) * LINE_HEADROOM
}

/**
 * Megabits per second a file of this size needs to play at its runtime; 0
 * when either is unknown. Computed in this order on every platform, so the
 * same inputs give the same number.
 */
export function neededMbps(sizeBytes: number | null, runtimeSeconds: number): number {
  if (sizeBytes == null || !(sizeBytes > 0) || !(runtimeSeconds > 0)) return 0
  return (sizeBytes * 8) / runtimeSeconds / 1_000_000
}

export function sourceFit(source: RankableSource, device: RankingDevice): SourceFit {
  if (source.neededMbps > 0 && source.neededMbps > budgetMbps(device.lineMbps)) return 'overBudget'
  if (
    device.screenHeight > 0 &&
    source.quality !== null &&
    QUALITY_HEIGHT[source.quality] > device.screenHeight * SCREEN_MARGIN
  ) {
    return 'exceedsScreen'
  }
  return 'fits'
}

interface Ranked {
  index: number
  source: RankableSource
  fit: SourceFit
}

function compareRanked(a: Ranked, b: Ranked): number {
  const cached = (a.source.cached === true ? 0 : 1) - (b.source.cached === true ? 0 : 1)
  if (cached !== 0) return cached
  const tier = FIT_TIER[a.fit] - FIT_TIER[b.fit]
  if (tier !== 0) return tier
  // Nothing in this tier plays smoothly: the lightest file buffers least.
  if (a.fit === 'overBudget') {
    return a.source.neededMbps - b.source.neededMbps || qualityRank(a.source.quality) - qualityRank(b.source.quality)
  }
  // The larger file at equal resolution, since bitrate is the only quality
  // signal left once the labels match.
  return (
    qualityRank(a.source.quality) - qualityRank(b.source.quality) ||
    (b.source.sizeBytes ?? 0) - (a.source.sizeBytes ?? 0)
  )
}

/**
 * Ranks sources best-first for this device. The answer is in input order:
 * entry `i` says where source `i` ranks and how it fits. Full ties keep their
 * input order (the sort is stable).
 */
export function rankSources(sources: readonly RankableSource[], device: RankingDevice): SourceRank[] {
  const ranked: Ranked[] = sources.map((source, index) => ({ index, source, fit: sourceFit(source, device) }))
  ranked.sort((a, b) => compareRanked(a, b) || a.index - b.index)
  const answer: SourceRank[] = new Array(sources.length)
  ranked.forEach((entry, position) => {
    answer[entry.index] = { rank: position, fit: entry.fit }
  })
  return answer
}

function clampLine(mbps: number): number {
  return Math.min(MAX_LINE_MBPS, Math.max(MIN_LINE_MBPS, mbps))
}

function isReading(mbps: number): boolean {
  return Number.isFinite(mbps) && mbps > 0
}

/**
 * The line speed to keep after a playback session, from the reading kept so
 * far and the session's peak. A faster peak is new evidence and replaces it.
 * A slower peak replaces it only when playback stalled for buffering, since a
 * file that never needed the full line says nothing about the line getting
 * slower. A session that measured nothing changes nothing, and "not measured
 * yet" (0) stays 0 rather than becoming the lower bound.
 */
export function foldLineSpeed(previousMbps: number, sessionPeakMbps: number, stalled: boolean): number {
  if (!isReading(sessionPeakMbps)) return isReading(previousMbps) ? clampLine(previousMbps) : 0
  if (!isReading(previousMbps)) return clampLine(sessionPeakMbps)
  if (sessionPeakMbps > previousMbps) return clampLine(sessionPeakMbps)
  if (stalled) return clampLine(sessionPeakMbps)
  return clampLine(previousMbps)
}

function boundedOrZero(value: number | undefined, max: number): number {
  if (value === undefined || !Number.isFinite(value) || value <= 0 || value > max) return 0
  return value
}

/**
 * The ranking inputs as every caller must use them: anything missing, not a
 * finite number, negative or out of bounds is unknown (0); the line speed is
 * kept to two decimals and the others to whole numbers, so a value sent to
 * the server is always written in plain decimals and reads back the same.
 */
export function normalizeRankingInput(input: Partial<StreamRankingInput> | undefined): StreamRankingInput {
  return {
    lineMbps: Math.round(boundedOrZero(input?.lineMbps, MAX_LINE_MBPS) * 100) / 100,
    screenHeight: Math.round(boundedOrZero(input?.screenHeight, MAX_SCREEN_HEIGHT)),
    runtimeSeconds: Math.round(boundedOrZero(input?.runtimeSeconds, MAX_RUNTIME_SECONDS)),
  }
}

/** Whether two ranking inputs would rank the same streams the same way. */
export function sameRankingInput(a: StreamRankingInput, b: StreamRankingInput): boolean {
  return a.lineMbps === b.lineMbps && a.screenHeight === b.screenHeight && a.runtimeSeconds === b.runtimeSeconds
}

/** The rule's facts of one stream, read from the addon's text. */
export function rankableStream(stream: Stream, runtimeSeconds: number): RankableSource {
  const info = parseStreamInfo(stream)
  return {
    cached: info.cached,
    quality: info.quality,
    sizeBytes: info.sizeBytes,
    neededMbps: neededMbps(info.sizeBytes, runtimeSeconds),
  }
}

/**
 * Annotates every stream of a streams answer with its `halo` rank and fit,
 * ranked across all the addons at once. The API and the on-device backend
 * both answer through this. Addons, their order and the order of streams
 * inside each stay as they were, so a client that ignores `halo` sees the
 * answer it always did. Any `halo` a stream already carried is replaced.
 */
export function rankStreamResults(result: StreamsResult, input: Partial<StreamRankingInput> | undefined): StreamsResult {
  const ranking = normalizeRankingInput(input)
  const sources = result.results.flatMap((group) =>
    group.streams.map((stream) => rankableStream(stream, ranking.runtimeSeconds)),
  )
  const ranks = rankSources(sources, ranking)
  let next = 0
  return {
    ...result,
    results: result.results.map((group) => ({
      ...group,
      streams: group.streams.map((stream) => ({ ...stream, halo: ranks[next++]! })),
    })),
  }
}

/**
 * A `halo` annotation as a client may trust it: a whole, non-negative rank and
 * a known fit. Anything else (an older server sends none; a server is still
 * untrusted input) reads as null.
 */
export function readSourceRank(value: unknown): SourceRank | null {
  if (typeof value !== 'object' || value === null) return null
  const { rank, fit } = value as { rank?: unknown; fit?: unknown }
  if (typeof rank !== 'number' || !Number.isSafeInteger(rank) || rank < 0) return null
  if (fit !== 'fits' && fit !== 'exceedsScreen' && fit !== 'overBudget') return null
  return { rank, fit }
}
