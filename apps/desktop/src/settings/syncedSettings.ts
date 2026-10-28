import type { SettingsPayload, UserSettings } from '@halo/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getClient } from '../api'

const MIRROR_KEY = 'halo.settings.v1'
const QUERY_KEY = ['settings'] as const

/**
 * Bumped whenever the backend changes (sign-in, sign-out, Halo without an
 * account), so a read or save still in flight for the previous one cannot
 * land in the new session's cache or mirror.
 */
let backendGeneration = 0

/**
 * The mirror belongs to one account (or this PC): another must never fall
 * back to it, and a save from it would overwrite that account's real
 * settings. Called on every change of backend.
 */
export function resetSettingsMirror(): void {
  backendGeneration += 1
  localStorage.removeItem(MIRROR_KEY)
}

/** Merge a patch into current settings; explicit undefined means "clear this preference". */
function applyPatch(current: UserSettings, patch: Partial<UserSettings>): UserSettings {
  const merged: UserSettings = { ...current, ...patch }
  for (const key of Object.keys(patch) as Array<keyof UserSettings>) {
    if (patch[key] === undefined) delete merged[key]
  }
  return merged
}

/**
 * Settings live on the server (synced across devices, LWW) with a localStorage
 * mirror so the player can honor preferences when the server is unreachable.
 * Desktop port of mobile's settings.ts — same query key and merge semantics.
 */
const settingsQuery = {
  queryKey: QUERY_KEY,
  staleTime: 60_000,
  queryFn: async (): Promise<SettingsPayload> => {
    const generation = backendGeneration
    try {
      const payload = await getClient().getSettings()
      if (generation === backendGeneration) localStorage.setItem(MIRROR_KEY, JSON.stringify(payload))
      return payload
    } catch (err) {
      const mirror = localStorage.getItem(MIRROR_KEY)
      if (mirror) return JSON.parse(mirror) as SettingsPayload
      throw err
    }
  },
}

export function useSettings(): UserSettings {
  const { data } = useQuery(settingsQuery)
  return data?.value ?? {}
}

/**
 * True once the settings query has settled (server, mirror, or failure).
 * Gate flows that must not start from defaults — e.g. autoplay prefetch,
 * which must honor a persisted autoplay-off before any request goes out.
 */
export function useSettingsLoaded(): boolean {
  const { isFetched } = useQuery(settingsQuery)
  return isFetched
}

export function useUpdateSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    // The cache is patched synchronously so rapid successive updates build on
    // each other; each PUT then snapshots the fully-merged cache value. Without
    // this, two in-flight mutations race and the later one drops the earlier
    // one's field with a newer LWW timestamp.
    onMutate: async (patch: Partial<UserSettings>) => {
      await queryClient.cancelQueries({ queryKey: QUERY_KEY })
      const previous = queryClient.getQueryData<SettingsPayload>(QUERY_KEY)
      queryClient.setQueryData<SettingsPayload>(QUERY_KEY, {
        value: applyPatch(previous?.value ?? {}, patch),
        updatedAt: Date.now(),
      })
    },
    mutationFn: async (_patch: Partial<UserSettings>): Promise<SettingsPayload | null> => {
      const generation = backendGeneration
      const merged = queryClient.getQueryData<SettingsPayload>(QUERY_KEY)?.value ?? {}
      const payload = await getClient().putSettings(merged, Date.now())
      // Saved to a backend that is no longer the active one: its echo is not
      // this session's settings.
      if (generation !== backendGeneration) return null
      localStorage.setItem(MIRROR_KEY, JSON.stringify(payload))
      return payload
    },
    // A newer optimistic write may already be in the cache; never let an older
    // server echo roll it back.
    onSuccess: (payload) => {
      if (payload === null) return
      queryClient.setQueryData<SettingsPayload>(QUERY_KEY, (old) =>
        old && old.updatedAt > payload.updatedAt ? old : payload,
      )
    },
    // Refetch server truth rather than snapshot-rollback — restoring a snapshot
    // could clobber another in-flight mutation's optimistic state.
    onError: () => void queryClient.invalidateQueries({ queryKey: QUERY_KEY }),
  })
}
