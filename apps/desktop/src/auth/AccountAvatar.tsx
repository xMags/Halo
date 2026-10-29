import { useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { getAccountClient } from '../api'
import { FluentIcon } from '../components/FluentIcon'
import { useNav } from '../nav'
import { useMe } from '../queries'
import { useSession } from './session'

/**
 * The account's profile picture at the end of the title bar, as Windows apps
 * put the signed-in account there; it opens Settings, where the account lives.
 *
 * The picture is fetched only when `Me.avatarVersion` names one, and cached by
 * that fingerprint, so it is asked for again only after it changes. Signed in
 * without a picture it shows the account's initial; without an account, the
 * contact glyph (Settings is where signing in starts).
 */
export function AccountAvatar() {
  const { state } = useSession()
  const { setRoot } = useNav()
  const me = useMe()
  const version = state === 'authenticated' ? me.data?.avatarVersion : undefined
  const picture = useQuery({
    queryKey: ['avatar', version],
    queryFn: async () => (await getAccountClient()?.getAvatar()) ?? null,
    enabled: version !== undefined,
    staleTime: Infinity,
  })
  const url = useObjectUrl(picture.data ?? null)

  const signedIn = state === 'authenticated'
  const name = me.data?.username ?? ''
  return (
    <button
      type="button"
      className="tb-avatar"
      aria-label={signedIn && name ? `${name}: account and settings` : 'Account and settings'}
      title={signedIn && name ? name : 'Settings'}
      onClick={() => setRoot('settings')}
    >
      {url ? (
        <img className="tb-avatar-img" src={url} alt="" draggable={false} />
      ) : signedIn && name ? (
        <span className="tb-avatar-initial">{name.slice(0, 1).toUpperCase()}</span>
      ) : (
        <FluentIcon glyph="contact" size={14} />
      )}
    </button>
  )
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
