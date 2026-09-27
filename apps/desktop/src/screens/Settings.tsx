import { DEFAULT_ADDON_URLS, LANGUAGE_OPTIONS, type AddonEntry } from '@halo/core'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import mark from '../assets/halo-mark.png'
import avatar from '../assets/user-avatar.png'
import { useBuildInfo } from '../about'
import { getServerUrl } from '../api'
import { Icon } from '../components/Icon'
import { Segmented } from '../components/Segmented'
import { Toggle } from '../components/Toggle'
import { initials } from '../format'
import { setLocalPrefs, useLocalPrefs } from '../localPrefs'
import { subtitlePreviewMetrics } from '../playerLogic'
import {
  useAddons,
  useMe,
  usePatchAddon,
  usePatchGlobalAddon,
  useSetAddons,
  useSetGlobalAddons,
} from '../queries'
import { describeStatus, useServerStatus } from '../serverStatus'
import { RAIL_ANCHOR_FRACTION, activeSettingsSection, isScrolledToEnd } from '../settingsRail'
import {
  SETTINGS_SECTIONS,
  clearSettingsSectionRequest,
  useSettingsSectionRequest,
  type SettingsSection,
} from '../settingsSection'
import { useSession } from '../session'
import { useSettings, useUpdateSettings } from '../settings'
import {
  SUBTITLE_FONTS,
  SUBTITLE_OUTLINES,
  SUBTITLE_SCALE_DEFAULT,
  SUBTITLE_SCALE_MAX,
  SUBTITLE_SCALE_MIN,
  SUBTITLE_SCALE_STEP,
} from '../subtitleStyle'
import type { ThemeChoice } from '../theme'
import { subtitleCaptionShadow } from './player/SubtitlePreview'

const RAIL: Array<{ key: SettingsSection; label: string; hint: string }> = [
  { key: 'appearance', label: 'Appearance', hint: 'Theme' },
  { key: 'addons', label: 'Addons', hint: 'Catalogs, streams, subtitles' },
  { key: 'playback', label: 'Playback', hint: 'Decoding, autoplay, resume' },
  { key: 'subtitles', label: 'Subtitles', hint: 'Size, font, appearance' },
  { key: 'account', label: 'Server & account', hint: 'Sync, identity, version' },
]

function sectionElement(form: HTMLElement | null, key: SettingsSection): HTMLElement | null {
  return form?.querySelector<HTMLElement>(`[data-settings-section="${key}"]`) ?? null
}

/**
 * One scrolling form beside a fixed rail, as the native Settings page is. The
 * rail does not switch pages: it lights the section being read and jumps to
 * a section when clicked.
 */
