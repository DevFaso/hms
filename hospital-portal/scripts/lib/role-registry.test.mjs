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
import { readFileSync, statSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { roleNamesFrom, bareRoleName, READABLE } from './role-registry.mjs';
import { walk } from './walk.mjs';
import { validateDeclaration } from './enum-domains.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_DIR = resolve(SCRIPT_DIR, '..', '..', '..');
const DOMAINS = JSON.parse(
  readFileSync(resolve(SCRIPT_DIR, '..', 'i18n-enum-domains.json'), 'utf8'),
);

/**
 * Exactly what check-i18n-enum-coverage.mjs gathers, from the same
 * declaration.
 *
 * This used to be a flat `readdirSync` over the migration folder while the
 * gate used a recursive `walk` over the DECLARED paths — so a migration in a
 * subfolder, or a source added to the declaration, would be read by the gate
 * and not by the test that exists to prove they agree.
 */
const declaredSources = () =>
  DOMAINS.role.roles
    .map((path) => resolve(REPO_DIR, path))
    // statSync, not the extension: the gate branches on isDirectory(), and a
    // declared `.sql` FILE sent this helper's `walk` into readdirSync on a
    // regular file. Both path shapes validate, so both have to work here.
    .flatMap((full) => (statSync(full).isDirectory() ? walk(full, READABLE) : [full]))
    .map((path) => ({ path, text: readFileSync(path, 'utf8') }));

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

test('every spelling of the same table is the same table', () => {
  // A spelling the parser misses is a role that ships unkeyed, and only a
  // grand total of zero is an error — which the other 33 names prevent.
  for (const table of [
    `"security".roles`,
    `security.roles`,
    `"security"."roles"`,
    `security . roles`,
    `roles`,
  ]) {
    assert.deepEqual(
      roleNamesFrom(sql(`INSERT INTO ${table} (code) VALUES ('ROLE_MIDWIFE');`)),
      ['MIDWIFE'],
      `INSERT INTO ${table} was not read`,
    );
  }
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
  assert.deepEqual(
    roleNamesFrom(sql(`/* INSERT INTO "security".roles (code) VALUES ('ROLE_PLANNED'); */`)),
    [],
  );
});

test('punctuation inside a description does not end the statement', () => {
  // V2 seeds twenty-four roles in ONE statement, each with a prose
  // description. A `;` in one of them used to drop every role after it, and
  // a `--` used to comment out the rest of the line — silently, and with the
  // total still well above any floor a test could asserted against.
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `INSERT INTO "security".roles (code, description) VALUES\n` +
          `  ('ROLE_A', 'Runs the bench; signs the results'),\n` +
          `  ('ROLE_B', 'Covid--19 lead'),\n` +
          `  ('ROLE_C', 'it''s a role; really');`,
      ),
    ),
    ['A', 'B', 'C'],
  );
});

test('a role name that only appears in prose is still only prose', () => {
  // The names are read back out of the statement, so a mention inside a
  // description is indistinguishable from a seeded code. That over-match is
  // accepted — an extra name is a keyed label nothing sends, which the
  // coverage gate reports as a note — but it should not reach OUTSIDE the
  // statement.
  assert.deepEqual(
    roleNamesFrom(sql(`SELECT 'ROLE_ELSEWHERE'; INSERT INTO roles (code) VALUES ('ROLE_D');`)),
    ['D'],
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

test('reads a Java seeder whatever shape its catalog takes', () => {
  // Tying this to `roles.put(` coupled the gate to a local variable's name.
  // RoleSeeder uses a map, DevSyntheticDataSeeder a String[]; both are
  // declared sources.
  assert.deepEqual(
    roleNamesFrom(
      java(
        `roles.put("ROLE_STAFF",   "General support staff");\n` +
          `catalog.put( "ROLE_LAB_MANAGER", "Lab manager" );\n` +
          `String[] codes = { "ROLE_MIDWIFE", ROLE_CONSTANT };`,
      ),
    ),
    ['LAB_MANAGER', 'MIDWIFE', 'STAFF'],
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

test('a role literal in Java prose is still only read from a declared seeder', () => {
  // The .java rule is broad ON PURPOSE, and the declaration is what keeps it
  // honest: only files someone listed as role sources are read at all.
  assert.deepEqual(roleNamesFrom([{ path: 'SecurityConfig.java', text: `"ROLE_X"` }]), ['X']);
  assert.deepEqual(roleNamesFrom([{ path: 'SecurityConfig.txt', text: `"ROLE_X"` }]), []);
});

test('a source that is neither .sql nor .java contributes nothing', () => {
  assert.deepEqual(roleNamesFrom([{ path: 'notes.md', text: `'ROLE_DOCTOR'` }]), []);
});

test('bareRoleName strips the prefix once and leaves bare names alone', () => {
  assert.equal(bareRoleName('ROLE_DOCTOR'), 'DOCTOR');
  assert.equal(bareRoleName('DOCTOR'), 'DOCTOR');
  assert.equal(bareRoleName('ROLE_ROLE_X'), 'ROLE_X');
});

test('the role domain declares sources that exist and validate', () => {
  const errors = [];
  assert.equal(validateDeclaration('role', DOMAINS.role, errors), true, errors.join('; '));
  assert.ok(DOMAINS.role.roles.length >= 2, 'the migrations are not the only writer');
});

test('every declared Java seeder contributes at least one role', () => {
  // The Java half yields nothing today that the migrations do not, so a
  // parser that quietly stopped matching would be invisible in the total.
  for (const source of declaredSources().filter((s) => s.path.endsWith('.java'))) {
    assert.ok(
      roleNamesFrom([source]).length > 0,
      `${source.path} is declared as a role source but parses to no roles`,
    );
  }
});

test('the real migrations parse to the registry the portal keys', () => {
  const names = roleNamesFrom(declaredSources());

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
