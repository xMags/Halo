import { useMemo, useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Image,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { Stack, useLocalSearchParams, useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import type { MetaVideo } from '@halo/core'
import { libraryItemFromMeta, useLibrary, useMeta, useUpsertLibrary, useWatchStates } from '@/queries'
import { useDownloads } from '@/downloads'
import { colors, radius, spacing } from '@/theme'
import { SelectSheet } from '@/components/SelectSheet'
import { HeroScrim, MetaLine } from '@/components/ui'

export default function DetailScreen() {
  const { type, id } = useLocalSearchParams<{ type: string; id: string }>()
  const router = useRouter()
  const insets = useSafeAreaInsets()
  const { data: meta, isLoading, isError } = useMeta(type, id)
  const { data: library } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const upsertLibrary = useUpsertLibrary()
  const [season, setSeason] = useState<number | null>(null)
  const [seasonSheetOpen, setSeasonSheetOpen] = useState(false)

  const itemId = `${type}:${id}`
  const libraryEntry = (library ?? []).find((item) => item.id === itemId && !item.removedAt)
  const downloads = useDownloads()
  const downloadsById = new Map(downloads.map((d) => [d.id, d]))

  const seasons = useMemo(() => {
    const numbers = new Set<number>()
    for (const video of meta?.videos ?? []) {
      if (video.season !== undefined) numbers.add(video.season)
    }
    // Season 0 (specials) sorts last, like every other media center.
    return [...numbers].sort((a, b) => (a === 0 ? 1 : b === 0 ? -1 : a - b))
  }, [meta])

  if (isLoading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
      </View>
    )
  }
  if (isError || !meta) {
    return (
      <View style={styles.center}>
        <Text style={styles.dim}>No installed addon could describe this title.</Text>
      </View>
    )
  }

  const activeSeason = season ?? seasons[0] ?? null
  const episodes = (meta.videos ?? [])
    .filter((video) => activeSeason === null || video.season === activeSeason)
    .sort((a, b) => (a.episode ?? 0) - (b.episode ?? 0))

  const toggleLibrary = () => {
    const now = Date.now()
    if (libraryEntry) {
      void upsertLibrary.mutateAsync([{ ...libraryEntry, removedAt: now, updatedAt: now }])
    } else {
      void upsertLibrary.mutateAsync([libraryItemFromMeta(meta)])
    }
  }

  const openStreams = (videoId: string, episodeLabel?: string) => {
    router.push({
      pathname: '/streams/[type]/[videoId]',
      params: {
        type,
        videoId,
        itemId,
        title: episodeLabel ? `${meta.name} — ${episodeLabel}` : meta.name,
        showName: meta.name,
        ...(episodeLabel ? { episodeLabel } : {}),
        ...(meta.poster ? { poster: meta.poster } : {}),
      },
    })
  }

  const progressFor = (videoId: string) => {
    const state = (watchStates ?? []).find((s) => s.videoId === videoId)
    if (!state || state.durationSec === 0) return null
    return state
  }

  const inLibrary = !!libraryEntry
  const header = (
    <View>
      <View style={styles.hero}>
        <Image source={{ uri: meta.background ?? meta.poster }} style={StyleSheet.absoluteFill} resizeMode="cover" />
        <HeroScrim />
        <View style={styles.heroBody}>
          <Text style={styles.name}>{meta.name}</Text>
          <MetaLine parts={[meta.releaseInfo, meta.runtime]} rating={meta.imdbRating} />
        </View>
      </View>

      <View style={styles.body}>
        <View style={styles.actions}>
          {type === 'movie' ? (
            <Pressable style={styles.playButton} onPress={() => openStreams(id)}>
              <Ionicons name="play" size={19} color={colors.onPrimary} />
              <Text style={styles.playButtonText}>Sources</Text>
            </Pressable>
          ) : null}
          <Pressable
            style={[styles.iconAction, type !== 'movie' && styles.iconActionWide]}
            onPress={toggleLibrary}
          >
            <Ionicons name={inLibrary ? 'bookmark' : 'bookmark-outline'} size={20} color={colors.accent} />
            {type !== 'movie' ? (
              <Text style={styles.iconActionText}>{inLibrary ? 'In Library' : 'My List'}</Text>
            ) : null}
          </Pressable>
        </View>

        {meta.description ? <Text style={styles.description}>{meta.description}</Text> : null}

        {seasons.length > 0 ? (
          <Pressable style={styles.seasonPicker} onPress={() => setSeasonSheetOpen(true)}>
            <Text style={styles.seasonPickerText}>
              {activeSeason === 0 ? 'Specials' : `Season ${activeSeason}`}
            </Text>
            <Ionicons name="chevron-down" size={16} color={colors.text} />
          </Pressable>
        ) : null}
      </View>
    </View>
  )

  return (
    <>
      <Stack.Screen options={{ headerShown: false }} />
      <View style={styles.container}>
        <FlatList
          data={type === 'series' ? episodes : []}
          keyExtractor={(video) => video.id}
          ListHeaderComponent={header}
          showsVerticalScrollIndicator={false}
          contentContainerStyle={{ paddingBottom: insets.bottom + spacing.lg }}
          renderItem={({ item }) => (
            <EpisodeRow
              video={item}
              progress={progressFor(item.id)}
              downloaded={downloadsById.get(item.id)?.status === 'done'}
              onPress={() => openStreams(item.id, episodeTag(item))}
            />
          )}
        />
        <Pressable
          style={[styles.backButton, { top: insets.top + spacing.xs }]}
          onPress={() => router.back()}
          hitSlop={8}
        >
          <Ionicons name="chevron-back" size={24} color={colors.text} />
        </Pressable>
      </View>
      <SelectSheet
        visible={seasonSheetOpen}
        title="Season"
        options={seasons.map((s) => ({
          key: String(s),
          label: s === 0 ? 'Specials' : `Season ${s}`,
          selected: s === activeSeason,
        }))}
        onSelect={(key) => setSeason(Number(key))}
        onClose={() => setSeasonSheetOpen(false)}
      />
    </>
  )
}

function episodeTag(video: MetaVideo): string {
  if (video.season === undefined || video.episode === undefined) return video.title ?? video.name ?? video.id
  return `S${String(video.season).padStart(2, '0')}E${String(video.episode).padStart(2, '0')}`
}

function EpisodeRow({
  video,
  progress,
  downloaded,
  onPress,
}: {
  video: MetaVideo
  progress: { positionSec: number; durationSec: number; watched: boolean } | null
  downloaded: boolean
  onPress: () => void
}) {
  const fraction = progress ? progress.positionSec / progress.durationSec : 0
  return (
    <Pressable style={styles.episode} onPress={onPress}>
      {video.thumbnail ? (
        <Image source={{ uri: video.thumbnail }} style={styles.thumb} resizeMode="cover" />
      ) : (
        <View style={[styles.thumb, styles.thumbFallback]}>
          <Ionicons name="play" size={18} color={colors.textDim} />
        </View>
      )}
      <View style={styles.episodeBody}>
        <Text style={styles.episodeTitle} numberOfLines={1}>
          {video.episode !== undefined ? `${video.episode}. ` : ''}
          {video.title ?? video.name ?? video.id}
        </Text>
        {video.overview ? (
          <Text style={styles.dim} numberOfLines={2}>
            {video.overview}
          </Text>
        ) : null}
        {progress && !progress.watched && fraction > 0.02 ? (
          <View style={styles.progressTrack}>
            <View style={[styles.progressFill, { width: `${Math.min(100, fraction * 100)}%` }]} />
          </View>
        ) : null}
      </View>
      {downloaded ? <Ionicons name="arrow-down-circle" size={18} color={colors.accent} /> : null}
      {progress?.watched ? <Ionicons name="checkmark-circle" size={18} color={colors.success} /> : null}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  center: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
  },
  hero: {
    height: 460,
    justifyContent: 'flex-end',
    backgroundColor: colors.surface,
  },
  heroBody: { paddingHorizontal: spacing.md + 2, paddingBottom: spacing.sm },
  name: { color: colors.text, fontSize: 32, fontWeight: '800', letterSpacing: 0.3, marginBottom: 6 },
  backButton: {
    position: 'absolute',
    left: spacing.md,
    width: 34,
    height: 34,
    borderRadius: 999,
    backgroundColor: 'rgba(0,0,0,0.4)',
    alignItems: 'center',
    justifyContent: 'center',
  },
  body: { paddingHorizontal: spacing.md + 2, paddingTop: spacing.sm },
  dim: { color: colors.textDim, fontSize: 13, marginTop: spacing.xs, textAlign: 'center' },
  description: { color: '#c3c9d6', fontSize: 14, lineHeight: 21, marginTop: spacing.md },
  actions: { flexDirection: 'row', gap: spacing.sm + 2 },
  playButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 7,
    backgroundColor: colors.primary,
    borderRadius: radius.md,
    paddingVertical: 13,
  },
  playButtonText: { color: colors.onPrimary, fontWeight: '700', fontSize: 16 },
  iconAction: {
    width: 52,
    borderRadius: radius.md,
    backgroundColor: colors.glass,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    gap: 8,
  },
  iconActionWide: { flex: 1, paddingVertical: 13 },
  iconActionText: { color: colors.accent, fontWeight: '600', fontSize: 15 },
  seasonPicker: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    alignSelf: 'flex-start',
    backgroundColor: colors.surfaceHigh,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: 9,
    marginTop: spacing.lg,
  },
  seasonPickerText: { color: colors.text, fontWeight: '600' },
  episode: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    gap: spacing.sm + 2,
  },
  thumb: { width: 120, height: 68, borderRadius: radius.sm - 2, backgroundColor: colors.surface },
  thumbFallback: { alignItems: 'center', justifyContent: 'center' },
  episodeBody: { flex: 1 },
  episodeTitle: { color: colors.text, fontSize: 14, fontWeight: '600' },
  progressTrack: {
    height: 3,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.18)',
    marginTop: 6,
    overflow: 'hidden',
  },
  progressFill: { height: '100%', backgroundColor: colors.accent },
})
