import type { ReactElement } from 'react'
import type { PlayerVideoProps } from './PlayerVideo.types'

/**
 * Type facade for the platform-split implementations: Metro resolves
 * `./PlayerVideo` to PlayerVideo.ios.tsx or PlayerVideo.android.tsx at bundle
 * time, while tsc (no `moduleSuffixes` configured) resolves this file. Both
 * implementations type their props as PlayerVideoProps, which keeps this
 * declaration honest.
 */
declare function PlayerVideo(props: PlayerVideoProps): ReactElement
export default PlayerVideo
