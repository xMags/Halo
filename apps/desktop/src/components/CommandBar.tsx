import { initials } from '../format'
import { useNav } from '../nav'
import { useMe } from '../queries'
import { useScreenTitle } from '../screenTitle'
import { Icon } from './Icon'

/**
 * The bar under the title bar: where you are, how to go back, and the two
 * destinations that are reachable from anywhere — search and the account.
 * It never scrolls; the view below it does.
 */
export function CommandBar() {
  const { canPop, pop, setRoot } = useNav()
  const { title, crumb } = useScreenTitle()
  const { data: me } = useMe()

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
      <div className="spacer" />

      <button type="button" className="cmd-search" onClick={() => setRoot('search')}>
        <Icon name="search" size={14} />
        <span className="spacer">Search titles, addons…</span>
        <span className="kbd">CTRL K</span>
      </button>

      <button
        type="button"
        className="account-pill"
        title="Server & account"
        onClick={() => setRoot('settings')}
      >
        <span className="avatar">{me ? initials(me.username) : '··'}</span>
        <span className="ellipsis">{me?.username ?? 'account'}</span>
      </button>
    </div>
  )
}
