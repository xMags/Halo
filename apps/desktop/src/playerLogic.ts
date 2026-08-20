export type PlayerShortcut = 'toggle-pause' | 'seek-back' | 'seek-forward' | 'toggle-fullscreen' | 'escape'

export interface PlayerShortcutInput {
  code: string
  ctrlKey: boolean
  altKey: boolean
  shiftKey: boolean
  metaKey: boolean
  repeat: boolean
  interactiveTarget: boolean
}

/** Resolves only unmodified player-level shortcuts that the focused control does not own. */
export function resolvePlayerShortcut(input: PlayerShortcutInput): PlayerShortcut | null {
  if (input.ctrlKey || input.altKey || input.shiftKey || input.metaKey) {
    return null
  }

  // Escape peels player layers even when a button or slider currently owns
  // focus. Other shortcuts leave focused controls to their native keyboard
  // behavior, preventing Space or arrows from acting twice.
  if (input.code === 'Escape') return input.repeat ? null : 'escape'
  if (input.interactiveTarget) return null
  if (input.repeat && input.code !== 'ArrowLeft' && input.code !== 'ArrowRight') return null

  switch (input.code) {
    case 'Space':
      return 'toggle-pause'
    case 'ArrowLeft':
      return 'seek-back'
    case 'ArrowRight':
      return 'seek-forward'
    case 'KeyF':
      return 'toggle-fullscreen'
    default:
      return null
  }
}

/** mpv renders into the whole HWND, so windowed playback reserves the app title bar explicitly. */
export function videoTopMarginRatio(fullscreen: boolean, windowHeight: number, titleBarHeight: number): number {
  if (fullscreen || windowHeight <= 0 || titleBarHeight <= 0) return 0
  return Math.min(1, titleBarHeight / windowHeight)
}

export function shouldRunUpNextCountdown(visible: boolean, endReached: boolean): boolean {
  return visible && endReached
}

export function upNextCancelAction(endReached: boolean): 'dismiss' | 'exit' {
  return endReached ? 'exit' : 'dismiss'
}
