import { LANGUAGE_OPTIONS, type AddonEntry } from '@halo/core'
import { useState } from 'react'
import { useBuildInfo } from '../about'
import { getServerUrl } from '../api'
import { Icon } from '../components/Icon'
import { Slider } from '../components/Slider'
import { initials } from '../format'
import {
  useAddons,
  useMe,
  usePatchAddon,
  usePatchGlobalAddon,
  useSetAddons,
  useSetGlobalAddons,
} from '../queries'
import { describeStatus, useServerStatus } from '../serverStatus'
import { usePublishScreenTitle } from '../screenTitle'
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

const TABS = [
  { key: 'addons', label: 'Addons' },
  { key: 'playback', label: 'Playback' },
  { key: 'subtitles', label: 'Subtitles' },
  { key: 'server', label: 'Server & account' },
] as const
type Tab = (typeof TABS)[number]['key']

export function Settings() {
  const [tab, setTab] = useState<Tab>('addons')
  const { data: build } = useBuildInfo()

  usePublishScreenTitle('Settings', TABS.find((t) => t.key === tab)!.label.toUpperCase())

  return (
    <div className="set-layout">
      <div className="set-side">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          {TABS.map((item) => (
            <button
              key={item.key}
              type="button"
              className={`set-tab ${tab === item.key ? 'set-tab-active' : ''}`}
              onClick={() => setTab(item.key)}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="version-card">
          <div className="kicker" style={{ fontSize: 9, letterSpacing: '0.14em' }}>
            HALO DESKTOP
          </div>
          <div className="version-line">
            v{build?.app ?? '…'} · mpv {build?.mpv ?? '…'}
            <br />
            tauri {build?.tauri ?? '…'}
          </div>
        </div>
      </div>

      <div className="set-pane no-bar">
        {tab === 'addons' && <AddonsPane />}
        {tab === 'playback' && <PlaybackPane />}
        {tab === 'subtitles' && <SubtitlesPane />}
        {tab === 'server' && <ServerPane />}
      </div>
    </div>
  )
}

/* ── Addons ──────────────────────────────────────────────────────────────── */

function AddonsPane() {
  const { data: addons } = useAddons()
  const { data: me } = useMe()
  const setAddons = useSetAddons()
  const setGlobalAddons = useSetGlobalAddons()

  const isAdmin = me?.isAdmin ?? false
  const globalAddons = addons?.global ?? []
  const userAddons = addons?.user ?? []

  return (
    <div className="set-form">
      <div className="pane-title">Addons</div>
      <div className="pane-hint">
        Catalogs, streams and subtitles come from these. Order sets priority.
      </div>

      <AddonList
        addons={userAddons}
        allAddons={[...globalAddons, ...userAddons]}
        scope="yours"
        emptyHint="No addons yet — paste a Stremio-compatible manifest URL above."
        onSave={(urls) => setAddons.mutateAsync(urls)}
      />

      {isAdmin ? (
        <>
          <div className="pane-title" style={{ marginTop: 30 }}>
            Global addons
          </div>
          <div className="pane-hint">
            Admin-managed. Anything here is installed for every user on this server.
          </div>
          <AddonList
            addons={globalAddons}
            allAddons={[...globalAddons, ...userAddons]}
            scope="global"
            emptyHint="No global addons yet."
            onSave={(urls) => setGlobalAddons.mutateAsync(urls)}
          />
        </>
      ) : (
        globalAddons.length > 0 && (
          <>
            <div className="pane-title" style={{ marginTop: 30 }}>
              Global addons
            </div>
            <div className="pane-hint">Installed for everyone by this server&apos;s admin.</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 16 }}>
              {globalAddons.map((item) => (
                <div key={item.id} className="row-card">
                  <AddonIdentity item={item} scope="global" />
                  <span style={{ color: 'var(--text-dim)' }} title="Managed by the server admin">
                    <Icon name="lock" size={14} />
                  </span>
                </div>
              ))}
            </div>
          </>
        )
      )}
    </div>
  )
}

function AddonIdentity({ item, scope }: { item: AddonEntry; scope: 'yours' | 'global' }) {
  const provides = [
    item.manifest.catalogs.length > 0 ? `${item.manifest.catalogs.length} catalogs` : null,
    ...item.manifest.resources.map((r) => (typeof r === 'string' ? r : r.name)),
  ]
    .filter(Boolean)
    .join(' · ')

  return (
    <>
      <div className="addon-tile">{initials(item.manifest.name)}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div className="addon-name ellipsis">{item.manifest.name}</div>
          <div className="addon-version">v{item.manifest.version}</div>
          <div className={`badge ${scope === 'global' ? 'badge-accent' : ''}`}>
            {scope === 'global' ? 'GLOBAL' : 'YOURS'}
          </div>
        </div>
        <div className="addon-provides ellipsis">
          {provides || item.manifest.description || 'No declared resources'}
        </div>
      </div>
    </>
  )
}

/**
 * One addon list (the user's own or, for an admin, the global one) with
 * add-by-URL, the hide-catalogs toggle and removal. Only transport URLs are
 * sent, in priority order — the server diffs against what it has stored and
 * fetches manifests for new URLs only.
 */
function AddonList({
  addons,
  allAddons,
  scope,
  emptyHint,
  onSave,
}: {
  addons: AddonEntry[]
  allAddons: AddonEntry[]
  scope: 'yours' | 'global'
  emptyHint: string
  onSave: (transportUrls: string[]) => Promise<unknown>
}) {
  const patchAddon = usePatchAddon()
  const patchGlobalAddon = usePatchGlobalAddon()
  const [url, setUrl] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)

  const patch = scope === 'global' ? patchGlobalAddon : patchAddon

  const add = async () => {
    const transportUrl = url.trim()
    if (!transportUrl || adding) return
    if (allAddons.some((a) => a.transportUrl === transportUrl)) {
      setAddError('Already installed.')
      return
    }
    setAdding(true)
    setAddError(null)
    try {
      // Own entries always carry their URL (the caller sent it) — only global
      // entries are redacted, and only for non-admins, who never get here.
      await onSave([...addons.map((a) => a.transportUrl!), transportUrl])
      setUrl('')
    } catch (err) {
      setAddError(err instanceof Error ? err.message : 'Invalid manifest URL')
    } finally {
      setAdding(false)
    }
  }

  const remove = (item: AddonEntry) => {
    const question =
      scope === 'global'
        ? `Remove “${item.manifest.name}” for every user?`
        : `Remove “${item.manifest.name}”?`
    if (!window.confirm(question)) return
    void onSave(
      addons.filter((a) => a.transportUrl !== item.transportUrl).map((a) => a.transportUrl!),
    )
  }

  return (
    <>
      <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
        <input
          className="field field-mono"
          style={{ padding: '10px 13px' }}
          placeholder="https://…/manifest.json"
          value={url}
          spellCheck={false}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void add()
          }}
        />
        <button
          type="button"
          className="btn-primary"
          style={{ borderRadius: 10 }}
          disabled={adding || !url.trim()}
          onClick={() => void add()}
        >
          {adding ? 'Adding…' : 'Add'}
        </button>
      </div>
      {addError && (
        <div className="error-text" style={{ marginTop: 8 }}>
          {addError}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 16 }}>
        {addons.length === 0 ? (
          <div className="pane-hint">{emptyHint}</div>
        ) : (
          addons.map((item) => (
            <div key={item.id} className="row-card">
              <AddonIdentity item={item} scope={scope} />
              <div style={{ display: 'flex', gap: 6 }}>
                {/* Hidden addons come back with a stripped manifest — the flag
                    is the only way to know the toggle should still render. */}
                {(item.manifest.catalogs.length > 0 || item.hideCatalogs) && (
                  <button
                    type="button"
                    className={`icon-btn ${item.hideCatalogs ? '' : 'icon-btn-on'}`}
                    title={
                      item.hideCatalogs
                        ? `Show catalogs on Home${scope === 'global' ? ' (all users)' : ''}`
                        : `Hide catalogs from Home${scope === 'global' ? ' (all users)' : ''}`
                    }
                    onClick={() =>
                      patch.mutate({ addonId: item.id, hideCatalogs: !item.hideCatalogs })
                    }
                  >
                    <Icon name={item.hideCatalogs ? 'eyeOff' : 'eye'} size={14} />
                  </button>
                )}
                <button
                  type="button"
                  className="icon-btn"
                  title="Remove addon"
                  onClick={() => remove(item)}
                >
                  <Icon name="x" size={12} />
                </button>
              </div>
            </div>
          ))
        )}
      </div>
    </>
  )
}

