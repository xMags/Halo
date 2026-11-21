import { DEFAULT_ADDON_URLS, LANGUAGE_OPTIONS, type AddonEntry } from '@halo/core'
import { openUrl } from '@tauri-apps/plugin-opener'
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import avatar from '../assets/user-avatar.png'
import { TmdbKeyCard } from './TmdbKeyCard'
import { addAddon, moveAddon, removeAddon, type AddonListEdit } from './addonListEdits'
import { RELEASES_URL, useBuildInfo } from './about'
import { ComboBox } from '../components/ComboBox'
import { showDialog } from '../components/Dialog'
import { FluentIcon, type FluentGlyph } from '../components/FluentIcon'
import { HaloMark } from '../components/HaloMark'
import { Segmented } from '../components/Segmented'
import { Slider } from '../components/Slider'
import { Toggle } from '../components/Toggle'
import { initials } from '../format'
import { setLocalPrefs, useLocalPrefs } from '../localPrefs'
import { subtitlePreviewMetrics } from '../player/playerLogic'
import {
  useAddons,
  useMe,
  usePatchAddon,
  usePatchGlobalAddon,
  useSetAddons,
  useSetGlobalAddons,
} from '../queries'
import { accountDisplayName, accountLabel, DEVICE_ACCOUNT_LABEL, DEVICE_ACCOUNT_NAME } from '../auth/accountLabel'
import { useAccountPicture } from '../auth/accountPicture'
import { describeStatus, useServerStatus } from '../auth/serverStatus'
import { RAIL_ANCHOR_FRACTION, activeSettingsSection, isScrolledToEnd } from './settingsRail'
import { languageChoices } from '../home/catalogLanguage'
import {
  SETTINGS_SECTIONS,
  clearSettingsSectionRequest,
  useSettingsSectionRequest,
  type SettingsSection,
} from './settingsSection'
import { useSession } from '../auth/session'
import { useSettings, useUpdateSettings } from './syncedSettings'
import {
  SUBTITLE_FONTS,
  SUBTITLE_OUTLINES,
  SUBTITLE_SCALE_DEFAULT,
  SUBTITLE_SCALE_MAX,
  SUBTITLE_SCALE_MIN,
  SUBTITLE_SCALE_STEP,
} from '../player/subtitleStyle'
import type { ThemeChoice } from '../theme'
import { subtitleCaptionShadow } from '../player/SubtitlePreview'

/** The rail, in the form's order: a glyph and a label per section. */
const RAIL: Array<{ key: SettingsSection; label: string; glyph: FluentGlyph }> = [
  { key: 'account', label: 'Account', glyph: 'person' },
  { key: 'appearance', label: 'Appearance', glyph: 'theme' },
  { key: 'playback', label: 'Playback', glyph: 'play' },
  { key: 'subtitles', label: 'Subtitles', glyph: 'captions' },
  { key: 'addons', label: 'Addons', glyph: 'plugin' },
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
  const request = useSettingsSectionRequest()
  const scroller = useRef<HTMLDivElement>(null)
  const form = useRef<HTMLDivElement>(null)
  /**
   * The section last jumped to. It is held at the top while content above it
   * is still arriving, until the reader scrolls for themselves.
   */
  const pinned = useRef<SettingsSection | null>(null)
  const [active, setActive] = useState<SettingsSection>(SETTINGS_SECTIONS[0] ?? 'account')

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
    setActive(SETTINGS_SECTIONS[index] ?? 'account')
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
          <div className="set-rail-list">
            {RAIL.map((item) => (
              <button
                key={item.key}
                type="button"
                className={`set-rail-btn ${active === item.key ? 'set-rail-btn-active' : ''}`}
                aria-current={active === item.key ? 'location' : undefined}
                onClick={() => jumpTo(item.key)}
              >
                <span className="set-rail-pill" />
                <FluentIcon glyph={item.glyph} size={16} />
                <span className="set-rail-label">{item.label}</span>
              </button>
            ))}
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
            <AccountSection />
            <AppearanceSection />
            <PlaybackSection />
            <SubtitlesSection />
            <AddonsSection />
            <AboutCard />
          </div>
        </div>
      </div>
    </div>
  )
}

