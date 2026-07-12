import { type ReactNode, useEffect, useRef } from 'react'
import { Animated, FlatList, Pressable, StyleSheet, Text, useWindowDimensions, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { BlurView } from 'expo-blur'
import { colors, radius, spacing } from '../theme'

export interface SelectOption {
  key: string
  label: string
  detail?: string
  selected?: boolean
}

interface Props {
  visible: boolean
  title: string
  options: SelectOption[]
  onSelect: (key: string) => void
  onClose: () => void
  footer?: ReactNode
  description?: string
  presentation?: 'bottom' | 'side'
}

/**
 * Frosted bottom-sheet picker used for audio tracks, subtitles, and seasons.
 *
 * Deliberately NOT an RN <Modal>: with multiple supportedOrientations, iOS
 * lays the modal container out with stale portrait metrics inside the
 * landscape-locked player, leaving the sheet floating mid-screen. An in-tree
 * absolute overlay always gets the screen's real dimensions. Render it as the
 * LAST sibling of a screen's root view (never inside a ScrollView).
 */
export function SelectSheet({
  visible,
  title,
  options,
  onSelect,
  onClose,
  footer,
  description,
  presentation = 'bottom',
}: Props) {
  const slide = useRef(new Animated.Value(0)).current
  // A concrete number: percentage maxHeight resolves against the wrapper,
  // whose height is undefined, so Yoga would ignore it entirely.
  const { height: windowHeight, width: windowWidth } = useWindowDimensions()

  useEffect(() => {
    if (visible) {
      slide.setValue(0)
      Animated.timing(slide, { toValue: 1, duration: 220, useNativeDriver: true }).start()
    }
  }, [visible, slide])

  if (!visible) return null

  const translation = slide.interpolate({ inputRange: [0, 1], outputRange: [80, 0] })
  const side = presentation === 'side'

  return (
    <View style={styles.overlay} pointerEvents="box-none">
      <Pressable style={styles.backdrop} onPress={onClose} />
      <Animated.View
        style={[
          side ? styles.sideWrap : styles.sheetWrap,
          side
            ? { width: Math.min(windowWidth * 0.48, 440), transform: [{ translateX: translation }] }
            : { transform: [{ translateY: translation }] },
          { opacity: slide },
        ]}
      >
        <BlurView
          intensity={44}
          tint="dark"
          style={[styles.sheet, side ? styles.sideSheet : { maxHeight: windowHeight * 0.7 }]}
        >
          {side ? null : <View style={styles.grabber} />}
          <View style={styles.header}>
            <View style={styles.headerText}>
              <Text style={styles.title}>{title}</Text>
              {description ? <Text style={styles.description}>{description}</Text> : null}
            </View>
            <Pressable style={styles.closeButton} onPress={onClose} hitSlop={8}>
              <Ionicons name="close" size={20} color={colors.text} />
            </Pressable>
          </View>
          <FlatList
            data={options}
            keyExtractor={(o) => o.key}
            style={styles.list}
            renderItem={({ item }) => (
              <Pressable
                style={({ pressed }) => [styles.option, pressed && styles.optionPressed]}
                onPress={() => {
                  onSelect(item.key)
                  onClose()
                }}
              >
                <View style={styles.check}>
                  {item.selected ? <Ionicons name="checkmark" size={20} color={colors.accent} /> : null}
                </View>
                <View style={styles.optionText}>
                  <Text style={[styles.label, item.selected && styles.labelSelected]}>{item.label}</Text>
                  {item.detail ? <Text style={styles.detail}>{item.detail}</Text> : null}
                </View>
              </Pressable>
            )}
          />
          {footer ? <View style={[styles.footer, side && styles.sideFooter]}>{footer}</View> : null}
        </BlurView>
      </Animated.View>
    </View>
  )
}

const styles = StyleSheet.create({
  overlay: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    zIndex: 100,
    elevation: 100,
  },
  backdrop: {
    position: 'absolute',
    top: 0,
    left: 0,
    right: 0,
    bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },
  sheetWrap: {
    position: 'absolute',
    left: 0,
    right: 0,
    bottom: 0,
  },
  sideWrap: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
  },
  sheet: {
    backgroundColor: colors.sheetTint,
    borderTopLeftRadius: radius.xl + 4,
    borderTopRightRadius: radius.xl + 4,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xl + spacing.sm,
    overflow: 'hidden',
  },
  sideSheet: {
    height: '100%',
    borderTopRightRadius: 0,
    borderBottomLeftRadius: radius.xl + 4,
    borderLeftWidth: StyleSheet.hairlineWidth,
    paddingBottom: spacing.md,
  },
  grabber: {
    alignSelf: 'center',
    width: 38,
    height: 5,
    borderRadius: 999,
    backgroundColor: 'rgba(255,255,255,0.25)',
    marginBottom: spacing.sm,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: spacing.lg,
    paddingBottom: spacing.sm,
  },
  headerText: { flex: 1 },
  title: {
    color: colors.text,
    fontSize: 18,
    fontWeight: '700',
  },
  description: { color: colors.textDim, fontSize: 11.5, marginTop: 2 },
  closeButton: {
    width: 34,
    height: 34,
    borderRadius: radius.pill,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  list: { flexGrow: 0, flexShrink: 1 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    paddingVertical: 13,
    paddingHorizontal: spacing.lg,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: 'rgba(255,255,255,0.08)',
  },
  optionPressed: { backgroundColor: 'rgba(255,255,255,0.06)' },
  check: { width: 22 },
  optionText: { flex: 1 },
  label: { color: 'rgba(255,255,255,0.9)', fontSize: 15.5, fontWeight: '500' },
  labelSelected: { color: '#fff', fontWeight: '700' },
  detail: { color: 'rgba(255,255,255,0.45)', fontSize: 12, marginTop: 1 },
  footer: {
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: 'rgba(255,255,255,0.1)',
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.md,
  },
  sideFooter: { paddingBottom: spacing.sm },
})
