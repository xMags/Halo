import { useState } from 'react'
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
} from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { LANGUAGE_OPTIONS, languageLabel } from '@halo/core'
import { api } from '@/api'
import { useAddons, useMe, useSetAddons, useSetGlobalAddons } from '@/queries'
import { useSession } from '@/session'
import { useSettings, useUpdateSettings } from '@/settings'
import { colors, radius, spacing, TAB_BAR_SPACE, type } from '@/theme'
import { useResponsive } from '@/responsive'
import { SelectSheet } from '@/components/SelectSheet'

/**
 * Settings screen. Route stays `addons` (other code navigates by this path),
 * but it now houses addon management, server status, and sign-out.
 */
export default function SettingsScreen() {
  const insets = useSafeAreaInsets()
  const { contentMaxWidth } = useResponsive()
  const { data: addons } = useAddons()
  const { data: me } = useMe()
  const setAddons = useSetAddons()
  const setGlobalAddons = useSetGlobalAddons()
  const { signOut } = useSession()
  const settings = useSettings()
  const updateSettings = useUpdateSettings()
  const [url, setUrl] = useState('')
  const [adding, setAdding] = useState(false)
  const [globalUrl, setGlobalUrl] = useState('')
  const [addingGlobal, setAddingGlobal] = useState(false)
  const [languagePick, setLanguagePick] = useState<'audio' | 'subtitles' | null>(null)

  const isAdmin = me?.isAdmin ?? false
  const globalAddons = addons?.global ?? []
  const userAddons = addons?.user ?? []

  const preferredFor = (pick: 'audio' | 'subtitles') =>
    pick === 'audio' ? settings.preferredAudioLang : settings.preferredSubtitleLang

  // Only the user's own list is sent, as transport URLs in priority order; the
  // server diffs against what's stored and fetches manifests for new URLs only.
  const saveUserAddons = (urls: string[]) => setAddons.mutateAsync(urls)

  const add = async () => {
    const transportUrl = url.trim()
    if (!transportUrl) return
    if ([...globalAddons, ...userAddons].some((a) => a.transportUrl === transportUrl)) {
      Alert.alert('Already installed')
      return
    }
    setAdding(true)
    try {
      // Own entries always carry their URL (the caller sent it) — only global
      // entries are redacted for non-admins.
      await saveUserAddons([...userAddons.map((a) => a.transportUrl!), transportUrl])
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
          void saveUserAddons(userAddons.filter((a) => a.transportUrl !== transportUrl).map((a) => a.transportUrl!))
        },
      },
    ])
  }

  // Global addons: admin-only, declares the list every user sees. Same diff
  // contract as the user list, but writes via putGlobalAddons (403 for
  // non-admins — the section is gated on isAdmin).
  const saveGlobalAddons = (urls: string[]) => setGlobalAddons.mutateAsync(urls)

  const addGlobal = async () => {
    const transportUrl = globalUrl.trim()
    if (!transportUrl) return
    if ([...globalAddons, ...userAddons].some((a) => a.transportUrl === transportUrl)) {
      Alert.alert('Already installed')
      return
    }
    setAddingGlobal(true)
    try {
      // Admin-gated section: the server keeps global URLs visible to admins.
      await saveGlobalAddons([...globalAddons.map((a) => a.transportUrl!), transportUrl])
      setGlobalUrl('')
    } catch (err) {
      Alert.alert('Could not install addon', err instanceof Error ? err.message : 'Invalid manifest URL')
    } finally {
      setAddingGlobal(false)
    }
  }

  const removeGlobal = (transportUrl: string, name: string) => {
    Alert.alert('Remove global addon?', `${name}\n\nThis removes it for every user.`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Remove',
        style: 'destructive',
        onPress: () => {
          void saveGlobalAddons(globalAddons.filter((a) => a.transportUrl !== transportUrl).map((a) => a.transportUrl!))
        },
      },
    ])
  }

  return (
    <View style={styles.container}>
    <ScrollView
      contentContainerStyle={[
        { paddingTop: insets.top + spacing.xs, paddingBottom: TAB_BAR_SPACE, paddingHorizontal: spacing.md },
        contentMaxWidth ? { maxWidth: contentMaxWidth, width: '100%', alignSelf: 'center' } : null,
      ]}
      keyboardShouldPersistTaps="handled"
      showsVerticalScrollIndicator={false}
    >
      <Text style={[type.largeTitle, styles.title]}>Settings</Text>

      <Text style={styles.groupLabel}>Addons</Text>
      <View style={styles.card}>
        <View style={styles.addRow}>
          <TextInput
            style={styles.input}
            value={url}
            onChangeText={setUrl}
            placeholder="https://…/manifest.json"
            placeholderTextColor={colors.textDim}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            onSubmitEditing={add}
          />
          <Pressable style={styles.addButton} onPress={add} disabled={adding}>
            {adding ? (
              <ActivityIndicator color={colors.onAccent} size="small" />
            ) : (
              <Text style={styles.addButtonText}>Add</Text>
            )}
          </Pressable>
        </View>
        {userAddons.map((item, i) => (
          <View key={item.id} style={[styles.addonRow, i === userAddons.length - 1 && styles.lastRow]}>
            <View style={styles.addonIcon}>
              <Ionicons name="extension-puzzle" size={19} color={colors.accent} />
            </View>
            <View style={styles.addonBody}>
              <Text style={styles.addonName}>
                {item.manifest.name} <Text style={styles.addonVersion}>v{item.manifest.version}</Text>
              </Text>
              {item.manifest.description ? (
                <Text style={styles.addonDescription} numberOfLines={1}>
                  {item.manifest.description}
                </Text>
              ) : null}
            </View>
            <Pressable onPress={() => remove(item.transportUrl!, item.manifest.name)} hitSlop={8}>
              <Ionicons name="close" size={19} color={colors.textDim} />
            </Pressable>
          </View>
        ))}
      </View>

      {isAdmin ? (
        <>
          <Text style={styles.groupLabel}>Global · admin</Text>
          <View style={styles.card}>
            <View style={styles.addRow}>
              <TextInput
                style={styles.input}
                value={globalUrl}
                onChangeText={setGlobalUrl}
                placeholder="https://…/manifest.json"
                placeholderTextColor={colors.textDim}
                autoCapitalize="none"
                autoCorrect={false}
                keyboardType="url"
                onSubmitEditing={addGlobal}
              />
              <Pressable style={styles.addButton} onPress={addGlobal} disabled={addingGlobal}>
                {addingGlobal ? (
                  <ActivityIndicator color={colors.onAccent} size="small" />
                ) : (
                  <Text style={styles.addButtonText}>Add</Text>
                )}
              </Pressable>
            </View>
            {globalAddons.length === 0 ? (
              <View style={[styles.addonRow, styles.lastRow]}>
                <Text style={styles.addonDescription}>No global addons yet — anything added here appears for every user.</Text>
              </View>
            ) : (
              globalAddons.map((item, i) => (
                <View key={item.id} style={[styles.addonRow, i === globalAddons.length - 1 && styles.lastRow]}>
                  <View style={styles.addonIcon}>
                    <Ionicons name="globe-outline" size={19} color={colors.accent} />
                  </View>
                  <View style={styles.addonBody}>
                    <Text style={styles.addonName}>
                      {item.manifest.name} <Text style={styles.addonVersion}>v{item.manifest.version}</Text>
                    </Text>
                    {item.manifest.description ? (
                      <Text style={styles.addonDescription} numberOfLines={1}>
                        {item.manifest.description}
                      </Text>
                    ) : null}
                  </View>
                  <Pressable onPress={() => removeGlobal(item.transportUrl!, item.manifest.name)} hitSlop={8}>
                    <Ionicons name="close" size={19} color={colors.textDim} />
                  </Pressable>
                </View>
              ))
            )}
          </View>
        </>
      ) : globalAddons.length > 0 ? (
        <>
          <Text style={styles.groupLabel}>Global</Text>
          <View style={styles.card}>
            {globalAddons.map((item, i) => (
              <View key={item.id} style={[styles.addonRow, i === globalAddons.length - 1 && styles.lastRow]}>
                <View style={styles.addonIcon}>
                  <Ionicons name="globe-outline" size={19} color={colors.accent} />
                </View>
                <View style={styles.addonBody}>
                  <Text style={styles.addonName}>
                    {item.manifest.name} <Text style={styles.addonVersion}>v{item.manifest.version}</Text>
                  </Text>
                  {item.manifest.description ? (
                    <Text style={styles.addonDescription} numberOfLines={1}>
                      {item.manifest.description}
                    </Text>
                  ) : null}
                </View>
                <Ionicons name="lock-closed" size={15} color={colors.textDim} />
              </View>
            ))}
          </View>
        </>
      ) : null}

      <Text style={styles.groupLabel}>Playback</Text>
      <View style={styles.card}>
        <Pressable style={styles.settingRow} onPress={() => setLanguagePick('audio')}>
          <Text style={styles.settingKey}>Default audio language</Text>
          <View style={styles.settingChevron}>
            <Text style={styles.settingValue}>
              {settings.preferredAudioLang ? languageLabel(settings.preferredAudioLang) : 'Auto'}
            </Text>
            <Ionicons name="chevron-forward" size={15} color={colors.textDim} />
          </View>
        </Pressable>
        <Pressable style={styles.settingRow} onPress={() => setLanguagePick('subtitles')}>
          <Text style={styles.settingKey}>Default subtitles</Text>
          <View style={styles.settingChevron}>
            <Text style={styles.settingValue}>
              {settings.preferredSubtitleLang ? languageLabel(settings.preferredSubtitleLang) : 'Off'}
            </Text>
            <Ionicons name="chevron-forward" size={15} color={colors.textDim} />
          </View>
        </Pressable>
        <View style={[styles.settingRow, styles.lastRow]}>
          <Text style={styles.settingKey}>Autoplay next episode</Text>
          <Switch
            value={settings.autoplayNextEpisode ?? true}
            onValueChange={(value) => updateSettings.mutate({ autoplayNextEpisode: value })}
            trackColor={{ true: colors.accent }}
          />
        </View>
      </View>

      <Text style={styles.groupLabel}>Server</Text>
      <View style={styles.card}>
        <View style={styles.settingRow}>
          <Text style={styles.settingKey}>Server</Text>
          <Text style={styles.settingValue} numberOfLines={1}>
            {api().baseUrl.replace(/^https?:\/\//, '')}
          </Text>
        </View>
        <View style={[styles.settingRow, styles.lastRow]}>
          <Text style={styles.settingKey}>Status</Text>
          <View style={styles.statusValue}>
            <View style={styles.statusDot} />
            <Text style={styles.statusText}>Connected</Text>
          </View>
        </View>
      </View>

      <Pressable style={[styles.card, styles.signOut]} onPress={() => void signOut()}>
        <Text style={styles.signOutText}>Sign Out</Text>
      </Pressable>
    </ScrollView>

      {/* Sibling of the ScrollView: SelectSheet is an absolute overlay now. */}
      <SelectSheet
        visible={languagePick !== null}
        title={languagePick === 'audio' ? 'Default audio language' : 'Default subtitles'}
        options={[
          {
            key: 'none',
            label: languagePick === 'audio' ? 'Auto (first track)' : 'Off',
            selected: languagePick !== null && !preferredFor(languagePick),
          },
          ...LANGUAGE_OPTIONS.map((lang) => ({
            key: lang.code,
            label: lang.label,
            selected: languagePick !== null && preferredFor(languagePick) === lang.code,
          })),
        ]}
        onSelect={(key) => {
          const value = key === 'none' ? undefined : key
          updateSettings.mutate(
            languagePick === 'audio' ? { preferredAudioLang: value } : { preferredSubtitleLang: value },
          )
        }}
        onClose={() => setLanguagePick(null)}
      />
    </View>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  title: { marginBottom: spacing.md },
  groupLabel: { ...type.overline, marginTop: spacing.md, marginBottom: spacing.sm, paddingHorizontal: spacing.xs },
  card: {
    backgroundColor: colors.glass,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    overflow: 'hidden',
  },
  addRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    padding: spacing.sm + 2,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  input: {
    flex: 1,
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderRadius: radius.sm + 1,
    color: colors.text,
    paddingHorizontal: 12,
    paddingVertical: 9,
    fontSize: 13.5,
  },
  addButton: {
    backgroundColor: colors.accent,
    borderRadius: radius.sm + 1,
    paddingHorizontal: 16,
    minWidth: 56,
    alignItems: 'center',
    justifyContent: 'center',
  },
  addButtonText: { color: colors.onAccent, fontSize: 14, fontWeight: '600' },
  addonRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    paddingHorizontal: spacing.sm + 4,
    paddingVertical: spacing.sm + 4,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  addonIcon: {
    width: 34,
    height: 34,
    borderRadius: radius.sm + 1,
    backgroundColor: 'rgba(10,132,255,0.14)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  addonBody: { flex: 1 },
  addonName: { color: colors.text, fontSize: 15, fontWeight: '600' },
  addonVersion: { color: colors.textDim, fontWeight: '400', fontSize: 12 },
  addonDescription: { color: colors.textDim, fontSize: 12.5, marginTop: 1 },
  settingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.md,
    paddingVertical: 13,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  settingKey: { color: colors.text, fontSize: 15 },
  settingValue: { color: colors.textDim, fontSize: 14, flexShrink: 1, marginLeft: spacing.md },
  settingChevron: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  statusValue: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  statusDot: { width: 7, height: 7, borderRadius: 999, backgroundColor: colors.success },
  statusText: { color: colors.success, fontSize: 14, fontWeight: '500' },
  lastRow: { borderBottomWidth: 0 },
  signOut: { marginTop: spacing.lg, alignItems: 'center', paddingVertical: spacing.md },
  signOutText: { color: colors.danger, fontSize: 15, fontWeight: '600' },
})
