import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import type { HmsUserRow } from '../db.ts';
import type { KeycloakAdminClient, KeycloakUserRepresentation } from '../keycloak.ts';
import {
  APP_USER_ID_ATTRIBUTE,
  backfillAppUserIds,
  checkAppUserIdLinks,
  classifyLink,
  withAppUserId,
} from '../backfill.ts';

const silentLogger = {
  info: () => undefined,
  warn: () => undefined,
  error: () => undefined,
};

function hmsUser(id: string, username: string): HmsUserRow {
  return {
    id,
    username,
    email: `${username}@example.com`,
    firstName: null,
    lastName: null,
    phoneNumber: null,
    isActive: true,
  };
}

function realm(
  reps: Record<string, KeycloakUserRepresentation>,
  overrides: Partial<KeycloakAdminClient> = {},
): {
  client: KeycloakAdminClient;
  writes: Array<{ id: string; rep: KeycloakUserRepresentation }>;
} {
  const writes: Array<{ id: string; rep: KeycloakUserRepresentation }> = [];
  const stub: Partial<KeycloakAdminClient> = {
    findUserIdByUsername: async (username: string) =>
      Object.entries(reps).find(([, r]) => r.username === username)?.[0] ?? null,
    getUser: async (id: string) => reps[id] ?? null,
    updateUser: async (id: string, rep: KeycloakUserRepresentation) => {
      writes.push({ id, rep });
    },
    listUsers: async (first: number, max: number) => Object.values(reps).slice(first, first + max),
    ...overrides,
  };
  return { client: stub as KeycloakAdminClient, writes };
}

describe('withAppUserId', () => {
  it('sets app_user_id and keeps every other attribute and field', () => {
    const rep = { id: 'kc-1', username: 'alice', email: 'a@x', attributes: { hospital_id: ['h-1'] } };
    const out = withAppUserId(rep, 'u-1');
    assert.deepEqual(out.attributes, { hospital_id: ['h-1'], [APP_USER_ID_ATTRIBUTE]: ['u-1'] });
    assert.equal(out.email, 'a@x');
    assert.equal(out.username, 'alice');
  });
});

describe('backfillAppUserIds', () => {
  it('links an unlinked user, skips a linked one, counts one missing from the realm', async () => {
    const { client, writes } = realm({
      'kc-a': { id: 'kc-a', username: 'alice', attributes: { hospital_id: ['h-1'] } },
      'kc-b': { id: 'kc-b', username: 'bob', attributes: { [APP_USER_ID_ATTRIBUTE]: ['u-b'] } },
    });
    const outcome = await backfillAppUserIds({
      users: [hmsUser('u-a', 'alice'), hmsUser('u-b', 'bob'), hmsUser('u-c', 'carol')],
      client,
      dryRun: false,
      logger: silentLogger,
    });
    assert.equal(outcome.updated, 1);
    assert.equal(outcome.alreadyLinked, 1);
    assert.equal(outcome.notInKeycloak, 1);
    assert.equal(outcome.failed, 0);
    assert.deepEqual(writes.map((w) => w.id), ['kc-a']);
    assert.deepEqual(writes[0]?.rep.attributes, { hospital_id: ['h-1'], [APP_USER_ID_ATTRIBUTE]: ['u-a'] });
  });

  it('links a mixed-case HMS username to the lower-case user Keycloak stores', async () => {
    const { client, writes } = realm({ 'kc-j': { id: 'kc-j', username: 'jdoe' } });
    const outcome = await backfillAppUserIds({
      users: [hmsUser('u-j', 'JDoe')],
      client,
      dryRun: false,
      logger: silentLogger,
    });
    assert.equal(outcome.updated, 1);
    assert.equal(outcome.notInKeycloak, 0);
    assert.deepEqual(writes.map((w) => w.id), ['kc-j']);
  });

  it('writes nothing on a dry run but reports what it would write', async () => {
    const { client, writes } = realm({ 'kc-a': { id: 'kc-a', username: 'alice' } });
    const outcome = await backfillAppUserIds({
      users: [hmsUser('u-a', 'alice')],
      client,
      dryRun: true,
      logger: silentLogger,
    });
    assert.equal(outcome.updated, 1);
    assert.equal(writes.length, 0);
  });

  it('replaces an app_user_id that names another account, and counts it', async () => {
    const { client, writes } = realm({
      'kc-a': { id: 'kc-a', username: 'alice', attributes: { [APP_USER_ID_ATTRIBUTE]: ['u-other'] } },
    });
    const outcome = await backfillAppUserIds({
      users: [hmsUser('u-a', 'alice')],
      client,
      dryRun: false,
      logger: silentLogger,
    });
    assert.equal(outcome.relinked, 1);
    assert.deepEqual(writes[0]?.rep.attributes?.[APP_USER_ID_ATTRIBUTE], ['u-a']);
  });

  it('records a per-user failure and carries on', async () => {
    const { client: failing } = realm(
      { 'kc-a': { id: 'kc-a', username: 'alice' } },
      {
        updateUser: async () => {
          throw new Error('HTTP 500');
        },
      },
    );
    const outcome = await backfillAppUserIds({
      users: [hmsUser('u-a', 'alice')],
      client: failing,
      dryRun: false,
      logger: silentLogger,
    });
    assert.equal(outcome.failed, 1);
    assert.deepEqual(outcome.failures, [{ username: 'alice', reason: 'HTTP 500' }]);
  });
});

describe('classifyLink / checkAppUserIdLinks', () => {
  const hmsById = new Map([['u-a', hmsUser('u-a', 'Alice')]]);

  it('classifies linked, missing, unknown and mismatched realm users', () => {
    assert.equal(classifyLink({ username: 'alice', attributes: { [APP_USER_ID_ATTRIBUTE]: ['u-a'] } }, hmsById), 'linked');
    assert.equal(classifyLink({ username: 'alice' }, hmsById), 'missing');
    assert.equal(classifyLink({ username: 'alice', attributes: { [APP_USER_ID_ATTRIBUTE]: ['u-x'] } }, hmsById), 'unknown');
    assert.equal(classifyLink({ username: 'bob', attributes: { [APP_USER_ID_ATTRIBUTE]: ['u-a'] } }, hmsById), 'mismatched');
  });

  it('pages through the realm and skips service accounts', async () => {
    const { client } = realm({
      a: { username: 'alice', attributes: { [APP_USER_ID_ATTRIBUTE]: ['u-a'] } },
      b: { username: 'bob' },
      s: { username: 'service-account-hms-backend' },
    });
    const outcome = await checkAppUserIdLinks({ users: [hmsUser('u-a', 'alice')], client, pageSize: 1 });
    assert.deepEqual(outcome, { checked: 2, linked: 1, missing: 1, unknown: 0, mismatched: 0 });
  });
});
