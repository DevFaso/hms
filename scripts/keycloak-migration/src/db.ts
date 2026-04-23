/**
 * Source-of-truth reader for the HMS Postgres database.
 *
 * Only active, non-deleted users are migrated. Role assignments are joined
 * from `security.user_role_hospital_assignment` → `security.roles` →
 * `hospitals` so the result carries the denormalised `{hospitalId, role}`
 * pairs the Keycloak protocol mapper expects.
 */

import type { Pool, PoolClient } from 'pg';

export interface HmsUserRow {
  readonly id: string;
  readonly username: string;
  readonly email: string;
  readonly firstName: string | null;
  readonly lastName: string | null;
  readonly phoneNumber: string | null;
  readonly isActive: boolean;
}

export interface HmsRoleAssignmentRow {
  readonly userId: string;
  readonly hospitalId: string | null;
  readonly role: string;
}

export interface HmsUserWithRoles extends HmsUserRow {
  readonly assignments: readonly HmsRoleAssignmentRow[];
  readonly primaryHospitalId: string | null;
  readonly realmRoles: readonly string[];
}

type Queryable = Pool | PoolClient;

const USERS_QUERY = `
  SELECT id, username, email, first_name, last_name, phone_number, is_active
  FROM security.users
  WHERE is_active = TRUE
    AND is_deleted = FALSE
  ORDER BY username ASC
`;

const ASSIGNMENTS_QUERY = `
  SELECT a.user_id, a.hospital_id, r.code AS role_code
  FROM security.user_role_hospital_assignment a
  JOIN security.roles r ON r.id = a.role_id
  WHERE a.is_active = TRUE
`;

export async function readActiveUsers(db: Queryable): Promise<HmsUserRow[]> {
  const result = await db.query<{
    id: string;
    username: string;
    email: string;
    first_name: string | null;
    last_name: string | null;
    phone_number: string | null;
    is_active: boolean;
  }>(USERS_QUERY);

  return result.rows.map((row) => ({
    id: row.id,
    username: row.username,
    email: row.email,
    firstName: row.first_name,
    lastName: row.last_name,
    phoneNumber: row.phone_number,
    isActive: row.is_active,
  }));
}

export async function readRoleAssignments(db: Queryable): Promise<HmsRoleAssignmentRow[]> {
  const result = await db.query<{
    user_id: string;
    hospital_id: string | null;
    role_code: string;
  }>(ASSIGNMENTS_QUERY);

  return result.rows.map((row) => ({
    userId: row.user_id,
    hospitalId: row.hospital_id,
    role: row.role_code,
  }));
}

/**
 * Joins the two queries in JS (a single trip each) so callers can iterate
 * users with their assignment array pre-attached. We intentionally do NOT
 * LEFT JOIN in SQL — the script must still emit users with zero assignments
 * so ops has visibility into orphaned accounts before they land in Keycloak.
 */
export async function readUsersWithRoles(db: Queryable): Promise<HmsUserWithRoles[]> {
  const [users, assignments] = await Promise.all([
    readActiveUsers(db),
    readRoleAssignments(db),
  ]);

  const byUser = new Map<string, HmsRoleAssignmentRow[]>();
  for (const a of assignments) {
    const bucket = byUser.get(a.userId);
    if (bucket) {
      bucket.push(a);
    } else {
      byUser.set(a.userId, [a]);
    }
  }

  return users.map((u) => {
    const userAssignments = byUser.get(u.id) ?? [];
    const realmRoles = Array.from(
      new Set(userAssignments.map((a) => a.role).filter((r): r is string => Boolean(r))),
    ).sort();
    const primaryHospitalId = userAssignments.find((a) => a.hospitalId !== null)?.hospitalId ?? null;
    return {
      ...u,
      assignments: userAssignments,
      primaryHospitalId,
      realmRoles,
    };
  });
}
