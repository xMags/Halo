import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  foldLineSpeed,
  neededMbps,
  normalizeRankingInput,
  rankSources,
  rankStreamResults,
  readSourceRank,
  sameRankingInput,
  type RankableSource,
  type SourceFit,
  type StreamsResult,
} from '../src/index'

/*
 * The shared vectors (test/vectors/source-ranking.json) hold the rule both
 * this package and the Kotlin app must follow; see their `about` line.
 */

interface RankingCase {
  name: string
  device: { lineMbps: number; screenHeight: number }
  sources: Array<RankableSource & { id: string }>
  order: string[]
  fit: Record<string, SourceFit>
}

interface LineSpeedCase {
  name: string
  previous: number
  sessionPeak: number
  stalled: boolean
  expect: number
}

const vectors = JSON.parse(readFileSync(new URL('./vectors/source-ranking.json', import.meta.url), 'utf8')) as {
  ranking: RankingCase[]
  lineSpeed: LineSpeedCase[]
}

for (const vector of vectors.ranking) {
  test(`ranking: ${vector.name}`, () => {
    const ranks = rankSources(vector.sources, vector.device)
    const order = vector.sources
      .map((source, index) => ({ id: source.id, rank: ranks[index]!.rank }))
      .sort((a, b) => a.rank - b.rank)
      .map((entry) => entry.id)
    assert.deepEqual(order, vector.order)
    // Ranks are the positions 0..n-1, each used once.
    assert.deepEqual(
      ranks.map((entry) => entry.rank).sort((a, b) => a - b),
      vector.sources.map((_, index) => index),
    )
    const fit = Object.fromEntries(vector.sources.map((source, index) => [source.id, ranks[index]!.fit]))
    assert.deepEqual(fit, vector.fit)
  })
}

for (const vector of vectors.lineSpeed) {
  test(`line speed: ${vector.name}`, () => {
    assert.equal(foldLineSpeed(vector.previous, vector.sessionPeak, vector.stalled), vector.expect)
  })
}

test('line speed: a peak that is not a finite number keeps the reading', () => {
  assert.equal(foldLineSpeed(50, Number.NaN, true), 50)
  assert.equal(foldLineSpeed(50, Number.POSITIVE_INFINITY, false), 50)
  assert.equal(foldLineSpeed(Number.NaN, Number.NaN, false), 0)
  assert.equal(foldLineSpeed(Number.NaN, 12, false), 12)
})

test('needed bitrate is size over runtime in megabits, zero when either is unknown', () => {
  assert.equal(neededMbps(4_500_000_000, 3600), 10)
  assert.equal(neededMbps(null, 3600), 0)
  assert.equal(neededMbps(4_500_000_000, 0), 0)
  assert.equal(neededMbps(0, 3600), 0)
})

test('ranking input: unknown, broken or out-of-range values read as 0; the rest are rounded', () => {
  assert.deepEqual(normalizeRankingInput(undefined), { lineMbps: 0, screenHeight: 0, runtimeSeconds: 0 })
  assert.deepEqual(normalizeRankingInput({ lineMbps: 87.456, screenHeight: 1079.6, runtimeSeconds: 5400.4 }), {
    lineMbps: 87.46,
    screenHeight: 1080,
    runtimeSeconds: 5400,
  })
  assert.deepEqual(
    normalizeRankingInput({ lineMbps: Number.NaN, screenHeight: -1, runtimeSeconds: 10_000_000 }),
    { lineMbps: 0, screenHeight: 0, runtimeSeconds: 0 },
  )
  // A reading too small to write in plain decimals rounds to unknown.
  assert.equal(normalizeRankingInput({ lineMbps: 1e-7 }).lineMbps, 0)
  assert.ok(sameRankingInput(normalizeRankingInput({ lineMbps: 10 }), { lineMbps: 10, screenHeight: 0, runtimeSeconds: 0 }))
  assert.ok(!sameRankingInput(normalizeRankingInput({ lineMbps: 10 }), normalizeRankingInput({ lineMbps: 11 })))
})

test('a streams answer is annotated across addons without regrouping or reordering', () => {
  const result: StreamsResult = {
    results: [
      {
        addon: { id: 'a', name: 'Alpha' },
        streams: [
          { url: 'https://cdn.test/1', title: 'Film.2160p.Remux [RD+] 60 GB' },
          { url: 'https://cdn.test/2', title: 'Film.720p 1 GB' },
        ],
      },
      {
        addon: { id: 'b', name: 'Beta' },
        streams: [
          { url: 'https://cdn.test/3', title: 'Film.2160p.WEB [RD+] 10 GB' },
          // Anything a stream already claims is replaced, never trusted.
          { url: 'https://cdn.test/4', title: 'Film.1080p [RD+] 5 GB', halo: { rank: 0, fit: 'fits' } },
        ],
      },
    ],
    errors: [{ id: 'c', name: 'Gamma', code: 'timeout', message: 'Addon timed out.' }],
  }
  // 2 hours on a measured 20 Mbps line: a budget of 14 Mbps.
  const ranked = rankStreamResults(result, { lineMbps: 20, runtimeSeconds: 7200 })
  assert.deepEqual(
    ranked.results.map((group) => [group.addon.id, group.streams.map((stream) => stream.url)]),
    [
      ['a', ['https://cdn.test/1', 'https://cdn.test/2']],
      ['b', ['https://cdn.test/3', 'https://cdn.test/4']],
    ],
  )
  assert.deepEqual(ranked.errors, result.errors)
  assert.deepEqual(
    ranked.results.flatMap((group) => group.streams.map((stream) => stream.halo)),
    [
      { rank: 2, fit: 'overBudget' },
      { rank: 3, fit: 'fits' },
      { rank: 0, fit: 'fits' },
      { rank: 1, fit: 'fits' },
    ],
  )
  // The input is left as it was.
  assert.deepEqual(result.results[1]!.streams[1]!.halo, { rank: 0, fit: 'fits' })
  assert.equal(result.results[0]!.streams[0]!.halo, undefined)
})

test('a missing ranking input ranks with every bitrate unknown', () => {
  const ranked = rankStreamResults(
    {
      results: [
        {
          addon: { id: 'a', name: 'Alpha' },
          streams: [
            { url: 'https://cdn.test/1', title: 'Film.1080p 5 GB' },
            { url: 'https://cdn.test/2', title: 'Film.2160p 60 GB' },
          ],
        },
      ],
      errors: [],
    },
    undefined,
  )
  assert.deepEqual(
    ranked.results[0]!.streams.map((stream) => stream.halo),
    [
      { rank: 1, fit: 'fits' },
      { rank: 0, fit: 'fits' },
    ],
  )
})

test('an annotation is read only when its rank and fit are well formed', () => {
  assert.deepEqual(readSourceRank({ rank: 3, fit: 'exceedsScreen' }), { rank: 3, fit: 'exceedsScreen' })
  assert.equal(readSourceRank(undefined), null)
  assert.equal(readSourceRank({ rank: -1, fit: 'fits' }), null)
  assert.equal(readSourceRank({ rank: 1.5, fit: 'fits' }), null)
  assert.equal(readSourceRank({ rank: '1', fit: 'fits' }), null)
  assert.equal(readSourceRank({ rank: 1, fit: 'slow' }), null)
})
