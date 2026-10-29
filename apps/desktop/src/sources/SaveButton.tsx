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

/** Saving, as it stands for this source; only a ready one takes a click. */
export function SaveButton({ state, onSave }: { state: SaveState; onSave: () => void }) {
  if (state.kind === 'unavailable') return null
  return (
    <button
      type="button"
      className={`btn sx-btn sx-h40 sx-save ${state.kind === 'saved' ? 'sx-save-done' : ''}`}
      disabled={state.kind !== 'ready'}
      onClick={onSave}
    >
      {state.kind === 'saving' ? (
        <ProgressRing size={14} />
      ) : (
        <FluentIcon glyph={state.kind === 'saved' ? 'check' : 'downloads'} size={14} />
      )}
      <span>{saveLabel(state)}</span>
    </button>
  )
}
