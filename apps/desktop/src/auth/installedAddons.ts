import type { SignInAddon, SignInAddonModule } from './signInAddons'

// The git-ignored local add-on module, bundled only when it exists: a glob
// that matches nothing is an empty object, so a public build simply has none.
const modules = import.meta.glob<SignInAddonModule>('../../../../local/desktop/signInAddons.ts', { eager: true })

export const installedSignInAddons: readonly SignInAddon[] = Object.values(modules).flatMap((module) => module.signInAddons)

export function signInAddonFor(mode: string): SignInAddon | undefined {
  return installedSignInAddons.find((addon) => addon.mode === mode)
}
