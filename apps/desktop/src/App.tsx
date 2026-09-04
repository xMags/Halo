import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { NavRail } from './components/NavRail'
import { TitleBar } from './components/TitleBar'
import { NavProvider, useNav } from './nav'
import { SessionProvider, useSession } from './session'
import { Connect } from './screens/Connect'
import { Detail } from './screens/Detail'
import { Downloads } from './screens/Downloads'
import { Home } from './screens/Home'
import { Library } from './screens/Library'
import { Login } from './screens/Login'
import { Player } from './screens/Player'
import { Search } from './screens/Search'
import { Settings } from './screens/Settings'
import { SourcesSheet } from './screens/SourcesSheet'
import { useWindowFullscreen } from './window'
import { getServerUrl, getSessionKind } from './api'
import { setDownloadsAccount, clearDownloadsAccount, opaqueDownloadOwner } from './downloads'
import { getLocalDownloadOwner, setLocalDownloadOwner } from './localAuth'
import { getOidcDownloadOwner, setOidcDownloadOwner } from './oidc'
import { useMe } from './queries'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { retry: 1, refetchOnWindowFocus: false },
  },
})

function Routes() {
  const { state } = useSession()
  if (state === 'unconfigured') return <Connect />
  if (state === 'unauthenticated') return <Login />
  return (
    <NavProvider>
      <DownloadsAccountBinding />
      <Shell />
    </NavProvider>
  )
}

function DownloadsAccountBinding() {
  const { data: me } = useMe()
  useEffect(() => {
    const owner = getSessionKind() === 'oidc' ? getOidcDownloadOwner() : getLocalDownloadOwner()
    if (owner) void setDownloadsAccount(owner).catch(() => undefined)
    return () => { void clearDownloadsAccount().catch(() => undefined) }
  }, [])
  useEffect(() => {
    if (!me) return
    const server = getServerUrl()
    if (!server) return
    void opaqueDownloadOwner(server, me.id).then((owner) => {
      if (getSessionKind() === 'oidc') setOidcDownloadOwner(owner)
      else setLocalDownloadOwner(owner)
      return setDownloadsAccount(owner)
    }).catch(() => undefined)
  }, [me])
  return null
}

function Shell() {
  const { screen, setRoot, sheet } = useNav()
  const windowFullscreen = useWindowFullscreen()

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
        <NavRail />
        <div className="content">
          <Stack />
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
    case 'player':
      return null // handled by Shell
  }
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <Routes />
      </SessionProvider>
    </QueryClientProvider>
  )
}
