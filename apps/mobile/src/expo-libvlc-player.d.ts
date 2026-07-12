import 'expo-libvlc-player'

declare module 'expo-libvlc-player' {
  interface LibVlcPlayerViewNativeProps {
    subtitleDelayMs?: number
  }

  interface LibVlcPlayerViewProps {
    /** Positive values display subtitles later; negative values display them earlier. */
    subtitleDelayMs?: number
  }
}
