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
import type { MetaVideo } from '@halo/core'
import { libraryItemFromMeta, useLibrary, useMeta, useUpsertLibrary, useWatchStates } from '@/queries'
import { colors, spacing } from '@/theme'
import { SelectSheet } from '@/components/SelectSheet'

export default function DetailScreen() {
  const { type, id } = useLocalSearchParams<{ type: string; id: string }>()
  const router = useRouter()
  const { data: meta, isLoading, isError } = useMeta(type, id)
  const { data: library } = useLibrary()
  const { data: watchStates } = useWatchStates()
  const upsertLibrary = useUpsertLibrary()
  const [season, setSeason] = useState<number | null>(null)
  const [seasonSheetOpen, setSeasonSheetOpen] = useState(false)

  const itemId = `${type}:${id}`
  const libraryEntry = (library ?? []).find((item) => item.id === itemId && !item.removedAt)

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
        ...(meta.poster ? { poster: meta.poster } : {}),
      },
    })
  }

  const progressFor = (videoId: string) => {
    const state = (watchStates ?? []).find((s) => s.videoId === videoId)
    if (!state || state.durationSec === 0) return null
    return state
  }

  const header = (
    <View>
      <Image source={{ uri: meta.background ?? meta.poster }} style={styles.hero} resizeMode="cover" />
      <View style={styles.body}>
        <Text style={styles.name}>{meta.name}</Text>
        <Text style={styles.dim}>
          {[meta.releaseInfo, meta.runtime, meta.imdbRating && `★ ${meta.imdbRating}`]
            .filter(Boolean)
            .join('  ·  ')}
        </Text>
        {meta.description ? <Text style={styles.description}>{meta.description}</Text> : null}

        <View style={styles.actions}>
          {type === 'movie' ? (
            <Pressable style={styles.playButton} onPress={() => openStreams(id)}>
              <Ionicons name="play" size={18} color={colors.background} />
              <Text style={styles.playButtonText}>Sources</Text>
            </Pressable>
          ) : null}
          <Pressable style={styles.libraryButton} onPress={toggleLibrary}>
            <Ionicons
              name={libraryEntry ? 'bookmark' : 'bookmark-outline'}
              size={18}
              color={colors.accent}
            />
            <Text style={styles.libraryButtonText}>
              {libraryEntry ? 'In library' : 'Add to library'}
            </Text>
          </Pressable>
        </View>

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
      <Stack.Screen options={{ title: meta.name }} />
      <FlatList
        style={styles.container}
        data={type === 'series' ? episodes : []}
        keyExtractor={(video) => video.id}
        ListHeaderComponent={header}
        renderItem={({ item }) => (
          <EpisodeRow
            video={item}
            progress={progressFor(item.id)}
            onPress={() => openStreams(item.id, episodeTag(item))}
          />
        )}
      />
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
  onPress,
}: {
  video: MetaVideo
  progress: { positionSec: number; durationSec: number; watched: boolean } | null
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
      {progress?.watched ? <Ionicons name="checkmark-circle" size={18} color={colors.success} /> : null}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  center: {
    flex: 1,
    backgroundColor: colors.background,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.lg,
  },
  hero: {
    width: '100%',
    height: 210,
    backgroundColor: colors.surface,
  },
  body: {
    padding: spacing.md,
  },
  name: {
    color: colors.text,
    fontSize: 24,
    fontWeight: '700',
  },
  dim: {
    color: colors.textDim,
    fontSize: 13,
    marginTop: spacing.xs,
  },
  description: {
    color: colors.text,
    fontSize: 14,
    lineHeight: 20,
    marginTop: spacing.sm,
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  playButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.accent,
    borderRadius: 10,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
  },
  playButtonText: {
    color: colors.background,
    fontWeight: '700',
  },
  libraryButton: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderColor: colors.accent,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: spacing.md,
    paddingVertical: 10,
  },
  libraryButtonText: {
    color: colors.accent,
    fontWeight: '600',
  },
  seasonPicker: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    alignSelf: 'flex-start',
    backgroundColor: colors.surfaceHigh,
    borderRadius: 8,
    paddingHorizontal: spacing.md,
    paddingVertical: 8,
    marginTop: spacing.md,
  },
  seasonPickerText: {
    color: colors.text,
    fontWeight: '600',
  },
  episode: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    gap: spacing.sm,
  },
  thumb: {
    width: 120,
    height: 68,
    borderRadius: 6,
    backgroundColor: colors.surface,
  },
  thumbFallback: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  episodeBody: {
    flex: 1,
  },
  episodeTitle: {
    color: colors.text,
    fontSize: 14,
    fontWeight: '600',
  },
  progressTrack: {
    height: 3,
    borderRadius: 2,
    backgroundColor: colors.surfaceHigh,
    marginTop: 6,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    backgroundColor: colors.accent,
  },
})
