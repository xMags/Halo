import type { Me } from '@halo/core'

/**
 * The mono caption under the account name. A server that has tiers reports
 * the account's, which then says it all (`PREMIUM`); without one the caption
 * names the account kind and whether it administers this server, as the
 * native app does.
 */
export function accountLabel(me: Pick<Me, 'isAdmin' | 'plan'> | undefined): string {
  if (me?.plan) return me.plan.toUpperCase()
  return me?.isAdmin ? 'ADMIN · HALO ACCOUNT' : 'HALO ACCOUNT'
}
