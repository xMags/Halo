import { Alert, Image, Pressable, SectionList, StyleSheet, Text, View } from 'react-native'
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
import { formatBytes } from '@/format'
import { colors, radius, spacing, TAB_BAR_SPACE, type } from '@/theme'
import { CenterMessage } from '@/components/ui'

interface Section {
  itemId: string
  itemType: string
  name: string
  poster?: string
  data: DownloadEntry[]
}

/** Downloads belong to their show: one section per library item. */
function groupByItem(entries: DownloadEntry[]): Section[] {
  const byItem = new Map<string, DownloadEntry[]>()
  for (const entry of entries) {
    const group = byItem.get(entry.itemId)
    if (group) group.push(entry)
    else byItem.set(entry.itemId, [entry])
  }
  return [...byItem.entries()].map(([itemId, group]) => {
    const first = group[0]!
    return {
      itemId,
      itemType: first.type,
      name: first.showName ?? first.title,
      poster: first.poster,
      // Episode order, falling back to download order for movies/unknowns.
      data: [...group].sort((a, b) =>
        a.episodeLabel && b.episodeLabel
          ? a.episodeLabel.localeCompare(b.episodeLabel)
          : a.createdAt - b.createdAt,
      ),
    }
  })
}

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

  const openDetail = (section: Section) => {
    router.push({
      pathname: '/detail/[type]/[id]',
      params: { type: section.itemType, id: section.itemId.slice(section.itemType.length + 1) },
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
      {downloads.length > 0 ? <Text style={styles.summary}>{summaryLine(downloads)}</Text> : null}
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
    <SectionList
      style={styles.container}
      contentContainerStyle={{ paddingBottom: TAB_BAR_SPACE }}
      sections={groupByItem(downloads)}
      keyExtractor={(e) => e.id}
      ListHeaderComponent={header}
      showsVerticalScrollIndicator={false}
      stickySectionHeadersEnabled={false}
      renderSectionHeader={({ section }) => (
        <Pressable
          style={({ pressed }) => [styles.sectionHeader, pressed && styles.pressed]}
          onPress={() => openDetail(section)}
        >
          {section.poster ? (
            <Image source={{ uri: section.poster }} style={styles.poster} resizeMode="cover" />
          ) : (
            <View style={[styles.poster, styles.posterFallback]}>
              <Ionicons name="film-outline" size={18} color={colors.textDim} />
            </View>
          )}
          <View style={styles.sectionBody}>
            <Text style={styles.sectionName} numberOfLines={1}>
              {section.name}
            </Text>
            <Text style={styles.sectionMeta}>{sectionSummary(section.data)}</Text>
          </View>
          <Ionicons name="chevron-forward" size={18} color={colors.textDim} />
        </Pressable>
      )}
      renderItem={({ item }) => (
        <Pressable
          style={({ pressed }) => [styles.row, pressed && styles.pressed]}
          onPress={() => (item.status === 'done' ? play(item) : undefined)}
          onLongPress={() => confirmRemove(item)}
        >
          <View style={styles.rowBody}>
            <Text style={styles.title} numberOfLines={1}>
              {item.episodeLabel ?? (item.type === 'movie' ? 'Movie' : item.title)}
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
          <IconButton name="trash-outline" color={colors.textDim} onPress={() => confirmRemove(item)} />
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
    <Pressable
      onPress={onPress}
      hitSlop={8}
      style={({ pressed }) => [styles.iconButton, pressed && styles.pressed]}
    >
      <Ionicons name={name} size={24} color={color} />
    </Pressable>
  )
}

function summaryLine(entries: DownloadEntry[]): string {
  const bytes = entries.reduce((sum, e) => sum + (e.totalBytes || e.downloadedBytes), 0)
  const count = entries.length
  return `${count} ${count === 1 ? 'item' : 'items'} · ${formatBytes(bytes)} on device`
}

function sectionSummary(entries: DownloadEntry[]): string {
  const bytes = entries.reduce((sum, e) => sum + (e.totalBytes || e.downloadedBytes), 0)
  const active = entries.filter((e) => e.status === 'downloading').length
  const parts = [`${entries.length} ${entries.length === 1 ? 'download' : 'downloads'}`, formatBytes(bytes)]
  if (active > 0) parts.push(`${active} in progress`)
  return parts.filter(Boolean).join(' · ')
}

function statusLabel(entry: DownloadEntry): string {
  switch (entry.status) {
    case 'done':
      return `Downloaded · ${formatBytes(entry.totalBytes || entry.downloadedBytes)}`
    case 'downloading':
      return entry.totalBytes > 0
        ? `${formatBytes(entry.downloadedBytes)} of ${formatBytes(entry.totalBytes)}`
        : 'Starting…'
    case 'paused':
      return 'Paused'
    case 'error':
      return 'Failed — tap refresh to retry'
  }
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  summary: { ...type.overline, marginTop: 6, marginBottom: spacing.sm },
  pressed: { opacity: 0.55 },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.lg,
    paddingBottom: spacing.sm,
  },
  poster: { width: 46, height: 69, borderRadius: radius.sm - 2, backgroundColor: colors.surface },
  posterFallback: { alignItems: 'center', justifyContent: 'center' },
  sectionBody: { flex: 1, minWidth: 0 },
  sectionName: { ...type.heading },
  sectionMeta: { color: colors.textDim, fontSize: 12.5, marginTop: 2 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginLeft: spacing.md + 46 + spacing.sm + 4,
    paddingRight: spacing.md,
    paddingVertical: spacing.sm + 2,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  rowBody: { flex: 1, minWidth: 0 },
  title: { color: colors.text, fontSize: 14.5, fontWeight: '600' },
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
