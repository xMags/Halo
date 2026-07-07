import { useState } from 'react'
import { FlatList, StyleSheet, Text, View } from 'react-native'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import type { LibraryItem, MetaPreview } from '@halo/core'
import { useLibrary } from '@/queries'
import { colors, spacing, TAB_BAR_SPACE, type } from '@/theme'
import { PosterCard } from '@/components/PosterCard'
import { Segmented, CenterMessage } from '@/components/ui'

type Filter = 'All' | 'Movies' | 'Series'
const FILTER_TYPE: Record<Filter, string | null> = { All: null, Movies: 'movie', Series: 'series' }

export default function LibraryScreen() {
  const insets = useSafeAreaInsets()
  const { data: items } = useLibrary()
  const [filter, setFilter] = useState<Filter>('All')

  const active = (items ?? []).filter((item) => !item.removedAt)
  const typeFilter = FILTER_TYPE[filter]
  const shown = typeFilter ? active.filter((i) => i.type === typeFilter) : active

  const header = (
    <View style={{ paddingTop: insets.top + spacing.xs }}>
      <Text style={[type.largeTitle, styles.title]}>Library</Text>
      {active.length > 0 ? (
        <View style={styles.filter}>
          <Segmented options={['All', 'Movies', 'Series']} value={filter} onChange={(v) => setFilter(v as Filter)} />
        </View>
      ) : null}
    </View>
  )

  if (active.length === 0) {
    return (
      <View style={styles.container}>
        <View style={{ paddingHorizontal: spacing.md }}>{header}</View>
        <CenterMessage>Nothing saved yet. Open a title and tap “Add to library”.</CenterMessage>
      </View>
    )
  }

  return (
    <FlatList
      style={styles.container}
      contentContainerStyle={{ paddingHorizontal: spacing.md, paddingBottom: TAB_BAR_SPACE }}
      data={shown}
      numColumns={3}
      keyExtractor={(item) => item.id}
      columnWrapperStyle={styles.rowWrap}
      ListHeaderComponent={header}
      showsVerticalScrollIndicator={false}
      renderItem={({ item }) => <PosterCard meta={toMetaPreview(item)} fill />}
    />
  )
}

function toMetaPreview(item: LibraryItem): MetaPreview {
  const metaId = item.id.slice(item.type.length + 1)
  return { id: metaId, type: item.type, name: item.name, poster: item.poster }
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  title: { paddingHorizontal: 0, marginBottom: spacing.xs },
  filter: { marginBottom: spacing.md },
  rowWrap: { gap: spacing.sm + 2, marginBottom: spacing.sm + 2 },
})
