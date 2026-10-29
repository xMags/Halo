import assert from 'node:assert/strict'
import test from 'node:test'
import type { MetaVideo } from '@halo/core'
import { episodePages, pageContaining } from '../src/browse/detail/episodePages.ts'

function season(count: number, firstNumber = 1): MetaVideo[] {
  return Array.from({ length: count }, (_, index) => ({
    id: `show:1:${firstNumber + index}`,
    season: 1,
    episode: firstNumber + index,
  })) as MetaVideo[]
}

test('a season of up to a page is one page; a longer one splits in order', () => {
  assert.equal(episodePages(season(25)).length, 1)
  const pages = episodePages(season(2000))
  assert.equal(pages.length, 80)
  assert.deepEqual(pages.slice(0, 2).map((page) => page.label), ['Episodes 1–25', 'Episodes 26–50'])
  assert.equal(pages[79]!.label, 'Episodes 1976–2000')
  assert.equal(pages.flatMap((page) => page.episodes).length, 2000)
})

test('labels follow the episodes’ own numbers, and positions where they have none', () => {
  assert.equal(episodePages(season(60, 101), 50)[1]!.label, 'Episodes 151–160')
  const unnumbered = season(3).map(({ id }) => ({ id }) as MetaVideo)
  assert.equal(episodePages(unnumbered, 2)[1]!.label, 'Episode 3')
})

test('the panel opens on the page holding the episode in progress', () => {
  const pages = episodePages(season(200))
  assert.equal(pageContaining(pages, 'show:1:120'), 4)
  assert.equal(pageContaining(pages, 'elsewhere'), 0)
  assert.equal(pageContaining(pages, null), 0)
})
