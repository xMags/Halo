import { createHash } from 'node:crypto'
import { formatUserCode } from './deviceSignIn'

/**
 * The page a phone opens to approve a TV: the code (filled in from the QR
 * code's address), a username and password, and Approve or Deny. Plain HTML
 * with one inline style and no script, served with a Content-Security-Policy
 * that allows nothing else, so nothing on it can be swapped or injected.
 */

const STYLE = `
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center;
  background: #0b0d12; color: #eef1f6; font: 16px/1.45 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; padding: 16px; }
main { width: 100%; max-width: 380px; }
h1 { font-size: 24px; margin: 0 0 8px; }
p { color: #a7afbd; margin: 0 0 20px; }
label { display: block; font-size: 14px; color: #a7afbd; margin: 14px 0 6px; }
input { width: 100%; padding: 12px 14px; border-radius: 10px; border: 1px solid #2a3140; background: #131823; color: #eef1f6; font-size: 16px; }
input[name=code] { letter-spacing: 3px; text-transform: uppercase; font-weight: 700; }
.actions { display: flex; gap: 12px; margin-top: 22px; }
button { flex: 1; padding: 13px; border-radius: 10px; border: 0; font-size: 16px; font-weight: 700; cursor: pointer; }
.approve { background: #2f7cf6; color: #fff; }
.deny { background: #3a1418; color: #ff8a8a; border: 1px solid #6b2229; }
.notice { padding: 12px 14px; border-radius: 10px; margin-bottom: 18px; background: #131823; }
.error { background: #2a1114; color: #ff9b9b; }
.done { background: #10261a; color: #8ee0ad; }
`

const STYLE_HASH = createHash('sha256').update(STYLE).digest('base64')

/** Headers every answer of the device sign-in carries: never cached, never framed. */
export const DEVICE_PAGE_HEADERS: Record<string, string> = {
  'Content-Security-Policy': `default-src 'none'; style-src 'sha256-${STYLE_HASH}'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'`,
  'Cache-Control': 'no-store',
  'Referrer-Policy': 'no-referrer',
  'X-Content-Type-Options': 'nosniff',
}

export type PageNotice = { kind: 'error' | 'done'; text: string }

function shell(body: string): string {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8">` +
    `<meta name="viewport" content="width=device-width, initial-scale=1">` +
    `<title>Sign in to Halo on your TV</title><style>${STYLE}</style></head><body><main>${body}</main></body></html>`
}

/**
 * The approval form. `code` must already be a normalized code (letters from
 * the code alphabet only) or empty: it is placed in the page as is.
 */
export function approvalPage(code: string, notice?: PageNotice): string {
  const shown = code ? formatUserCode(code) : ''
  const noticeHtml = notice ? `<div class="notice ${notice.kind}">${notice.text}</div>` : ''
  return shell(
    `<h1>Sign in to Halo on your TV?</h1>` +
      `<p>Check that the code below matches the one on your TV, then sign in to approve it.</p>` +
      noticeHtml +
      `<form method="post" action="/auth/device/approve">` +
      `<label for="code">Code on your TV</label>` +
      `<input id="code" name="code" value="${shown}" maxlength="9" autocomplete="off" autocapitalize="characters" required>` +
      `<label for="username">Username</label>` +
      `<input id="username" name="username" autocomplete="username" autocapitalize="none" required>` +
      `<label for="password">Password</label>` +
      `<input id="password" name="password" type="password" autocomplete="current-password" required>` +
      `<div class="actions">` +
      `<button class="approve" name="decision" value="approve" type="submit">Approve</button>` +
      `<button class="deny" name="decision" value="deny" type="submit">Deny</button>` +
      `</div></form>`,
  )
}

/** What the phone shows once the TV has its answer. */
export function resultPage(approved: boolean): string {
  return shell(
    approved
      ? `<h1>Your TV is signed in</h1><div class="notice done">Halo on your TV will open in a moment. You can close this page.</div>`
      : `<h1>Sign-in declined</h1><div class="notice">Your TV was not signed in. You can close this page.</div>`,
  )
}
