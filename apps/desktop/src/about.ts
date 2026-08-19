import { getTauriVersion, getVersion } from '@tauri-apps/api/app'
import { useQuery } from '@tanstack/react-query'
import { mpvGet } from './mpv'

export interface BuildInfo {
  /** App version from tauri.conf.json. */
  app: string
  /** libmpv's release number. */
  mpv: string
  tauri: string
}

/**
 * mpv reports something like `mpv v0.41.0-878-g94335ab87` — the release number
 * is the part anyone reads, and the git suffix is long enough to wrap the
 * sidebar card. Anything unrecognisable is passed through untouched rather
 * than dropped, so an unusual build still identifies itself.
 */
function mpvRelease(raw: string | null): string {
  if (!raw) return '—'
  return /v?(\d+\.\d+\.\d+)/.exec(raw)?.[1] ?? raw.replace(/^mpv\s+/i, '')
}

/**
 * What the settings sidebar prints. Read once per session — none of it can
 * change while the process is running — and best-effort: a version string is
 * never worth failing a screen over.
 */
export function useBuildInfo() {
  return useQuery({
    queryKey: ['buildInfo'],
    staleTime: Infinity,
    gcTime: Infinity,
    retry: false,
    queryFn: async (): Promise<BuildInfo> => {
      const [app, tauri, mpv] = await Promise.all([
        getVersion().catch(() => '—'),
        getTauriVersion().catch(() => '—'),
        mpvGet('mpv-version').catch(() => null),
      ])
      return { app, tauri, mpv: mpvRelease(mpv) }
    },
  })
}
