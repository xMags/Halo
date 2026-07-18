import { describe, expect, it } from 'vitest'
import { nextVideo, type MetaVideo } from '@halo/core'

// Pure @halo/core logic, tested here because core ships raw TS and this
// package already carries the vitest setup.

const ep = (id: string, season: number, episode: number, released?: string): MetaVideo => ({ id, season, episode, released })

const SERIES: MetaVideo[] = [
  ep('s:1:1', 1, 1),
  ep('s:1:2', 1, 2),
  ep('s:2:1', 2, 1),
  ep('s:2:2', 2, 2),
  ep('s:0:1', 0, 1),
]

describe('nextVideo ordering', () => {
  it('advances within a season', () => {
    expect(nextVideo(SERIES, 's:1:1')?.id).toBe('s:1:2')
  })

  it('crosses a season boundary', () => {
    expect(nextVideo(SERIES, 's:1:2')?.id).toBe('s:2:1')
  })

  it('sorts specials (season 0) after every regular season', () => {
    expect(nextVideo(SERIES, 's:2:2')?.id).toBe('s:0:1')
  })

  it('returns null after the last video and for an unknown id', () => {
    expect(nextVideo(SERIES, 's:0:1')).toBeNull()
    expect(nextVideo(SERIES, 'movie-id')).toBeNull()
    expect(nextVideo([], 'anything')).toBeNull()
  })

  it('orders by season/episode numbers, not array position', () => {
    const shuffled = [ep('s:2:1', 2, 1), ep('s:1:2', 1, 2), ep('s:1:1', 1, 1)]
    expect(nextVideo(shuffled, 's:1:2')?.id).toBe('s:2:1')
  })

  it('refuses to advance into an unaired episode', () => {
    const now = Date.parse('2026-07-18T00:00:00Z')
    const videos = [ep('s:1:1', 1, 1, '2026-01-01T00:00:00Z'), ep('s:1:2', 1, 2, '2026-12-01T00:00:00Z')]
    expect(nextVideo(videos, 's:1:1', now)).toBeNull()
    const aired = [ep('s:1:1', 1, 1, '2026-01-01T00:00:00Z'), ep('s:1:2', 1, 2, '2026-07-01T00:00:00Z')]
    expect(nextVideo(aired, 's:1:1', now)?.id).toBe('s:1:2')
  })

  it('falls back to array order when season/episode numbers are absent', () => {
    const absoluteOrder: MetaVideo[] = [{ id: 'a' }, { id: 'b' }, { id: 'c' }]
    expect(nextVideo(absoluteOrder, 'a')?.id).toBe('b')
    expect(nextVideo(absoluteOrder, 'c')).toBeNull()
  })
})
