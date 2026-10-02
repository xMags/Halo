import type { OtherAuthConfig } from '@halo/core'

/**
 * A sign-in method a local build adds for a server auth mode this app does
 * not know itself (it knows OIDC and local accounts). Public builds install
 * none. A local build puts a module at `local/desktop/signInAddons.ts` in the
 * repository root, a git-ignored folder, exporting {@link SignInAddonModule};
 * installedAddons.ts bundles it when it is there.
 *
 * An add-on owns its whole session: persistence, the token the API client
 * sends, what a 401 means and sign-out. It keeps the app's rule that only a
 * definitive rejection ends a session and a network failure never does.
 */
export interface SignInAddon {
  /** The `/auth/config` mode this add-on signs in for. */
  readonly mode: string
  /** The sign-in button's text. */
  readonly buttonLabel: string
  /**
   * Runs the browser sign-in for the server at `serverUrl`, whose
   * `/auth/config` answered `config`, and persists the session it gets.
   * `onOpened` hears the sign-in URL once the browser has it. A refusal by
   * the provider throws a SignInRefusedError (signInFailure.ts).
   */
  signIn(serverUrl: string, config: OtherAuthConfig, onOpened?: (authUrl: string) => void): Promise<void>
  /** Loads a persisted session into memory; true when there is one. */
  restore(): boolean
  /** Forgets the session on this device only. */
  clear(): void
  /** Ends the session with the provider (best-effort) and forgets it. */
  signOut(): Promise<void>
  getAccessToken(): Promise<string | null>
  /** The API client's post-401 hook: a token to retry with, or null once the session is over. */
  refreshAccessToken(): Promise<string | null>
  /** Opaque local partition for device-owned downloads. Not an auth claim. */
  getDownloadOwner(): string | null
  setDownloadOwner(downloadOwner: string): void
}

/** What `local/desktop/signInAddons.ts` exports. */
export interface SignInAddonModule {
  signInAddons: readonly SignInAddon[]
}
