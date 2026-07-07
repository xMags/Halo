import { FlatList, Modal, Pressable, StyleSheet, Text, View } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { colors, spacing } from '../theme'

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

/** Bottom-sheet picker used for audio tracks, subtitles, and seasons. */
export function SelectSheet({ visible, title, options, onSelect, onClose }: Props) {
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose}>
        <Pressable style={styles.sheet} onPress={(e) => e.stopPropagation()}>
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
                <View style={styles.optionText}>
                  <Text style={[styles.label, item.selected && styles.labelSelected]}>{item.label}</Text>
                  {item.detail ? <Text style={styles.detail}>{item.detail}</Text> : null}
                </View>
                {item.selected ? <Ionicons name="checkmark" size={20} color={colors.accent} /> : null}
              </Pressable>
            )}
          />
        </Pressable>
      </Pressable>
    </Modal>
  )
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.6)',
    justifyContent: 'flex-end',
  },
  sheet: {
    backgroundColor: colors.surfaceHigh,
    borderTopLeftRadius: 16,
    borderTopRightRadius: 16,
    paddingTop: spacing.md,
    paddingBottom: spacing.xl,
    maxHeight: '65%',
  },
  title: {
    color: colors.text,
    fontSize: 16,
    fontWeight: '600',
    paddingHorizontal: spacing.md,
    marginBottom: spacing.sm,
  },
  list: {
    flexGrow: 0,
  },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: spacing.md,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: colors.border,
  },
  optionText: {
    flex: 1,
  },
  label: {
    color: colors.text,
    fontSize: 15,
  },
  labelSelected: {
    color: colors.accent,
    fontWeight: '600',
  },
  detail: {
    color: colors.textDim,
    fontSize: 12,
    marginTop: 2,
  },
})
