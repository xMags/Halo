import { LANGUAGE_OPTIONS, type AddonEntry } from '@halo/core'
import { useState } from 'react'
import { getServerUrl } from '../api'
import { Icon } from '../components/Icon'
import {
  useAddons,
  useMe,
  usePatchAddon,
  usePatchGlobalAddon,
  useSetAddons,
  useSetGlobalAddons,
} from '../queries'
import { useSession } from '../session'
import { useSettings, useUpdateSettings } from '../settings'

/** Settings: addon management, playback preferences, server status, sign-out. */
export function Settings() {
  const { data: addons } = useAddons()
  const { data: me } = useMe()
  const setAddons = useSetAddons()
  const setGlobalAddons = useSetGlobalAddons()
  const { signOut } = useSession()

  const isAdmin = me?.isAdmin ?? false
  const globalAddons = addons?.global ?? []
  const userAddons = addons?.user ?? []

  return (
    <div className="screen-scroll">
      <div style={{ maxWidth: 640, padding: '20px 32px 48px' }}>
        <div className="t-large-title" style={{ marginBottom: 16 }}>
          Settings
        </div>

        <GroupLabel>Addons</GroupLabel>
        <AddonSection
          addons={userAddons}
          allAddons={[...globalAddons, ...userAddons]}
          icon="grid"
          emptyHint="No addons yet — paste a Stremio-compatible manifest URL above."
          onSave={(urls) => setAddons.mutateAsync(urls)}
          global={false}
        />

        {isAdmin ? (
          <>
            <GroupLabel>Global · admin</GroupLabel>
            <AddonSection
              addons={globalAddons}
              allAddons={[...globalAddons, ...userAddons]}
              icon="globe"
              emptyHint="No global addons yet — anything added here appears for every user."
              onSave={(urls) => setGlobalAddons.mutateAsync(urls)}
              global
            />
          </>
        ) : (
          globalAddons.length > 0 && (
            <>
              <GroupLabel>Global</GroupLabel>
              <div className="settings-card">
                {globalAddons.map((item) => (
                  <div key={item.id} className="settings-row">
                    <span className="addon-icon">
                      <Icon name="globe" size={17} />
                    </span>
                    <AddonBody item={item} />
                    <span style={{ color: 'var(--text-dim)' }}>
                      <Icon name="lock" size={14} />
                    </span>
                  </div>
                ))}
              </div>
            </>
          )
        )}

        <PlaybackSection />

        <GroupLabel>Server</GroupLabel>
        <div className="settings-card">
          <div className="settings-row">
            <span className="settings-key">Server</span>
            <span className="settings-value">
              {getServerUrl()?.replace(/^https?:\/\//, '') ?? ''}
            </span>
          </div>
          <div className="settings-row">
            <span className="settings-key">Signed in as</span>
            <span className="settings-value">{me?.username ?? '…'}</span>
          </div>
          <div className="settings-row">
            <span className="settings-key">Status</span>
            <span className="settings-value" style={{ color: 'var(--success)' }}>
              <span className="status-dot" style={{ display: 'inline-block', marginRight: 6 }} />
              Connected
            </span>
          </div>
        </div>

        <button
          type="button"
          className="btn btn-glass"
          onClick={() => void signOut()}
          style={{ marginTop: 24, width: '100%', color: 'var(--danger)' }}
        >
          Sign Out
        </button>
      </div>
    </div>
  )
}

function GroupLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="t-overline" style={{ margin: '20px 4px 8px' }}>
      {children}
    </div>
  )
}

function AddonBody({ item }: { item: AddonEntry }) {
  return (
    <div style={{ flex: 1, minWidth: 0 }}>
      <div style={{ fontSize: 14.5, fontWeight: 600 }}>
        {item.manifest.name}{' '}
        <span style={{ color: 'var(--text-dim)', fontWeight: 400, fontSize: 12 }}>
          v{item.manifest.version}
        </span>
      </div>
      {item.manifest.description && (
        <div
          className="t-caption"
          style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
        >
          {item.manifest.description}
        </div>
      )}
    </div>
  )
}

/**
 * One addon list (user or global) with add-by-URL, hide-catalogs toggle and
 * removal. Only transport URLs are sent, in priority order — the server diffs
 * against what's stored and fetches manifests for new URLs only.
 */
