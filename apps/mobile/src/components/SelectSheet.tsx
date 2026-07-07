import { FlatList, Modal, Pressable, StyleSheet, Text, View } from 'react-native'
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
}

/** Frosted bottom-sheet picker used for audio tracks, subtitles, and seasons. */
export function SelectSheet({ visible, title, options, onSelect, onClose }: Props) {
  return (
    <Modal
      visible={visible}
      transparent
      animationType="slide"
      onRequestClose={onClose}
      // RN Modal defaults to portrait-only, which yanks the landscape player
      // back to portrait whenever a sheet opens.
      supportedOrientations={['portrait', 'portrait-upside-down', 'landscape-left', 'landscape-right']}
    >
      <Pressable style={styles.backdrop} onPress={onClose}>
        <Pressable onPress={(e) => e.stopPropagation()}>
          <BlurView intensity={40} tint="dark" style={styles.sheet}>
            <View style={styles.grabber} />
            <Text style={styles.title}>{title}</Text>
            <FlatList
              data={options}
              keyExtractor={(o) => o.key}
              style={styles.list}
              renderItem={({ item }) => (
                <Pressable
                  style={styles.option}
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
          </BlurView>
        </Pressable>
      </Pressable>
    </Modal>
  )
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.55)',
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: colors.sheetTint,
    borderTopLeftRadius: radius.xl + 4,
    borderTopRightRadius: radius.xl + 4,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderColor: colors.glassBorder,
    paddingTop: spacing.sm,
    paddingBottom: spacing.xl + spacing.sm,
    maxHeight: '70%',
    overflow: 'hidden',
  },
  grabber: {
    alignSelf: 'center',
    width: 38,
    height: 5,
    borderRadius: 999,
    backgroundColor: 'rgba(255,255,255,0.25)',
    marginBottom: spacing.sm,
  },
  title: {
    color: colors.text,
    fontSize: 18,
    fontWeight: '700',
    paddingHorizontal: spacing.lg,
    marginBottom: spacing.xs,
  },
  list: { flexGrow: 0 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm + 4,
    paddingVertical: 13,
    paddingHorizontal: spacing.lg,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: 'rgba(255,255,255,0.08)',
  },
  check: { width: 22 },
  optionText: { flex: 1 },
  label: { color: 'rgba(255,255,255,0.9)', fontSize: 15.5, fontWeight: '500' },
  labelSelected: { color: '#fff', fontWeight: '700' },
  detail: { color: 'rgba(255,255,255,0.45)', fontSize: 12, marginTop: 1 },
})
