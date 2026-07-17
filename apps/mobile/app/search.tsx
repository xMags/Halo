import { useEffect, useState } from 'react'
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native'
import { useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { useSearch } from '@/queries'
import { colors, spacing } from '@/theme'
import { gridItemWidth, useResponsive } from '@/responsive'
import { PosterCard } from '@/components/PosterCard'
import { CenterMessage, SearchField } from '@/components/ui'

const DEBOUNCE_MS = 350
const GRID_GAP = spacing.sm

export default function SearchScreen() {
  const router = useRouter()
  const insets = useSafeAreaInsets()
  const { width, posterColumns } = useResponsive()
  const [term, setTerm] = useState('')
  const [debounced, setDebounced] = useState('')

  const itemWidth = gridItemWidth(width, posterColumns, { horizontalPadding: spacing.md, gap: GRID_GAP })

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [term])

  const { data: results, isFetching } = useSearch(debounced)
  const active = debounced.trim().length >= 2

  return (
    <View style={[styles.container, { paddingTop: insets.top + spacing.xs }]}>
      <View style={styles.header}>
        <View style={styles.field}>
          <SearchField
            editable
            autoFocus
            value={term}
            onChangeText={setTerm}
            onClear={() => setTerm('')}
          />
        </View>
        <Pressable onPress={() => router.back()} hitSlop={8}>
          <Text style={styles.cancel}>Cancel</Text>
        </Pressable>
      </View>

      {!active ? (
        <CenterMessage>Search every installed addon — titles, series, anything.</CenterMessage>
      ) : isFetching ? (
        <View style={styles.center}>
          <ActivityIndicator color={colors.accent} size="large" />
        </View>
      ) : (results ?? []).length === 0 ? (
        <CenterMessage>No results for “{debounced.trim()}”.</CenterMessage>
      ) : (
        <FlatList
          // numColumns can't change in place — key remount is required by FlatList.
          key={posterColumns}
          data={results}
          keyExtractor={(meta) => `${meta.type}:${meta.id}`}
          numColumns={posterColumns}
          columnWrapperStyle={styles.rowWrap}
          contentContainerStyle={styles.grid}
          keyboardShouldPersistTaps="handled"
          keyboardDismissMode="on-drag"
          renderItem={({ item }) => <PosterCard meta={item} width={itemWidth} showLabel />}
        />
      )}
    </View>
  )
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: colors.background },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    paddingHorizontal: spacing.md,
    paddingBottom: spacing.md,
  },
  field: { flex: 1 },
  cancel: { color: colors.accent, fontSize: 15, fontWeight: '600' },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  grid: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
  rowWrap: { gap: spacing.sm, marginBottom: spacing.md },
})
