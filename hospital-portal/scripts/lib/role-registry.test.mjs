#!/usr/bin/env node
/**
 * The role vocabulary is parsed out of SQL rather than read from an enum, so
 * every shape the migrations actually contain gets a case here — starting with
 * the one that already fooled a regex on this tree: V30 carries its rollback
 * DELETE as a `--` comment, and V159's DELETEs are conditional.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { roleNamesFrom, bareRoleName } from './role-registry.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_DIR = resolve(SCRIPT_DIR, '..', '..', '..');
const MIGRATIONS = resolve(REPO_DIR, 'hospital-core/src/main/resources/db/migration');
const SEEDER = resolve(
  REPO_DIR,
  'hospital-core/src/main/java/com/example/hms/seed/RoleSeeder.java',
);

const sql = (text) => [{ path: 'V1__x.sql', text }];
const java = (text) => [{ path: 'RoleSeeder.java', text }];

test('reads the names out of a roles INSERT', () => {
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `INSERT INTO "security".roles (id, code, name) VALUES\n` +
          `  (gen_random_uuid(), 'ROLE_DOCTOR', 'ROLE_DOCTOR'),\n` +
          `  (gen_random_uuid(), 'ROLE_NURSE', 'ROLE_NURSE');`,
      ),
    ),
    ['DOCTOR', 'NURSE'],
  );
});

test('an unquoted schema name is the same table', () => {
  assert.deepEqual(
    roleNamesFrom(sql(`INSERT INTO security.roles (code) VALUES ('ROLE_MIDWIFE');`)),
    ['MIDWIFE'],
  );
});

test('a role named only in a DELETE is not seeded by it', () => {
  // V159 retires roles. Whether the row survives is conditional, so the name
  // still has to be keyed — but the DELETE is not what creates it.
  assert.deepEqual(
    roleNamesFrom(sql(`DELETE FROM "security".roles WHERE code IN ('ROLE_GHOST');`)),
    [],
  );
});

test('a DELETE parked in a comment cannot seed a role either', () => {
  // The exact shape in V30: a rollback statement kept as documentation.
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `-- Rollback: DELETE FROM "security".roles WHERE code = 'ROLE_COMMENTED';\n` +
          `INSERT INTO "security".roles (code) VALUES ('ROLE_REAL');`,
      ),
    ),
    ['REAL'],
  );
});

test('a commented-out INSERT is not a seeded role', () => {
  assert.deepEqual(
    roleNamesFrom(sql(`-- INSERT INTO "security".roles (code) VALUES ('ROLE_PLANNED');`)),
    [],
  );
});

test('an INSERT into another table is ignored', () => {
  assert.deepEqual(
    roleNamesFrom(sql(`INSERT INTO "security".user_roles (role_id) SELECT id FROM r;`)),
    [],
  );
  // `role_permissions` starts with the same nine characters as `roles`.
  assert.deepEqual(
    roleNamesFrom(sql(`INSERT INTO "security".role_permissions (x) VALUES ('ROLE_DOCTOR');`)),
    [],
  );
});

test("reads RoleSeeder's catalog", () => {
  assert.deepEqual(
    roleNamesFrom(
      java(
        `roles.put("ROLE_STAFF",   "General support staff");\n` +
          `roles.put( "ROLE_LAB_MANAGER", "Lab manager" );`,
      ),
    ),
    ['LAB_MANAGER', 'STAFF'],
  );
});

test('a role only mentioned in a Java comment is not seeded', () => {
  assert.deepEqual(
    roleNamesFrom(
      java(
        `// roles.put("ROLE_OLD", "retired");\n` +
          `/* roles.put("ROLE_BLOCKED", "x"); */\n` +
          `roles.put("ROLE_KEPT", "y");`,
      ),
    ),
    ['KEPT'],
  );
});

test('names are deduped across sources and sorted', () => {
  assert.deepEqual(
    roleNamesFrom([
      { path: 'V2__seed.sql', text: `INSERT INTO "security".roles (code) VALUES ('ROLE_NURSE');` },
      {
        path: 'RoleSeeder.java',
        text: `roles.put("ROLE_NURSE", "x"); roles.put("ROLE_ADMIN", "y");`,
      },
    ]),
    ['ADMIN', 'NURSE'],
  );
});

test('a source that is neither .sql nor .java contributes nothing', () => {
  assert.deepEqual(roleNamesFrom([{ path: 'notes.md', text: `'ROLE_DOCTOR'` }]), []);
});

test('bareRoleName strips the prefix once and leaves bare names alone', () => {
  assert.equal(bareRoleName('ROLE_DOCTOR'), 'DOCTOR');
  assert.equal(bareRoleName('DOCTOR'), 'DOCTOR');
  assert.equal(bareRoleName('ROLE_ROLE_X'), 'ROLE_X');
});

test('the real migrations parse to the registry the portal keys', () => {
  const sources = readdirSync(MIGRATIONS)
    .filter((f) => f.endsWith('.sql'))
    .map((f) => ({ path: `${MIGRATIONS}/${f}`, text: readFileSync(`${MIGRATIONS}/${f}`, 'utf8') }));
  sources.push({ path: SEEDER, text: readFileSync(SEEDER, 'utf8') });
  const names = roleNamesFrom(sources);

  // A floor, not an exact count: a migration that adds a role should fail the
  // COVERAGE gate with an UNKEYED line naming it, not this test with an
  // off-by-one nobody can act on.
  assert.ok(names.length >= 33, `expected the seeded registry, got ${names.length}`);
  for (const expected of ['DOCTOR', 'NURSE', 'MIDWIFE', 'STAFF', 'PATIENT', 'SUPER_ADMIN']) {
    assert.ok(names.includes(expected), `${expected} missing from the parsed registry`);
  }
  // Retired by V159 where unheld — still parsed, deliberately. See the lib.
  assert.ok(names.includes('MANAGER'), 'V159-retired roles must still be keyed');
  assert.ok(
    names.every((n) => !n.startsWith('ROLE_')),
    'names come back bare',
  );
});
