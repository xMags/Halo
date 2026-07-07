import { FlatList, StyleSheet, Text, View } from 'react-native'
import type { LibraryItem } from '@halo/core'
import { useLibrary } from '@/queries'
import { colors, spacing } from '@/theme'
import { PosterCard } from '@/components/PosterCard'

export default function LibraryScreen() {
  const { data: items } = useLibrary()
  const active = (items ?? []).filter((item) => !item.removedAt)

  if (active.length === 0) {
    return (
      <View style={styles.center}>
        <Text style={styles.message}>
          Nothing saved yet. Open a title and tap “Add to library”.
        </Text>
      </View>
    )
  }

  return (
    <FlatList
      style={styles.container}
      contentContainerStyle={styles.content}
      data={active}
      numColumns={3}
      keyExtractor={(item) => item.id}
      columnWrapperStyle={styles.rowWrap}
      renderItem={({ item }) => <PosterCard meta={toMetaPreview(item)} />}
    />
  )
}

function toMetaPreview(item: LibraryItem) {
  const metaId = item.id.slice(item.type.length + 1)
  return { id: metaId, type: item.type, name: item.name, poster: item.poster }
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.background,
  },
  content: {
    padding: spacing.md,
  },
  rowWrap: {
    gap: spacing.sm,
    marginBottom: spacing.md,
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
