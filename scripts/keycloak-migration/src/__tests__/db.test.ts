import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import { readUsersWithRoles } from '../db.ts';

interface QueryResult<T> {
  rows: T[];
}

/**
 * Lightweight stand-in for the pg Pool. We only exercise the query surface
 * used by `readUsersWithRoles`, and we discriminate between the two queries
 * by pattern-matching on the SQL text so tests stay resilient to whitespace.
 */
function poolStub(
  users: Array<Record<string, unknown>>,
  assignments: Array<Record<string, unknown>>,
): { query: (sql: string) => Promise<QueryResult<unknown>> } {
  return {
    async query(sql: string) {
      if (sql.includes('FROM security.users')) {
        return { rows: users };
      }
      if (sql.includes('FROM security.user_role_hospital_assignment')) {
        return { rows: assignments };
      }
      throw new Error(`Unexpected SQL: ${sql}`);
    },
  };
}

describe('readUsersWithRoles', () => {
  it('joins assignments to users and deduplicates realm roles', async () => {
    const pool = poolStub(
      [
        {
          id: 'u-1',
          username: 'alice',
          email: 'alice@example.com',
          first_name: 'Alice',
          last_name: 'Example',
          phone_number: '+1',
          is_active: true,
        },
      ],
      [
        { user_id: 'u-1', hospital_id: 'h-1', role_code: 'ROLE_DOCTOR' },
        { user_id: 'u-1', hospital_id: 'h-1', role_code: 'ROLE_DOCTOR' }, // duplicate
        { user_id: 'u-1', hospital_id: 'h-2', role_code: 'ROLE_LAB_SCIENTIST' },
      ],
    );

    const [alice] = await readUsersWithRoles(pool as Parameters<typeof readUsersWithRoles>[0]);

    assert.ok(alice);
    assert.equal(alice.id, 'u-1');
    assert.equal(alice.assignments.length, 3);
    assert.deepEqual(alice.realmRoles, ['ROLE_DOCTOR', 'ROLE_LAB_SCIENTIST']);
    assert.equal(alice.primaryHospitalId, 'h-1');
  });

  it('returns users with no assignments (orphaned) as empty arrays', async () => {
    const pool = poolStub(
      [
        {
          id: 'u-99',
          username: 'orphan',
          email: 'orphan@example.com',
          first_name: null,
          last_name: null,
          phone_number: null,
          is_active: true,
        },
      ],
      [],
    );

    const [orphan] = await readUsersWithRoles(pool as Parameters<typeof readUsersWithRoles>[0]);

    assert.ok(orphan);
    assert.deepEqual(orphan.assignments, []);
    assert.deepEqual(orphan.realmRoles, []);
    assert.equal(orphan.primaryHospitalId, null);
  });
});
