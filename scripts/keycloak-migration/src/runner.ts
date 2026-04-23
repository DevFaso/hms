/**
 * Core migration runner. Pure logic — no process.exit, no top-level I/O —
 * so it is fully unit-testable with mocked dependencies.
 */

import type { HmsUserWithRoles } from './db.ts';
import type { KeycloakAdminClient, KeycloakUserPayload } from './keycloak.ts';

export interface MigrationPlan {
  readonly dryRun: boolean;
  readonly forcePasswordReset: boolean;
  readonly requireEmailVerified: boolean;
}

export interface MigrationOutcome {
  readonly total: number;
  readonly created: number;
  readonly skipped: number;
  readonly failed: number;
  readonly orphaned: number;
  readonly failures: ReadonlyArray<{ readonly username: string; readonly reason: string }>;
}

export interface Logger {
  info(message: string, context?: Record<string, unknown>): void;
  warn(message: string, context?: Record<string, unknown>): void;
  error(message: string, context?: Record<string, unknown>): void;
}

export const consoleLogger: Logger = {
  info: (m, ctx) => console.log(format('INFO', m, ctx)),
  warn: (m, ctx) => console.warn(format('WARN', m, ctx)),
  error: (m, ctx) => console.error(format('ERROR', m, ctx)),
};

function format(level: string, message: string, context?: Record<string, unknown>): string {
  if (!context || Object.keys(context).length === 0) {
    return `[${new Date().toISOString()}] ${level.padEnd(5)} ${message}`;
  }
  return `[${new Date().toISOString()}] ${level.padEnd(5)} ${message} ${JSON.stringify(context)}`;
}

export function buildUserPayload(
  user: HmsUserWithRoles,
  plan: MigrationPlan,
): KeycloakUserPayload {
  const requiredActions: string[] = [];
  if (plan.forcePasswordReset) requiredActions.push('UPDATE_PASSWORD');
  if (!plan.requireEmailVerified) requiredActions.push('VERIFY_EMAIL');

  const roleAssignmentsJson = user.assignments
    .map((a) =>
      JSON.stringify({ hospitalId: a.hospitalId ?? '', role: a.role }),
    )
    .slice() // defensive copy before sort
    .sort();

  return {
    username: user.username,
    email: user.email,
    firstName: user.firstName,
    lastName: user.lastName,
    enabled: user.isActive,
    emailVerified: plan.requireEmailVerified,
    attributes: {
      hospital_id: user.primaryHospitalId ? [user.primaryHospitalId] : [],
      role_assignments: roleAssignmentsJson,
      ...(user.phoneNumber ? { phone_number: [user.phoneNumber] } : {}),
    },
    requiredActions,
  };
}

export interface MigrateOptions {
  readonly users: readonly HmsUserWithRoles[];
  readonly client: KeycloakAdminClient;
  readonly plan: MigrationPlan;
  readonly logger?: Logger;
}

export async function migrateUsers(options: MigrateOptions): Promise<MigrationOutcome> {
  const logger = options.logger ?? consoleLogger;
  const { users, client, plan } = options;

  let created = 0;
  let skipped = 0;
  let failed = 0;
  let orphaned = 0;
  const failures: Array<{ username: string; reason: string }> = [];

  for (const user of users) {
    if (user.assignments.length === 0) {
      orphaned += 1;
      logger.warn('User has no active role assignments; importing with no realm roles', {
        username: user.username,
        userId: user.id,
      });
    }

    try {
      const existingId = await client.findUserIdByUsername(user.username);
      if (existingId) {
        skipped += 1;
        logger.info('User already exists in Keycloak — skipping', {
          username: user.username,
          keycloakUserId: existingId,
        });
        continue;
      }

      const payload = buildUserPayload(user, plan);

      if (plan.dryRun) {
        logger.info('[dry-run] Would create user', {
          username: user.username,
          email: user.email,
          realmRoles: user.realmRoles,
          primaryHospitalId: user.primaryHospitalId,
          requiredActions: payload.requiredActions,
        });
        created += 1;
        continue;
      }

      const keycloakId = await client.createUser(payload);
      logger.info('Created Keycloak user', { username: user.username, keycloakId });

      const roles = await client.resolveRealmRoles(user.realmRoles);
      const missing = user.realmRoles.filter(
        (name) => !roles.some((r) => r.name === name),
      );
      if (missing.length > 0) {
        logger.warn('Some realm roles were not found in Keycloak and will be skipped', {
          username: user.username,
          missing,
        });
      }
      await client.assignRealmRoles(keycloakId, roles);

      if (plan.forcePasswordReset) {
        const actions = ['UPDATE_PASSWORD'];
        if (!plan.requireEmailVerified) actions.push('VERIFY_EMAIL');
        await client.sendExecuteActionsEmail(keycloakId, actions);
      }

      created += 1;
    } catch (err) {
      failed += 1;
      const reason = err instanceof Error ? err.message : String(err);
      failures.push({ username: user.username, reason });
      logger.error('Failed to migrate user', { username: user.username, reason });
    }
  }

  return {
    total: users.length,
    created,
    skipped,
    failed,
    orphaned,
    failures,
  };
}
