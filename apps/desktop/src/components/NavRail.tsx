import { initials } from '../format'
import { useNav, type Section } from '../nav'
import { useMe } from '../queries'
import { Icon, type IconName } from './Icon'

const ITEMS: Array<{ section: Section; label: string; icon: IconName }> = [
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'library', label: 'Library', icon: 'bookmark' },
  { section: 'settings', label: 'Settings', icon: 'sliders' },
]

/**
 * Persistent left rail: the sections, and the signed-in account pinned to the
 * bottom. Detail and Sources are pushed on top of Home, so the stack root —
 * not the visible screen — decides which row is lit.
 */
export function NavRail() {
  const { section, setRoot } = useNav()
  const { data: me } = useMe()

  return (
    <nav className="rail">
      <div className="rail-kicker">BROWSE</div>
      <div className="rail-items">
        {ITEMS.map((item) => (
          <button
            key={item.section}
            type="button"
            className={`rail-item ${section === item.section ? 'rail-item-active' : ''}`}
            onClick={() => setRoot(item.section)}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>
          </button>
        ))}
      </div>

      <div className="rail-account">
        <button
          type="button"
          className="account-pill account-pill-rail"
          title="Server & account"
          onClick={() => setRoot('settings')}
        >
          <span className="avatar">{me ? initials(me.username) : '··'}</span>
          <span className="ellipsis">{me?.username ?? 'account'}</span>
        </button>
      </div>
    </nav>
  )
}
