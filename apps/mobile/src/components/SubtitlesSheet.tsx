import { type ReactNode, useEffect, useRef, useState } from 'react'
import { Animated, FlatList, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { BlurView } from 'expo-blur'
import { colors, radius, spacing } from '../theme'

export interface SubtitleVariant {
  key: string
  /** Primary line: embedded track name or the source addon's name. */
  label: string
  /** Secondary line: "Embedded", "Downloaded", or the addon's subtitle id. */
  detail?: string
  /** File already on device — selectable offline, no re-download. */
  local?: boolean
  selected: boolean
}

export interface SubtitleLanguageGroup {
  key: string
  label: string
  variants: SubtitleVariant[]
}

interface Props {
  visible: boolean
  title: string
  description?: string
  /** Language groups in display order; the middle column shows one group's variants. */
  groups: SubtitleLanguageGroup[]
  /** True when no track (embedded or external) is active. */
  offSelected: boolean
  onSelectOff: () => void
  onSelectVariant: (key: string) => void
  /** Timing + appearance controls — the right pane. */
  appearance: ReactNode
  onClose: () => void
}

const groupSelected = (group: SubtitleLanguageGroup): boolean => group.variants.some((v) => v.selected)

/**
 * Subtitles panel: a wide three-column overlay for landscape playback,
 * Stremio-style. Left picks a language, middle picks a variant within it
 * (embedded / downloaded / per-addon results), right holds timing +
 * appearance controls. Same in-tree absolute overlay as SelectSheet —
 * deliberately NOT an RN <Modal> (see SelectSheet for the
 * stale-portrait-metrics reason). Browsing a language applies nothing;
 * only tapping a variant (or Off) changes playback, so exploring and
 * tuning are one continuous flow.
 */
export function SubtitlesSheet({
  visible,
  title,
  description,
  groups,
  offSelected,
  onSelectOff,
  onSelectVariant,
  appearance,
  onClose,
}: Props) {
  const slide = useRef(new Animated.Value(0)).current
  const { width: windowWidth } = useWindowDimensions()
  /** Language being browsed — decoupled from the active track on purpose. */
  const [langKey, setLangKey] = useState<string | null>(null)

  useEffect(() => {
    if (visible) {
      slide.setValue(0)
      Animated.timing(slide, { toValue: 1, duration: 220, useNativeDriver: true }).start()
    }
  }, [visible, slide])

  // On open, land on the language that holds the active track. Not re-run on
  // group changes — yanking the browsed language while subs stream in would
  // fight the user.
  useEffect(() => {
    if (visible) setLangKey(groups.find(groupSelected)?.key ?? groups[0]?.key ?? null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible])

  // Groups grow as tracks/results arrive; only repair a missing/invalid pick.
  useEffect(() => {
    if (langKey !== null && groups.some((g) => g.key === langKey)) return
    setLangKey(groups.find(groupSelected)?.key ?? groups[0]?.key ?? null)
  }, [groups, langKey])

  if (!visible) return null

  const translation = slide.interpolate({ inputRange: [0, 1], outputRange: [90, 0] })
  const panelWidth = Math.min(windowWidth * 0.92, 980)
  const browsed = groups.find((g) => g.key === langKey)

  return (
    <View style={styles.overlay} pointerEvents="box-none">
      <Pressable style={styles.backdrop} onPress={onClose} />
      <Animated.View
        style={[styles.wrap, { width: panelWidth, transform: [{ translateX: translation }], opacity: slide }]}
      >
        <BlurView intensity={44} tint="dark" style={styles.panel}>
          <View style={styles.header}>
            <View style={styles.headerText}>
              <Text style={styles.title}>{title}</Text>
              {description ? <Text style={styles.description}>{description}</Text> : null}
            </View>
            <Pressable style={styles.closeButton} onPress={onClose} hitSlop={8}>
              <Ionicons name="close" size={20} color={colors.text} />
            </Pressable>
          </View>

          <View style={styles.body}>
            <View style={styles.languagesPane}>
              <Text style={styles.paneHeading}>LANGUAGE</Text>
              <FlatList
                data={groups}
                keyExtractor={(g) => g.key}
                style={styles.list}
                showsVerticalScrollIndicator={false}
                ListHeaderComponent={
                  <LanguageRow
                    label="Off"
                    browsing={false}
                    active={offSelected}
                    onPress={onSelectOff}
                  />
                }
                renderItem={({ item }) => (
                  <LanguageRow
                    label={item.label}
                    browsing={item.key === langKey}
                    active={groupSelected(item)}
                    onPress={() => setLangKey(item.key)}
                  />
                )}
              />
            </View>

            <View style={styles.divider} />

            <View style={styles.variantsPane}>
              <Text style={styles.paneHeading}>VARIANT</Text>
              {browsed && browsed.variants.length > 0 ? (
                <FlatList
                  data={browsed.variants}
                  keyExtractor={(v) => v.key}
                  style={styles.list}
                  showsVerticalScrollIndicator={false}
                  renderItem={({ item }) => (
                    <Pressable
                      style={({ pressed }) => [
                        styles.option,
                        item.selected && styles.optionSelected,
                        pressed && styles.optionPressed,
                      ]}
                      onPress={() => onSelectVariant(item.key)}
                    >
                      <View style={styles.optionText}>
                        <Text style={[styles.label, item.selected && styles.labelSelected]} numberOfLines={1}>
                          {item.label}
                        </Text>
                        {item.detail ? (
                          <Text style={styles.detail} numberOfLines={1}>
                            {item.detail}
                          </Text>
                        ) : null}
                      </View>
                      {item.local ? (
                        <Ionicons name="arrow-down-circle" size={15} color={colors.success} />
                      ) : null}
                      {item.selected ? <View style={styles.dot} /> : null}
                    </Pressable>
                  )}
                />
              ) : (
                <Text style={styles.emptyText}>
                  {groups.length === 0 ? 'No subtitles found' : 'No subtitles for this language'}
                </Text>
              )}
            </View>

            <View style={styles.divider} />

            <View style={styles.appearancePane}>
              <Text style={[styles.paneHeading, styles.appearanceHeading]}>SETTINGS</Text>
              <ScrollView
                contentContainerStyle={styles.appearanceContent}
                showsVerticalScrollIndicator={false}
              >
                {appearance}
              </ScrollView>
            </View>
          </View>
        </BlurView>
      </Animated.View>
    </View>
  )
}

function LanguageRow({
  label,
  browsing,
  active,
  onPress,
}: {
  label: string
  /** This language's variants are shown in the middle column. */
  browsing: boolean
  /** This language holds the active track (or Off is in effect). */
  active: boolean
  onPress: () => void
}) {
  return (
    <Pressable
      style={({ pressed }) => [styles.option, browsing && styles.optionSelected, pressed && styles.optionPressed]}
      onPress={onPress}
    >
      <Text style={[styles.label, styles.langLabel, (browsing || active) && styles.labelSelected]} numberOfLines={1}>
        {label}
      </Text>
      {active ? <View style={styles.dot} /> : null}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  overlay: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, zIndex: 100, elevation: 100 },
  backdrop: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.55)' },
  wrap: { position: 'absolute', top: 0, right: 0, bottom: 0 },
  panel: {
    flex: 1,
    backgroundColor: colors.sheetTint,
    borderTopLeftRadius: radius.xl + 4,
    borderBottomLeftRadius: radius.xl + 4,
    borderLeftWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.xl,
    paddingTop: spacing.lg,
    paddingBottom: spacing.md,
  },
  headerText: { flex: 1 },
  title: { color: colors.text, fontSize: 20, fontWeight: '700' },
  description: { color: colors.textDim, fontSize: 12, marginTop: 2 },
  closeButton: {
    width: 36,
    height: 36,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  body: { flex: 1, flexDirection: 'row' },
  languagesPane: { flex: 0.75, paddingLeft: spacing.xl, paddingRight: spacing.md, paddingBottom: spacing.lg },
  variantsPane: { flex: 1.05, paddingHorizontal: spacing.md, paddingBottom: spacing.lg },
  paneHeading: {
    color: colors.textDim,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
    marginBottom: spacing.sm,
    paddingHorizontal: spacing.xs,
  },
  appearanceHeading: { paddingHorizontal: spacing.xl },
  list: { flex: 1 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    paddingVertical: 12,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.md,
  },
  optionSelected: { backgroundColor: 'rgba(255,255,255,0.06)' },
  optionPressed: { backgroundColor: 'rgba(255,255,255,0.1)' },
  optionText: { flex: 1 },
  label: { color: 'rgba(255,255,255,0.9)', fontSize: 15.5, fontWeight: '500' },
  langLabel: { flex: 1 },
  labelSelected: { color: '#fff', fontWeight: '700' },
  detail: { color: 'rgba(255,255,255,0.45)', fontSize: 12, marginTop: 1 },
  dot: { width: 8, height: 8, borderRadius: 4, backgroundColor: colors.accent },
  emptyText: { color: colors.textDim, fontSize: 13, paddingHorizontal: spacing.xs, paddingTop: spacing.sm },
  divider: { width: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.12)', marginVertical: spacing.sm },
  appearancePane: { flex: 1.2 },
  appearanceContent: { paddingHorizontal: spacing.xl, paddingBottom: spacing.xl, gap: spacing.lg },
})
