import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { ContextMenuHost } from './components/ContextMenu'
import { DialogHost } from './components/Dialog'
import { NavRail } from './components/NavRail'
import { TitleBar } from './components/TitleBar'
import { NavProvider, useNav } from './nav'
import { SessionProvider, useSession } from './auth/session'
import { Catalog } from './browse/Catalog'
import { Detail } from './browse/Detail'
import { Downloads } from './downloads/Downloads'
import { Home } from './home/Home'
import { Library } from './browse/Library'
import { Login } from './auth/Login'
import { Player } from './player/Player'
import { Search } from './search/Search'
import { Settings } from './settings/Settings'
import { SourcesSheet } from './sources/SourcesSheet'
import { useWindowFullscreen } from './window'
import { getDeviceProfile, getServerUrl, getSessionDownloadOwner, setSessionDownloadOwner } from './api'
import { deviceDownloadOwner } from './device/deviceStore'
import { setDownloadsAccount, clearDownloadsAccount, opaqueDownloadOwner } from './downloads/downloadsStore'
import { useLocalPrefs } from './localPrefs'
import { presenceSetEnabled } from './player/presence'
import { useMe } from './queries'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
})

function Routes() {
  const { state, signingIn } = useSession()
  if (state === 'chooser' || signingIn) return <Login />
  return (
    <NavProvider>
      <DownloadsAccountBinding />
      <PresencePreferenceBinding />
      <Shell />
    </NavProvider>
  )
}

function DownloadsAccountBinding() {
  const { state } = useSession()
  const { data: me } = useMe()
  useEffect(() => {
    // Without an account, downloads belong to this PC's profile. Signed in, a
    // previous launch's derived owner binds before `me` arrives.
    const profile = state === 'device' ? getDeviceProfile() : null
    if (profile) void deviceDownloadOwner(profile).then(setDownloadsAccount).catch(() => undefined)
    const owner = state === 'device' ? null : getSessionDownloadOwner()
    if (owner) void setDownloadsAccount(owner).catch(() => undefined)
    return () => { void clearDownloadsAccount().catch(() => undefined) }
  }, [state])
  useEffect(() => {
    if (!me) return
    void opaqueDownloadOwner(getServerUrl(), me.id).then((owner) => {
      setSessionDownloadOwner(owner)
      return setDownloadsAccount(owner)
    }).catch(() => undefined)
  }, [me])
  return null
}

/** Hands the device-local Rich Presence switch to the Rust service. */
function PresencePreferenceBinding() {
  const { discordPresence } = useLocalPrefs()
  useEffect(() => {
    presenceSetEnabled(discordPresence)
  }, [discordPresence])
  return null
}

function Shell() {
  const { screen, setRoot, sheet } = useNav()
  const windowFullscreen = useWindowFullscreen()
  // Lives here, not in the pane: the player replaces the whole shell tree, and
  // the pane must come back the way it was left.
  const [paneOpen, setPaneOpen] = useState(true)

  useEffect(() => {
    if (screen.name !== 'player' && windowFullscreen.fullscreen) {
      void windowFullscreen.setFullscreen(false)
    }
  }, [screen.name, windowFullscreen.fullscreen, windowFullscreen.setFullscreen])

  // Desktop staple: Ctrl/Cmd+K jumps to search from any browse screen.
  useEffect(() => {
    if (screen.name === 'player') return
    const onKey = (e: KeyboardEvent) => {
      const t = e.target
      if (
        t instanceof HTMLInputElement ||
        t instanceof HTMLTextAreaElement ||
        t instanceof HTMLSelectElement
      )
        return
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        setRoot('search')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [screen.name, setRoot])

  // The player owns the media surface: the shell's opaque background would
  // cover the video mpv paints behind the webview, so it is not rendered at
  // all. Fullscreen state lives above the video key so an autoplay replace can
  // remount media state without desynchronizing the native window.
  if (screen.name === 'player') {
    return <Player key={screen.videoId} {...screen} windowFullscreen={windowFullscreen} />
  }

  return (
    <div className="shell">
      <div className="glow glow-a" />
      <div className="glow glow-b" />
      <TitleBar />
      <div className="shell-body">
        <NavRail open={paneOpen} onToggle={() => setPaneOpen((open) => !open)} />
        <div className="content">
          <div className="content-host">
            <Stack />
          </div>
        </div>
      </div>
      {sheet && <SourcesSheet params={sheet} />}
    </div>
  )
}

function Stack() {
  const { screen } = useNav()
  switch (screen.name) {
    case 'home':
      return <Home />
    case 'search':
      return <Search />
    case 'library':
      return <Library />
    case 'downloads':
      return <Downloads />
    case 'settings':
      return <Settings />
    case 'detail':
      return <Detail type={screen.type} id={screen.id} />
    case 'catalog':
      return <Catalog title={screen.title} source={screen.source} items={screen.items} />
    case 'player':
      return null // handled by Shell
  }
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <Routes />
        <ContextMenuHost />
        <DialogHost />
      </SessionProvider>
    </QueryClientProvider>
  )
}
