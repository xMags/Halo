import { type ReactNode } from 'react'
import { Pressable, StyleSheet, Text, TextInput, View, type ViewStyle } from 'react-native'
import { Ionicons } from '@expo/vector-icons'
import { LinearGradient } from 'expo-linear-gradient'
import { colors, heroScrim, heroScrimLocations, radius, spacing, type } from '../theme'

/** Bottom-weighted black scrim over hero art so titles/controls stay legible. */
export function HeroScrim({ style }: { style?: ViewStyle }) {
  return (
    <LinearGradient
      colors={heroScrim}
      locations={heroScrimLocations}
      style={[StyleSheet.absoluteFill, style]}
      pointerEvents="none"
    />
  )
}

interface SegmentedProps {
  options: string[]
  value: string
  onChange: (value: string) => void
}

/** iOS-style segmented filter used on Home and Library. */
export function Segmented({ options, value, onChange }: SegmentedProps) {
  return (
    <View style={segStyles.track}>
      {options.map((opt) => {
        const active = opt === value
        return (
          <Pressable key={opt} style={[segStyles.seg, active && segStyles.segActive]} onPress={() => onChange(opt)}>
            <Text style={[segStyles.label, active && segStyles.labelActive]}>{opt}</Text>
          </Pressable>
        )
      })}
    </View>
  )
}

const segStyles = StyleSheet.create({
  track: { flexDirection: 'row', gap: spacing.sm, backgroundColor: 'rgba(255,255,255,0.06)', borderRadius: radius.md - 3, padding: 3 },
  seg: { flex: 1, alignItems: 'center', paddingVertical: 6, borderRadius: radius.sm - 1 },
  segActive: { backgroundColor: 'rgba(255,255,255,0.16)' },
  label: { fontSize: 13.5, fontWeight: '600', color: colors.textDim },
  labelActive: { color: '#fff' },
})

interface SearchFieldProps {
  value?: string
  placeholder?: string
  /** Static mode: whole field is a button (navigates elsewhere). */
  onPress?: () => void
  /** Editable mode. */
  editable?: boolean
  onChangeText?: (text: string) => void
  onClear?: () => void
  onSubmitEditing?: () => void
  autoFocus?: boolean
}

/** Frosted search field. Static (onPress) or editable (editable + onChangeText). */
export function SearchField({
  value,
  placeholder = 'Search movies, series…',
  onPress,
  editable,
  onChangeText,
  onClear,
  onSubmitEditing,
  autoFocus,
}: SearchFieldProps) {
  const body = (
    <View style={searchStyles.field}>
      <Ionicons name="search" size={17} color={value ? colors.text : colors.textDim} />
      {editable ? (
        <TextInput
          style={searchStyles.input}
          value={value}
          onChangeText={onChangeText}
          placeholder={placeholder}
          placeholderTextColor={colors.textDim}
          autoCapitalize="none"
          autoCorrect={false}
          autoFocus={autoFocus}
          returnKeyType="search"
          onSubmitEditing={onSubmitEditing}
        />
      ) : (
        <Text style={[searchStyles.placeholder, value && searchStyles.value]}>{value || placeholder}</Text>
      )}
      {value ? (
        <Pressable onPress={onClear} hitSlop={8}>
          <Ionicons name="close-circle" size={17} color={colors.textDim} />
        </Pressable>
      ) : null}
    </View>
  )
  if (onPress) {
    return (
      <Pressable onPress={onPress} style={({ pressed }) => (pressed ? { opacity: 0.7 } : undefined)}>
        {body}
      </Pressable>
    )
  }
  return body
}

const searchStyles = StyleSheet.create({
  field: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm, backgroundColor: colors.fieldFill, borderRadius: radius.md - 1, paddingHorizontal: 12, paddingVertical: 10 },
  input: { flex: 1, color: colors.text, fontSize: 15, padding: 0 },
  placeholder: { flex: 1, color: colors.textDim, fontSize: 15 },
  value: { color: colors.text, fontWeight: '500' },
})

/** Metadata dot-separated row (year · runtime · rating). */
export function MetaLine({ parts, rating }: { parts: (string | undefined | false)[]; rating?: string }) {
  const items = parts.filter(Boolean) as string[]
  return (
    <View style={metaStyles.row}>
      {items.map((part, i) => (
        <View key={i} style={metaStyles.row}>
          {i > 0 ? <Text style={metaStyles.dot}>·</Text> : null}
          <Text style={metaStyles.text}>{part}</Text>
        </View>
      ))}
      {rating ? (
        <View style={metaStyles.row}>
          {items.length ? <Text style={metaStyles.dot}>·</Text> : null}
          <Ionicons name="star" size={12} color={colors.gold} />
          <Text style={[metaStyles.text, { color: colors.gold }]}>{rating}</Text>
        </View>
      ) : null}
    </View>
  )
}

const metaStyles = StyleSheet.create({
  row: { flexDirection: 'row', alignItems: 'center', gap: 6 },
  text: { color: '#c7cdd9', fontSize: 13, fontWeight: '600' },
  dot: { color: '#c7cdd9', fontSize: 13 },
})

/** Centered empty/error state. */
export function CenterMessage({ children }: { children: ReactNode }) {
  return (
    <View style={centerStyles.center}>
      <Text style={centerStyles.text}>{children}</Text>
    </View>
  )
}

const centerStyles = StyleSheet.create({
  center: { flex: 1, backgroundColor: colors.background, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  text: { ...type.body, color: colors.textDim, textAlign: 'center', fontSize: 15 },
})
