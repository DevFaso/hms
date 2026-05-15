/**
 * KC-4 / P-6 — entry point for the one-shot user migration.
 *
 * Usage:
 *   HMS_DATABASE_URL=... KEYCLOAK_BASE_URL=... KEYCLOAK_ADMIN_USERNAME=...
 *   KEYCLOAK_ADMIN_PASSWORD=... npm run migrate -- --dry-run
 *
 * Exits with code 1 on any migration failure so CI jobs fail loudly.
 */

// `pg` ships CJS internals; Node ≥24 no longer synthesises named ESM exports
// for it, so the previous `import { Pool } from 'pg'` throws at module-eval
// with "does not provide an export named 'Pool'". The CJS-interop pattern
// below works on Node 20/22/24 and the runtime Pool surface is identical.
import pg from 'pg';
const { Pool } = pg;
import { ConfigError, loadConfig } from './config.ts';
import { readUsersWithRoles } from './db.ts';
import { KeycloakAdminClient } from './keycloak.ts';
import { consoleLogger, migrateUsers } from './runner.ts';

async function main(): Promise<void> {
  const config = loadConfig();

  consoleLogger.info('Starting KC-4 user migration', {
    realm: config.keycloak.realm,
    baseUrl: config.keycloak.baseUrl,
    dryRun: config.dryRun,
    forcePasswordReset: config.forcePasswordReset,
    batchSize: config.batchSize,
  });

  const pool = new Pool({ connectionString: config.databaseUrl });
  try {
    const users = await readUsersWithRoles(pool);
    consoleLogger.info('Loaded users from HMS database', { count: users.length });

    const client = new KeycloakAdminClient({
      baseUrl: config.keycloak.baseUrl,
      realm: config.keycloak.realm,
      adminClientId: config.keycloak.adminClientId,
      adminUsername: config.keycloak.adminUsername,
      adminPassword: config.keycloak.adminPassword,
    });

    const outcome = await migrateUsers({
      users,
      client,
      plan: {
        dryRun: config.dryRun,
        forcePasswordReset: config.forcePasswordReset,
        requireEmailVerified: config.requireEmailVerified,
      },
    });

    consoleLogger.info('Migration complete', {
      total: outcome.total,
      created: outcome.created,
      skipped: outcome.skipped,
      failed: outcome.failed,
      orphaned: outcome.orphaned,
    });

    if (outcome.failed > 0) {
      consoleLogger.error('Migration finished with failures', {
        failures: outcome.failures,
      });
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
    consoleLogger.error('Migration aborted with unrecoverable error', { reason });
  }
  process.exit(1);
});