/** A section of the form: its title, then a column of setting cards 4px apart. */
function Section({ id, title, children }: { id: SettingsSection; title: string; children: ReactNode }) {
  return (
    <section className="set-section" data-settings-section={id}>
      <h2 className="set-section-title">{title}</h2>
      {children}
    </section>
  )
}

/** One setting's card. A grouped card holds a header row and further rows. */
function SettingCard({ children }: { children: ReactNode }) {
  return <div className="set-card">{children}</div>
}

/**
 * One setting: an optional glyph, the label over its hint, and the control at
 * the row's end. (Native: SettingRow.)
 */
function SettingRow({
  glyph,
  label,
  hint,
  children,
}: {
  glyph?: FluentGlyph
  label: ReactNode
  hint?: ReactNode
  children?: ReactNode
}) {
  return (
    <div className="set-row">
      {glyph && (
        <span className="set-row-glyph">
          <FluentIcon glyph={glyph} size={20} />
        </span>
      )}
      <span className="set-row-text">
        <span className="set-label">{label}</span>
        {hint && <span className="set-hint">{hint}</span>}
      </span>
      {children !== undefined && <span className="set-row-control">{children}</span>}
    </div>
  )
}

/** A row inside a grouped card: indented to the header's label, a hairline over it. */
function SettingGroupRow({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <div className="set-group-row">
      <span className="set-row-text">
        <span className="set-label">{label}</span>
        {hint && <span className="set-hint">{hint}</span>}
      </span>
      <span className="set-row-control">{children}</span>
    </div>
  )
}

/** A switch with the On/Off word before it, which always says what the switch shows. */
function OnOffSwitch({
  label,
  on,
  onChange,
}: {
  label: string
  on: boolean
  onChange: (next: boolean) => void
}) {
  return (
    <span className="set-switch">
      <span className="set-onoff">{on ? 'On' : 'Off'}</span>
      <Toggle label={label} on={on} onChange={onChange} />
    </span>
  )
}

/* ── Account ─────────────────────────────────────────────────────────────── */

function AccountSection() {
  const { state } = useSession()
  return (
    <Section id="account" title="Account">
      {state === 'device' ? <DeviceAccount /> : <SignedInAccount />}
    </Section>
  )
}

/** The account card: picture, name over the role line, and (signed in) the server status. */
function AccountCard({ picture, name, role, status }: { picture: string; name: string; role: string; status?: ReactNode }) {
  return (
    <SettingCard>
      <div className="set-account">
        <img className="set-avatar" src={picture} alt="" draggable={false} />
        <span className="set-account-text">
          <span className="set-account-name ellipsis">{name}</span>
          <span className="mono">{role}</span>
        </span>
        {status}
      </div>
    </SettingCard>
  )
}

/** Halo used without an account: nothing to sign out of, and signing in is the way to sync. */
function DeviceAccount() {
  const { startSignIn } = useSession()

  return (
    <>
      <AccountCard picture={avatar} name={DEVICE_ACCOUNT_NAME} role={DEVICE_ACCOUNT_LABEL} />
      <SettingCard>
        <SettingRow
          glyph="sync"
          label="Sign in to sync"
          hint="Your library, watch progress and addons are kept on this PC. Sign in to sync them across your devices."
        >
          <button type="button" className="btn-accent" onClick={startSignIn}>
            Sign in
          </button>
        </SettingRow>
      </SettingCard>
      <TmdbKeyCard />
    </>
  )
}

