import { useDownloads } from '../downloads'
import { formatTimeLeft, initials } from '../format'
import { buildContinueWatching } from '../homeRows'
import { useNav, type Section } from '../nav'
import { useLibrary, useMe, useWatchStates } from '../queries'
import { ArtImage } from './ArtImage'
import { Icon, type IconName } from './Icon'

const ITEMS: Array<{ section: Section; label: string; icon: IconName }> = [
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'library', label: 'Library', icon: 'library' },
  { section: 'downloads', label: 'Downloads', icon: 'downloads' },
  { section: 'settings', label: 'Settings', icon: 'settings' },
]

/** How many in-progress titles the pane's shortcut list carries. */
const JUMP_LIMIT = 3

/**
 * The 224px navigation pane: the five sections, three shortcuts back into
 * whatever is half-watched, and the account pinned to the bottom.
 *
 * Detail and the sources sheet open on top of a section, so the stack *root* —
 * not the visible screen — decides which row is lit: opening a poster from
 * Home leaves Home lit, which is where Back goes.
 *
 * The shortcut rows deliberately print only the time left, not `S02E04 · …`:
 * the episode tag lives in the title's meta, and resolving it here would make
 * a permanently-mounted pane issue addon round-trips on every screen.
 */
export function NavRail() {
  const { section, push, setRoot } = useNav()
  const { data: me } = useMe()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()
  const { downloads } = useDownloads()

  const activeTransfers = downloads.filter(
    (item) => item.status === 'downloading' || item.status === 'queued',
  ).length

  const jump = buildContinueWatching(watchStates, library).slice(0, JUMP_LIMIT)
  const name = me?.username ?? 'account'

  return (
    <nav className="nav">
      <div className="nav-kicker">MENU</div>
      {ITEMS.map((item) => (
        <button
          key={item.section}
          type="button"
          className={`nav-item ${section === item.section ? 'nav-item-active' : ''}`}
          onClick={() => setRoot(item.section)}
        >
          <Icon name={item.icon} />
          <span className="spacer ellipsis">{item.label}</span>
          {item.section === 'downloads' && activeTransfers > 0 && (
            <span className="nav-badge">{activeTransfers}</span>
          )}
        </button>
      ))}

      {jump.length > 0 && (
        <>
          <div className="nav-kicker nav-kicker-jump">JUMP BACK IN</div>
          {jump.map((item) => {
            const state = (watchStates ?? []).find((s) => s.itemId === item.itemId)
            return (
              <button
                key={item.itemId}
                type="button"
                className="jump-row"
                title={item.meta.name}
                onClick={() => push({ name: 'detail', type: item.meta.type, id: item.meta.id })}
              >
                <span className="art jump-art">
                  <ArtImage src={item.meta.poster} />
                </span>
                <span style={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 1 }}>
                  <span className="jump-title ellipsis">{item.meta.name}</span>
                  <span className="jump-meta ellipsis">
                    {state
                      ? formatTimeLeft(state.positionSec, state.durationSec).toUpperCase()
                      : 'IN PROGRESS'}
                  </span>
                </span>
              </button>
            )
          })}
        </>
      )}

      <div className="spacer" />

      <button
        type="button"
        className="account-row"
        title="Server & account"
        onClick={() => setRoot('settings')}
      >
        <span className="avatar">{me ? initials(me.username) : '··'}</span>
        <span style={{ minWidth: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span className="account-name ellipsis">{name}</span>
          <span className="account-kicker">HALO ACCOUNT</span>
        </span>
      </button>
    </nav>
  )
}
