import { type ReactNode, useEffect, useRef } from 'react'
import { Animated, FlatList, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { BlurView } from 'expo-blur'
import { colors, radius, spacing } from '../theme'
import type { SelectOption } from './SelectSheet'

interface Props {
  visible: boolean
  title: string
  description?: string
  /** Subtitle track list — the left "finding subs" pane. */
  tracks: SelectOption[]
  onSelectTrack: (key: string) => void
  /** Timing + appearance controls — the right pane. */
  appearance: ReactNode
  onClose: () => void
}

/**
 * Subtitles panel: a wide two-pane overlay for landscape playback. The left
 * pane is the track list (finding a sub), the right pane holds timing +
 * appearance controls, so neither crowds the other as controls grow. Same
 * in-tree absolute overlay as SelectSheet — deliberately NOT an RN <Modal>
 * (see SelectSheet for the stale-portrait-metrics reason). Selecting a track
 * does NOT close the panel, so tuning appearance and picking a track are one
 * continuous flow.
 */
export function SubtitlesSheet({ visible, title, description, tracks, onSelectTrack, appearance, onClose }: Props) {
  const slide = useRef(new Animated.Value(0)).current
  const { width: windowWidth } = useWindowDimensions()

  useEffect(() => {
    if (visible) {
      slide.setValue(0)
      Animated.timing(slide, { toValue: 1, duration: 220, useNativeDriver: true }).start()
    }
  }, [visible, slide])

  if (!visible) return null

  const translation = slide.interpolate({ inputRange: [0, 1], outputRange: [90, 0] })
  const panelWidth = Math.min(windowWidth * 0.9, 940)

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
            <View style={styles.tracksPane}>
              <Text style={styles.paneHeading}>TRACK</Text>
              <FlatList
                data={tracks}
                keyExtractor={(o) => o.key}
                style={styles.list}
                showsVerticalScrollIndicator={false}
                renderItem={({ item }) => (
                  <Pressable
                    style={({ pressed }) => [styles.option, item.selected && styles.optionSelected, pressed && styles.optionPressed]}
                    onPress={() => onSelectTrack(item.key)}
                  >
                    <View style={styles.check}>
                      {item.selected ? <Ionicons name="checkmark" size={20} color={colors.accent} /> : null}
                    </View>
                    <View style={styles.optionText}>
                      <Text style={[styles.label, item.selected && styles.labelSelected]} numberOfLines={1}>
                        {item.label}
                      </Text>
                      {item.detail ? <Text style={styles.detail}>{item.detail}</Text> : null}
                    </View>
                  </Pressable>
                )}
              />
            </View>

            <View style={styles.divider} />

            <ScrollView
              style={styles.appearancePane}
              contentContainerStyle={styles.appearanceContent}
              showsVerticalScrollIndicator={false}
            >
              {appearance}
            </ScrollView>
          </View>
        </BlurView>
      </Animated.View>
    </View>
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
  tracksPane: { flex: 1, paddingLeft: spacing.xl, paddingRight: spacing.md, paddingBottom: spacing.lg },
  paneHeading: {
    color: colors.textDim,
    fontSize: 11,
    fontWeight: '700',
    letterSpacing: 1,
    marginBottom: spacing.sm,
    paddingHorizontal: spacing.xs,
  },
  list: { flex: 1 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    paddingVertical: 12,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.md,
  },
  optionSelected: { backgroundColor: 'rgba(255,255,255,0.06)' },
  optionPressed: { backgroundColor: 'rgba(255,255,255,0.1)' },
  check: { width: 22 },
  optionText: { flex: 1 },
  label: { color: 'rgba(255,255,255,0.9)', fontSize: 15.5, fontWeight: '500' },
  labelSelected: { color: '#fff', fontWeight: '700' },
  detail: { color: 'rgba(255,255,255,0.45)', fontSize: 12, marginTop: 1 },
  divider: { width: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.12)', marginVertical: spacing.sm },
  appearancePane: { flex: 1.15 },
  appearanceContent: { paddingHorizontal: spacing.xl, paddingBottom: spacing.xl, gap: spacing.lg },
})
