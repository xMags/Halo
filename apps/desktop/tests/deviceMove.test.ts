import assert from 'node:assert/strict'
import test from 'node:test'
import {
  CINEMETA_URL,
  HaloApiError,
  OPENSUBTITLES_URL,
  type AddonEntry,
  type AddonsResponse,
  type HaloBackend,
  type HaloClient,
  type LibraryItem,
  type Manifest,
  type SettingsPayload,
  type UserSettings,
  type WatchState,
} from '@halo/core'
import { addonsToMove, chunked, moveRecordsIntoAccount, shouldMoveSettings } from '../src/device/mergeIntoAccount'

function manifest(name: string): Manifest {
  return { id: name, version: '1', name, resources: [], types: [], catalogs: [] }
}

function addon(url: string, name: string, extra: Partial<AddonEntry> = {}): AddonEntry {
  return { id: `id:${name}`, transportUrl: url, manifest: manifest(name), position: 0, ...extra }
}

test('only addons added on this PC move, never the built-ins or ones the account has', () => {
  const device = [
    addon(CINEMETA_URL, 'Cinemeta'),
    addon('https://a.test/manifest.json', 'A', { hideCatalogs: true }),
    addon(OPENSUBTITLES_URL, 'OpenSubtitles'),
    addon('https://b.test/manifest.json', 'B'),
  ]
  const account = [addon('https://b.test/manifest.json', 'B')]
  assert.deepEqual(addonsToMove(device, account), [
    { transportUrl: 'https://a.test/manifest.json', name: 'A', hideCatalogs: true },
  ])
})

test("this PC's settings are used only by an account that never saved any", () => {
  assert.equal(shouldMoveSettings({ value: { playbackRate: 1.5 }, updatedAt: 5 }, { value: {}, updatedAt: 0 }), true)
  assert.equal(shouldMoveSettings({ value: { playbackRate: 1.5 }, updatedAt: 5 }, { value: {}, updatedAt: 1 }), false)
  assert.equal(shouldMoveSettings({ value: {}, updatedAt: 0 }, { value: {}, updatedAt: 0 }), false)
})

test('rows are sent in bounded chunks', () => {
  assert.deepEqual(chunked([1, 2, 3, 4, 5], 2), [[1, 2], [3, 4], [5]])
  assert.deepEqual(chunked([], 2), [])
})

/* ── The move itself, against fakes of both sides ─────────────────────────── */

interface DeviceData {
  library: LibraryItem[]
  watchStates: WatchState[]
  settings: SettingsPayload
  addons: AddonEntry[]
}

function fakeDevice(data: DeviceData): HaloBackend {
  return {
    getLibrary: async () => data.library,
    getWatchStates: async () => data.watchStates,
    getSettings: async () => data.settings,
    getAddons: async (): Promise<AddonsResponse> => ({ global: [], user: data.addons }),
  } as unknown as HaloBackend
}

class FakeAccount {
  calls: string[] = []
  own: AddonEntry[] = []
  settings: SettingsPayload = { value: {}, updatedAt: 0 }
  /** URLs whose manifest "does not load" (400), and URLs that hit a network error. */
  refused = new Set<string>()
  unreachable = new Set<string>()

  async putLibrary(items: LibraryItem[]) {
    this.calls.push(`library:${items.length}`)
    return items
  }
  async putWatchStates(states: WatchState[]) {
    this.calls.push(`watch:${states.length}`)
    return states
  }
  async getSettings() {
    return this.settings
  }
  async putSettings(value: UserSettings, updatedAt: number) {
    this.calls.push(`settings:${updatedAt}`)
    this.settings = { value, updatedAt }
    return this.settings
  }
  async getAddons(): Promise<AddonsResponse> {
    return { global: [], user: this.own }
  }
  async putAddons(urls: string[]) {
    const added = urls[urls.length - 1]!
    this.calls.push(`addons:${urls.length}`)
    if (this.refused.has(added)) throw new HaloApiError(400, '{"error":"could not fetch a valid manifest"}')
    if (this.unreachable.has(added)) throw new TypeError('network error')
    this.own = urls.map((url, position) => this.own.find((a) => a.transportUrl === url) ?? addon(url, url, { position }))
    return this.own
  }
  async patchAddon(addonId: string, patch: { hideCatalogs: boolean }) {
    this.calls.push(`patch:${addonId}:${patch.hideCatalogs}`)
  }
}

function library(count: number): LibraryItem[] {
  return Array.from({ length: count }, (_, i) => ({ id: `movie:tt${i}`, type: 'movie', name: `M${i}`, addedAt: 1, updatedAt: 1 }))
}

test('everything moves, a dead addon is skipped and named, and the rest still go in', async () => {
  const account = new FakeAccount()
  account.own = [addon(CINEMETA_URL, 'Cinemeta')]
  account.refused.add('https://dead.test/manifest.json')
  const result = await moveRecordsIntoAccount(
    fakeDevice({
      library: library(1_200),
      watchStates: [{ videoId: 'tt1', itemId: 'movie:tt1', positionSec: 1, durationSec: 2, watched: false, updatedAt: 3 }],
      settings: { value: { playbackRate: 1.25 }, updatedAt: 9 },
      addons: [
        addon(CINEMETA_URL, 'Cinemeta'),
        addon('https://dead.test/manifest.json', 'Dead'),
        addon('https://alive.test/manifest.json', 'Alive', { hideCatalogs: true }),
      ],
    }),
    account as unknown as HaloClient,
  )
  assert.deepEqual(result.failedAddons, ['Dead'])
  assert.deepEqual(account.calls, [
    'library:500',
    'library:500',
    'library:200',
    'watch:1',
    'settings:9',
    'addons:2',
    'addons:2',
    'patch:id:https://alive.test/manifest.json:true',
  ])
  assert.deepEqual(account.own.map((a) => a.transportUrl), [CINEMETA_URL, 'https://alive.test/manifest.json'])
})

test('an account that cannot be reached stops the move, so it can run again', async () => {
  const account = new FakeAccount()
  account.settings = { value: { playbackRate: 2 }, updatedAt: 4 }
  account.unreachable.add('https://a.test/manifest.json')
  await assert.rejects(
    moveRecordsIntoAccount(
      fakeDevice({
        library: [],
        watchStates: [],
        settings: { value: { playbackRate: 1 }, updatedAt: 9 },
        addons: [addon('https://a.test/manifest.json', 'A')],
      }),
      account as unknown as HaloClient,
    ),
    TypeError,
  )
  // The account's own settings were kept: it already had some.
  assert.equal(account.calls.some((call) => call.startsWith('settings:')), false)
})
