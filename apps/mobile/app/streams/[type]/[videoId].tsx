import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useLocalSearchParams, useRouter } from 'expo-router'
import { computeVideoHash, languageMatches, type Stream } from '@halo/core'
import { api } from '@/api'
import { useStreams } from '@/queries'
import { attachDownloadSubtitle, getDownload, startDownload, useDownloads } from '@/downloads'
import { useSettings } from '@/settings'
import { formatBytes } from '@/format'
import { colors, radius, spacing, type } from '@/theme'
import { useResponsive } from '@/responsive'
import { CenterMessage } from '@/components/ui'

const HASH_TIMEOUT_MS = 8_000

/** AbortSignal.timeout isn't in Hermes — manual equivalent. */
function timeoutSignal(ms: number): AbortSignal {
  const controller = new AbortController()
  setTimeout(() => controller.abort(), ms)
  return controller.signal
}

export default function StreamsScreen() {
  const params = useLocalSearchParams<{
    type: string
    videoId: string
    itemId: string
    title: string
    showName?: string
    episodeLabel?: string
    poster?: string
  }>()
  const router = useRouter()
  const { contentMaxWidth } = useResponsive()
  const { data: addonStreams, isLoading } = useStreams(params.type, params.videoId)
  const downloads = useDownloads()
  const settings = useSettings()
  const existingDownload = downloads.find((d) => d.id === params.videoId && d.status === 'done')

  const play = (stream: Stream) => {
    router.replace({
      pathname: '/player',
      params: {
        uri: stream.url!,
        videoId: params.videoId,
        itemId: params.itemId,
        type: params.type,
        title: params.title,
        ...(stream.behaviorHints?.filename ? { filename: stream.behaviorHints.filename } : {}),
        ...(stream.behaviorHints?.videoSize ? { videoSize: String(stream.behaviorHints.videoSize) } : {}),
      },
    })
  }

  /**
   * Best preferred-language subtitle for the stream, attached to the download
   * entry after the fact. Runs fully in the background — the download button
   * must feel instant, and a video without its subtitle beats no download.
   * No preferred language means no bundled sub, mirroring the player's
   * auto-select ("unset = subtitles off by default").
   */
  const attachPreferredSubtitle = async (stream: Stream) => {
    const preferred = settings.preferredSubtitleLang
    if (!preferred) return
    let videoHash: string | undefined
    let videoSize = stream.behaviorHints?.videoSize
    try {
      const hash = await computeVideoHash(stream.url!, { signal: timeoutSignal(HASH_TIMEOUT_MS) })
      videoHash = hash.hash
      videoSize = videoSize ?? hash.size
    } catch {
      // Range-less or slow host: id/name search still returns candidates.
    }
    try {
      const { results } = await api().getSubtitles(
        params.type,
        params.videoId,
        { videoHash, videoSize, filename: stream.behaviorHints?.filename },
        { signal: timeoutSignal(HASH_TIMEOUT_MS) },
      )
      const match = results
        .flatMap((r) => r.subtitles ?? [])
        .find((sub) => languageMatches(sub.lang, preferred))
      if (match) await attachDownloadSubtitle(params.videoId, { url: match.url, lang: match.lang })
    } catch {
      // Unreachable server — the download itself is never blocked on subtitles.
    }
  }

  const beginDownload = (stream: Stream) => {
    if (getDownload(params.videoId)) {
      Alert.alert('Already downloaded', 'This video is already in your downloads.')
      return
    }
    void startDownload({
      id: params.videoId,
      itemId: params.itemId,
      type: params.type,
      title: params.title,
      showName: params.showName ?? params.title,
      episodeLabel: params.episodeLabel,
      filename: stream.behaviorHints?.filename,
      poster: params.poster,
      streamUrl: stream.url!,
    })
    void attachPreferredSubtitle(stream)
    Alert.alert('Download started', 'Track progress in the Downloads tab.')
  }

  if (isLoading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
        <Text style={styles.dim}>Asking your addons for sources…</Text>
      </View>
    )
  }

  if (!addonStreams || addonStreams.length === 0) {
    return (
      <CenterMessage>
        No playable sources. Install a stream addon (e.g. a debrid-backed one) in Settings.
      </CenterMessage>
    )
  }

  return (
    <ScrollView
        style={styles.container}
        contentContainerStyle={[
          styles.content,
          contentMaxWidth ? { maxWidth: contentMaxWidth, width: '100%', alignSelf: 'center' } : null,
        ]}
      >
        {existingDownload ? (
          <Pressable
            style={({ pressed }) => [styles.offlineCard, pressed && styles.pressed]}
            onPress={() =>
              router.replace({
                pathname: '/player',
                params: {
                  uri: existingDownload.fileUri,
                  videoId: existingDownload.id,
                  itemId: existingDownload.itemId,
                  type: existingDownload.type,
                  title: existingDownload.title,
                  ...(existingDownload.subtitleUri ? { subtitleUri: existingDownload.subtitleUri } : {}),
                },
              })
            }
          >
            <Ionicons name="arrow-down-circle" size={22} color={colors.success} />
            <View style={styles.offlineBody}>
              <Text style={styles.streamName}>Downloaded</Text>
              <Text style={styles.dim}>On this device · plays offline</Text>
            </View>
            <Ionicons name="play" size={20} color={colors.text} />
          </Pressable>
        ) : null}
        {addonStreams.map((group) => (
          <View key={group.addonId} style={styles.group}>
            <Text style={styles.groupHeading}>{group.addonName}</Text>
            <View style={styles.card}>
              {group.streams.map((stream, index) => {
                const key = `${group.addonId}:${index}`
                return (
                  <View
                    key={key}
                    style={[styles.streamRow, index === group.streams.length - 1 && styles.lastRow]}
                  >
                    <Pressable
                      style={({ pressed }) => [styles.streamBody, pressed && styles.pressed]}
                      onPress={() => play(stream)}
                    >
                      <View style={styles.streamHead}>
                        <Text style={[styles.streamName, styles.streamNameFlex]} numberOfLines={1}>
                          {stream.name ?? group.addonName}
                        </Text>
                        {stream.behaviorHints?.videoSize ? (
                          <Text style={styles.size}>{formatBytes(stream.behaviorHints.videoSize)}</Text>
                        ) : null}
                      </View>
                      {stream.title ?? stream.description ? (
                        <Text style={styles.dim} numberOfLines={2}>
                          {stream.title ?? stream.description}
                        </Text>
                      ) : null}
                      {stream.behaviorHints?.filename ? (
                        <Text style={styles.filename} numberOfLines={1}>
                          {stream.behaviorHints.filename}
                        </Text>
                      ) : null}
                    </Pressable>
                    <Pressable
                      onPress={() => beginDownload(stream)}
                      hitSlop={8}
                      style={({ pressed }) => [styles.downloadButton, pressed && styles.pressed]}
                    >
                      <Ionicons name="download-outline" size={22} color={colors.accent} />
                    </Pressable>
                  </View>
                )
              })}
            </View>
          </View>
        ))}
    </ScrollView>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  content: { padding: spacing.md },
  center: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
    gap: spacing.sm,
  },
  group: { marginBottom: spacing.md },
  groupHeading: { ...type.overline, color: colors.accent, marginBottom: spacing.sm, paddingHorizontal: spacing.xs },
  card: {
    backgroundColor: colors.glass,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    overflow: 'hidden',
  },
  streamRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  lastRow: { borderBottomWidth: 0 },
  streamBody: { flex: 1, paddingHorizontal: spacing.md, paddingVertical: spacing.sm + 2 },
  streamHead: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  streamName: { color: colors.text, fontSize: 14, fontWeight: '600' },
  streamNameFlex: { flexShrink: 1 },
  size: { color: colors.textDim, fontSize: 12.5, fontWeight: '600', fontVariant: ['tabular-nums'] },
  dim: { color: colors.textDim, fontSize: 12, marginTop: 2 },
  filename: { color: colors.textDim, fontSize: 10.5, marginTop: 3, fontVariant: ['tabular-nums'], opacity: 0.8 },
  downloadButton: { paddingHorizontal: spacing.md, paddingVertical: spacing.sm, minWidth: 54, alignItems: 'center' },
  pressed: { opacity: 0.55 },
  offlineCard: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    backgroundColor: colors.glass,
    borderRadius: radius.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm + 4,
    marginBottom: spacing.md,
  },
  offlineBody: { flex: 1 },
})