export function Settings() {
  const { data: build } = useBuildInfo()
  const request = useSettingsSectionRequest()
  const scroller = useRef<HTMLDivElement>(null)
  const form = useRef<HTMLDivElement>(null)
  /**
   * The section last jumped to. It is held at the top while content above it
   * is still arriving, until the reader scrolls for themselves.
   */
  const pinned = useRef<SettingsSection | null>(null)
  const [active, setActive] = useState<SettingsSection>('appearance')

  const updateActive = useCallback(() => {
    const view = scroller.current
    if (!view || view.clientHeight <= 0) return
    const viewTop = view.getBoundingClientRect().top
    const tops = SETTINGS_SECTIONS.map((key) => {
      const section = sectionElement(form.current, key)
      return section ? section.getBoundingClientRect().top - viewTop : Number.POSITIVE_INFINITY
    })
    const atEnd = isScrolledToEnd(view.scrollTop, view.scrollHeight, view.clientHeight)
    const index = activeSettingsSection(tops, view.clientHeight * RAIL_ANCHOR_FRACTION, atEnd)
    setActive(SETTINGS_SECTIONS[index] ?? 'appearance')
  }, [])

  const jumpTo = useCallback(
    (key: SettingsSection) => {
      const view = scroller.current
      const section = sectionElement(form.current, key)
      if (!view || !section) return
      // An immediate jump, as the native rail makes.
      view.scrollTop += section.getBoundingClientRect().top - view.getBoundingClientRect().top
      pinned.current = key
      updateActive()
    },
    [updateActive],
  )

  const releasePin = () => {
    pinned.current = null
  }

  // A deep link asks for a section. Taken once the form is laid out, so it
  // lands whether Settings was already open or is opening because of it.
  useLayoutEffect(() => {
    if (!request) return
    jumpTo(request)
    clearSettingsSectionRequest()
  }, [request, jumpTo])

  // Sections move without any scrolling when content above them finishes
  // loading, as the addon list does, and the visible height changes with the
  // window. Either can change which section is being read, and a load would
  // carry a section just jumped to away from the top.
  useEffect(() => {
    const view = scroller.current
    const content = form.current
    if (!view || !content) return
    const observer = new ResizeObserver(() => {
      if (pinned.current) jumpTo(pinned.current)
      else updateActive()
    })
    observer.observe(view)
    observer.observe(content)
    return () => observer.disconnect()
  }, [jumpTo, updateActive])

  return (
    <div className="view view-col">
      <div className="set-page">
        <div className="set-rail">
          <div className="set-rail-title">Settings</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            {RAIL.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`set-rail-btn ${active === item.key ? 'set-rail-btn-active' : ''}`}
                aria-current={active === item.key ? 'location' : undefined}
                onClick={() => jumpTo(item.key)}
              >
                <span className="set-rail-label">{item.label}</span>
                <span className="set-rail-hint">{item.hint}</span>
              </button>
            ))}
          </div>

          <div className="about-card">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <img src={mark} alt="" style={{ width: 20, height: 20 }} />
              <span style={{ fontSize: 14, fontWeight: 600 }}>Halo Desktop</span>
            </div>
            <button
              type="button"
              className="btn-link soon"
              style={{ alignSelf: 'flex-start', paddingInline: 0 }}
              title="Coming soon"
            >
              Check for updates
            </button>
            <div className="about-rule" />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }} className="mono">
              <span>VERSION {build?.app ?? '…'}</span>
              <span>
                MPV {build?.mpv ?? '…'} · TAURI {build?.tauri ?? '…'}
              </span>
            </div>
          </div>
        </div>

        <div
          ref={scroller}
          className="set-scroll"
          onScroll={updateActive}
          onWheel={releasePin}
          onPointerDown={releasePin}
          onKeyDown={releasePin}
        >
          <div ref={form} className="set-form">
            <AppearanceSection />
            <AddonsSection />
            <PlaybackSection />
            <SubtitlesSection />
            <AccountSection />
          </div>
        </div>
      </div>
    </div>
  )
}

/** A section of the form: its heading, then its controls at the native spacing. */
function Section({
  id,
  gap,
  title,
  sub,
  children,
}: {
  id: SettingsSection
  gap: number
  title: string
  sub: string
  children: ReactNode
}) {
  return (
    <section className="set-section" data-settings-section={id} style={{ gap }}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <div className="set-h">{title}</div>
        <div className="set-sub">{sub}</div>
      </div>
      {children}
    </section>
  )
}

/** A labelled row in a settings card, with its control at the far end. (Native: SettingRow.) */
function SettingRow({ label, hint, children }: { label: ReactNode; hint: string; children: ReactNode }) {
  return (
    <div className="set-row">
      <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <span className="opt-label">{label}</span>
        <span className="set-row-hint">{hint}</span>
      </span>
      {children}
    </div>
  )
}

/* ── Appearance ──────────────────────────────────────────────────────────── */

/**
 * The three theme cards are drawn as miniature windows rather than swatches:
 * a palette only means something once you can see where it lands, and System
 * splits the card down the middle to say it is both.
 */
