import { Redirect } from 'expo-router'
import { useSession } from '@/session'

/**
 * Terminal for the post-logout redirect deep link (same "Unmatched Route"
 * hazard as ./callback). The auth session normally swallows the redirect
 * before navigation happens; this route catches the Android path where
 * expo-router processes the incoming link anyway.
 */
export default function OAuthLogout() {
  const { status } = useSession()
  return <Redirect href={status === 'ready' ? '/' : '/login'} />
}
