import { Redirect } from 'expo-router'
import { useSession } from '@/session'

/**
 * Terminal for the OAuth redirect deep link. expo-router navigates on every
 * incoming link in parallel with the auth session capturing it; without this
 * route the login redirect lands on "Unmatched Route". The token exchange
 * itself happens in src/oidc.ts — this screen only steers navigation to a
 * screen the current auth state permits.
 */
export default function OAuthCallback() {
  const { status } = useSession()
  return <Redirect href={status === 'ready' ? '/' : '/login'} />
}
