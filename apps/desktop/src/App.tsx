import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { CommandBar } from './components/CommandBar'
import { NavRail } from './components/NavRail'
import { TitleBar } from './components/TitleBar'
import { NavProvider, useNav } from './nav'
import { ScreenTitleProvider } from './screenTitle'
import { SessionProvider, useSession } from './session'
import { Connect } from './screens/Connect'
import { Detail } from './screens/Detail'
import { Home } from './screens/Home'
import { Library } from './screens/Library'
import { Login } from './screens/Login'
import { Player } from './screens/Player'
import { Search } from './screens/Search'
import { Settings } from './screens/Settings'
import { Streams } from './screens/Streams'
import { useWindowFullscreen } from './window'

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
      <ScreenTitleProvider>
        <Shell />
      </ScreenTitleProvider>
    </NavProvider>
  )
}

function Shell() {
  const { screen, setRoot } = useNav()
  const windowFullscreen = useWindowFullscreen()

  useEffect(() => {
    if (screen.name !== 'player' && windowFullscreen.fullscreen) {
      void windowFullscreen.setFullscreen(false)
    }
  }, [screen.name, windowFullscreen.fullscreen, windowFullscreen.setFullscreen])

  // Desktop staple: "/" or Ctrl+K jumps to search from any browse screen.
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
      if (e.key === '/' || (e.ctrlKey && e.key.toLowerCase() === 'k')) {
        e.preventDefault()
        setRoot('search')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [screen.name, setRoot])

  // The player owns the media surface and adds the shared title bar only in
  // windowed mode. Fullscreen state lives here, above the video key, so an
  // autoplay replace can remount media state without desynchronizing the
  // native window. Resume, prefetch and overlays still reset per video.
  if (screen.name === 'player') {
    return <Player key={screen.videoId} {...screen} windowFullscreen={windowFullscreen} />
  }

  return (
    <div className="shell">
      <TitleBar />
      <div className="shell-body">
        <NavRail />
        <main className="main-col">
          <CommandBar />
          <Stack />
        </main>
      </div>
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
    case 'settings':
      return <Settings />
    case 'detail':
      return <Detail type={screen.type} id={screen.id} />
    case 'streams':
      return <Streams {...screen} />
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
