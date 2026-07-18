import { useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import type { MetaPreview } from '@halo/core'
import { useCatalog, useEffectiveAddons, useLibrary, useMeta, useWatchStates } from '@/queries'
import { colors, radius, spacing, TAB_BAR_SPACE, type } from '@/theme'
import { useResponsive } from '@/responsive'
import { CatalogRow } from '@/components/CatalogRow'
import { PosterCard } from '@/components/PosterCard'
import { HeroScrim, Segmented, SearchField, MetaLine, CenterMessage } from '@/components/ui'

type Filter = 'All' | 'Movies' | 'Series'
const FILTER_TYPE: Record<Filter, string | null> = { All: null, Movies: 'movie', Series: 'series' }

export default function HomeScreen() {
  const insets = useSafeAreaInsets()
  const router = useRouter()
  const { pick } = useResponsive()
  const [filter, setFilter] = useState<Filter>('All')
  const { data: addons, isLoading, isError } = useEffectiveAddons()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()

  // Parameterless catalogs only — search/genre-gated ones can't render as
  // rows. Addons flagged hideCatalogs (e.g. debrid cloud listings) keep their
  // streams but stay out of discovery entirely.
  const allRows = (addons ?? [])
    .filter((addon) => !addon.hideCatalogs)
    .flatMap((addon) =>
      addon.manifest.catalogs
        .filter((catalog) => !(catalog.extra ?? []).some((e) => e.isRequired))
        .filter((catalog) => !(catalog.extraRequired ?? []).length)
        .map((catalog) => ({
          key: `${addon.id}/${catalog.type}/${catalog.id}`,
          addonId: addon.id,
          addonName: addon.manifest.name,
          type: catalog.type,
          catalogId: catalog.id,
          catalogName: catalog.name,
        })),
    )
  const typeFilter = FILTER_TYPE[filter]
  const rows = typeFilter ? allRows.filter((r) => r.type === typeFilter) : allRows

  // Featured = first title of the first visible catalog; fetch its meta for
  // wide background art + rating.
  const lead = rows[0]
  const { data: leadCatalog } = useCatalog(lead?.addonId ?? '', lead?.type ?? '', lead?.catalogId ?? '', {
    enabled: !!lead,
  })
  const featuredPreview = leadCatalog?.[0]
  const { data: featuredMeta } = useMeta(featuredPreview?.type ?? '', featuredPreview?.id ?? '', {
    enabled: !!featuredPreview,
  })
  const featured = featuredMeta ?? featuredPreview

  const continueItems = buildContinueWatching(watchStates, library)
  const recentItems = buildRecentlyWatched(watchStates, library, continueItems, typeFilter)
  const libraryItems = (library ?? [])
    .filter((item) => !item.removedAt)
    .filter((item) => !typeFilter || item.type === typeFilter)
    .sort((a, b) => b.addedAt - a.addedAt)
    .map(
      (item): MetaPreview => ({
        id: item.id.slice(item.type.length + 1),
        type: item.type,
        name: item.name,
        poster: item.poster,
      }),
    )

  if (isLoading) {
    return (
      <View style={styles.center}>
        <ActivityIndicator color={colors.accent} size="large" />
      </View>
    )
  }
  if (isError) return <CenterMessage>Could not reach your Halo server.</CenterMessage>
  if (allRows.length === 0) {
    return <CenterMessage>No catalogs yet — add an addon in Settings.</CenterMessage>
  }

  const openFeatured = () => {
    if (!featured) return
    router.push({ pathname: '/detail/[type]/[id]', params: { type: featured.type, id: featured.id } })
  }
  const playFeatured = () => {
    if (!featured) return
    if (featured.type === 'movie') {
      router.push({
        pathname: '/streams/[type]/[videoId]',
        params: {
          type: featured.type,
          videoId: featured.id,
          itemId: `${featured.type}:${featured.id}`,
          title: featured.name,
          ...(featured.poster ? { poster: featured.poster } : {}),
        },
      })
    } else {
      openFeatured()
    }
  }

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={{ paddingTop: insets.top + spacing.xs, paddingBottom: TAB_BAR_SPACE }}
      showsVerticalScrollIndicator={false}
    >
      <View style={styles.header}>
        <Text style={type.largeTitle}>Watch</Text>
        <View style={{ marginTop: spacing.sm + 4 }}>
          <SearchField onPress={() => router.push('/search')} />
        </View>
        <View style={{ marginTop: spacing.sm + 4 }}>
          <Segmented options={['All', 'Movies', 'Series']} value={filter} onChange={(v) => setFilter(v as Filter)} />
        </View>
      </View>

      {featured ? (
        <Pressable style={[styles.featured, { height: pick(210, 300, 340) }]} onPress={openFeatured}>
          <Image
            source={{ uri: featured.background ?? featured.poster }}
            style={styles.featuredImg}
            resizeMode="cover"
          />
          <HeroScrim />
          <View style={styles.featuredBody}>
            <Text style={styles.featuredTitle} numberOfLines={1}>
              {featured.name}
            </Text>
            <MetaLine
              parts={[featured.releaseInfo, (featured.genres ?? [])[0]]}
              rating={featured.imdbRating}
            />
            <Pressable style={styles.playButton} onPress={playFeatured}>
              <Ionicons name="play" size={16} color={colors.onPrimary} />
              <Text style={styles.playText}>Play</Text>
            </Pressable>
          </View>
        </Pressable>
      ) : null}

      {continueItems.length > 0 ? (
        <View style={styles.row}>
          <Text style={styles.heading}>Continue Watching</Text>
          <FlatList
            horizontal
            data={continueItems}
            keyExtractor={(item) => item.meta.id}
            renderItem={({ item }) => <PosterCard meta={item.meta} width={pick(132, 150, 168)} progress={item.progress} />}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.rowList}
          />
        </View>
      ) : null}

      {recentItems.length > 0 ? (
        <View style={styles.row}>
          <Text style={styles.heading}>Recently Watched</Text>
          <FlatList
            horizontal
            data={recentItems}
            keyExtractor={(item) => item.id}
            renderItem={({ item }) => <PosterCard meta={item} width={pick(132, 150, 168)} />}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.rowList}
          />
        </View>
      ) : null}

      {libraryItems.length > 0 ? (
        <View style={styles.row}>
          <Text style={styles.heading}>My Library</Text>
          <FlatList
            horizontal
            data={libraryItems}
            keyExtractor={(item) => item.id}
            renderItem={({ item }) => <PosterCard meta={item} width={pick(132, 150, 168)} />}
            showsHorizontalScrollIndicator={false}
            contentContainerStyle={styles.rowList}
          />
        </View>
      ) : null}

      {rows.map((r) => (
        <CatalogRow
          key={r.key}
          addonId={r.addonId}
          addonName={r.addonName}
          type={r.type}
          catalogId={r.catalogId}
          catalogName={r.catalogName}
        />
      ))}
    </ScrollView>
  )
}

