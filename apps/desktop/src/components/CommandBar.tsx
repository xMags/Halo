import { useNav } from '../nav'
import { useCommandBarSlotRef, useScreenTitle } from '../screenTitle'
import { Icon } from './Icon'

/**
 * The bar under the title bar: where you are, how to go back, the current
 * screen's own controls, and search, which is reachable from anywhere. The
 * account lives in the rail's footer. It never scrolls; the view below does.
 */
export function CommandBar() {
  const { canPop, pop, setRoot } = useNav()
  const { title, crumb } = useScreenTitle()
  const slotRef = useCommandBarSlotRef()

  return (
    <div className="cmdbar">
      <button
        type="button"
        className="cmd-back"
        title="Back"
        disabled={!canPop}
        onClick={pop}
      >
        <Icon name="chevronLeft" size={15} />
      </button>
      <div className="cmd-title ellipsis">{title}</div>
      {crumb && <div className="cmd-crumb ellipsis">{crumb}</div>}
      <div className="cmd-actions" ref={slotRef} />
      <div className="spacer" />

      <button type="button" className="cmd-search" onClick={() => setRoot('search')}>
        <Icon name="search" size={14} />
        <span className="spacer">Search titles, addons…</span>
        <span className="kbd">CTRL K</span>
      </button>
    </div>
  )
}
