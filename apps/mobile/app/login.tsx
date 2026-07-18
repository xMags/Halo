import { useEffect, useRef, useState } from 'react'
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'
import { DEFAULT_SERVER_URL, getStoredServerUrl } from '@/api'
import { useResponsive } from '@/responsive'
import { useSession } from '@/session'
import { colors, radius, spacing } from '@/theme'

export default function LoginScreen() {
  const { isTablet } = useResponsive()
  const { signIn, signInLocal } = useSession()
  const [serverUrl, setServerUrl] = useState(DEFAULT_SERVER_URL)
  // 'server': just the URL; the server's /auth/config decides what comes next.
  // 'credentials': the server runs local accounts — collect username/password.
  const [phase, setPhase] = useState<'server' | 'credentials'>('server')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const usernameRef = useRef<TextInput>(null)

  useEffect(() => {
    void getStoredServerUrl().then(setServerUrl)
  }, [])

  const changeServerUrl = (url: string) => {
    setServerUrl(url)
    // A different server may authenticate differently — restart the flow.
    if (phase !== 'server') {
      setPhase('server')
      setUsername('')
      setPassword('')
    }
  }

  const credentialsMissing = phase === 'credentials' && (!username.trim() || !password)

  const submit = async () => {
    // Keyboard "done" fires this regardless of the button's disabled state.
    if (busy || credentialsMissing) return
    setBusy(true)
    setError(null)
    try {
      if (phase === 'server') {
        const result = await signIn(serverUrl.trim())
        if (result === 'credentials-required') {
          setPhase('credentials')
          usernameRef.current?.focus()
        }
        return
      }
      await signInLocal(serverUrl.trim(), username.trim(), password)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not connect')
    } finally {
      setBusy(false)
    }
  }

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <View style={[styles.form, isTablet && styles.formTablet]}>
        <Text style={styles.logo}>halo</Text>
        <Text style={styles.hint}>
          {phase === 'server'
            ? 'Your Halo server syncs your addons, library and watch progress.'
            : 'This server uses local accounts. Sign in with your Halo username and password.'}
        </Text>
        <TextInput
          style={styles.input}
          value={serverUrl}
          onChangeText={changeServerUrl}
          placeholder="Server URL"
          placeholderTextColor={colors.textDim}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          onSubmitEditing={submit}
        />
        {phase === 'credentials' ? (
          <>
            <TextInput
              ref={usernameRef}
              style={styles.input}
              value={username}
              onChangeText={setUsername}
              placeholder="Username"
              placeholderTextColor={colors.textDim}
              autoCapitalize="none"
              autoCorrect={false}
              autoComplete="username"
              textContentType="username"
              returnKeyType="next"
            />
            <TextInput
              style={styles.input}
              value={password}
              onChangeText={setPassword}
              placeholder="Password"
              placeholderTextColor={colors.textDim}
              secureTextEntry
              autoCapitalize="none"
              autoComplete="password"
              textContentType="password"
              onSubmitEditing={submit}
            />
          </>
        ) : null}
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <Pressable
          style={[styles.button, (busy || credentialsMissing) && styles.buttonDisabled]}
          onPress={submit}
          disabled={busy || credentialsMissing}
        >
          {busy ? (
            <ActivityIndicator color={colors.onAccent} />
          ) : (
            <Text style={styles.buttonText}>{phase === 'server' ? 'Continue' : 'Sign In'}</Text>
          )}
        </Pressable>
      </View>
    </KeyboardAvoidingView>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
    justifyContent: 'center',
  },
  form: {
    paddingHorizontal: spacing.lg,
    gap: spacing.md,
  },
  // Auth forms read best narrower than the 700dp reading cap — a lone input
  // and button stretched across a tablet is the complaint this fixes.
  formTablet: {
    width: '100%',
    maxWidth: 480,
    alignSelf: 'center',
  },
  logo: {
    color: colors.text,
    fontSize: 48,
    fontWeight: '800',
    textAlign: 'center',
    letterSpacing: 1,
  },
  hint: {
    color: colors.textDim,
    textAlign: 'center',
    fontSize: 14,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
  input: {
    backgroundColor: colors.fieldFill,
    borderRadius: radius.md,
    color: colors.text,
    paddingHorizontal: spacing.md,
    paddingVertical: 13,
    fontSize: 15,
  },
  error: {
    color: colors.danger,
    textAlign: 'center',
  },
  button: {
    backgroundColor: colors.accent,
    borderRadius: radius.md,
    paddingVertical: 15,
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  buttonDisabled: {
    opacity: 0.6,
  },
  buttonText: {
    color: colors.onAccent,
    fontWeight: '700',
    fontSize: 16,
  },
})
