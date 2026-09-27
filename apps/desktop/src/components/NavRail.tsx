import { useLayoutEffect, useRef } from 'react'
import { getServerUrl } from '../api'
import { useDownloads } from '../downloads'
import { formatTimeLeft, initials } from '../format'
import { buildContinueWatching } from '../homeRows'
import { useNav, type Section } from '../nav'
import { useLibrary, useMe, useWatchStates } from '../queries'
import { ArtImage } from './ArtImage'
import { Icon, type IconName } from './Icon'

interface NavEntry {
  section: Section
  label: string
  icon: IconName
}

const MENU_ITEMS: NavEntry[] = [
  { section: 'home', label: 'Home', icon: 'home' },
  { section: 'search', label: 'Search', icon: 'search' },
  { section: 'library', label: 'Library', icon: 'library' },
  { section: 'downloads', label: 'Downloads', icon: 'downloads' },
]

/** WinUI pins Settings to the pane footer rather than the menu list. */
const SETTINGS_ITEM: NavEntry = { section: 'settings', label: 'Settings', icon: 'settings' }

/** How many in-progress titles the pane's shortcut list carries. */
const JUMP_LIMIT = 3

/** Matches `.nav-pill`'s height; the stretch is measured in pill lengths. */
const PILL_HEIGHT = 16

/**
 * The 224px navigation pane, laid out as WinUI's NavigationView in Left mode:
 * the four sections, three shortcuts back into whatever is half-watched, and a
 * footer holding Settings and the account.
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

  const itemRefs = useRef(new Map<Section, HTMLButtonElement>())
  const shownSection = useRef(section)

  // Runs before paint, so the new pill starts over the old row on the very
  // first frame instead of flashing at rest and then jumping back.
  useLayoutEffect(() => {
    const from = shownSection.current
    shownSection.current = section
    if (from === section) return
    const fromItem = itemRefs.current.get(from)
    const toItem = itemRefs.current.get(section)
    const pill = toItem?.querySelector<HTMLElement>('.nav-pill')
    if (!fromItem || !toItem || !pill) return
    slidePill(pill, fromItem.getBoundingClientRect().top - toItem.getBoundingClientRect().top)
  }, [section])

  const activeTransfers = downloads.filter(
    (item) => item.status === 'downloading' || item.status === 'queued',
  ).length

  const jump = buildContinueWatching(watchStates, library).slice(0, JUMP_LIMIT)
  const name = me?.username ?? 'Account'
  const server = serverHost(getServerUrl())

  const renderItem = (entry: NavEntry, badge?: number) => {
    const active = section === entry.section
    return (
      <button
        key={entry.section}
        ref={(el) => {
          if (el) itemRefs.current.set(entry.section, el)
          else itemRefs.current.delete(entry.section)
        }}
        type="button"
        className={`nav-item ${active ? 'nav-item-active' : ''}`}
        aria-current={active ? 'page' : undefined}
        onClick={() => setRoot(entry.section)}
      >
        {active && <span className="nav-pill" aria-hidden />}
        <Icon name={entry.icon} />
        <span className="spacer ellipsis">{entry.label}</span>
        {badge !== undefined && badge > 0 && <span className="nav-badge">{badge}</span>}
      </button>
    )
  }

  return (
    <nav className="nav">
      {MENU_ITEMS.map((entry) =>
        renderItem(entry, entry.section === 'downloads' ? activeTransfers : undefined),
      )}

      {jump.length > 0 && (
        <>
          <div className="nav-separator" role="separator" />
          <div className="nav-header">Jump back in</div>
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
                  <span className="nav-secondary ellipsis">
                    {state ? capitalize(formatTimeLeft(state.positionSec, state.durationSec)) : 'In progress'}
                  </span>
                </span>
              </button>
            )
          })}
        </>
      )}

      <div className="spacer" />

      <div className="nav-separator" role="separator" />
      {renderItem(SETTINGS_ITEM)}
      <button
        type="button"
        className="account-row"
        title="Server & account"
        onClick={() => setRoot('settings')}
      >
        <span className="avatar">{me ? initials(me.username) : '··'}</span>
        <span className="nav-lines">
          <span className="account-name ellipsis">{name}</span>
          <span className="nav-secondary ellipsis">{server ?? 'Halo account'}</span>
        </span>
      </button>
    </nav>
  )
}

/**
 * WinUI's selection change: the incoming pill starts over the old row,
 * stretches across the gap, then settles into place. Only the new row's pill
 * is animated, so an interrupted slide never strands an indicator on a row
 * that is no longer selected.
 */
function slidePill(pill: HTMLElement, offset: number) {
  if (offset === 0 || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const stretch = (Math.abs(offset) + PILL_HEIGHT) / PILL_HEIGHT
  pill.animate(
    [
      { transform: `translateY(${offset}px) scaleY(1)`, easing: 'cubic-bezier(0.9, 0.1, 1, 0.2)' },
      {
        transform: `translateY(${offset / 2}px) scaleY(${stretch})`,
        offset: 0.3,
        easing: 'cubic-bezier(0.1, 0.9, 0.2, 1)',
      },
      { transform: 'translateY(0) scaleY(1)' },
    ],
    { duration: 480 },
  )
}

/** The account row's second line: which server this session belongs to. */
function serverHost(url: string | null): string | null {
  if (!url) return null
  try {
    return new URL(url).host
  } catch {
    return null
  }
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}
