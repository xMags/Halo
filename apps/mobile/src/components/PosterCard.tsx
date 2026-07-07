import { Image, Pressable, StyleSheet, Text, View } from 'react-native'
import { useRouter } from 'expo-router'
import type { MetaPreview } from '@halo/core'
import { colors, POSTER_RATIO, POSTER_WIDTH, radius } from '../theme'

interface Props {
  meta: MetaPreview
  /** Fixed width (horizontal rows). Ignored when `fill` is set. */
  width?: number
  /** Fill the parent column width (grids via numColumns). */
  fill?: boolean
  /** Show the title under the poster (off by default — poster-forward). */
  showLabel?: boolean
  /** 0..1 watch progress bar under the poster. */
  progress?: number
}

export function PosterCard({ meta, width = POSTER_WIDTH, fill = false, showLabel = false, progress }: Props) {
  const router = useRouter()
  return (
    <Pressable
      style={fill ? styles.fillCard : { width }}
      onPress={() =>
        router.push({ pathname: '/detail/[type]/[id]', params: { type: meta.type, id: meta.id } })
      }
    >
      <Image
        source={{ uri: meta.poster }}
        style={fill ? styles.posterFill : { width, height: width * POSTER_RATIO, borderRadius: radius.md, backgroundColor: colors.surface }}
        resizeMode="cover"
      />
      {progress != null && progress > 0.02 ? (
        <View style={styles.track}>
          <View style={[styles.fillBar, { width: `${Math.min(100, progress * 100)}%` }]} />
        </View>
      ) : null}
      {showLabel ? (
        <Text style={styles.title} numberOfLines={1}>
          {meta.name}
        </Text>
      ) : null}
    </Pressable>
  )
}

const styles = StyleSheet.create({
  fillCard: { flex: 1 },
  posterFill: {
    width: '100%',
    aspectRatio: 1 / POSTER_RATIO,
    borderRadius: radius.md,
    backgroundColor: colors.surface,
  },
  track: {
    height: 3,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.18)',
    marginTop: 7,
    overflow: 'hidden',
  },
  fillBar: { height: '100%', backgroundColor: colors.accent },
  title: {
    color: colors.textDim,
    fontSize: 11.5,
    fontWeight: '500',
    marginTop: 6,
  },
})
