/**
 * Last explicit subtitle choice, remembered per video (exact restore) and per
 * item (language carryover to the next episode). Only user clicks are
 * recorded — auto-applied defaults never write, so preference-based selection
 * can't reinforce itself into looking like a choice. Local-only; not synced
 * across devices (mobile keeps the same store shape in AsyncStorage).
 * Desktop has no downloads, so mobile's 'downloaded' kind doesn't exist here.
 */
export interface SubtitleChoice {
  kind: 'off' | 'embedded' | 'external'
  /** Language of the choice — the addon's code for external subs, the track's tag for embedded. */
  lang?: string
  /** Embedded: exact track name, for same-file restore before language fallback. */
  trackName?: string
  /** External: addon subtitle id, to re-find the exact result for this video. */
  subId?: string
  updatedAt: number
}

const STORAGE_KEY = 'halo.subtitleChoices.v1'
const MAX_ENTRIES = 300

type ChoiceMap = Record<string, SubtitleChoice>

function readAll(): ChoiceMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as ChoiceMap) : {}
  } catch {
    return {}
  }
}

/** Exact video choice first, then the item-level one (episode carryover). */
export function getSubtitleChoice(videoId: string, itemId: string): SubtitleChoice | undefined {
  const all = readAll()
  return all[`video:${videoId}`] ?? all[`item:${itemId}`]
}

export function rememberSubtitleChoice(
  videoId: string,
  itemId: string,
  choice: Omit<SubtitleChoice, 'updatedAt'>,
): void {
  const all = readAll()
  const stamped: SubtitleChoice = { ...choice, updatedAt: Date.now() }
  all[`video:${videoId}`] = stamped
  all[`item:${itemId}`] = stamped
  // Cap growth: drop the oldest entries once past the limit.
  const keys = Object.keys(all)
  if (keys.length > MAX_ENTRIES) {
    keys.sort((a, b) => all[a]!.updatedAt - all[b]!.updatedAt)
    for (const key of keys.slice(0, keys.length - MAX_ENTRIES)) delete all[key]
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(all))
  } catch {
    // Quota failure — memory is best-effort.
  }
}
