import { LANGUAGE_OPTIONS, type AddonEntry } from '@halo/core'
import { useState } from 'react'
import mark from '../assets/halo-mark.png'
import { useBuildInfo } from '../about'
import { getServerUrl } from '../api'
import { Icon } from '../components/Icon'
import { Segmented } from '../components/Segmented'
import { Toggle } from '../components/Toggle'
import { initials } from '../format'
import { setLocalPrefs, useLocalPrefs } from '../localPrefs'
import {
  useAddons,
  useMe,
  usePatchAddon,
  usePatchGlobalAddon,
  useSetAddons,
  useSetGlobalAddons,
} from '../queries'
import { describeStatus, useServerStatus } from '../serverStatus'
import { setSettingsSection, useSettingsSection, type SettingsSection } from '../settingsSection'
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

const RAIL: Array<{ key: SettingsSection; label: string; hint: string }> = [
  { key: 'appearance', label: 'Appearance', hint: 'Theme' },
  { key: 'addons', label: 'Addons', hint: 'Catalogs, streams, subtitles' },
  { key: 'playback', label: 'Playback', hint: 'Decoding, autoplay, resume' },
  { key: 'subtitles', label: 'Subtitles', hint: 'Size, font, appearance' },
  { key: 'account', label: 'Server & account', hint: 'Sync, identity, version' },
]

export function Settings() {
  const section = useSettingsSection()
  const { data: build } = useBuildInfo()

  return (
    <div className="view">
      <div className="set-page">
        <div className="set-rail">
          <div className="set-rail-title">Settings</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            {RAIL.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`set-rail-btn ${section === item.key ? 'set-rail-btn-active' : ''}`}
                onClick={() => setSettingsSection(item.key)}
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

        <div className="set-form">
          {section === 'appearance' && <AppearanceSection />}
          {section === 'addons' && <AddonsSection />}
          {section === 'playback' && <PlaybackSection />}
          {section === 'subtitles' && <SubtitlesSection />}
          {section === 'account' && <AccountSection />}
        </div>
      </div>
    </div>
  )
}