/* ── Playback ────────────────────────────────────────────────────────────── */

function PlaybackPane() {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()

  const languageSelect = (
    value: string | undefined,
    noneLabel: string,
    onChange: (value: string | undefined) => void,
  ) => (
    <select
      className="select-chip"
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

  return (
    <div className="set-form">
      <div className="pane-title">Playback</div>
      <div className="pane-hint">
        Defaults applied when a stream starts. Everything can be changed mid-playback.
      </div>

      <div className="opt-card">
        <div className="opt-row">
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="opt-key">Default audio language</div>
            <div className="opt-hint">Picked automatically when the file offers it.</div>
          </div>
          {languageSelect(settings.preferredAudioLang, 'Auto (first track)', (value) =>
            updateSettings.mutate({ preferredAudioLang: value }),
          )}
        </div>

        <div className="opt-row">
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="opt-key">Default subtitles</div>
            <div className="opt-hint">
              Falls back to addon subtitles when the file has no matching track.
            </div>
          </div>
          {languageSelect(settings.preferredSubtitleLang, 'Off', (value) =>
            updateSettings.mutate({ preferredSubtitleLang: value }),
          )}
        </div>

        <div className="opt-row">
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="opt-key">Autoplay next episode</div>
            <div className="opt-hint">
              Prefetches the next episode so the handoff needs no addon round-trip.
            </div>
          </div>
          <input
            type="checkbox"
            className="toggle"
            checked={settings.autoplayNextEpisode ?? true}
            onChange={(e) => updateSettings.mutate({ autoplayNextEpisode: e.target.checked })}
          />
        </div>
      </div>
    </div>
  )
}

/* ── Subtitles ───────────────────────────────────────────────────────────── */

function SubtitlesPane() {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()

  const scale = settings.subtitleScalePercent ?? SUBTITLE_SCALE_DEFAULT
  const outline = settings.subtitleOutline ?? 'normal'
  const shadow = settings.subtitleShadow ?? true

  return (
    <div className="set-form">
      <div className="pane-title">Subtitles</div>
      <div className="pane-hint">
        Styling applies live through mpv, and to plain-text subtitles only — subtitles that carry
        their own styling (ASS) keep the look their author gave them.
      </div>

      <div className="card" style={{ marginTop: 16, background: 'var(--surface)', borderRadius: 13 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10 }}>
          <div style={{ fontSize: 13.5, fontWeight: 600 }}>Size</div>
          <div className="spacer" />
          <div style={{ font: '700 13px/1 var(--mono)' }}>{scale}%</div>
        </div>
        <Slider
          label="Subtitle size"
          value={scale}
          min={SUBTITLE_SCALE_MIN}
          max={SUBTITLE_SCALE_MAX}
          step={SUBTITLE_SCALE_STEP}
          scale={[String(SUBTITLE_SCALE_MIN), '100', String(SUBTITLE_SCALE_MAX)]}
          onChange={(value) => updateSettings.mutate({ subtitleScalePercent: value })}
        />

        <div
          style={{
            display: 'flex',
            gap: 22,
            marginTop: 18,
            paddingTop: 15,
            borderTop: '1px solid rgba(255,255,255,.06)',
            flexWrap: 'wrap',
          }}
        >
          <PillGroup
            label="FONT"
            options={SUBTITLE_FONTS.map((font) => ({ key: font.family ?? '', label: font.label }))}
            active={settings.subtitleFontFamily ?? ''}
            onPick={(key) => updateSettings.mutate({ subtitleFontFamily: key || undefined })}
          />
          <PillGroup
            label="OUTLINE"
            options={SUBTITLE_OUTLINES.map((o) => ({ key: o.key, label: o.label }))}
            active={outline}
            onPick={(key) =>
              updateSettings.mutate({ subtitleOutline: key as typeof outline })
            }
          />
          <PillGroup
            label="SHADOW"
            options={[
              { key: 'on', label: 'On' },
              { key: 'off', label: 'Off' },
            ]}
            active={shadow ? 'on' : 'off'}
            onPick={(key) => updateSettings.mutate({ subtitleShadow: key === 'on' })}
          />
        </div>
      </div>

      <div className="card" style={{ marginTop: 12, background: 'var(--surface)', borderRadius: 13 }}>
        <div className="kicker" style={{ letterSpacing: '0.14em' }}>
          PREVIEW
        </div>
        {/* Approximates the mpv render: the true one is the player itself, and
            these controls apply there live. */}
        <div className="art art-wide sub-preview">
          <div
            className="sub-preview-caption"
            style={{
              fontSize: 15 * (scale / 100),
              fontFamily: settings.subtitleFontFamily ?? 'inherit',
              WebkitTextStroke: outline === 'none' ? undefined : `${
                outline === 'thin' ? 0.4 : outline === 'thick' ? 1.2 : 0.8
              }px rgba(0,0,0,.9)`,
              textShadow: shadow ? undefined : 'none',
            }}
          >
            We were never told where the line was.
          </div>
        </div>
      </div>
    </div>
  )
}

function PillGroup({
  label,
  options,
  active,
  onPick,
}: {
  label: string
  options: ReadonlyArray<{ key: string; label: string }>
  active: string
  onPick: (key: string) => void
}) {
  return (
    <div>
      <div className="kicker" style={{ letterSpacing: '0.14em' }}>
        {label}
      </div>
      <div style={{ display: 'flex', gap: 5, marginTop: 9 }}>
        {options.map((option) => (
          <button
            key={option.key}
            type="button"
            className={`pill-sm ${active === option.key ? 'pill-sm-active' : ''}`}
            onClick={() => onPick(option.key)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  )
}

/* ── Server & account ────────────────────────────────────────────────────── */

function ServerPane() {
  const { data: me } = useMe()
  const { signOut, disconnect } = useSession()
  const { data: build } = useBuildInfo()
  const status = useServerStatus()

  const rows: Array<{ key: string; value: string; color?: string }> = [
    // Full URL, not just the host: http vs https is the difference between a
    // working self-hosted server and a confusing failure.
    { key: 'Server', value: getServerUrl() ?? status.host },
    { key: 'Signed in as', value: me?.username ?? '…' },
    {
      key: 'Status',
      value: describeStatus(status),
      color: status.state === 'connected' ? 'var(--success)' : 'var(--warning)',
    },
    { key: 'Client', value: `v${build?.app ?? '…'} · mpv ${build?.mpv ?? '…'}` },
  ]

  return (
    <div className="set-form">
      <div className="pane-title">Server &amp; account</div>
      <div className="pane-hint">
        One Halo server holds your library, watch state and addon list.
      </div>

      <div className="opt-card">
        {rows.map((row) => (
          <div key={row.key} className="opt-row">
            <div className="spacer opt-key">{row.key}</div>
            <div className="opt-value" style={row.color ? { color: row.color } : undefined}>
              {row.value}
            </div>
          </div>
        ))}
      </div>

      <div style={{ display: 'flex', gap: 9, marginTop: 18 }}>
        <button
          type="button"
          className="btn-glass"
          onClick={() => {
            if (window.confirm('Forget this server and start over?')) disconnect()
          }}
        >
          Switch server
        </button>
        <button type="button" className="btn-danger" onClick={() => void signOut()}>
          Sign out
        </button>
      </div>
    </div>
  )
}
