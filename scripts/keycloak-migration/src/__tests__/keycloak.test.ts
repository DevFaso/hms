import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { KeycloakAdminClient, KeycloakError } from '../keycloak.ts';

interface FetchCall {
  url: string;
  init: RequestInit;
}

function createFetchMock(
  handlers: Array<(call: FetchCall) => Response | Promise<Response>>,
): {
  fetch: typeof fetch;
  calls: FetchCall[];
} {
  const calls: FetchCall[] = [];
  let index = 0;
  const mock: typeof fetch = async (input, init = {}) => {
    const url = typeof input === 'string' ? input : (input as URL | Request).toString();
    const call = { url, init: init as RequestInit };
    calls.push(call);
    const handler = handlers[index++];
    if (!handler) {
      throw new Error(`Unexpected fetch #${calls.length} to ${url}`);
    }
    return handler(call);
  };
  return { fetch: mock, calls };
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function newClient(fetchImpl: typeof fetch, now: () => number = Date.now): KeycloakAdminClient {
  return new KeycloakAdminClient({
    baseUrl: 'https://kc.example.com',
    realm: 'hms',
    adminClientId: 'admin-cli',
    adminUsername: 'admin',
    adminPassword: 'secret',
    fetchImpl,
    now,
  });
}

describe('KeycloakAdminClient.findUserIdByUsername', () => {
  it('returns the user id when Keycloak returns an exact match', async () => {
    const { fetch, calls } = createFetchMock([
      () => json(200, { access_token: 'tok', expires_in: 300 }),
      () => json(200, [{ id: 'uuid-1', username: 'alice' }]),
    ]);
    const client = newClient(fetch);

    const id = await client.findUserIdByUsername('alice');

    assert.equal(id, 'uuid-1');
    assert.equal(calls.length, 2);
    assert.ok(calls[1]!.url.includes('username=alice'));
    assert.ok(calls[1]!.url.includes('exact=true'));
    assert.equal((calls[1]!.init.headers as Headers).get('Authorization'), 'Bearer tok');
  });

  it('returns null when the user does not exist', async () => {
    const { fetch } = createFetchMock([
      () => json(200, { access_token: 'tok', expires_in: 300 }),
      () => json(200, []),
    ]);
    const id = await newClient(fetch).findUserIdByUsername('missing');
    assert.equal(id, null);
  });
});

describe('KeycloakAdminClient.createUser', () => {
  it('parses the user id out of the Location header on 201', async () => {
    const { fetch } = createFetchMock([
      () => json(200, { access_token: 'tok', expires_in: 300 }),
      () =>
        new Response(null, {
          status: 201,
          headers: { Location: 'https://kc.example.com/admin/realms/hms/users/new-id' },
        }),
    ]);

    const id = await newClient(fetch).createUser({
      username: 'alice',
      email: 'alice@example.com',
      firstName: 'Alice',
      lastName: 'Example',
      enabled: true,
      emailVerified: false,
      attributes: {},
      requiredActions: [],
    });

    assert.equal(id, 'new-id');
  });

  it('throws KeycloakError on 409 conflict (caller decides what to do)', async () => {
    const { fetch } = createFetchMock([
      () => json(200, { access_token: 'tok', expires_in: 300 }),
      () => new Response('conflict', { status: 409 }),
    ]);

    await assert.rejects(
      () =>
        newClient(fetch).createUser({
          username: 'alice',
          email: 'alice@example.com',
          firstName: null,
          lastName: null,
          enabled: true,
          emailVerified: false,
          attributes: {},
          requiredActions: [],
        }),
      (err: unknown) => err instanceof KeycloakError && err.status === 409,
    );
  });
});

describe('KeycloakAdminClient token caching', () => {
  it('reuses the cached admin token until near expiry', async () => {
    let nowMs = 1_000_000;
    const { fetch, calls } = createFetchMock([
      () => json(200, { access_token: 'tok-1', expires_in: 300 }),
      () => json(200, []),
      () => json(200, []),
    ]);
    const client = newClient(fetch, () => nowMs);

    await client.findUserIdByUsername('a');
    nowMs += 60_000; // 1 min later, token still valid
    await client.findUserIdByUsername('b');

    assert.equal(calls.length, 3); // one token + two user lookups
    assert.ok(calls[0]!.url.includes('/realms/master/protocol/openid-connect/token'));
  });
});

describe('KeycloakAdminClient.resolveRealmRoles', () => {
  it('skips roles that Keycloak returns 404 for', async () => {
    const { fetch } = createFetchMock([
      () => json(200, { access_token: 'tok', expires_in: 300 }),
      () => json(200, { id: 'r1', name: 'ROLE_DOCTOR' }),
      () => new Response('not found', { status: 404 }),
    ]);

    const resolved = await newClient(fetch).resolveRealmRoles(['ROLE_DOCTOR', 'ROLE_GHOST']);

    assert.deepEqual(resolved, [{ id: 'r1', name: 'ROLE_DOCTOR' }]);
  });
});
