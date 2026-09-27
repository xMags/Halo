import avatar from '../assets/user-avatar.png'
import { useDownloads } from '../downloads'
import { formatClock, videoIdTag } from '../format'
import { buildContinueWatching } from '../homeRows'
import { useNav, type Section } from '../nav'
import { useLibrary, useMe, useWatchStates } from '../queries'
import { ArtImage } from './ArtImage'
import { FluentIcon, type FluentGlyph } from './FluentIcon'

const ITEMS: Array<{ section: Section; label: string; glyph: FluentGlyph }> = [
  { section: 'home', label: 'Home', glyph: 'home' },
  { section: 'search', label: 'Search', glyph: 'search' },
  { section: 'library', label: 'Library', glyph: 'library' },
  { section: 'downloads', label: 'Downloads', glyph: 'downloads' },
  { section: 'settings', label: 'Settings', glyph: 'settings' },
]

/** How many in-progress titles the pane's shortcut list carries. */
const JUMP_LIMIT = 3

interface NavRailProps {
  /** Expanded (224px) or compact (48px, icons only). Owned by the shell. */
  open: boolean
  onToggle: () => void
}

/**
 * The navigation pane, drawn as the native WinUI Halo Desktop draws its
 * NavigationView: a pane toggle, the five sections under a MENU header, three
 * shortcuts back into whatever is half-watched, and the account at the foot.
 *
 * The toggle collapses the pane to its 48px compact strip, where only the
 * icons and the avatar remain and each row names itself in a tooltip. Jump
 * back in exists only while the pane is open, as it does natively.
 *
 * Detail and the sources sheet open on top of a section, so the stack *root* —
 * not the visible screen — decides which row is lit: opening a poster from
 * Home leaves Home lit, which is where Back goes.
 *
 * The shortcut rows print the episode tag parsed from the watch state's video
 * id, never the episode's name, so a permanently-mounted pane never issues
 * addon round-trips.
 */
export function NavRail({ open, onToggle }: NavRailProps) {
  const { section, push, setRoot } = useNav()
  const { data: me } = useMe()
  const { data: watchStates } = useWatchStates()
  const { data: library } = useLibrary()
  const { downloads } = useDownloads()

  const activeTransfers = downloads.filter(
    (item) => item.status === 'downloading' || item.status === 'queued',
  ).length

  const jump = open ? buildContinueWatching(watchStates, library).slice(0, JUMP_LIMIT) : []
  const role = me?.isAdmin ? 'ADMIN · HALO ACCOUNT' : 'HALO ACCOUNT'

  return (
    <nav className={`nav ${open ? '' : 'nav-compact'}`}>
      <button
        type="button"
        className="nav-toggle"
        title={open ? 'Close Navigation' : 'Open Navigation'}
        aria-expanded={open}
        onClick={onToggle}
      >
        <FluentIcon glyph="menu" />
      </button>

      <div className="nav-header">MENU</div>
      {ITEMS.map((item) => {
        const active = section === item.section
        return (
          <button
            key={item.section}
            type="button"
            className={`nav-item ${active ? 'nav-item-active' : ''}`}
            title={open ? undefined : item.label}
            aria-current={active ? 'page' : undefined}
            onClick={() => setRoot(item.section)}
          >
            <FluentIcon glyph={item.glyph} />
            <span className="spacer ellipsis">{item.label}</span>
            {item.section === 'downloads' && activeTransfers > 0 && (
              <span className="nav-badge">{activeTransfers}</span>
            )}
          </button>
        )
      })}

      {jump.length > 0 && (
        <>
          <div className="nav-header nav-header-jump">JUMP BACK IN</div>
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
                <span className="nav-lines">
                  <span className="jump-title ellipsis">{item.meta.name}</span>
                  <span className="jump-meta ellipsis">
                    {state ? jumpMeta(state.videoId, item.meta.id, state.positionSec, state.durationSec) : ''}
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
        title={open ? 'Server & account' : me?.username ?? 'Server & account'}
        onClick={() => setRoot('settings')}
      >
        <img className="account-avatar" src={avatar} alt="" draggable={false} />
        <span className="nav-lines account-lines">
          <span className="account-name ellipsis">{me?.username ?? ''}</span>
          <span className="account-role ellipsis">{role}</span>
        </span>
      </button>
    </nav>
  )
}

/** `S01E01 · 30:02 LEFT`, the native pane's second line. */
function jumpMeta(videoId: string, metaId: string, positionSec: number, durationSec: number): string {
  return `${videoIdTag(videoId, metaId)} · ${formatClock(durationSec - positionSec)} LEFT`
}
