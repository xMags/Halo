import { buildContinueWatching } from '../homeRows'
import { formatTimeLeft } from '../format'
import { useNav, type Section } from '../nav'
import { useLibrary, useWatchStates } from '../queries'
import { describeStatus, useServerStatus } from '../serverStatus'
import { Icon, type IconName } from './Icon'

const ITEMS: Array<{ section: Section; label: string; icon: IconName }> = [
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'library', label: 'Library', icon: 'bookmark' },
  { section: 'settings', label: 'Settings', icon: 'sliders' },
]

/** How many in-progress titles the rail's shortcut list holds. */
const JUMP_BACK_LIMIT = 3

/**
 * Persistent left rail: sections, a jump-back-in shortcut list, and the
 * server's reachability pinned to the bottom. Detail and Sources are pushed
 * on top of Home, so the stack root — not the visible screen — decides which
 * row is lit.
 */
export function NavRail() {
  const { section, setRoot, push } = useNav()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()
  const status = useServerStatus()

  const jumpBack = buildContinueWatching(watchStates, library).slice(0, JUMP_BACK_LIMIT)

  const dotClass =
    status.state === 'connected'
      ? 'status-dot'
      : status.state === 'probing'
        ? 'status-dot status-dot-warn'
        : 'status-dot status-dot-down'

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

      {jumpBack.length > 0 && (
        <>
          <div className="rail-kicker" style={{ padding: '26px 0 10px 22px' }}>
            JUMP BACK IN
          </div>
          <div className="jump-list">
            {jumpBack.map((item) => {
              const state = (watchStates ?? []).find((s) => s.itemId === item.itemId)
              return (
                <button
                  key={item.itemId}
                  type="button"
                  className="jump-row"
                  title={item.meta.name}
                  onClick={() => push({ name: 'detail', type: item.meta.type, id: item.meta.id })}
                >
                  <div className="art jump-thumb">
                    {item.meta.poster && <img src={item.meta.poster} alt="" draggable={false} />}
                  </div>
                  <div style={{ minWidth: 0 }}>
                    <div className="jump-name ellipsis">{item.meta.name}</div>
                    {state && (
                      <div className="jump-meta">
                        {formatTimeLeft(state.positionSec, state.durationSec)}
                      </div>
                    )}
                  </div>
                </button>
              )
            })}
          </div>
        </>
      )}

      <div className="rail-status" title={status.host}>
        <span className={dotClass} />
        <div style={{ minWidth: 0 }}>
          <div className="rail-host ellipsis">{status.host}</div>
          <div className="rail-status-line">{describeStatus(status)}</div>
        </div>
      </div>
    </nav>
  )
}
