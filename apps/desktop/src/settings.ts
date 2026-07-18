import type { SettingsPayload, UserSettings } from '@halo/core'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getClient } from './api'

const MIRROR_KEY = 'halo.settings.v1'
const QUERY_KEY = ['settings'] as const

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
    try {
      const payload = await getClient().getSettings()
      localStorage.setItem(MIRROR_KEY, JSON.stringify(payload))
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
    mutationFn: async (_patch: Partial<UserSettings>) => {
      const merged = queryClient.getQueryData<SettingsPayload>(QUERY_KEY)?.value ?? {}
      const payload = await getClient().putSettings(merged, Date.now())
      localStorage.setItem(MIRROR_KEY, JSON.stringify(payload))
      return payload
    },
    // A newer optimistic write may already be in the cache; never let an older
    // server echo roll it back.
    onSuccess: (payload) => {
      queryClient.setQueryData<SettingsPayload>(QUERY_KEY, (old) =>
        old && old.updatedAt > payload.updatedAt ? old : payload,
      )
    },
    // Refetch server truth rather than snapshot-rollback — restoring a snapshot
    // could clobber another in-flight mutation's optimistic state.
    onError: () => void queryClient.invalidateQueries({ queryKey: QUERY_KEY }),
  })
}