function SignedInAccount() {
  const { data: me } = useMe()
  const { signOut } = useSession()
  const status = useServerStatus()

  // The account's own picture when it has one; the stock avatar otherwise.
  const picture = useAccountPicture()
  const statusTone =
    status.state === 'connected' ? 'connected' : status.state === 'probing' ? 'checking' : 'down'

  const confirmSignOut = () => {
    void showDialog({
      title: 'Sign out?',
      body: 'You will need to sign in again to use this server.',
      primary: 'Sign out',
      close: 'Cancel',
      destructive: true,
    }).then((confirmed) => {
      if (confirmed) signOut()
    })
  }

  return (
    <>
      <AccountCard
        picture={picture ?? avatar}
        name={me ? accountDisplayName(me.username) : '…'}
        role={accountLabel(me)}
        status={<span className={`status-pill status-pill-${statusTone}`}>{describeStatus(status)}</span>}
      />
      <SettingCard>
        <SettingRow
          glyph="signOut"
          label="Sign out"
          hint="Library, watch progress and addon order sync through this server"
        >
          <button type="button" className="btn-danger" onClick={confirmSignOut}>
            Sign out
          </button>
        </SettingRow>
      </SettingCard>
    </>
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
    <Section id="appearance" title="Appearance">
      <SettingCard>
        <SettingRow glyph="theme" label="Theme" hint="System follows your Windows setting" />
        <div className="theme-cards" role="radiogroup" aria-label="Theme">
          {THEME_CARDS.map((card) => (
            <button
              key={card.key}
              type="button"
              role="radio"
              className={`theme-card ${prefs.theme === card.key ? 'theme-card-active' : ''}`}
              aria-checked={prefs.theme === card.key}
              onClick={() => setLocalPrefs({ theme: card.key })}
            >
              <span className="theme-mini" style={{ background: card.bg, border: `1px solid ${card.frame}` }}>
                {card.split && <span className="theme-mini-split" />}
                <span className="theme-mini-tile" style={{ background: card.tile }} />
                <span className="theme-mini-lines">
                  <span style={{ background: card.line }} />
                  <span style={{ background: card.line, width: 46 }} />
                  <span style={{ background: card.accent, width: 34 }} />
                </span>
              </span>
              <span className="set-label">{card.label}</span>
            </button>
          ))}
        </div>
      </SettingCard>
    </Section>
  )
}

/* ── Playback ────────────────────────────────────────────────────────────── */

function LanguageSelect({
  value,
  noneLabel,
  ariaLabel,
  onChange,
}: {
  value: string | undefined
  noneLabel: string
  ariaLabel: string
  onChange: (value: string | undefined) => void
}) {
  const options = [
    { value: 'none', label: noneLabel },
    ...LANGUAGE_OPTIONS.map((lang) => ({ value: lang.code, label: lang.label })),
  ]
  return (
    <ComboBox
      options={options}
      value={value ?? 'none'}
      width={168}
      ariaLabel={ariaLabel}
      onChange={(val) => onChange(val === 'none' ? undefined : val)}
    />
  )
}

