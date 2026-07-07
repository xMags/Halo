/**
 * Addons preinstalled on first boot so the app works before the user adds
 * anything: Cinemeta for catalogs/metadata, OpenSubtitles for subtitles.
 * Both are the official Stremio-operated instances.
 */
export const DEFAULT_ADDON_URLS = [
  'https://v3-cinemeta.strem.io/manifest.json',
  'https://opensubtitles-v3.strem.io/manifest.json',
] as const

export const CINEMETA_URL = DEFAULT_ADDON_URLS[0]
export const OPENSUBTITLES_URL = DEFAULT_ADDON_URLS[1]
