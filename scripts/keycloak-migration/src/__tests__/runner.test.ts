import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import type { HmsUserWithRoles } from '../db.ts';
import type { KeycloakAdminClient, KeycloakUserPayload } from '../keycloak.ts';
import { buildUserPayload, migrateUsers } from '../runner.ts';

const silentLogger = {
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};

function user(overrides: Partial<HmsUserWithRoles> = {}): HmsUserWithRoles {
  return {
    id: 'u-1',
    username: 'alice',
    email: 'alice@example.com',
    firstName: 'Alice',
    lastName: 'Example',
    phoneNumber: '+15551234',
    isActive: true,
    assignments: [
      { userId: 'u-1', hospitalId: 'h-1', role: 'ROLE_DOCTOR' },
      { userId: 'u-1', hospitalId: 'h-1', role: 'ROLE_LAB_SCIENTIST' },
    ],
    primaryHospitalId: 'h-1',
    realmRoles: ['ROLE_DOCTOR', 'ROLE_LAB_SCIENTIST'],
    ...overrides,
  };
}

interface CallLog {
  find: string[];
  created: KeycloakUserPayload[];
  resolved: Array<readonly string[]>;
  assigned: Array<{ userId: string; roleCount: number }>;
  emails: Array<{ userId: string; actions: readonly string[] }>;
}

function stubClient(overrides: Partial<KeycloakAdminClient> = {}): {
  client: KeycloakAdminClient;
  calls: CallLog;
} {
  const calls: CallLog = { find: [], created: [], resolved: [], assigned: [], emails: [] };
  const stub: Partial<KeycloakAdminClient> = {
    findUserIdByUsername: async (username: string) => {
      calls.find.push(username);
      return null;
    },
    createUser: async (payload: KeycloakUserPayload) => {
      calls.created.push(payload);
      return 'kc-' + payload.username;
    },
    resolveRealmRoles: async (names: readonly string[]) => {
      calls.resolved.push(names);
      return names.map((n, i) => ({ id: `role-${i}`, name: n }));
    },
    assignRealmRoles: async (userId: string, roles) => {
      calls.assigned.push({ userId, roleCount: roles.length });
    },
    sendExecuteActionsEmail: async (userId: string, actions: readonly string[]) => {
      calls.emails.push({ userId, actions });
    },
    ...overrides,
  };
  return { client: stub as KeycloakAdminClient, calls };
}

describe('buildUserPayload', () => {
  it('serialises role_assignments as sorted JSON strings and includes primary hospital_id', () => {
    const payload = buildUserPayload(user(), {
      dryRun: false,
      forcePasswordReset: true,
      requireEmailVerified: false,
    });

    assert.equal(payload.username, 'alice');
    assert.deepEqual(payload.attributes.hospital_id, ['h-1']);
    assert.deepEqual(payload.attributes.phone_number, ['+15551234']);
    assert.deepEqual(payload.attributes.role_assignments, [
      JSON.stringify({ hospitalId: 'h-1', role: 'ROLE_DOCTOR' }),
      JSON.stringify({ hospitalId: 'h-1', role: 'ROLE_LAB_SCIENTIST' }),
    ]);
    assert.deepEqual(payload.requiredActions, ['UPDATE_PASSWORD', 'VERIFY_EMAIL']);
    assert.equal(payload.emailVerified, false);
  });

  it('omits VERIFY_EMAIL when emails are already trusted', () => {
    const payload = buildUserPayload(user(), {
      dryRun: false,
      forcePasswordReset: true,
      requireEmailVerified: true,
    });
    assert.deepEqual(payload.requiredActions, ['UPDATE_PASSWORD']);
    assert.equal(payload.emailVerified, true);
  });

  it('emits empty attributes rather than nulls when data is missing', () => {
    const payload = buildUserPayload(
      user({
        phoneNumber: null,
        primaryHospitalId: null,
        assignments: [],
      }),
      { dryRun: false, forcePasswordReset: false, requireEmailVerified: false },
    );
    assert.deepEqual(payload.attributes.hospital_id, []);
    assert.deepEqual(payload.attributes.role_assignments, []);
    assert.equal(payload.attributes.phone_number, undefined);
    assert.deepEqual(payload.requiredActions, ['VERIFY_EMAIL']);
  });
});