const THEME_CARDS: Array<{
  key: ThemeChoice
  label: string
  frame: string
  bg: string
  tile: string
  line: string
  accent: string
  split?: boolean
}> = [
  {
    key: 'light',
    label: 'Light',
    frame: 'rgba(0,0,0,.133)',
    bg: '#ffffff',
    tile: '#E6E6EA',
    line: '#C8C8CE',
    accent: '#005FB8',
  },
  {
    key: 'dark',
    label: 'Dark',
    frame: 'rgba(255,255,255,.149)',
    bg: '#202024',
    tile: '#313138',
    line: '#54545C',
    accent: '#60CDFF',
  },
  {
    key: 'system',
    label: 'System',
    frame: 'rgba(255,255,255,.149)',
    bg: '#202024',
    tile: '#E6E6EA',
    line: '#54545C',
    accent: '#60CDFF',
    split: true,
  },
]

function AppearanceSection() {
  const prefs = useLocalPrefs()

  return (
    <Section
      id="appearance"
      gap={14}
      title="Appearance"
      sub="Choose how Halo looks. System follows your Windows setting."
    >
      <div style={{ display: 'flex', gap: 12 }}>
        {THEME_CARDS.map((card) => (
          <button
            key={card.key}
            type="button"
            className={`theme-card ${prefs.theme === card.key ? 'theme-card-active' : ''}`}
            aria-pressed={prefs.theme === card.key}
            onClick={() => setLocalPrefs({ theme: card.key })}
          >
            <span
              className="theme-mini"
              style={{ background: card.bg, border: `1px solid ${card.frame}` }}
            >
              {card.split && <span className="theme-mini-split" />}
              <span className="theme-mini-tile" style={{ background: card.tile }} />
              <span className="theme-mini-lines">
                <span style={{ background: card.line }} />
                <span style={{ background: card.line, width: 46 }} />
                <span style={{ background: card.accent, width: 34 }} />
              </span>
            </span>
            <span style={{ fontSize: 15 }}>{card.label}</span>
          </button>
        ))}
      </div>
    </Section>
  )
}

/* ── Addons ──────────────────────────────────────────────────────────────── */

function providesLine(item: AddonEntry): string {
  return (
    [
      item.manifest.catalogs.length > 0 ? `${item.manifest.catalogs.length} catalogs` : null,
      ...item.manifest.resources.map((r) => (typeof r === 'string' ? r : r.name)),
    ]
      .filter(Boolean)
      .join(' · ') ||
    item.manifest.description ||
    'No declared resources'
  )
}

/**
 * Cinemeta and OpenSubtitles, which every Halo app installs so it works
 * before anything is added. They stay in your own list: without them there
 * are no catalogs to browse and no subtitles.
 */
function isBuiltInAddon(item: AddonEntry, scope: 'yours' | 'global'): boolean {
  if (scope !== 'yours' || item.transportUrl === undefined) return false
  return (DEFAULT_ADDON_URLS as readonly string[]).includes(item.transportUrl)
}

