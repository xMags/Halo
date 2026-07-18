import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { NavProvider, useNav } from './nav'
import { SessionProvider, useSession } from './session'
import { Connect } from './screens/Connect'
import { Detail } from './screens/Detail'
import { Home } from './screens/Home'
import { Login } from './screens/Login'
import { Player } from './screens/Player'
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
      <Stack />
    </NavProvider>
  )
}

function Stack() {
  const { screen } = useNav()
  switch (screen.name) {
    case 'home':
      return <Home />
    case 'detail':
      return <Detail type={screen.type} id={screen.id} />
    case 'streams':
      return <Streams {...screen} />
    case 'player':
      return <Player {...screen} />
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