describe('migrateUsers', () => {
  it('creates users, resolves + assigns roles, and triggers the reset email', async () => {
    const { client, calls } = stubClient();

    const outcome = await migrateUsers({
      users: [user()],
      client,
      plan: { dryRun: false, forcePasswordReset: true, requireEmailVerified: false },
      logger: silentLogger,
    });

    assert.deepEqual(outcome, {
      total: 1,
      created: 1,
      skipped: 0,
      failed: 0,
      orphaned: 0,
      failures: [],
    });
    assert.deepEqual(calls.find, ['alice']);
    assert.equal(calls.created.length, 1);
    assert.deepEqual(calls.resolved, [['ROLE_DOCTOR', 'ROLE_LAB_SCIENTIST']]);
    assert.deepEqual(calls.assigned, [{ userId: 'kc-alice', roleCount: 2 }]);
    assert.deepEqual(calls.emails, [
      { userId: 'kc-alice', actions: ['UPDATE_PASSWORD', 'VERIFY_EMAIL'] },
    ]);
  });

  it('is idempotent: pre-existing users are skipped without creating a duplicate', async () => {
    const { client, calls } = stubClient({
      findUserIdByUsername: async () => 'existing-id',
    });

    const outcome = await migrateUsers({
      users: [user(), user({ id: 'u-2', username: 'bob', email: 'bob@example.com' })],
      client,
      plan: { dryRun: false, forcePasswordReset: true, requireEmailVerified: false },
      logger: silentLogger,
    });

    assert.equal(outcome.skipped, 2);
    assert.equal(outcome.created, 0);
    assert.equal(calls.created.length, 0);
    assert.equal(calls.emails.length, 0);
  });

  it('dry-run mode does not call create/assign/email but counts would-be creations', async () => {
    const { client, calls } = stubClient();

    const outcome = await migrateUsers({
      users: [user()],
      client,
      plan: { dryRun: true, forcePasswordReset: true, requireEmailVerified: false },
      logger: silentLogger,
    });

    assert.equal(outcome.created, 1);
    assert.equal(calls.created.length, 0);
    assert.equal(calls.assigned.length, 0);
    assert.equal(calls.emails.length, 0);
  });

  it('counts orphaned users but still imports them with zero realm roles', async () => {
    const orphan = user({
      id: 'u-3',
      username: 'zoe',
      email: 'zoe@example.com',
      assignments: [],
      realmRoles: [],
      primaryHospitalId: null,
    });
    const { client } = stubClient();

    const outcome = await migrateUsers({
      users: [orphan],
      client,
      plan: { dryRun: false, forcePasswordReset: true, requireEmailVerified: false },
      logger: silentLogger,
    });

    assert.equal(outcome.orphaned, 1);
    assert.equal(outcome.created, 1);
  });

  it('continues past individual failures and reports them in the outcome', async () => {
    let call = 0;
    const { client } = stubClient({
      createUser: async (payload: KeycloakUserPayload) => {
        call += 1;
        if (call === 1) throw new Error('boom');
        return 'kc-' + payload.username;
      },
    });

    const outcome = await migrateUsers({
      users: [user(), user({ id: 'u-2', username: 'bob', email: 'bob@example.com' })],
      client,
      plan: { dryRun: false, forcePasswordReset: true, requireEmailVerified: false },
      logger: silentLogger,
    });

    assert.equal(outcome.failed, 1);
    assert.equal(outcome.created, 1);
    assert.equal(outcome.failures.length, 1);
    assert.equal(outcome.failures[0]!.username, 'alice');
    assert.match(outcome.failures[0]!.reason, /boom/);
  });
});