function AddonsSection() {
  const { data: addons, isPending, isError, refetch } = useAddons()
  const { data: me } = useMe()
  const setAddons = useSetAddons()
  const setGlobalAddons = useSetGlobalAddons()
  const patchAddon = usePatchAddon()
  const patchGlobalAddon = usePatchGlobalAddon()

  const [url, setUrl] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)

  const isAdmin = me?.isAdmin ?? false
  const globalAddons = addons?.global ?? []
  const userAddons = addons?.user ?? []
  const all = [...globalAddons, ...userAddons]

  const add = async () => {
    const transportUrl = url.trim()
    if (!transportUrl || adding) return
    if (all.some((a) => a.transportUrl === transportUrl)) {
      setAddError('Already installed.')
      return
    }
    setAdding(true)
    setAddError(null)
    try {
      // Own entries always carry their URL (the caller sent it); only global
      // entries are redacted, and only for non-admins, who never get here.
      await setAddons.mutateAsync([...userAddons.map((a) => a.transportUrl!), transportUrl])
      setUrl('')
    } catch (err) {
      setAddError(err instanceof Error ? err.message : 'Invalid manifest URL')
    } finally {
      setAdding(false)
    }
  }

  const remove = (item: AddonEntry, scope: 'yours' | 'global') => {
    if (isBuiltInAddon(item, scope)) return
    const question =
      scope === 'global'
        ? `Remove “${item.manifest.name}” for every user?`
        : `Remove “${item.manifest.name}”?`
    if (!window.confirm(question)) return
    const list = scope === 'global' ? globalAddons : userAddons
    const urls = list.filter((a) => a.transportUrl !== item.transportUrl).map((a) => a.transportUrl!)
    void (scope === 'global' ? setGlobalAddons.mutateAsync(urls) : setAddons.mutateAsync(urls))
  }

  /**
   * Order is priority: the first addon that can answer a resolution request
   * wins, so dragging a row is a real setting, not decoration. Only rows
   * within one scope reorder against each other: the server keeps the global
   * and per-user lists apart, and they resolve in that order.
   */
  const reorder = (scope: 'yours' | 'global', fromId: string, toId: string) => {
    const list = scope === 'global' ? globalAddons : userAddons
    const from = list.findIndex((a) => a.id === fromId)
    const to = list.findIndex((a) => a.id === toId)
    if (from < 0 || to < 0 || from === to) return
    const next = [...list]
    next.splice(to, 0, ...next.splice(from, 1))
    const urls = next.map((a) => a.transportUrl!)
    void (scope === 'global' ? setGlobalAddons.mutateAsync(urls) : setAddons.mutateAsync(urls))
  }

  const row = (item: AddonEntry, scope: 'yours' | 'global') => {
    const editable = scope === 'yours' || isAdmin
    const patch = scope === 'global' ? patchGlobalAddon : patchAddon
    // The admin installs this same addon for everyone, so the server skips
    // this copy. It stays listed, greyed, because it comes back into use if
    // the global entry is removed; until then its order and catalogs mean
    // nothing.
    const shadowed = scope === 'yours' && item.providedGlobally === true
    const builtIn = isBuiltInAddon(item, scope)
    const movable = editable && !shadowed
    // A hidden addon comes back with a stripped manifest, so the flag is the
    // only way to know the switch should still be drawn.
    const hasCatalogs = !shadowed && (item.manifest.catalogs.length > 0 || item.hideCatalogs)

    let lockTitle: string | null = null
    if (!editable) lockTitle = 'Managed by the server admin'
    else if (builtIn) lockTitle = 'Built into Halo, so it cannot be removed'

    return (
      <div
        key={item.id}
        className={`addon-row ${dragKey === item.id ? 'addon-row-dragging' : ''} ${
          overKey === item.id ? 'addon-row-over' : ''
        } ${shadowed ? 'addon-row-shadowed' : ''}`}
        draggable={movable}
        onDragStart={() => setDragKey(item.id)}
        onDragEnd={() => {
          setDragKey(null)
          setOverKey(null)
        }}
        onDragOver={(event) => {
          if (!movable || !dragKey || dragKey === item.id) return
          event.preventDefault()
          setOverKey(item.id)
        }}
        onDrop={(event) => {
          event.preventDefault()
          if (dragKey) reorder(scope, dragKey, item.id)
          setDragKey(null)
          setOverKey(null)
        }}
      >
        <span
          className="drag-handle"
          title={
            shadowed
              ? 'Not used while the global copy is installed'
              : editable
                ? 'Drag to change priority'
                : 'Managed by the server admin'
          }
        >
          <Icon name="reorder" size={12} />
        </span>
        <span className="addon-tile">{initials(item.manifest.name)}</span>
        <span className="addon-text">
          <span style={{ display: 'flex', alignItems: 'center', gap: 9, minWidth: 0 }}>
            <span className="addon-name ellipsis">{item.manifest.name}</span>
            <span className="mono">v{item.manifest.version}</span>
            <span className={`addon-badge ${scope === 'yours' ? 'addon-badge-yours' : ''}`}>
              {scope === 'yours' ? 'YOURS' : 'GLOBAL'}
            </span>
          </span>
          <span className="addon-provides ellipsis">
            {shadowed ? 'Provided globally, so this copy is not used' : providesLine(item)}
          </span>
        </span>
        {hasCatalogs ? (
          <Toggle
            label={`Show ${item.manifest.name} catalogs on Home`}
            on={!item.hideCatalogs}
            disabled={!editable}
            onChange={(next) => patch.mutate({ addonId: item.id, hideCatalogs: !next })}
          />
        ) : (
          <span />
        )}
        {lockTitle === null ? (
          <button
            type="button"
            className="icon-btn icon-btn-bare icon-btn-28"
            title="Remove addon"
            onClick={() => remove(item, scope)}
          >
            <Icon name="trash" size={14} />
          </button>
        ) : (
          <span style={{ display: 'flex', color: 'var(--t4)', padding: 6 }} title={lockTitle}>
            <Icon name="lock" size={14} />
          </span>
        )}
      </div>
    )
  }

  // A failed refresh keeps showing the list it already had; only a first
  // load with nothing to show is an error.
  let body: ReactNode
  if (isPending) {
    body = (
      <div className="set-state">
        <span className="spinner spinner-32" />
        <span>Refreshing addons…</span>
      </div>
    )
  } else if (isError && !addons) {
    body = (
      <div className="set-state">
        <span className="error-text">Addons could not be refreshed.</span>
        <button type="button" className="btn" onClick={() => void refetch()}>
          Retry
        </button>
      </div>
    )
  } else if (all.length === 0) {
    body = <div className="set-state set-state-quiet">No addons are installed for this account.</div>
  } else {
    body = (
      <div className="list-card">
        {globalAddons.map((item) => row(item, 'global'))}
        {userAddons.map((item) => row(item, 'yours'))}
      </div>
    )
  }

  return (
    <Section
      id="addons"
      gap={14}
      title="Addons"
      sub="Catalogs, streams and subtitles come from these. Order sets priority."
    >
      <div style={{ display: 'flex', gap: 8 }}>
        <input
          className="field spacer"
          placeholder="https://addon.example.com/manifest.json"
          value={url}
          spellCheck={false}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void add()
          }}
        />
        <button
          type="button"
          className="btn-accent"
          style={{ paddingInline: 18 }}
          disabled={adding || !url.trim()}
          onClick={() => void add()}
        >
          {adding ? 'Adding…' : 'Add'}
        </button>
      </div>
      {addError && <div className="error-text">{addError}</div>}
      {body}
    </Section>
  )
}

