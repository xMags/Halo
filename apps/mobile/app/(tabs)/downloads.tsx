import { Alert, FlatList, Image, Pressable, StyleSheet, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import {
  pauseDownload,
  removeDownload,
  resumeDownload,
  useDownloads,
  type DownloadEntry,
} from '@/downloads'
import { colors, radius, spacing, TAB_BAR_SPACE, type } from '@/theme'
import { CenterMessage } from '@/components/ui'

export default function DownloadsScreen() {
  const downloads = useDownloads()
  const router = useRouter()
  const insets = useSafeAreaInsets()

  const play = (entry: DownloadEntry) => {
    router.push({
      pathname: '/player',
      params: {
        uri: entry.fileUri,
        videoId: entry.id,
        itemId: entry.itemId,
        type: entry.type,
        title: entry.title,
        ...(entry.subtitleUri ? { subtitleUri: entry.subtitleUri } : {}),
      },
    })
  }

  const confirmRemove = (entry: DownloadEntry) => {
    Alert.alert('Delete download?', entry.title, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: () => void removeDownload(entry.id) },
    ])
  }

  const header = (
    <View style={{ paddingTop: insets.top + spacing.xs, paddingHorizontal: spacing.md }}>
      <Text style={type.largeTitle}>Downloads</Text>
      {downloads.length > 0 ? (
        <Text style={styles.summary}>{summaryLine(downloads)}</Text>
      ) : null}
    </View>
  )

  if (downloads.length === 0) {
    return (
      <View style={styles.container}>
        {header}
        <CenterMessage>
          Downloads live here — pick a source on any title and tap the download icon. They play fully
          offline.
        </CenterMessage>
      </View>
    )
  }

  return (
    <FlatList
      style={styles.container}
      contentContainerStyle={{ paddingBottom: TAB_BAR_SPACE }}
      data={downloads}
      keyExtractor={(e) => e.id}
      ListHeaderComponent={header}
      showsVerticalScrollIndicator={false}
      renderItem={({ item }) => (
        <Pressable
          style={styles.row}
          onPress={() => (item.status === 'done' ? play(item) : undefined)}
          onLongPress={() => confirmRemove(item)}
        >
          {item.poster ? (
            <Image source={{ uri: item.poster }} style={styles.thumb} resizeMode="cover" />
          ) : (
            <View style={[styles.thumb, styles.thumbFallback]}>
              <Ionicons name="film-outline" size={20} color={colors.textDim} />
            </View>
          )}
          <View style={styles.rowBody}>
            <Text style={styles.title} numberOfLines={1}>
              {item.title}
            </Text>
            <Text style={[styles.detail, item.status === 'error' && styles.detailError]} numberOfLines={1}>
              {statusLabel(item)}
              {item.subtitleLang ? `  ·  subs: ${item.subtitleLang}` : ''}
            </Text>
            {item.status !== 'done' && item.totalBytes > 0 ? (
              <View style={styles.progressTrack}>
                <View
                  style={[
                    styles.progressFill,
                    item.status === 'paused' && styles.progressPaused,
                    { width: `${Math.min(100, (item.downloadedBytes / item.totalBytes) * 100)}%` },
                  ]}
                />
              </View>
            ) : null}
          </View>
          {item.status === 'downloading' ? (
            <IconButton name="pause" onPress={() => void pauseDownload(item.id)} />
          ) : null}
          {item.status === 'paused' ? (
            <IconButton name="play" onPress={() => void resumeDownload(item.id)} />
          ) : null}
          {item.status === 'error' ? (
            <IconButton name="refresh" color={colors.danger} onPress={() => void resumeDownload(item.id)} />
          ) : null}
          {item.status === 'done' ? (
            <IconButton name="play-circle" onPress={() => play(item)} />
          ) : null}
        </Pressable>
      )}
    />
  )
}

function IconButton({
  name,
  onPress,
  color = colors.accent,
}: {
  name: keyof typeof Ionicons.glyphMap
  onPress: () => void
  color?: string
}) {
  return (
    <Pressable onPress={onPress} hitSlop={8} style={styles.iconButton}>
      <Ionicons name={name} size={26} color={color} />
    </Pressable>
  )
}

const mb = (n: number) => `${(n / 1024 / 1024).toFixed(0)} MB`
function gb(n: number): string {
  return n >= 1024 * 1024 * 1024 ? `${(n / 1024 / 1024 / 1024).toFixed(1)} GB` : mb(n)
}

function summaryLine(entries: DownloadEntry[]): string {
  const bytes = entries.reduce((sum, e) => sum + (e.totalBytes || e.downloadedBytes), 0)
  const count = entries.length
  return `${count} ${count === 1 ? 'item' : 'items'} · ${gb(bytes)} on device`
}

function statusLabel(entry: DownloadEntry): string {
  switch (entry.status) {
    case 'done':
      return `Downloaded · ${gb(entry.totalBytes || entry.downloadedBytes)}`
    case 'downloading':
      return entry.totalBytes > 0 ? `${mb(entry.downloadedBytes)} of ${mb(entry.totalBytes)}` : 'Starting…'
    case 'paused':
      return 'Paused'
    case 'error':
      return 'Failed — tap to retry'
  }
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  summary: { ...type.overline, marginTop: 6, marginBottom: spacing.sm },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm + 2,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  thumb: { width: 46, height: 69, borderRadius: radius.sm - 2, backgroundColor: colors.surface },
  thumbFallback: { alignItems: 'center', justifyContent: 'center' },
  rowBody: { flex: 1, minWidth: 0 },
  title: { color: colors.text, fontSize: 15, fontWeight: '600' },
  detail: { color: colors.textDim, fontSize: 12.5, marginTop: 2 },
  detailError: { color: '#ff8f8f' },
  progressTrack: {
    height: 4,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.16)',
    marginTop: spacing.sm,
    overflow: 'hidden',
  },
  progressFill: { height: '100%', backgroundColor: colors.accent },
  progressPaused: { backgroundColor: colors.textDim },
  iconButton: { padding: spacing.xs },
})
