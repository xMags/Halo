/**
 * Addons preinstalled on first boot so the app works before the user adds
 * anything: Cinemeta for catalogs/metadata, OpenSubtitles for subtitles (both
 * the official Stremio-operated instances), and The Movie Database addon for
 * TMDB's catalogs and pictured cast, as ElfHosted's unconfigured public
 * instance: a configured URL carries its owner's settings and keys, so one
 * must never be built in.
 */
export const DEFAULT_ADDON_URLS = [
  'https://v3-cinemeta.strem.io/manifest.json',
  'https://opensubtitles-v3.strem.io/manifest.json',
  'https://tmdb.elfhosted.com/manifest.json',
] as const

export const CINEMETA_URL = DEFAULT_ADDON_URLS[0]
export const OPENSUBTITLES_URL = DEFAULT_ADDON_URLS[1]