function AddonSection({
  addons,
  allAddons,
  icon,
  emptyHint,
  onSave,
  global,
}: {
  addons: AddonEntry[]
  allAddons: AddonEntry[]
  icon: 'grid' | 'globe'
  emptyHint: string
  onSave: (transportUrls: string[]) => Promise<unknown>
  global: boolean
}) {
  const patchAddon = usePatchAddon()
  const patchGlobalAddon = usePatchGlobalAddon()
  const [url, setUrl] = useState('')
  const [adding, setAdding] = useState(false)
  const [addError, setAddError] = useState<string | null>(null)

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
      // entries are redacted for non-admins.
      await onSave([...addons.map((a) => a.transportUrl!), transportUrl])
      setUrl('')
    } catch (err) {
      setAddError(err instanceof Error ? err.message : 'Invalid manifest URL')
    } finally {
      setAdding(false)
    }
  }

  const remove = (item: AddonEntry) => {
    const scope = global ? `Remove "${item.manifest.name}" for every user?` : `Remove "${item.manifest.name}"?`
    if (!window.confirm(scope)) return
    void onSave(addons.filter((a) => a.transportUrl !== item.transportUrl).map((a) => a.transportUrl!))
  }

  const patch = global ? patchGlobalAddon : patchAddon

  return (
    <div className="settings-card">
      <div className="settings-row" style={{ gap: 8 }}>
        <input
          className="field"
          style={{ padding: '9px 12px', fontSize: 13.5 }}
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
          className="btn btn-primary"
          style={{ padding: '9px 16px', fontSize: 13.5 }}
          disabled={adding}
          onClick={() => void add()}
        >
          {adding ? 'Adding…' : 'Add'}
        </button>
      </div>
      {addError && (
        <div className="settings-row">
          <span className="error-text">{addError}</span>
        </div>
      )}
      {addons.length === 0 ? (
        <div className="settings-row">
          <span className="t-caption">{emptyHint}</span>
        </div>
      ) : (
        addons.map((item) => (
          <div key={item.id} className="settings-row">
            <span className="addon-icon">
              <Icon name={icon} size={17} />
            </span>
            <AddonBody item={item} />
            {/* Hidden addons come back with a stripped manifest — the flag is
                the only way to know the toggle should still render. */}
            {(item.manifest.catalogs.length > 0 || item.hideCatalogs) && (
              <button
                type="button"
                className="icon-btn"
                title={
                  item.hideCatalogs
                    ? `Show catalogs on Home${global ? ' (all users)' : ''}`
                    : `Hide catalogs from Home${global ? ' (all users)' : ''}`
                }
                style={item.hideCatalogs ? undefined : { color: 'var(--accent)' }}
                onClick={() => patch.mutate({ addonId: item.id, hideCatalogs: !item.hideCatalogs })}
              >
                <Icon name={item.hideCatalogs ? 'eyeOff' : 'eye'} size={17} />
              </button>
            )}
            <button
              type="button"
              className="icon-btn"
              title="Remove addon"
              onClick={() => remove(item)}
            >
              <Icon name="x" size={16} />
            </button>
          </div>
        ))
      )}
    </div>
  )
}

function PlaybackSection() {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()

  const languageSelect = (
    value: string | undefined,
    noneLabel: string,
    onChange: (value: string | undefined) => void,
  ) => (
    <select
      className="field-select"
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
    <>
      <GroupLabel>Playback</GroupLabel>
      <div className="settings-card">
        <div className="settings-row">
          <span className="settings-key">Default audio language</span>
          {languageSelect(settings.preferredAudioLang, 'Auto (first track)', (value) =>
            updateSettings.mutate({ preferredAudioLang: value }),
          )}
        </div>
        <div className="settings-row">
          <span className="settings-key">Default subtitles</span>
          {languageSelect(settings.preferredSubtitleLang, 'Off', (value) =>
            updateSettings.mutate({ preferredSubtitleLang: value }),
          )}
        </div>
        <div className="settings-row">
          <span className="settings-key">Autoplay next episode</span>
          <input
            type="checkbox"
            className="toggle"
            checked={settings.autoplayNextEpisode ?? true}
            onChange={(e) => updateSettings.mutate({ autoplayNextEpisode: e.target.checked })}
          />
        </div>
      </div>
    </>
  )
}