function PlaybackSection() {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()
  const prefs = useLocalPrefs()

  return (
    <Section id="playback" title="Playback">
      <SettingCard>
        <SettingRow glyph="audio" label="Audio language" hint="Picked when the file offers a match">
          <LanguageSelect
            value={settings.preferredAudioLang}
            noneLabel="First track"
            ariaLabel="Audio language"
            onChange={(value) => updateSettings.mutate({ preferredAudioLang: value })}
          />
        </SettingRow>
      </SettingCard>
      <SettingCard>
        <SettingRow glyph="captions" label="Subtitle language" hint="Falls back to addon results">
          <LanguageSelect
            value={settings.preferredSubtitleLang}
            noneLabel="Off"
            ariaLabel="Subtitle language"
            onChange={(value) => updateSettings.mutate({ preferredSubtitleLang: value })}
          />
        </SettingRow>
      </SettingCard>
      <SettingCard>
        <SettingRow glyph="autoplay" label="Autoplay next episode" hint="8 second countdown, cancellable">
          <OnOffSwitch
            label="Autoplay next episode"
            on={settings.autoplayNextEpisode ?? true}
            onChange={(next) => updateSettings.mutate({ autoplayNextEpisode: next })}
          />
        </SettingRow>
      </SettingCard>
      <SettingCard>
        <SettingRow glyph="resume" label="Resume where I left off" hint="Ignored under 30 seconds watched">
          <OnOffSwitch
            label="Resume where I left off"
            on={prefs.resumePlayback}
            onChange={(next) => setLocalPrefs({ resumePlayback: next })}
          />
        </SettingRow>
      </SettingCard>
      <SettingCard>
        <SettingRow
          glyph="chat"
          label="Discord Rich Presence"
          hint="Shares the title and episode you are watching with Discord"
        >
          <OnOffSwitch
            label="Discord Rich Presence"
            on={prefs.discordPresence}
            onChange={(next) => setLocalPrefs({ discordPresence: next })}
          />
        </SettingRow>
      </SettingCard>
      <SettingCard>
        <SettingRow
          glyph="decoding"
          label="Hardware decoding"
          hint="mpv hwdec=auto-safe. Changes take effect on the next file."
        >
          <OnOffSwitch
            label="Hardware decoding"
            on={prefs.hardwareDecoding}
            onChange={(next) => setLocalPrefs({ hardwareDecoding: next })}
          />
        </SettingRow>
      </SettingCard>
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
    <Section id="subtitles" title="Subtitles">
      {/* A stand-in for a video frame rather than a themed surface, because
          a video frame is what subtitles are drawn over. It stays dark in
          both themes so the caption keeps the contrast playback gives it. */}
      <div className="sub-preview">
        <div className="sub-preview-scrim" />
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

      {/* One grouped card: a header row, then each style choice on its own
          hairline row. Font and Outline share a control column, so the
          Outline tray takes the Font tray's width as the native page binds it. */}
      <SettingCard>
        <SettingRow glyph="font" label="Caption style" hint="Applies live during playback" />
        <div className="set-group">
          <SettingGroupRow label="Size">
            <span className="set-slider">
              <Slider
                min={SUBTITLE_SCALE_MIN}
                max={SUBTITLE_SCALE_MAX}
                step={SUBTITLE_SCALE_STEP}
                value={scale}
                ariaLabel="Subtitle size"
                onChange={(value) => updateSettings.mutate({ subtitleScalePercent: value })}
              />
              <span className="set-slider-value">{scale}%</span>
            </span>
          </SettingGroupRow>
          <div className="set-tray-pair">
            <SettingGroupRow label="Font">
              <Segmented
                compact
                options={SUBTITLE_FONTS.map((font) => ({
                  value: font.family ?? '',
                  label: font.label,
                }))}
                value={settings.subtitleFontFamily ?? ''}
                onChange={(value) => updateSettings.mutate({ subtitleFontFamily: value || undefined })}
              />
            </SettingGroupRow>
            <SettingGroupRow label="Outline">
              <Segmented
                compact
                options={SUBTITLE_OUTLINES.map((o) => ({ value: o.key, label: o.label }))}
                value={outline}
                onChange={(value) => updateSettings.mutate({ subtitleOutline: value })}
              />
            </SettingGroupRow>
          </div>
          <SettingGroupRow label="Shadow">
            <OnOffSwitch
              label="Subtitle shadow"
              on={shadow}
              onChange={(next) => updateSettings.mutate({ subtitleShadow: next })}
            />
          </SettingGroupRow>
          {/* The size and font choices above always reach a plain text track.
              This row decides whether they also override a track that ships
              its own styling, the only case where the two can disagree. */}
          <SettingGroupRow
            label="Keep a track's own styling"
            hint="On, a styled track keeps its look. Off, these settings win."
          >
            <OnOffSwitch
              label="Keep a track's own styling"
              on={prefs.subtitleTrackStyling}
              onChange={(next) => setLocalPrefs({ subtitleTrackStyling: next })}
            />
          </SettingGroupRow>
        </div>
      </SettingCard>
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
 * Cinemeta, OpenSubtitles and The Movie Database, which every Halo app
 * installs so it works before anything is added. They stay in your own list:
 * without them there are no catalogs to browse and no subtitles.
 */
function isBuiltInAddon(item: AddonEntry, scope: 'yours' | 'global'): boolean {
  if (scope !== 'yours' || item.transportUrl === undefined) return false
  return (DEFAULT_ADDON_URLS as readonly string[]).includes(item.transportUrl)
}

/** What a row offers at its end, decided once so the slot width can be measured from it. */
interface AddonRowActions {
  installForEveryone: boolean
  remove: boolean
}

function addonRowActions(item: AddonEntry, scope: 'yours' | 'global', isAdmin: boolean): AddonRowActions {
  const shadowed = scope === 'yours' && item.providedGlobally === true
  return {
    installForEveryone: scope === 'yours' && isAdmin && !shadowed,
    // Only your own additions can be removed here. The built-in addons are
    // what Halo needs to browse at all, and global ones are managed on the
    // server, so neither offers the button rather than a disabled one.
    remove: scope === 'yours' && !isBuiltInAddon(item, scope),
  }
}

/** 28px buttons 2px apart; no buttons, no slot. (Native: AddonActionsWidth.) */
function actionsWidth(actions: AddonRowActions): number {
  const buttons = (actions.installForEveryone ? 1 : 0) + (actions.remove ? 1 : 0)
  return buttons === 0 ? 0 : buttons * 28 + (buttons - 1) * 2
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
  const [listError, setListError] = useState<string | null>(null)
  const [dragKey, setDragKey] = useState<string | null>(null)
  const [overKey, setOverKey] = useState<string | null>(null)

  const isAdmin = me?.isAdmin ?? false
  const globalAddons = addons?.global ?? []
  const userAddons = addons?.user ?? []
  const all = [...globalAddons, ...userAddons]
  // One list save at a time: each sends the whole list as this render saw it,
  // so a second one started before the first is refetched would undo it.
  const listBusy = setAddons.isPending || setGlobalAddons.isPending

  // Both lists share one slot width, as wide as the widest row's buttons, so
  // the catalog switches line up down the page; with no buttons anywhere the
  // slot (and its gap) is gone and the switches sit at the card's edge.
  const slotWidth = Math.max(
    0,
    ...globalAddons.map((item) => actionsWidth(addonRowActions(item, 'global', isAdmin))),
    ...userAddons.map((item) => actionsWidth(addonRowActions(item, 'yours', isAdmin))),
  )

  const saveList = async (scope: 'yours' | 'global', edit: AddonListEdit, failure: string) => {
    if (listBusy) return
    setListError(null)
    try {
      await (scope === 'global' ? setGlobalAddons : setAddons).mutateAsync(edit)
    } catch {
      setListError(failure)
    }
  }

  const add = async () => {
    const transportUrl = url.trim()
    // The save reads the account's list afresh and adds to that; the loaded
    // list here only answers "already installed".
    if (!transportUrl || adding || listBusy || !addons) return
    if (all.some((a) => a.transportUrl === transportUrl)) {
      setAddError('Already installed.')
      return
    }
    setAdding(true)
    setAddError(null)
    try {
      await setAddons.mutateAsync(addAddon(transportUrl))
      setUrl('')
    } catch (err) {
      setAddError(err instanceof Error ? err.message : 'Invalid manifest URL')
    } finally {
      setAdding(false)
    }
  }

  const remove = async (item: AddonEntry, scope: 'yours' | 'global') => {
    if (isBuiltInAddon(item, scope) || listBusy) return
    const confirmed = await showDialog({
      title: scope === 'global' ? 'Remove for every user?' : 'Remove addon?',
      body:
        scope === 'global'
          ? `“${item.manifest.name}” will be removed for everyone on this server.`
          : `“${item.manifest.name}” will be removed from your addons.`,
      primary: 'Remove',
      close: 'Cancel',
      destructive: true,
    })
    if (!confirmed || !item.transportUrl) return
    void saveList(scope, removeAddon(item.transportUrl), `“${item.manifest.name}” could not be removed. Try again.`)
  }

  /**
   * An admin shares one of their own addons with every user: it joins the end
   * of the global list, then leaves their own list, where it would only sit
   * shadowed by the global copy. A built-in stays in their list (it cannot be
   * removed there), shadowed until the global copy goes.
   */
  const makeGlobal = async (item: AddonEntry) => {
    const transportUrl = item.transportUrl
    if (!isAdmin || listBusy || !transportUrl) return
    if (globalAddons.some((a) => a.transportUrl === transportUrl)) return
    // The server sends global URLs to admins; without them the list cannot be re-sent whole.
    const globalUrls = globalAddons.map((a) => a.transportUrl)
    if (globalUrls.some((url) => !url)) {
      setListError('The global addon list could not be read in full. Refresh and try again.')
      return
    }
    const confirmed = await showDialog({
      title: 'Install for every user?',
      body: `“${item.manifest.name}” will be added to every account on this server, and your own copy will be replaced by it.`,
      primary: 'Install for everyone',
      close: 'Cancel',
    })
    if (!confirmed) return
    setListError(null)
    try {
      await setGlobalAddons.mutateAsync(addAddon(transportUrl))
    } catch {
      setListError(`“${item.manifest.name}” could not be installed for every user. Try again.`)
      return
    }
    if (isBuiltInAddon(item, 'yours')) return
    try {
      await setAddons.mutateAsync(removeAddon(transportUrl))
    } catch {
      setListError(`“${item.manifest.name}” is now global, but your own copy could not be removed. Remove it below.`)
    }
  }

  /**
   * Order is priority: the first addon that can answer a resolution request
   * wins, so dragging a row is a real setting, not decoration. Only rows
   * within one scope reorder against each other: the server keeps the global
   * and per-user lists apart, and they resolve in that order.
   */
  const reorder = (scope: 'yours' | 'global', fromId: string, toId: string) => {
    const list = scope === 'global' ? globalAddons : userAddons
    const from = list.find((a) => a.id === fromId)?.transportUrl
    const to = list.find((a) => a.id === toId)?.transportUrl
    if (!from || !to || from === to) return
    void saveList(scope, moveAddon(from, to), 'The new addon order could not be saved. Try again.')
  }

  const row = (item: AddonEntry, scope: 'yours' | 'global') => {
    const editable = scope === 'yours' || isAdmin
    const patch = scope === 'global' ? patchGlobalAddon : patchAddon
    // The admin installs this same addon for everyone, so the server skips
    // this copy. It stays listed, greyed, because it comes back into use if
    // the global entry is removed; until then its order and catalogs mean
    // nothing.
    const shadowed = scope === 'yours' && item.providedGlobally === true
    const movable = editable && !shadowed && !listBusy
    // A hidden addon comes back with a stripped manifest, so the flag is the
    // only way to know the switch should still be drawn.
    const hasCatalogs = !shadowed && (item.manifest.catalogs.length > 0 || item.hideCatalogs)
    const actions = addonRowActions(item, scope, isAdmin)

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
          <FluentIcon glyph="grip" size={16} />
        </span>
        <span className="addon-tile">{initials(item.manifest.name)}</span>
        <span className="addon-text">
          <span className="addon-title">
            <span className="addon-name ellipsis">{item.manifest.name}</span>
            <span className="mono">{item.manifest.version}</span>
          </span>
          <span className="addon-provides ellipsis">
            {shadowed ? 'Provided globally, so this copy is not used' : providesLine(item)}
          </span>
        </span>
        <span className="addon-end">
          {hasCatalogs && (
            <Toggle
              label={`Show ${item.manifest.name} catalogs on Home`}
              on={!item.hideCatalogs}
              disabled={!editable}
              onChange={(next) => {
                setListError(null)
                patch.mutate(
                  { addonId: item.id, hideCatalogs: !next },
                  { onError: () => setListError(`“${item.manifest.name}” catalogs could not be changed. Try again.`) },
                )
              }}
            />
          )}
          {slotWidth > 0 && (
            <span className="addon-actions" style={{ minWidth: slotWidth }}>
              {actions.installForEveryone && (
                <button
                  type="button"
                  className="icon-btn icon-btn-bare icon-btn-28"
                  title="Install for every user"
                  aria-label="Install for every user"
                  disabled={listBusy}
                  onClick={() => void makeGlobal(item)}
                >
                  <FluentIcon glyph="globe" size={14} />
                </button>
              )}
              {actions.remove && (
                <button
                  type="button"
                  className="icon-btn icon-btn-bare icon-btn-28 icon-btn-danger"
                  title="Remove addon"
                  aria-label="Remove addon"
                  disabled={listBusy}
                  onClick={() => void remove(item, scope)}
                >
                  <FluentIcon glyph="delete" size={14} />
                </button>
              )}
            </span>
          )}
        </span>
      </div>
    )
  }

  /** A list's heading names the list; "Catalogs on Home" names the switch column. */
  const listHeader = (title: string) => (
    <div className="addon-list-header">
      <span className="set-list-title">{title}</span>
      <span className="set-hint">Catalogs on Home</span>
    </div>
  )

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
      <div className="addon-lists">
        {globalAddons.length > 0 && (
          <>
            {listHeader('For everyone')}
            <div className="addon-list">{globalAddons.map((item) => row(item, 'global'))}</div>
          </>
        )}
        {userAddons.length > 0 && (
          <>
            {listHeader('Yours')}
            <div className="addon-list">{userAddons.map((item) => row(item, 'yours'))}</div>
          </>
        )}
      </div>
    )
  }

  return (
    <Section id="addons" title="Addons">
      <SettingCard>
        <div className="addon-add">
          <span className="set-row-glyph">
            <FluentIcon glyph="add" size={20} />
          </span>
          <span className="set-row-text addon-add-text">
            <span className="set-label">Add an addon</span>
            <span className="set-hint">Catalogs, streams and subtitles come from these. Order sets priority.</span>
          </span>
          <input
            className="field"
            aria-label="Addon manifest URL"
            placeholder="https://addon.example.com/manifest.json"
            value={url}
            spellCheck={false}
            onChange={(e) => {
              setUrl(e.target.value)
              // The message was about the URL that was there; editing it moves on.
              setAddError(null)
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') void add()
            }}
          />
          <button
            type="button"
            className="btn-accent addon-add-btn"
            disabled={adding || listBusy || !addons || !url.trim()}
            onClick={() => void add()}
          >
            {adding ? 'Adding…' : 'Add'}
          </button>
        </div>
      </SettingCard>
      {addError && <div className="set-message error-text">{addError}</div>}
      {listError && (
        <div className="set-message error-text" role="alert">
          {listError}
        </div>
      )}
      {body}
      <CatalogLanguageRow addons={all} />
    </Section>
  )
}

