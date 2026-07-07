import { useEffect } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Stack } from 'expo-router'
import { StatusBar } from 'expo-status-bar'
import { initDownloads } from '@/downloads'
import { SessionProvider, useSession } from '@/session'
import { colors } from '@/theme'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
    },
  },
})

function RootNavigator() {
  const { status } = useSession()
  if (status === 'loading') return null
  return (
    <Stack
      screenOptions={{
        headerStyle: { backgroundColor: colors.background },
        headerTintColor: colors.text,
        contentStyle: { backgroundColor: colors.background },
      }}
    >
      <Stack.Protected guard={status === 'ready'}>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen name="detail/[type]/[id]" options={{ title: '' }} />
        <Stack.Screen name="streams/[type]/[videoId]" options={{ title: 'Sources', presentation: 'modal' }} />
        <Stack.Screen name="player" options={{ headerShown: false, orientation: 'landscape', autoHideHomeIndicator: true }} />
      </Stack.Protected>
      <Stack.Protected guard={status !== 'ready'}>
        <Stack.Screen name="login" options={{ headerShown: false }} />
      </Stack.Protected>
    </Stack>
  )
}

export default function RootLayout() {
  useEffect(() => {
    void initDownloads()
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <StatusBar style="light" />
        <RootNavigator />
      </SessionProvider>
    </QueryClientProvider>
  )
}
