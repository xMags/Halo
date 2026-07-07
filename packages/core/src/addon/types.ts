/**
 * Types for the Stremio addon protocol.
 * Reference: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
 *
 * Fields addons emit inconsistently in the wild are optional; only fields the
 * protocol guarantees are required.
 */

export type ResourceName = 'catalog' | 'meta' | 'stream' | 'subtitles' | 'addon_catalog'

export interface ManifestResource {
  name: ResourceName
  types: string[]
  idPrefixes?: string[]
}

export interface CatalogExtra {
  name: string
  isRequired?: boolean
  options?: string[]
  optionsLimit?: number
}

export interface ManifestCatalog {
  type: string
  id: string
  name?: string
  extra?: CatalogExtra[]
  /** Legacy alternatives to `extra` still emitted by some addons. */
  extraSupported?: string[]
  extraRequired?: string[]
}

export interface Manifest {
  id: string
  version: string
  name: string
  description?: string
  logo?: string
  background?: string
  contactEmail?: string
  resources: Array<ResourceName | ManifestResource>
  types: string[]
  catalogs: ManifestCatalog[]
  idPrefixes?: string[]
  behaviorHints?: {
    adult?: boolean
    p2p?: boolean
    configurable?: boolean
    configurationRequired?: boolean
  }
}

export interface MetaPreview {
  id: string
  type: string
  name: string
  poster?: string
  posterShape?: 'square' | 'poster' | 'landscape'
  background?: string
  logo?: string
  description?: string
  releaseInfo?: string
  imdbRating?: string
  genres?: string[]
}

export interface MetaVideo {
  id: string
  title?: string
  /** Some addons use `name` instead of `title`. */
  name?: string
  released?: string
  thumbnail?: string
  overview?: string
  season?: number
  episode?: number
}

export interface MetaDetail extends MetaPreview {
  videos?: MetaVideo[]
  runtime?: string
  language?: string
  country?: string
  awards?: string
  website?: string
  cast?: string[]
  director?: string[]
  writer?: string[]
}

export interface StreamBehaviorHints {
  /** Not directly playable in a browser (e.g. requires transcoding). */
  notWebReady?: boolean
  /** Streams sharing a bingeGroup keep the same source across episodes. */
  bingeGroup?: string
  proxyHeaders?: { request?: Record<string, string>; response?: Record<string, string> }
  filename?: string
  videoSize?: number
  videoHash?: string
}

export interface Stream {
  /** Direct URL — the only source kind Halo plays (debrid/HTTP). */
  url?: string
  /** Torrent sources — recognized so they can be filtered out, never played. */
  infoHash?: string
  fileIdx?: number
  ytId?: string
  externalUrl?: string
  name?: string
  title?: string
  description?: string
  subtitles?: Subtitle[]
  behaviorHints?: StreamBehaviorHints
}

export interface Subtitle {
  id: string
  url: string
  /** ISO 639-2 language code in practice (e.g. "eng", "ger"). */
  lang: string
}

export interface CatalogResponse {
  metas: MetaPreview[]
}

export interface MetaResponse {
  meta: MetaDetail
}

export interface StreamsResponse {
  streams: Stream[]
}

export interface SubtitlesResponse {
  subtitles: Subtitle[]
}
