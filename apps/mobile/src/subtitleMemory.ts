import AsyncStorage from '@react-native-async-storage/async-storage'

/**
 * Last explicit subtitle choice, remembered per video (exact restore, incl.
 * offline via the persisted file) and per item (language carryover to the
 * next episode). Only user taps are recorded — auto-applied defaults never
 * write, so preference-based selection can't reinforce itself into looking
 * like a choice. Local-only; not synced across devices.
 */
export interface SubtitleChoice {
  kind: 'off' | 'embedded' | 'external' | 'downloaded'
  /** Language of the choice — the addon's code for external subs, a parsed label for embedded tracks. */
  lang?: string
  /** Embedded: exact track name, for same-file restore before language fallback. */
  trackName?: string
  /** External: addon subtitle id, to re-find the exact result for this video. */
  subId?: string
  /** External: stable local file name — same-video restore works offline from disk. */
  fileName?: string
  updatedAt: number
}

const STORAGE_KEY = 'halo.subtitleChoices.v1'
const MAX_ENTRIES = 300

type ChoiceMap = Record<string, SubtitleChoice>

async function readAll(): Promise<ChoiceMap> {
  try {
    const raw = await AsyncStorage.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as ChoiceMap) : {}
  } catch {
    return {}
  }
}

/** Exact video choice first, then the item-level one (episode carryover). */
export async function getSubtitleChoice(videoId: string, itemId: string): Promise<SubtitleChoice | undefined> {
  const all = await readAll()
  return all[`video:${videoId}`] ?? all[`item:${itemId}`]
}

export async function rememberSubtitleChoice(
  videoId: string,
  itemId: string,
  choice: Omit<SubtitleChoice, 'updatedAt'>,
): Promise<void> {
  const all = await readAll()
  const stamped: SubtitleChoice = { ...choice, updatedAt: Date.now() }
  all[`video:${videoId}`] = stamped
  all[`item:${itemId}`] = stamped
  // Cap growth: drop the oldest entries once past the limit.
  const keys = Object.keys(all)
  if (keys.length > MAX_ENTRIES) {
    keys.sort((a, b) => all[a]!.updatedAt - all[b]!.updatedAt)
    for (const key of keys.slice(0, keys.length - MAX_ENTRIES)) delete all[key]
  }
  await AsyncStorage.setItem(STORAGE_KEY, JSON.stringify(all))
}