/* ── Playback ────────────────────────────────────────────────────────────── */

function LanguageSelect({
  value,
  noneLabel,
  onChange,
}: {
  value: string | undefined
  noneLabel: string
  onChange: (value: string | undefined) => void
}) {
  return (
    <select
      className="select"
      style={{ width: 148 }}
      value={value ?? 'none'}
      onChange={(e) => onChange(e.target.value === 'none' ? undefined : e.target.value)}
    >
      <option value="none">{noneLabel}</option>
      {LANGUAGE_OPTIONS.map((lang) => (
        <option key={lang.code} value={lang.code}>
          {lang.label}
        </option>
      ))}
    </select>
  )
}

function PlaybackSection() {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()
  const prefs = useLocalPrefs()

  return (
    <Section
      id="playback"
      gap={10}
      title="Playback"
      sub="Applied to every stream mpv opens. Changes take effect on the next file."
    >
      <div className="list-card">
        <SettingRow label="Default audio language" hint="Picked when the file offers a match">
          <LanguageSelect
            value={settings.preferredAudioLang}
            noneLabel="First track"
            onChange={(value) => updateSettings.mutate({ preferredAudioLang: value })}
          />
        </SettingRow>
        <SettingRow label="Default subtitles" hint="Falls back to addon results">
          <LanguageSelect
            value={settings.preferredSubtitleLang}
            noneLabel="Off"
            onChange={(value) => updateSettings.mutate({ preferredSubtitleLang: value })}
          />
        </SettingRow>
        <SettingRow label="Autoplay next episode" hint="8 second countdown, cancellable">
          <Toggle
            label="Autoplay next episode"
            on={settings.autoplayNextEpisode ?? true}
            onChange={(next) => updateSettings.mutate({ autoplayNextEpisode: next })}
          />
        </SettingRow>
        <SettingRow label="Resume where I left off" hint="Ignored under 30 seconds watched">
          <Toggle
            label="Resume where I left off"
            on={prefs.resumePlayback}
            onChange={(next) => setLocalPrefs({ resumePlayback: next })}
          />
        </SettingRow>
        <SettingRow label="Hardware decoding" hint="mpv hwdec=auto-safe">
          <Toggle
            label="Hardware decoding"
            on={prefs.hardwareDecoding}
            onChange={(next) => setLocalPrefs({ hardwareDecoding: next })}
          />
        </SettingRow>
        <SettingRow
          label={
            <>
              Discord Rich Presence <span className="soon-chip">SOON</span>
            </>
          }
          hint="Shares the title and episode you are watching with Discord"
        >
          <Toggle label="Discord Rich Presence" on={false} disabled onChange={() => undefined} />
        </SettingRow>
      </div>
    </Section>
  )
}

