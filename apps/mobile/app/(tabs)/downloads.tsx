import { Alert, FlatList, Pressable, StyleSheet, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useRouter } from 'expo-router'
import {
  pauseDownload,
  removeDownload,
  resumeDownload,
  useDownloads,
  type DownloadEntry,
} from '@/downloads'
import { colors, spacing } from '@/theme'

export default function DownloadsScreen() {
  const downloads = useDownloads()
  const router = useRouter()

  if (downloads.length === 0) {
    return (
      <View style={styles.center}>
        <Text style={styles.message}>
          Downloads live here — pick a source on any title and tap the download icon. They play
          fully offline.
        </Text>
      </View>
    )
  }

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

  return (
    <FlatList
      style={styles.container}
      data={downloads}
      keyExtractor={(e) => e.id}
      renderItem={({ item }) => (
        <Pressable
          style={styles.row}
          onPress={() => (item.status === 'done' ? play(item) : undefined)}
          onLongPress={() => confirmRemove(item)}
        >
          <View style={styles.rowBody}>
            <Text style={styles.title} numberOfLines={1}>
              {item.title}
            </Text>
            <Text style={styles.detail}>
              {statusLabel(item)}
              {item.subtitleLang ? `  ·  subs: ${item.subtitleLang}` : ''}
            </Text>
            {item.status !== 'done' && item.totalBytes > 0 ? (
              <View style={styles.progressTrack}>
                <View
                  style={[styles.progressFill, { width: `${Math.min(100, (item.downloadedBytes / item.totalBytes) * 100)}%` }]}
                />
              </View>
            ) : null}
          </View>
          {item.status === 'downloading' ? (
            <IconButton name="pause" onPress={() => void pauseDownload(item.id)} />
          ) : null}
          {item.status === 'paused' || item.status === 'error' ? (
            <IconButton name="play" onPress={() => void resumeDownload(item.id)} />
          ) : null}
          {item.status === 'done' ? (
            <IconButton name="play-circle" onPress={() => play(item)} />
          ) : null}
        </Pressable>
      )}
    />
  )
}

function IconButton({ name, onPress }: { name: keyof typeof Ionicons.glyphMap; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} hitSlop={8} style={styles.iconButton}>
      <Ionicons name={name} size={24} color={colors.accent} />
    </Pressable>
  )
}

function statusLabel(entry: DownloadEntry): string {
  const mb = (n: number) => `${(n / 1024 / 1024).toFixed(0)} MB`
  switch (entry.status) {
    case 'done':
      return `Downloaded · ${mb(entry.totalBytes || entry.downloadedBytes)}`
    case 'downloading':
      return entry.totalBytes > 0 ? `${mb(entry.downloadedBytes)} of ${mb(entry.totalBytes)}` : 'Starting…'
    case 'paused':
      return 'Paused'
    case 'error':
      return 'Failed — tap play to retry'
  }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.border,
  },
  rowBody: {
    flex: 1,
    marginRight: spacing.sm,
  },
  title: {
    color: colors.text,
    fontSize: 15,
    fontWeight: '600',
  },
  detail: {
    color: colors.textDim,
    fontSize: 12,
    marginTop: 2,
  },
  progressTrack: {
    height: 4,
    borderRadius: 2,
    backgroundColor: colors.surfaceHigh,
    marginTop: spacing.sm,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: colors.accent,
  },
  iconButton: {
    padding: spacing.xs,
  },
  center: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
  },
  message: {
    color: colors.textDim,
    textAlign: 'center',
    fontSize: 15,
  },
})
