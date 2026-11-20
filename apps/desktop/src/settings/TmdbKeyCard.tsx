import { openUrl } from '@tauri-apps/plugin-opener'
import { useState } from 'react'
import tmdbLogo from '../assets/tmdb-logo.svg'
import { FluentIcon } from '../components/FluentIcon'
import { removeTmdbKey, saveTmdbKey, useTmdbKeyStatus, type TmdbKeySaveResult } from '../device/tmdbKey'

/*
 * Without an account, people search and IMDb/TVDB links ask TMDB directly with
 * the viewer's own free key. The pasted key is never shown back: once saved,
 * the card says so and offers to remove it. TMDB asks every app using its API
 * to credit it, as the line under the card does, with its logo less prominent
 * than the app's own marks.
 */

const GET_KEY_URL = 'https://www.themoviedb.org/settings/api'

const SAVE_PROBLEMS: Record<Exclude<TmdbKeySaveResult, 'saved'>, string> = {
  notAKey: "That isn't a TMDB API key or read access token. Copy one from your TMDB account's API page.",
  refused: 'TMDB refused this key. Check it and try again.',
  unreachable: "Couldn't reach TMDB to check the key. Try again when you're online.",
  notStored: "TMDB accepted the key, but Halo couldn't save it on this PC.",
}
const WORKING_TEXT = 'Key works. People search and IMDb and TVDB links are on.'
const REFUSED_LATER_TEXT = 'TMDB no longer accepts the saved key. Paste a new one to turn people search back on.'

export function TmdbKeyCard() {
  const status = useTmdbKeyStatus()
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const saved = status === 'working'

  const save = async () => {
    if (busy || !text.trim()) return
    setBusy(true)
    const result = await saveTmdbKey(text).catch((): TmdbKeySaveResult => 'notStored')
    setBusy(false)
    if (result === 'saved') {
      setText('')
      setMessage({ ok: true, text: WORKING_TEXT })
      return
    }
    setMessage({ ok: false, text: SAVE_PROBLEMS[result] })
  }

  const remove = async () => {
    setMessage(null)
    await removeTmdbKey().catch(() => setMessage({ ok: false, text: "Halo couldn't remove the key from this PC." }))
  }

  const shownMessage = message ?? (status === 'refused' ? { ok: false, text: REFUSED_LATER_TEXT } : null)

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
      <div className="card tmdb-card">
        <span className="tmdb-card-glyph">
          <FluentIcon glyph="person" size={20} />
        </span>
        <div className="tmdb-card-text">
          <span className="opt-label">People search</span>
          <span className="tmdb-card-hint">
            Find actors, directors and writers, and open IMDb and TVDB links, with your own TMDB key.{' '}
            <button type="button" className="tmdb-card-link" onClick={() => void openUrl(GET_KEY_URL).catch(() => undefined)}>
              Get a free key
            </button>
          </span>
        </div>
        {saved && (
          <div className="tmdb-card-saved">
            <span className="tmdb-card-check">
              <FluentIcon glyph="check" size={14} />
            </span>
            <span>Key saved</span>
            <button type="button" className="btn-danger btn-body" onClick={() => void remove()}>
              Remove
            </button>
          </div>
        )}
        {!saved && (
          <div className="tmdb-card-entry">
            <input
              className="field spacer"
              type="password"
              autoComplete="off"
              spellCheck={false}
              aria-label="TMDB API key or read access token"
              placeholder="Paste your API key or read access token"
              value={text}
              onChange={(event) => {
                setText(event.target.value)
                setMessage(null)
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void save()
              }}
            />
            <button type="button" className="btn-accent btn-body tmdb-card-save" disabled={busy || !text.trim()} onClick={() => void save()}>
              {busy ? 'Checking…' : 'Save'}
            </button>
          </div>
        )}
        <div className="tmdb-card-credit">
          <img src={tmdbLogo} alt="TMDB" height={10} draggable={false} />
          <span>This product uses the TMDB API but is not endorsed or certified by TMDB.</span>
        </div>
      </div>
      {shownMessage && (
        <div className={shownMessage.ok ? 'tmdb-card-status' : 'tmdb-card-status error-text'} role={shownMessage.ok ? 'status' : 'alert'}>
          {shownMessage.text}
        </div>
      )}
    </div>
  )
}
