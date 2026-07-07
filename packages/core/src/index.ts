export type {
  CatalogExtra,
  CatalogResponse,
  Manifest,
  ManifestCatalog,
  ManifestResource,
  MetaDetail,
  MetaPreview,
  MetaResponse,
  MetaVideo,
  ResourceName,
  Stream,
  StreamBehaviorHints,
  StreamsResponse,
  Subtitle,
  SubtitlesResponse,
} from './addon/types'

export {
  AddonRequestError,
  addonSupportsResource,
  encodeExtra,
  fetchManifest,
  getCatalog,
  getMeta,
  getStreams,
  getSubtitles,
  isPlayableStream,
  transportBase,
} from './addon/client'
export type { AddonFetchOptions } from './addon/client'

export { CINEMETA_URL, DEFAULT_ADDON_URLS, OPENSUBTITLES_URL } from './addon/constants'

export { computeVideoHash, computeVideoHashFromChunks, VIDEO_HASH_CHUNK_BYTES } from './subtitles/hash'
export type { VideoHashResult } from './subtitles/hash'
export { isVtt, srtToVtt } from './subtitles/srtToVtt'
export { LANGUAGE_OPTIONS, languageLabel, languageMatches } from './subtitles/languages'

export { HaloApiError, HaloClient } from './api/client'
export type { HaloClientOptions } from './api/client'
export type { AddonEntry, LibraryItem, SettingsPayload, UserSettings, WatchState } from './api/types'
