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

  // The player owns the whole window — mpv paints behind the webview and any
  // opaque chrome would cover it, including the title bar (the player draws
  // its own caption buttons instead). Keyed by video so an autoplay replace()
  // remounts it clean: resume, prefetch and overlay state must not leak into
  // the next episode.
  if (screen.name === 'player') return <Player key={screen.videoId} {...screen} />

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