function SectionHead({ title, sub }: { title: string; sub: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div className="set-h">{title}</div>
      <div className="set-sub">{sub}</div>
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
    <div className="set-section">
      <SectionHead
        title="Appearance"
        sub="Choose how Halo looks. System follows your Windows setting."
      />
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
    </div>
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

function AddonsSection() {
  const { data: addons } = useAddons()
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
      // Own entries always carry their URL (the caller sent it) — only global
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
   * within one scope reorder against each other — the server keeps the global
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
    // A hidden addon comes back with a stripped manifest, so the flag is the
    // only way to know the switch should still be drawn.
    const hasCatalogs = item.manifest.catalogs.length > 0 || item.hideCatalogs

    return (
      <div
        key={item.id}
        className={`addon-row ${dragKey === item.id ? 'addon-row-dragging' : ''} ${
          overKey === item.id ? 'addon-row-over' : ''
        }`}
        draggable={editable}
        onDragStart={() => setDragKey(item.id)}
        onDragEnd={() => {
          setDragKey(null)
          setOverKey(null)
        }}
        onDragOver={(event) => {
          if (!editable || !dragKey || dragKey === item.id) return
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
          title={editable ? 'Drag to change priority' : 'Managed by the server admin'}
        >
          <Icon name="reorder" size={12} />
        </span>
        <span className="addon-tile">{initials(item.manifest.name)}</span>
        <span style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: 9, minWidth: 0 }}>
            <span className="addon-name ellipsis">{item.manifest.name}</span>
            <span className="mono">v{item.manifest.version}</span>
            <span className={`addon-badge ${scope === 'yours' ? 'addon-badge-yours' : ''}`}>
              {scope === 'yours' ? 'YOURS' : 'GLOBAL'}
            </span>
          </span>
          <span className="addon-provides ellipsis">{providesLine(item)}</span>
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
        {editable ? (
          <button
            type="button"
            className="icon-btn icon-btn-bare icon-btn-28"
            title="Remove addon"
            onClick={() => remove(item, scope)}
          >
            <Icon name="trash" size={14} />
          </button>
        ) : (
          <span
            style={{ display: 'flex', color: 'var(--t4)', padding: 6 }}
            title="Managed by the server admin"
          >
            <Icon name="lock" size={14} />
          </span>
        )}
      </div>
    )
  }

  return (
    <div className="set-section">
      <SectionHead
        title="Addons"
        sub="Catalogs, streams and subtitles come from these. Order sets priority."
      />
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
          disabled={adding || !url.trim()}
          onClick={() => void add()}
        >
          {adding ? 'Adding…' : 'Add'}
        </button>
      </div>
      {addError && <div className="error-text">{addError}</div>}

      {all.length === 0 ? (
        <div className="set-sub">No addons yet — paste a Stremio-compatible manifest URL above.</div>
      ) : (
        <div className="list-card">
          {globalAddons.map((item) => row(item, 'global'))}
          {userAddons.map((item) => row(item, 'yours'))}
        </div>
      )}
      {globalAddons.length > 0 && !isAdmin && (
        <div className="set-sub" style={{ fontSize: 13 }}>
          Global addons are installed for everyone by this server&apos;s admin.
        </div>
      )}
    </div>
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
    <div className="set-section">
      <SectionHead
        title="Playback"
        sub="Applied to every stream mpv opens. Changes take effect on the next file."
      />
      <div className="list-card">
        <div className="list-row">
          <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="opt-label">Default audio language</span>
            <span className="opt-hint">Picked when the file offers a match</span>
          </span>
          <LanguageSelect
            value={settings.preferredAudioLang}
            noneLabel="First track"
            onChange={(value) => updateSettings.mutate({ preferredAudioLang: value })}
          />
        </div>
        <div className="list-row">
          <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="opt-label">Default subtitles</span>
            <span className="opt-hint">Falls back to addon results</span>
          </span>
          <LanguageSelect
            value={settings.preferredSubtitleLang}
            noneLabel="Off"
            onChange={(value) => updateSettings.mutate({ preferredSubtitleLang: value })}
          />
        </div>
        <div className="list-row">
          <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="opt-label">Autoplay next episode</span>
            <span className="opt-hint">Prefetched, so the handoff needs no addon round-trip</span>
          </span>
          <Toggle
            label="Autoplay next episode"
            on={settings.autoplayNextEpisode ?? true}
            onChange={(next) => updateSettings.mutate({ autoplayNextEpisode: next })}
          />
        </div>
        <div className="list-row">
          <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="opt-label">Resume where I left off</span>
            <span className="opt-hint">Ignored under 30 seconds watched</span>
          </span>
          <Toggle
            label="Resume where I left off"
            on={prefs.resumePlayback}
            onChange={(next) => setLocalPrefs({ resumePlayback: next })}
          />
        </div>
        <div className="list-row">
          <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="opt-label">Hardware decoding</span>
            <span className="opt-hint">mpv hwdec — this machine only, never synced</span>
          </span>
          <Toggle
            label="Hardware decoding"
            on={prefs.hardwareDecoding}
            onChange={(next) => setLocalPrefs({ hardwareDecoding: next })}
          />
        </div>
        <div className="list-row">
          <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span className="opt-label">
              Discord Rich Presence <span className="soon-chip">SOON</span>
            </span>
            <span className="opt-hint">Shares the title and episode you are watching</span>
          </span>
          <Toggle label="Discord Rich Presence" on={false} disabled onChange={() => undefined} />
        </div>
      </div>
    </div>
  )
}

/* ── Subtitles ───────────────────────────────────────────────────────────── */

/** Ring offsets for the preview's fake outline, per outline step. */
const OUTLINE_PX: Record<string, number> = { none: 0, thin: 1, normal: 2, thick: 3 }

/**
 * The bundled libass families have no webview equivalent, so the preview maps
 * each onto the closest face the WebView can actually draw. It is an
 * approximation on purpose — the real render is mpv, and these controls apply
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

  const ringPx = OUTLINE_PX[outline] ?? 2
  const ring = ringPx
    ? [
        [-1, -1],
        [0, -1],
        [1, -1],
        [-1, 0],
        [1, 0],
        [-1, 1],
        [0, 1],
        [1, 1],
      ].map(([x, y]) => `${x! * ringPx}px ${y! * ringPx}px 0 #000`)
    : []
  if (shadow) ring.push('2px 2px 3px rgba(0,0,0,.72)')

  return (
    <div className="set-section">
      <SectionHead title="Subtitles" sub="Appearance applies live during playback." />
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

          <div className="sub-row" style={{ marginTop: 16 }}>
            <span className="opt-label">Font</span>
            <Segmented
              even
              options={SUBTITLE_FONTS.map((font) => ({
                value: font.family ?? '',
                label: font.label,
              }))}
              value={settings.subtitleFontFamily ?? ''}
              onChange={(value) =>
                updateSettings.mutate({ subtitleFontFamily: value || undefined })
              }
            />
          </div>

          <div className="sub-row" style={{ marginTop: 10 }}>
            <span className="opt-label">Outline</span>
            <Segmented
              even
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

        <div className="sub-preview">
          <div className="sub-preview-scrim" />
          <div className="sub-preview-kicker">PREVIEW</div>
          <div
            className="sub-preview-caption"
            style={{
              fontFamily: previewFamily(settings.subtitleFontFamily),
              fontSize: Math.round(22 * (scale / 100)),
              textShadow: ring.length > 0 ? ring.join(',') : 'none',
            }}
          >
            The severed floor is not what it was.
          </div>
        </div>
      </div>
    </div>
  )
}

/* ── Server & account ────────────────────────────────────────────────────── */

function AccountSection() {
  const { data: me } = useMe()
  const { signOut, disconnect } = useSession()
  const { data: build } = useBuildInfo()
  const status = useServerStatus()

  const connected = status.state === 'connected'

  return (
    <div className="set-section">
      <SectionHead
        title="Server & account"
        sub="Library, watch progress and addon order sync through this server."
      />

      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 13, padding: 14 }}>
        <span className="avatar avatar-40">{me ? initials(me.username) : '··'}</span>
        <span className="spacer" style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span style={{ fontSize: 16, fontWeight: 600 }}>{me?.username ?? '…'}</span>
          <span className="mono">
            {me?.isAdmin ? 'OWNER · ALL LIBRARIES' : 'MEMBER · YOUR LIBRARY'}
          </span>
        </span>
        <span className={`status-pill ${connected ? '' : 'status-pill-off'}`}>
          {connected ? 'CONNECTED' : status.state.toUpperCase()}
        </span>
      </div>

      <div className="list-card">
        {[
          // The full URL, not just the host: http vs https is the difference
          // between a working self-hosted server and a confusing failure.
          { label: 'Server', value: getServerUrl() ?? status.host, accent: false },
          { label: 'Signed in as', value: me?.username ?? '…', accent: false },
          { label: 'Status', value: describeStatus(status), accent: connected },
          {
            label: 'Version',
            value: `v${build?.app ?? '…'} · mpv ${build?.mpv ?? '…'}`,
            accent: false,
          },
        ].map((row) => (
          <div key={row.label} className="list-row">
            <span className="spacer opt-label">{row.label}</span>
            <span
              className="mono"
              style={{ fontSize: 13, color: row.accent ? 'var(--su)' : 'var(--t2)' }}
            >
              {row.value}
            </span>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 9 }}>
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
    </div>
  )
}