/* ── Subtitles ───────────────────────────────────────────────────────────── */

/**
 * The bundled libass families have no webview equivalent, so the preview maps
 * each onto the closest face the WebView can actually draw. It is an
 * approximation on purpose: the real render is mpv, and these controls apply
 * there live.
 */
function previewFamily(family: string | undefined): string {
  if (family === 'Source Serif 4') return 'Georgia, "Times New Roman", serif'
  if (family === 'JetBrains Mono') return 'var(--mono)'
  return 'var(--sans)'
}

function SubtitlesSection() {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()
  const prefs = useLocalPrefs()

  const scale = settings.subtitleScalePercent ?? SUBTITLE_SCALE_DEFAULT
  const outline = settings.subtitleOutline ?? 'normal'
  const shadow = settings.subtitleShadow ?? true
  // The same arithmetic the player panel's preview uses, so both agree.
  const metrics = subtitlePreviewMetrics(scale, outline)

  return (
    <Section id="subtitles" gap={12} title="Subtitles" sub="Appearance applies live during playback.">
      <div className="sub-grid">
        <div className="card" style={{ display: 'flex', flexDirection: 'column' }}>
          <div className="sub-row sub-row-value">
            <span className="opt-label">Size</span>
            <input
              type="range"
              min={SUBTITLE_SCALE_MIN}
              max={SUBTITLE_SCALE_MAX}
              step={SUBTITLE_SCALE_STEP}
              value={scale}
              aria-label="Subtitle size"
              onChange={(e) =>
                updateSettings.mutate({ subtitleScalePercent: Number(e.target.value) })
              }
            />
            <span className="mono" style={{ color: 'var(--t2)', textAlign: 'right' }}>
              {scale}%
            </span>
          </div>
          <div className="ticks" style={{ margin: '-4px 44px 0 92px' }}>
            <span>{SUBTITLE_SCALE_MIN}</span>
            <span>100</span>
            <span>{SUBTITLE_SCALE_MAX}</span>
          </div>

          {/* One grid for both pickers, so the Outline tray takes the Font
              tray's width as the native page binds it to. */}
          <div className="sub-trays">
            <span className="opt-label">Font</span>
            <Segmented
              compact
              options={SUBTITLE_FONTS.map((font) => ({
                value: font.family ?? '',
                label: font.label,
              }))}
              value={settings.subtitleFontFamily ?? ''}
              onChange={(value) =>
                updateSettings.mutate({ subtitleFontFamily: value || undefined })
              }
            />
            <span className="opt-label">Outline</span>
            <Segmented
              compact
              options={SUBTITLE_OUTLINES.map((o) => ({ value: o.key, label: o.label }))}
              value={outline}
              onChange={(value) => updateSettings.mutate({ subtitleOutline: value })}
            />
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 10 }}>
            <span className="spacer opt-label">Shadow</span>
            <Toggle
              label="Subtitle shadow"
              on={shadow}
              onChange={(next) => updateSettings.mutate({ subtitleShadow: next })}
            />
          </div>

          <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 3 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
              <span className="spacer opt-label">Track styling</span>
              <Toggle
                label="Keep a styled track's own look"
                on={prefs.subtitleTrackStyling}
                onChange={(next) => setLocalPrefs({ subtitleTrackStyling: next })}
              />
            </div>
            <div className="opt-hint">
              On, a styled track keeps its own look. Off, these settings win.
            </div>
          </div>
        </div>

        {/* A stand-in for a video frame rather than a themed surface, because
            a video frame is what subtitles are drawn over. It stays dark in
            both themes so the caption keeps the contrast playback gives it. */}
        <div className="sub-preview">
          <div className="sub-preview-scrim" />
          <div className="sub-preview-kicker">PREVIEW</div>
          <div
            className="sub-preview-caption"
            style={{
              fontFamily: previewFamily(settings.subtitleFontFamily),
              fontSize: metrics.fontSize,
              textShadow: subtitleCaptionShadow(metrics, shadow),
            }}
          >
            Subtitle preview text
          </div>
        </div>
      </div>
    </Section>
  )
}