/** The value standing for "no choice": the catalog asks for its own default. */
const ADDON_DEFAULT = ''

/**
 * The account's language for Home's language rows (TMDB's "Language" row),
 * offered only when an installed addon has such a row. The choices are that
 * row's own list, which names languages Halo's player lists do not (Tamil,
 * Telugu); the setting syncs with the account.
 */
function CatalogLanguageRow({ addons }: { addons: AddonEntry[] }) {
  const settings = useSettings()
  const updateSettings = useUpdateSettings()
  const choices = addons
    .flatMap((addon) => addon.manifest.catalogs)
    .map((catalog) => languageChoices(catalog))
    .find((list): list is string[] => list !== null && list.length > 0)
  if (!choices) return null
  return (
    <>
      <div className="set-list-title set-home-title">Home</div>
      <SettingCard>
        <SettingRow glyph="globe" label="Language rows" hint="Rows such as TMDB's Language row show titles in this language">
          <ComboBox
            options={[
              { value: ADDON_DEFAULT, label: 'Addon default' },
              ...choices.map((language) => ({ value: language, label: language })),
            ]}
            value={settings.catalogLanguage ?? ADDON_DEFAULT}
            width="auto"
            minWidth={168}
            ariaLabel="Language rows on Home"
            onChange={(value) =>
              updateSettings.mutate({ catalogLanguage: value === ADDON_DEFAULT ? undefined : String(value) })
            }
          />
        </SettingRow>
      </SettingCard>
    </>
  )
}

/* ── About ───────────────────────────────────────────────────────────────── */

/** Closes the page: the app, its version and licence, and the way to newer builds. */
function AboutCard() {
  const { data: build } = useBuildInfo()
  return (
    <SettingCard>
      <div className="set-about">
        <HaloMark size={20} />
        <span className="set-row-text">
          <span className="set-label">Halo Desktop</span>
          <span className="mono">{`v${build?.app ?? '…'} · mpv ${build?.mpv ?? '…'}`}</span>
          <span className="set-hint">© 2026 Last Projects &amp; Shy · GPL-3.0 license</span>
        </span>
        <button
          type="button"
          className="btn set-about-btn"
          title="Opens the releases page on GitHub"
          onClick={() => void openUrl(RELEASES_URL).catch(() => {})}
        >
          Check for updates
        </button>
      </div>
    </SettingCard>
  )
}
