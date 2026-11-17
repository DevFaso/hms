/**
 * Entry point for the `app_user_id` backfill (docs/security/tenant-resolution.md §4.0).
 *
 * Usage (same environment as `npm run migrate`):
 *   npm run backfill:app-user-id -- --check     # read-only: count unlinked realm users, exit 1 if any
 *   npm run backfill:app-user-id -- --dry-run   # show what would be written
 *   npm run backfill:app-user-id                # write app_user_id, then re-check
 *
 * Run it wherever the Keycloak path is live BEFORE the one-resolver backend
 * deploys: that backend refuses hospital-scoped work to a Keycloak user with
 * no app_user_id.
 */

import pg from 'pg';
const { Pool } = pg;
import { ConfigError, loadConfig } from './config.ts';
import { readActiveUsers } from './db.ts';
import { KeycloakAdminClient } from './keycloak.ts';
import { consoleLogger } from './runner.ts';
import { backfillAppUserIds, checkAppUserIdLinks } from './backfill.ts';

async function main(): Promise<void> {
  const config = loadConfig();
  const checkOnly = process.argv.slice(2).includes('--check');

  const pool = new Pool({ connectionString: config.databaseUrl });
  try {
    const users = await readActiveUsers(pool);
    const client = new KeycloakAdminClient({
      baseUrl: config.keycloak.baseUrl,
      realm: config.keycloak.realm,
      adminClientId: config.keycloak.adminClientId,
      adminUsername: config.keycloak.adminUsername,
      adminPassword: config.keycloak.adminPassword,
    });

    if (!checkOnly) {
      const outcome = await backfillAppUserIds({
        users,
        client,
        dryRun: config.dryRun,
        logger: consoleLogger,
      });
      consoleLogger.info('app_user_id backfill complete', {
        dryRun: config.dryRun,
        total: outcome.total,
        updated: outcome.updated,
        alreadyLinked: outcome.alreadyLinked,
        notInKeycloak: outcome.notInKeycloak,
        relinked: outcome.relinked,
        failed: outcome.failed,
      });
      if (outcome.failed > 0) {
        process.exitCode = 1;
      }
    }

    const check = await checkAppUserIdLinks({ users, client });
    consoleLogger.info('app_user_id link check', { ...check });
    if (check.missing + check.unknown + check.mismatched > 0) {
      consoleLogger.warn('Realm users the backend will treat as unlinked (NO_LOCAL_USER); target is zero');
      process.exitCode = 1;
    }
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  if (err instanceof ConfigError) {
    consoleLogger.error(err.message);
  } else {
    const reason = err instanceof Error ? err.message : String(err);
    consoleLogger.error('app_user_id backfill aborted', { reason });
  }
  process.exit(1);
});
