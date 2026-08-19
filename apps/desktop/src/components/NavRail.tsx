import { useState } from 'react'
import { initials } from '../format'
import { useNav, type Section } from '../nav'
import { useMe } from '../queries'
import { Icon, type IconName } from './Icon'

const ITEMS: Array<{ section: Section; label: string; icon: IconName }> = [
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'library', label: 'Library', icon: 'library' },
  { section: 'settings', label: 'Settings', icon: 'settings' },
]

const COLLAPSED_KEY = 'halo.railCollapsed'

/**
 * Device-local, like the other shell preferences. The rail starts collapsed
 * and only stays open for someone who opened it, so the absent key and a
 * blocked store both mean collapsed; only an explicit '0' expands it.
 */
function readCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSED_KEY) !== '0'
  } catch {
    return true
  }
}

function writeCollapsed(collapsed: boolean): void {
  try {
    localStorage.setItem(COLLAPSED_KEY, collapsed ? '1' : '0')
  } catch {
    // Losing the preference is not worth failing a click over.
  }
}

/**
 * Persistent left rail: the sections, and the signed-in account pinned to the
 * bottom. Detail and Sources are pushed on top of Home, so the stack root —
 * not the visible screen — decides which row is lit.
 *
 * Collapsing follows WinUI's nav pane: the same rows narrowed to icons behind
 * the hamburger, with the labels becoming tooltips rather than disappearing.
 * It starts collapsed and remembers being expanded, so the shelves get the
 * width by default and the labels are one click away.
 */
export function NavRail() {
  const { section, setRoot } = useNav()
  const { data: me } = useMe()
  const [collapsed, setCollapsed] = useState(readCollapsed)

  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    writeCollapsed(next)
  }

  const name = me?.username ?? 'account'

  return (
    <nav className={`rail ${collapsed ? 'rail-collapsed' : ''}`}>
      <button
        type="button"
        className="rail-toggle"
        onClick={toggle}
        title={collapsed ? 'Expand navigation' : 'Collapse navigation'}
        aria-expanded={!collapsed}
      >
        <Icon name="menu" size={16} />
      </button>

      <div className="rail-kicker">BROWSE</div>
      <div className="rail-items">
        {ITEMS.map((item) => (
          <button
            key={item.section}
            type="button"
            className={`rail-item ${section === item.section ? 'rail-item-active' : ''}`}
            onClick={() => setRoot(item.section)}
            title={collapsed ? item.label : undefined}
          >
            <Icon name={item.icon} />
            <span className="rail-label">{item.label}</span>
          </button>
        ))}
      </div>

      <div className="rail-account">
        <button
          type="button"
          className="account-pill account-pill-rail"
          title={collapsed ? `${name} · server & account` : 'Server & account'}
          onClick={() => setRoot('settings')}
        >
          <span className="avatar">{me ? initials(me.username) : '··'}</span>
          <span className="rail-label ellipsis">{name}</span>
        </button>
      </div>
    </nav>
  )
}