type HomeWatchState = {
  itemId: string
  positionSec: number
  durationSec: number
  watched: boolean
  name?: string
  poster?: string
  updatedAt: number
}
type HomeLibraryItem = { id: string; type: string; name: string; poster?: string; removedAt?: number }

/**
 * Display fields for a watch state: the state's own denormalized name/poster
 * first, the library entry as fallback for states written by older clients.
 * itemId is `${type}:${metaId}`; the type segment never contains a colon, the
 * meta id may (e.g. kitsu ids).
 */
function watchStateMeta(s: HomeWatchState, libById: Map<string, HomeLibraryItem>): MetaPreview | null {
  const lib = libById.get(s.itemId)
  const name = s.name ?? lib?.name
  if (!name) return null
  const type = s.itemId.slice(0, s.itemId.indexOf(':'))
  return { id: s.itemId.slice(type.length + 1), type, name, poster: s.poster ?? lib?.poster }
}

function activeLibraryById(library: HomeLibraryItem[] | undefined): Map<string, HomeLibraryItem> {
  return new Map((library ?? []).filter((i) => !i.removedAt).map((i) => [i.id, i]))
}

/** In-progress items, most recent first — no longer requires library membership. */
function buildContinueWatching(
  watchStates: HomeWatchState[] | undefined,
  library: HomeLibraryItem[] | undefined,
): { meta: MetaPreview; progress: number; itemId: string }[] {
  const libById = activeLibraryById(library)
  // One card per show (most recent episode wins) — two in-progress episodes
  // of the same series must not produce duplicate keys.
  const seenItems = new Set<string>()
  return (watchStates ?? [])
    .filter((s) => s.durationSec > 0 && !s.watched)
    .map((s) => ({ s, fraction: s.positionSec / s.durationSec }))
    .filter(({ fraction }) => fraction > 0.02 && fraction < 0.95)
    .sort((a, b) => b.s.updatedAt - a.s.updatedAt)
    .flatMap(({ s, fraction }) => {
      if (seenItems.has(s.itemId)) return []
      seenItems.add(s.itemId)
      const meta = watchStateMeta(s, libById)
      return meta ? [{ meta, progress: fraction, itemId: s.itemId }] : []
    })
}

const RECENTLY_WATCHED_LIMIT = 15

/**
 * Playback history: last distinct items by recency, any progress — finished
 * shows included as a rewatch entry point. Items already in Continue Watching
 * are excluded so the two rows never duplicate.
 */
function buildRecentlyWatched(
  watchStates: HomeWatchState[] | undefined,
  library: HomeLibraryItem[] | undefined,
  continueItems: { itemId: string }[],
  typeFilter: string | null,
): MetaPreview[] {
  const libById = activeLibraryById(library)
  const exclude = new Set(continueItems.map((c) => c.itemId))
  const seenItems = new Set<string>()
  return (watchStates ?? [])
    .filter((s) => !exclude.has(s.itemId))
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .flatMap((s) => {
      if (seenItems.has(s.itemId)) return []
      seenItems.add(s.itemId)
      const meta = watchStateMeta(s, libById)
      if (!meta || (typeFilter && meta.type !== typeFilter)) return []
      return [meta]
    })
    .slice(0, RECENTLY_WATCHED_LIMIT)
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  center: { flex: 1, backgroundColor: colors.background, alignItems: 'center', justifyContent: 'center' },
  header: { paddingHorizontal: spacing.md, paddingBottom: spacing.md },
  featured: {
    marginHorizontal: spacing.md,
    marginBottom: spacing.lg,
    borderRadius: radius.xl,
    overflow: 'hidden',
    height: 210,
    justifyContent: 'flex-end',
    backgroundColor: colors.surface,
  },
  featuredImg: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  featuredBody: { padding: spacing.md },
  featuredTitle: { color: colors.text, fontSize: 24, fontWeight: '800', letterSpacing: 0.2 },
  playButton: {
    alignSelf: 'flex-start',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: colors.primary,
    borderRadius: radius.pill,
    paddingHorizontal: 18,
    paddingVertical: 8,
    marginTop: spacing.sm + 2,
  },
  playText: { color: colors.onPrimary, fontSize: 14, fontWeight: '700' },
  row: { marginBottom: spacing.lg },
  heading: {
    color: colors.text,
    fontSize: 18,
    fontWeight: '700',
    letterSpacing: -0.3,
    marginBottom: spacing.sm + 2,
    paddingHorizontal: spacing.md,
  },
  rowList: { paddingHorizontal: spacing.md, gap: 11 },
})
