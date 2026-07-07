import { useState } from 'react'
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { fetchManifest } from '@halo/core'
import { useAddons, useSetAddons } from '@/queries'
import { useSession } from '@/session'
import { colors, spacing } from '@/theme'

export default function AddonsScreen() {
  const { data: addons } = useAddons()
  const setAddons = useSetAddons()
  const { signOut } = useSession()
  const [url, setUrl] = useState('')
  const [adding, setAdding] = useState(false)

  const add = async () => {
    const transportUrl = url.trim()
    if (!transportUrl) return
    if ((addons ?? []).some((a) => a.transportUrl === transportUrl)) {
      Alert.alert('Already installed')
      return
    }
    setAdding(true)
    try {
      const manifest = await fetchManifest(transportUrl)
      const next = [...(addons ?? []), { transportUrl, manifest, position: addons?.length ?? 0 }]
      await setAddons.mutateAsync(next)
      setUrl('')
    } catch (err) {
      Alert.alert('Could not install addon', err instanceof Error ? err.message : 'Invalid manifest URL')
    } finally {
      setAdding(false)
    }
  }

  const remove = (transportUrl: string, name: string) => {
    Alert.alert('Remove addon?', name, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Remove',
        style: 'destructive',
        onPress: () => {
          const next = (addons ?? [])
            .filter((a) => a.transportUrl !== transportUrl)
            .map((a, position) => ({ ...a, position }))
          void setAddons.mutateAsync(next)
        },
      },
    ])
  }

  return (
    <View style={styles.container}>
      <View style={styles.addRow}>
        <TextInput
          style={styles.input}
          value={url}
          onChangeText={setUrl}
          placeholder="Addon manifest URL (…/manifest.json)"
          placeholderTextColor={colors.textDim}
          autoCapitalize="none"
          autoCorrect={false}
          keyboardType="url"
          onSubmitEditing={add}
        />
        <Pressable style={styles.addButton} onPress={add} disabled={adding}>
          {adding ? (
            <ActivityIndicator color={colors.background} />
          ) : (
            <Ionicons name="add" size={22} color={colors.background} />
          )}
        </Pressable>
      </View>

      <FlatList
        data={addons ?? []}
        keyExtractor={(a) => a.transportUrl}
        renderItem={({ item }) => (
          <View style={styles.addonRow}>
            <View style={styles.addonBody}>
              <Text style={styles.addonName}>
                {item.manifest.name} <Text style={styles.addonVersion}>v{item.manifest.version}</Text>
              </Text>
              {item.manifest.description ? (
                <Text style={styles.addonDescription} numberOfLines={2}>
                  {item.manifest.description}
                </Text>
              ) : null}
            </View>
            <Pressable onPress={() => remove(item.transportUrl, item.manifest.name)} hitSlop={8}>
              <Ionicons name="trash-outline" size={20} color={colors.danger} />
            </Pressable>
          </View>
        )}
      />

      <Pressable style={styles.signOut} onPress={() => void signOut()}>
        <Text style={styles.signOutText}>Sign out</Text>
      </Pressable>
    </View>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
    padding: spacing.md,
  },
  addRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginBottom: spacing.md,
  },
  input: {
    flex: 1,
    backgroundColor: colors.surface,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: colors.border,
    color: colors.text,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
    fontSize: 14,
  },
  addButton: {
    backgroundColor: colors.accent,
    borderRadius: 10,
    width: 44,
    alignItems: 'center',
    justifyContent: 'center',
  },
  addonRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  addonBody: {
    flex: 1,
    marginRight: spacing.sm,
  },
  addonName: {
    color: colors.text,
    fontSize: 15,
    fontWeight: '600',
  },
  addonVersion: {
    color: colors.textDim,
    fontWeight: '400',
    fontSize: 12,
  },
  addonDescription: {
    color: colors.textDim,
    fontSize: 12,
    marginTop: 2,
  },
  signOut: {
    alignItems: 'center',
    paddingVertical: spacing.md,
  },
  signOutText: {
    color: colors.danger,
    fontSize: 14,
  },
})
