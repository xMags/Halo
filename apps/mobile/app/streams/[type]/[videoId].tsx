import { useState } from 'react'
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
import {
  computeVideoHash,
  getSubtitles,
  addonSupportsResource,
  languageLabel,
  type Stream,
  type Subtitle,
} from '@halo/core'
import { useAddons, useStreams } from '@/queries'
import { getDownload, startDownload } from '@/downloads'
import { formatBytes } from '@/format'
import { colors, radius, spacing, type } from '@/theme'
import { SelectSheet } from '@/components/SelectSheet'
import { CenterMessage } from '@/components/ui'

export default function StreamsScreen() {
  const params = useLocalSearchParams<{
    type: string
    videoId: string
    itemId: string
    title: string
    poster?: string
  }>()
  const router = useRouter()
  const { data: addonStreams, isLoading } = useStreams(params.type, params.videoId)
  const { data: addons } = useAddons()
  const [subtitlePick, setSubtitlePick] = useState<{ stream: Stream; subtitles: Subtitle[] } | null>(null)
  const [preparingDownload, setPreparingDownload] = useState(false)

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

  // Download flow: offer the subtitle choice up front so the file ships with
  // the video and offline playback keeps subs.
  const prepareDownload = async (stream: Stream) => {
    setPreparingDownload(true)
    try {
      const subtitleAddons = (addons ?? []).filter((a) =>
        addonSupportsResource(a.manifest, 'subtitles', params.type, params.videoId),
      )
      let videoHash: string | undefined
      let videoSize = stream.behaviorHints?.videoSize
      try {
        const hash = await computeVideoHash(stream.url!)
        videoHash = hash.hash
        videoSize = videoSize ?? hash.size
      } catch {
        // Range-less host: id/name search still returns candidates.
      }
      const results = await Promise.allSettled(
        subtitleAddons.map((a) =>
          getSubtitles(a.transportUrl, params.type, params.videoId, {
            videoHash,
            videoSize,
            filename: stream.behaviorHints?.filename,
          }),
        ),
      )
      const subtitles = results
        .filter((r): r is PromiseFulfilledResult<{ subtitles: Subtitle[] }> => r.status === 'fulfilled')
        .flatMap((r) => r.value.subtitles ?? [])
      setSubtitlePick({ stream, subtitles })
    } finally {
      setPreparingDownload(false)
    }
  }

  const beginDownload = (stream: Stream, subtitle?: Subtitle) => {
    if (getDownload(params.videoId)) {
      Alert.alert('Already downloaded', 'This video is already in your downloads.')
      return
    }
    void startDownload({
      id: params.videoId,
      itemId: params.itemId,
      type: params.type,
      title: params.title,
      poster: params.poster,
      streamUrl: stream.url!,
      subtitle: subtitle ? { url: subtitle.url, lang: subtitle.lang } : undefined,
    })
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
    <>
      <ScrollView style={styles.container} contentContainerStyle={styles.content}>
        {addonStreams.map((group) => (
          <View key={group.transportUrl} style={styles.group}>
            <Text style={styles.groupHeading}>{group.addonName}</Text>
            <View style={styles.card}>
              {group.streams.map((stream, index) => (
                <View
                  key={`${group.transportUrl}:${index}`}
                  style={[styles.streamRow, index === group.streams.length - 1 && styles.lastRow]}
                >
                  <Pressable style={styles.streamBody} onPress={() => play(stream)}>
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
                  </Pressable>
                  <Pressable
                    onPress={() => void prepareDownload(stream)}
                    hitSlop={8}
                    style={styles.downloadButton}
                    disabled={preparingDownload}
                  >
                    <Ionicons name="download-outline" size={22} color={colors.accent} />
                  </Pressable>
                </View>
              ))}
            </View>
          </View>
        ))}
        {preparingDownload ? (
          <View style={styles.preparing}>
            <ActivityIndicator color={colors.accent} />
            <Text style={styles.dim}>Finding subtitles…</Text>
          </View>
        ) : null}
      </ScrollView>

      <SelectSheet
        visible={subtitlePick !== null}
        title="Download subtitles too?"
        options={[
          { key: 'none', label: 'No subtitles' },
          ...(subtitlePick?.subtitles ?? []).map((sub, index) => ({
            key: String(index),
            label: languageLabel(sub.lang),
            detail: sub.id,
          })),
        ]}
        onSelect={(key) => {
          if (!subtitlePick) return
          const subtitle = key === 'none' ? undefined : subtitlePick.subtitles[Number(key)]
          beginDownload(subtitlePick.stream, subtitle)
        }}
        onClose={() => setSubtitlePick(null)}
      />
    </>
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
  downloadButton: { paddingHorizontal: spacing.md, paddingVertical: spacing.sm },
  preparing: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.sm,
    padding: spacing.md,
  },
})
