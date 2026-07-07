import { Image, Pressable, StyleSheet, Text } from 'react-native'
import { useRouter } from 'expo-router'
import type { MetaPreview } from '@halo/core'
import { colors, POSTER_HEIGHT, POSTER_WIDTH, spacing } from '../theme'

export function PosterCard({ meta }: { meta: MetaPreview }) {
  const router = useRouter()
  return (
    <Pressable
      style={styles.card}
      onPress={() =>
        router.push({ pathname: '/detail/[type]/[id]', params: { type: meta.type, id: meta.id } })
      }
    >
      <Image source={{ uri: meta.poster }} style={styles.poster} resizeMode="cover" />
      <Text style={styles.title} numberOfLines={2}>
        {meta.name}
      </Text>
    </Pressable>
  )
}

const styles = StyleSheet.create({
  card: {
    width: POSTER_WIDTH,
    marginRight: spacing.sm,
  },
  poster: {
    width: POSTER_WIDTH,
    height: POSTER_HEIGHT,
    borderRadius: 8,
    backgroundColor: colors.surface,
  },
  title: {
    color: colors.textDim,
    fontSize: 12,
    marginTop: spacing.xs,
  },
})
