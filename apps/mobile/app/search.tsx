import { useEffect, useState } from 'react'
import { ActivityIndicator, FlatList, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { useRouter } from 'expo-router'
import { useSafeAreaInsets } from 'react-native-safe-area-context'
import { useSearch } from '@/queries'
import { addSearchTerm, clearSearchHistory, getSearchHistory, removeSearchTerm } from '@/searchHistory'
import { colors, radius, spacing } from '@/theme'
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
  const [history, setHistory] = useState<string[]>([])

  const itemWidth = gridItemWidth(width, posterColumns, { horizontalPadding: spacing.md, gap: GRID_GAP })

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term), DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [term])

  useEffect(() => {
    void getSearchHistory().then(setHistory)
  }, [])

  const { data: results, isFetching } = useSearch(debounced)
  const active = debounced.trim().length >= 2

  // History records only deliberate acts — submitting the query or opening a
  // result — never the debounced keystroke stream.
  const recordTerm = (value: string) => {
    void addSearchTerm(value).then(setHistory)
  }

  /** Tapped history entry: search immediately, no debounce wait. */
  const searchAgain = (value: string) => {
    setTerm(value)
    setDebounced(value)
    recordTerm(value)
  }

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
            onSubmitEditing={() => {
              if (term.trim().length >= 2) recordTerm(term)
            }}
          />
        </View>
        <Pressable onPress={() => router.back()} hitSlop={8}>
          <Text style={styles.cancel}>Cancel</Text>
        </Pressable>
      </View>

      {!active ? (
        history.length === 0 ? (
          <CenterMessage>Search every installed addon — titles, series, anything.</CenterMessage>
        ) : (
          <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={styles.history}>
            <View style={styles.historyHead}>
              <Text style={styles.historyTitle}>Recent</Text>
              <Pressable onPress={() => void clearSearchHistory().then(setHistory)} hitSlop={8}>
                <Text style={styles.historyClear}>Clear</Text>
              </Pressable>
            </View>
            {history.map((entry) => (
              <View key={entry} style={styles.historyRow}>
                <Pressable style={styles.historyTerm} onPress={() => searchAgain(entry)}>
                  <Ionicons name="time-outline" size={17} color={colors.textDim} />
                  <Text style={styles.historyText} numberOfLines={1}>
                    {entry}
                  </Text>
                </Pressable>
                <Pressable onPress={() => void removeSearchTerm(entry).then(setHistory)} hitSlop={10}>
                  <Ionicons name="close" size={16} color={colors.textDim} />
                </Pressable>
              </View>
            ))}
          </ScrollView>
        )
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
          renderItem={({ item }) => (
            <PosterCard meta={item} width={itemWidth} showLabel onBeforePress={() => recordTerm(debounced)} />
          )}
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
  history: { paddingHorizontal: spacing.md, paddingBottom: spacing.xl },
  historyHead: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
    paddingVertical: spacing.xs,
  },
  historyTitle: { color: colors.text, fontSize: 16, fontWeight: '700', letterSpacing: -0.2 },
  historyClear: { color: colors.accent, fontSize: 13.5, fontWeight: '600' },
  historyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: colors.hairline,
  },
  historyTerm: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 2,
    paddingVertical: spacing.sm + 4,
  },
  historyText: { color: colors.text, fontSize: 15, flexShrink: 1 },
})
