import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { getAccountClient } from '../api'
import { useMe } from '../queries'
import { useSession } from './session'

/**
 * The signed-in account's profile picture as an object URL, or null when there
 * is no account, no picture, or it has not loaded yet.
 *
 * Fetched only when `Me.avatarVersion` names a picture, and cached by that
 * fingerprint, so it is asked for again only after the picture changes.
 */
export function useAccountPicture(): string | null {
  const { state } = useSession()
  const me = useMe()
  const version = state === 'authenticated' ? me.data?.avatarVersion : undefined
  const picture = useQuery({
    queryKey: ['avatar', version],
    queryFn: async () => (await getAccountClient()?.getAvatar()) ?? null,
    enabled: version !== undefined,
    staleTime: Infinity,
  })
  return useObjectUrl(version === undefined ? null : picture.data ?? null)
}

/** An object URL for [blob], revoked when it changes or the component leaves. */
function useObjectUrl(blob: Blob | null): string | null {
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!blob) {
      setUrl(null)
      return
    }
    const next = URL.createObjectURL(blob)
    setUrl(next)
    return () => URL.revokeObjectURL(next)
  }, [blob])
  return url
}
