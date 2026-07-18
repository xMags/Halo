import { getServerUrl } from '../api'
import { useNav, type Section } from '../nav'
import { Icon, type IconName } from './Icon'

const ITEMS: Array<{ section: Section; label: string; icon: IconName }> = [
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'library', label: 'Library', icon: 'bookmark' },
  { section: 'settings', label: 'Settings', icon: 'sliders' },
]

/** Persistent left rail — the desktop counterpart of mobile's bottom tabs. */
export function Sidebar() {
  const { section, setRoot } = useNav()
  const host = getServerUrl()?.replace(/^https?:\/\//, '') ?? ''

  return (
    <nav className="sidebar">
      <div className="sidebar-brand">Halo</div>
      <div className="sidebar-items">
        {ITEMS.map((item) => (
          <button
            key={item.section}
            type="button"
            className={`sidebar-item ${section === item.section ? 'sidebar-item-active' : ''}`}
            onClick={() => setRoot(item.section)}
          >
            <Icon name={item.icon} />
            <span>{item.label}</span>
          </button>
        ))}
      </div>
      <div className="sidebar-footer" title={getServerUrl() ?? undefined}>
        <span className="status-dot" />
        <span className="sidebar-host">{host}</span>
      </div>
    </nav>
  )
}
