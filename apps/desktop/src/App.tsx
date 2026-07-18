import { SessionProvider, useSession } from './session'
import { Connect } from './screens/Connect'
import { Home } from './screens/Home'
import { Login } from './screens/Login'

function Routes() {
  const { state } = useSession()
  if (state === 'unconfigured') return <Connect />
  if (state === 'unauthenticated') return <Login />
  return <Home />
}

export function App() {
  return (
    <SessionProvider>
      <Routes />
    </SessionProvider>
  )
}
