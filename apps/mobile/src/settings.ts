import AsyncStorage from '@react-native-async-storage/async-storage'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { SettingsPayload, UserSettings } from '@halo/core'
import { api } from './api'

const MIRROR_KEY = 'halo.settings.v1'

/**
 * Settings live on the server (synced across devices, LWW) with an
 * AsyncStorage mirror so the player can honor preferences offline.
 */
export function useSettings(): UserSettings {
  const { data } = useQuery({
    queryKey: ['settings'],
    staleTime: 60_000,
    queryFn: async (): Promise<SettingsPayload> => {
      try {
        const payload = await api().getSettings()
        await AsyncStorage.setItem(MIRROR_KEY, JSON.stringify(payload))
        return payload
      } catch (err) {
        const mirror = await AsyncStorage.getItem(MIRROR_KEY)
        if (mirror) return JSON.parse(mirror) as SettingsPayload
        throw err
      }
    },
  })
  return data?.value ?? {}
}

export function useUpdateSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (patch: Partial<UserSettings>) => {
      const current = queryClient.getQueryData<SettingsPayload>(['settings'])?.value ?? {}
      // Explicit undefined means "clear this preference".
      const merged: UserSettings = { ...current, ...patch }
      for (const key of Object.keys(patch) as Array<keyof UserSettings>) {
        if (patch[key] === undefined) delete merged[key]
      }
      const payload = await api().putSettings(merged, Date.now())
      await AsyncStorage.setItem(MIRROR_KEY, JSON.stringify(payload))
      return payload
    },
    onSuccess: (payload) => queryClient.setQueryData(['settings'], payload),
  })
}
