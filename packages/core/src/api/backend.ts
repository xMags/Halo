import type { HaloClient } from './client'

/**
 * Everything the app's screens ask of a backend. `HaloClient` answers it from
 * the user's account on the Halo server; `DeviceBackend` answers it on the
 * device for people using Halo without an account. Account-only calls
 * (`getMe`, the admin's global addons, sign-in discovery) stay on
 * `HaloClient`.
 */
export type HaloBackend = Pick<
  HaloClient,
  | 'getAddons'
  | 'putAddons'
  | 'patchAddon'
  | 'getCatalog'
  | 'getMeta'
  | 'getStreams'
  | 'getNextEpisode'
  | 'getSubtitles'
  | 'getLibrary'
  | 'putLibrary'
  | 'getWatchStates'
  | 'putWatchStates'
  | 'getSettings'
  | 'putSettings'
>
