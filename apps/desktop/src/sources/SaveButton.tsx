import { FluentIcon } from '../components/FluentIcon'
import { ProgressRing } from '../components/ProgressRing'
import type { SaveState } from './sourcesModel'

function saveLabel(state: SaveState): string {
  switch (state.kind) {
    case 'ready':
      return state.replaces ? 'Save instead' : 'Save for offline'
    case 'saving':
      return state.fraction == null ? 'Saving' : `Saving ${Math.floor(state.fraction * 100)}%`
    case 'saved':
      return 'Saved'
    case 'unavailable':
      return ''
  }
}

/**
 * The phone's filled download arrow over its bar. Segoe Fluent Icons has only
 * the outlined tray, which reads thin beside the filled play glyph.
 */
function SolidDownload() {
  return (
    <svg className="sx-save-icon" viewBox="0 0 24 24" width={16} height={16} aria-hidden>
      <path d="M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z" fill="currentColor" />
    </svg>
  )
}

/**
 * Saving, as it stands for this source; only a ready one takes a click.
 * `prominent` is the pick's size, beside its Play; the details panel's is smaller.
 */
export function SaveButton({
  state,
  onSave,
  prominent = false,
}: {
  state: SaveState
  onSave: () => void
  prominent?: boolean
}) {
  if (state.kind === 'unavailable') return null
  return (
    <button
      type="button"
      className={`btn sx-btn ${prominent ? 'sx-h44' : 'sx-h40'} sx-save ${state.kind === 'saved' ? 'sx-save-done' : ''}`}
      disabled={state.kind !== 'ready'}
      onClick={onSave}
    >
      {state.kind === 'saving' ? (
        <ProgressRing size={14} />
      ) : state.kind === 'saved' ? (
        <FluentIcon glyph="check" size={15} />
      ) : (
        <SolidDownload />
      )}
      <span>{saveLabel(state)}</span>
    </button>
  )
}
