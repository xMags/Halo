/**
 * The Halo server a build signs in to is fixed at build time, the way the
 * native app compiles in Config/ServerConfig.local.h: VITE_HALO_SERVER_URL,
 * normally set in the git-ignored apps/desktop/.env.local. vite.config.ts runs
 * the same check, so a build without a usable address fails before it starts.
 * Pure, so Node can load it from the Vite config and the tests.
 */
export function parseServerUrl(raw: string | undefined): string {
  const value = raw?.trim()
  if (!value) {
    throw new Error(
      'VITE_HALO_SERVER_URL is not set. Copy apps/desktop/.env.example to .env.local and set the Halo server this build signs in to.',
    )
  }
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`VITE_HALO_SERVER_URL is not a valid URL: ${value}`)
  }
  const loopback = url.hostname === '127.0.0.1' || url.hostname === 'localhost' || url.hostname === '[::1]'
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) {
    throw new Error('VITE_HALO_SERVER_URL must use https (plain http only for a server on this machine)')
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('VITE_HALO_SERVER_URL must not carry credentials, a query or a fragment')
  }
  return url.href.replace(/\/+$/, '')
}


/**
 * A server address typed into the sign-in form, for a self-hosted Halo server
 * with its own accounts. The same rules as the built-in address, said for a
 * person rather than a build: https unless the server is on this PC, and only
 * the address itself. A bare host gets https in front. Throws an Error whose
 * message is meant for the form.
 */
export function parseServerAddress(raw: string): string {
  let value = raw.trim()
  if (!value) throw new Error('Enter your server’s address.')
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) value = `https://${value}`
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error('That isn’t a server address.')
  }
  const loopback = url.hostname === '127.0.0.1' || url.hostname === 'localhost' || url.hostname === '[::1]'
  if (url.protocol !== 'https:' && !(url.protocol === 'http:' && loopback)) {
    throw new Error('Use an https address. Plain http only works for a server on this PC.')
  }
  if (url.username || url.password || url.search || url.hash) {
    throw new Error('Enter only the server’s address.')
  }
  return url.href.replace(/\/+$/, '')
}