/* ── Server & account ────────────────────────────────────────────────────── */

/** The username with its first letter raised, as the native account card shows it. */
function displayName(username: string): string {
  return username.charAt(0).toUpperCase() + username.slice(1)
}

function AccountSection() {
  const { data: me } = useMe()
  const { signOut, disconnect } = useSession()
  const { data: build } = useBuildInfo()
  const status = useServerStatus()

  const statusLine = describeStatus(status)
  const statusTone =
    status.state === 'connected' ? 'connected' : status.state === 'probing' ? 'checking' : 'down'
  const statusColor =
    statusTone === 'connected' ? 'var(--su)' : statusTone === 'checking' ? 'var(--t3)' : 'var(--cr)'

  const rows: Array<{ label: string; value: string; color?: string }> = [
    // The full URL, not just the host: http vs https is the difference
    // between a working self-hosted server and a confusing failure.
    { label: 'Server', value: getServerUrl() ?? status.host },
    { label: 'Signed in as', value: me?.username ?? '…' },
    { label: 'Status', value: statusLine, color: statusColor },
    { label: 'Version', value: `v${build?.app ?? '…'} · mpv ${build?.mpv ?? '…'}` },
  ]

  return (
    <Section
      id="account"
      gap={12}
      title="Server & account"
      sub="Library, watch progress and addon order sync through this server."
    >
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 13, padding: 14 }}>
        <img className="set-avatar" src={avatar} alt="" draggable={false} />
        <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span style={{ fontSize: 14, fontWeight: 600 }}>{me ? displayName(me.username) : '…'}</span>
          <span className="mono">{me?.isAdmin ? 'ADMIN · HALO ACCOUNT' : 'HALO ACCOUNT'}</span>
        </span>
        <span className={`status-pill status-pill-${statusTone}`}>{statusLine}</span>
      </div>

      <div className="list-card">
        {rows.map((row) => (
          <div key={row.label} className="list-row">
            <span className="spacer opt-label">{row.label}</span>
            <span className="mono" style={{ fontSize: 13, color: row.color ?? 'var(--t2)' }}>
              {row.value}
            </span>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 10 }}>
        <button
          type="button"
          className="btn-danger"
          onClick={() => {
            if (window.confirm('Sign out of this server?')) signOut()
          }}
        >
          Sign out
        </button>
        <button
          type="button"
          className="btn"
          title="Forget this server and point Halo somewhere else"
          onClick={() => {
            if (window.confirm('Forget this server and start over?')) disconnect()
          }}
        >
          Switch server
        </button>
      </div>
    </Section>
  )
}
