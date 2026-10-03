/**
 * `app_user_id` backfill and link check (docs/security/tenant-resolution.md §4.0).
 *
 * The backend identifies a Keycloak principal ONLY by the `appUserId` claim,
 * which the realm maps from the `app_user_id` user attribute. The KC-4
 * migration never wrote it, so until this runs every Keycloak user has no
 * local account as far as the backend is concerned: no hospital scope, no
 * super-admin (NO_LOCAL_USER).
 *
 * - `backfillAppUserIds` sets `app_user_id = users.id` on the Keycloak user
 *   with the same username (the match KC-4 used), leaving every other
 *   attribute as it was. Idempotent; `dryRun` writes nothing.
 * - `checkAppUserIdLinks` reads every realm user and counts the ones the
 *   backend would treat as unlinked: no `app_user_id`, one that is not a live
 *   HMS user, or one that belongs to a different username. The target is zero.
 */

import type { HmsUserRow } from './db.ts';
import type { KeycloakAdminClient, KeycloakUserRepresentation } from './keycloak.ts';
import type { Logger } from './runner.ts';

export const APP_USER_ID_ATTRIBUTE = 'app_user_id';

/** Keycloak's own service-account users carry no HMS identity and are not counted. */
const SERVICE_ACCOUNT_PREFIX = 'service-account-';

export interface BackfillOutcome {
  readonly total: number;
  readonly updated: number;
  readonly alreadyLinked: number;
  /** HMS users with no Keycloak user of the same username. */
  readonly notInKeycloak: number;
  /** Keycloak users whose existing `app_user_id` named someone else (overwritten unless dry-run). */
  readonly relinked: number;
  readonly failed: number;
  readonly failures: ReadonlyArray<{ readonly username: string; readonly reason: string }>;
}

export interface BackfillOptions {
  readonly users: readonly HmsUserRow[];
  readonly client: KeycloakAdminClient;
  readonly dryRun: boolean;
  readonly logger: Logger;
}

function appUserIdOf(rep: KeycloakUserRepresentation | null | undefined): readonly string[] {
  return rep?.attributes?.[APP_USER_ID_ATTRIBUTE] ?? [];
}

/** The representation with `app_user_id` set to exactly `[hmsUserId]`, every other field kept. */
export function withAppUserId(
  rep: KeycloakUserRepresentation,
  hmsUserId: string,
): KeycloakUserRepresentation {
  return {
    ...rep,
    attributes: { ...(rep.attributes ?? {}), [APP_USER_ID_ATTRIBUTE]: [hmsUserId] },
  };
}

export async function backfillAppUserIds(options: BackfillOptions): Promise<BackfillOutcome> {
  const { users, client, dryRun, logger } = options;
  let updated = 0;
  let alreadyLinked = 0;
  let notInKeycloak = 0;
  let relinked = 0;
  let failed = 0;
  const failures: Array<{ username: string; reason: string }> = [];

  for (const user of users) {
    try {
      const keycloakId = await client.findUserIdByUsername(user.username);
      if (!keycloakId) {
        notInKeycloak += 1;
        continue;
      }
      const rep = await client.getUser(keycloakId);
      if (!rep) {
        notInKeycloak += 1;
        continue;
      }
      const current = appUserIdOf(rep);
      if (current.length === 1 && current[0] === user.id) {
        alreadyLinked += 1;
        continue;
      }
      if (current.length > 0) {
        relinked += 1;
        logger.warn('Keycloak user carries an app_user_id for another account; it will be replaced', {
          keycloakUserId: keycloakId,
          userId: user.id,
        });
      }
      if (dryRun) {
        logger.info('[dry-run] Would set app_user_id', { keycloakUserId: keycloakId, userId: user.id });
      } else {
        await client.updateUser(keycloakId, withAppUserId(rep, user.id));
      }
      updated += 1;
    } catch (err) {
      failed += 1;
      const reason = err instanceof Error ? err.message : String(err);
      failures.push({ username: user.username, reason });
      logger.error('Failed to backfill app_user_id', { userId: user.id, reason });
    }
  }

  return { total: users.length, updated, alreadyLinked, notInKeycloak, relinked, failed, failures };
}

export interface LinkCheckOutcome {
  readonly checked: number;
  readonly linked: number;
  /** No `app_user_id` at all. */
  readonly missing: number;
  /** An `app_user_id` that is not a live (active, not deleted) HMS user. */
  readonly unknown: number;
  /** An `app_user_id` whose HMS user has a different username. */
  readonly mismatched: number;
}

export interface LinkCheckOptions {
  readonly users: readonly HmsUserRow[];
  readonly client: KeycloakAdminClient;
  readonly pageSize?: number;
}

/** Classify one realm user against the live HMS users, keyed by id. */
export function classifyLink(
  rep: KeycloakUserRepresentation,
  hmsById: ReadonlyMap<string, HmsUserRow>,
): 'linked' | 'missing' | 'unknown' | 'mismatched' {
  const ids = appUserIdOf(rep);
  if (ids.length !== 1 || !ids[0]) return 'missing';
  const hms = hmsById.get(ids[0]);
  if (!hms) return 'unknown';
  const username = (rep.username ?? '').toLowerCase();
  return hms.username.toLowerCase() === username ? 'linked' : 'mismatched';
}

export async function checkAppUserIdLinks(options: LinkCheckOptions): Promise<LinkCheckOutcome> {
  const pageSize = options.pageSize ?? 100;
  const hmsById = new Map(options.users.map((u) => [u.id, u] as const));
  const counts = { checked: 0, linked: 0, missing: 0, unknown: 0, mismatched: 0 };
  for (let first = 0; ; first += pageSize) {
    const page = await options.client.listUsers(first, pageSize);
    for (const rep of page) {
      if ((rep.username ?? '').startsWith(SERVICE_ACCOUNT_PREFIX)) continue;
      counts.checked += 1;
      counts[classifyLink(rep, hmsById)] += 1;
    }
    if (page.length < pageSize) break;
  }
  return counts;
}
