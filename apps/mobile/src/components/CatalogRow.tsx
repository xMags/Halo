import { ActivityIndicator, FlatList, StyleSheet, Text, View } from 'react-native'
import { useCatalog } from '../queries'
import { useResponsive } from '../responsive'
import { colors, spacing, POSTER_WIDTH, POSTER_HEIGHT } from '../theme'
import { PosterCard } from './PosterCard'

interface Props {
  /** Opaque `AddonEntry.id` — catalogs are addressed by it, never by transport URL. */
  addonId: string
  addonName: string
  type: string
  catalogId: string
  catalogName?: string
}

export function CatalogRow({ addonId, addonName, type, catalogId, catalogName }: Props) {
  const { data: metas, isLoading, isError } = useCatalog(addonId, type, catalogId)
  const { pick } = useResponsive()
  const posterWidth = pick(POSTER_WIDTH, 140, 156)
  // Failed or empty catalogs disappear instead of leaving dead headers around.
  if (isError || metas?.length === 0) return null

  const label = `${catalogName ?? addonName} · ${typeLabel(type)}`
  return (
    <View style={styles.row}>
      <Text style={styles.heading}>{label}</Text>
      {isLoading ? (
        <ActivityIndicator style={styles.loader} color={colors.accent} />
      ) : (
        <FlatList
          horizontal
          data={metas}
          keyExtractor={(meta) => meta.id}
          renderItem={({ item }) => <PosterCard meta={item} width={posterWidth} />}
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.list}
        />
      )}
    </View>
  )
}

function typeLabel(type: string): string {
  if (type === 'movie') return 'Movies'
  if (type === 'series') return 'Series'
  return type
}

const styles = StyleSheet.create({
  row: {
    marginBottom: spacing.lg,
  },
  heading: {
    color: colors.text,
    fontSize: 18,
    fontWeight: '700',
    letterSpacing: -0.3,
    marginBottom: spacing.sm + 2,
    paddingHorizontal: spacing.md,
  },
  list: {
    paddingHorizontal: spacing.md,
    gap: 11,
  },
  loader: {
    height: POSTER_HEIGHT,
  },
})
