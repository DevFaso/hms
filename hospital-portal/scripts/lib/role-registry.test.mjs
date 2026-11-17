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
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath } from 'node:url';

import { roleNamesFrom, roleSourcesFrom, bareRoleName } from './role-registry.mjs';
import { validateDeclaration } from './enum-domains.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_DIR = resolve(SCRIPT_DIR, '..', '..', '..');
const DOMAINS = JSON.parse(
  readFileSync(resolve(SCRIPT_DIR, '..', 'i18n-enum-domains.json'), 'utf8'),
);

/**
 * Exactly what check-i18n-enum-coverage.mjs gathers: both call
 * `roleSourcesFrom` on the same declaration. This used to be a private copy
 * of the gate's existsSync / statSync / walk, and the two had drifted once
 * already (a flat readdirSync here against a recursive walk there).
 */
const declaredGroups = () => {
  const { groups, errors } = roleSourcesFrom(DOMAINS.role.roles, REPO_DIR);
  assert.deepEqual(errors, [], 'the declared role sources resolve');
  return groups;
};
const declaredSources = () => declaredGroups().flatMap((g) => g.sources);

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
    `public.roles`,
    `roles`,
  ]) {
    assert.deepEqual(
      roleNamesFrom(sql(`INSERT INTO ${table} (code) VALUES ('ROLE_MIDWIFE');`)),
      ['MIDWIFE'],
      `INSERT INTO ${table} was not read`,
    );
  }
});

test('the last statement in a file needs no trailing semicolon', () => {
  assert.deepEqual(roleNamesFrom(sql(`INSERT INTO roles (code) VALUES ('ROLE_MIDWIFE')`)), [
    'MIDWIFE',
  ]);
});

test('a role seeded inside a DO block is still a seeded role', () => {
  // The first fix for the apostrophe case BLANKED the body, which hid the
  // conditional-seed idiom entirely — trading one silent drop for another.
  // The body is its own lexical scope instead: its quotes and comments are
  // handled, and an unterminated one cannot reach past the closing tag.
  const D = '$' + '$';
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `DO ${D} BEGIN IF NOT EXISTS (SELECT 1 FROM "security".roles WHERE code = 'ROLE_X')
` +
          `  THEN INSERT INTO "security".roles (code) VALUES ('ROLE_BLOOD_BANK');
` +
          `END IF; END ${D};`,
      ),
    ),
    // ROLE_X is named in the guard's SELECT, not seeded by the INSERT, and the
    // statement match starts at INSERT — so it is correctly not a role here.
    ['BLOOD_BANK'],
  );
});

test('punctuation inside a dollar-quoted VALUE does not end the statement', () => {
  // The round that made a `DO $$` seed visible re-opened this one level down:
  // the body was scanned in place, so its own `;` survived into the view the
  // statement matcher reads. Dollar-quoting is the idiomatic way to write a
  // description containing an apostrophe, so this is the shape a French seed
  // description takes.
  const D = '$' + '$';
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `INSERT INTO "security".roles (code, description) VALUES\n` +
          `  ('ROLE_A', 'plain'),\n` +
          `  ('ROLE_B', ${D}Gere l'acces; valide les resultats${D}),\n` +
          `  ('ROLE_C', 'plain');`,
      ),
    ),
    ['A', 'B', 'C'],
  );
  assert.deepEqual(
    roleNamesFrom(
      sql(`INSERT INTO roles (description, code) VALUES (${D}Covid--19 lead${D}, 'ROLE_T');`),
    ),
    ['T'],
  );
});

test('a nested block comment ends where it really ends', () => {
  // Postgres nests them. Stopping at the first `*/` let a commented-out INSERT
  // back out — and an extra parsed name is an UNKEYED build failure, not a
  // note, so a deliberately retired role would have failed the build.
  assert.deepEqual(
    roleNamesFrom(sql(`/* superseded: /* old */ INSERT INTO roles (c) VALUES ('ROLE_K'); */`)),
    [],
  );
});

test('a dollar-quoted body cannot desync the statements around it', () => {
  // 21 migrations here use $$, and one apostrophe inside a body — `patient's`
  // — used to open a string that swallowed the rest of the file, taking every
  // role INSERT after it with it. Same desync as the `;`-in-a-description bug
  // above, through a door the first fix did not model.
  const D = '$' + '$';
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `COMMENT ON TABLE roles IS ${D}the patient's catalogue${D};\n` +
          `INSERT INTO "security".roles (code) VALUES ('ROLE_MIDWIFE');`,
      ),
    ),
    ['MIDWIFE'],
  );
  // A tagged body, with a semicolon and a doubled quote inside it.
  assert.deepEqual(
    roleNamesFrom(
      sql(
        `DO $fn$ BEGIN RAISE NOTICE 'it''s; fine'; END $fn$;\n` +
          `INSERT INTO roles (code) VALUES ('ROLE_MIDWIFE');`,
      ),
    ),
    ['MIDWIFE'],
  );
  // A bare `$` that is not a quote costs nothing.
  assert.deepEqual(roleNamesFrom(sql(`-- cost: $5\nINSERT INTO roles (c) VALUES ('ROLE_X');`)), [
    'X',
  ]);
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

test('every declared path contributes at least one role', () => {
  // The Java half yields nothing today that the migrations do not, so a
  // parser that quietly stopped matching would be invisible in the total.
  // The gate runs the same per-path check (UNPARSEABLE ROLE SOURCE).
  const groups = declaredGroups();
  assert.equal(groups.length, DOMAINS.role.roles.length);
  for (const { path, sources } of groups) {
    assert.ok(
      roleNamesFrom(sources).length > 0,
      `${path} is declared as a role source but parses to no roles`,
    );
  }
});

test('roleSourcesFrom accounts for each declared path on its own', () => {
  const dir = mkdtempSync(join(tmpdir(), 'p4-roles-'));
  try {
    mkdirSync(join(dir, 'empty'));
    writeFileSync(join(dir, 'empty', 'README.md'), 'no migrations here');
    mkdirSync(join(dir, 'migrations'));
    writeFileSync(
      join(dir, 'migrations', 'V1__r.sql'),
      "INSERT INTO roles (name) VALUES ('ROLE_DOCTOR');",
    );
    writeFileSync(join(dir, 'Makefile'), 'not a source');

    const { groups, errors } = roleSourcesFrom(
      ['migrations', 'empty', 'Makefile', 'gone.sql'],
      dir,
    );
    // A declared folder with nothing readable is a GROUP with no sources — the
    // gate's per-path check fails it; a grand-total check never would.
    assert.deepEqual(
      groups.map((g) => [g.path, g.sources.length, roleNamesFrom(g.sources)]),
      [
        ['migrations', 1, ['DOCTOR']],
        ['empty', 0, []],
      ],
    );
    assert.equal(errors.length, 2);
    assert.match(errors[0], /UNREADABLE ROLE SOURCE Makefile/);
    assert.match(errors[1], /MISSING ROLE SOURCE gone\.sql/);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test('a source with no role literal is skipped before it is parsed, and loses nothing', () => {
  // The pre-filter is exact: a name is only read from a 'ROLE_X' (SQL) or
  // "ROLE_X" (Java) literal, so a file with none can contribute none.
  assert.deepEqual(roleNamesFrom(sql('CREATE TABLE roles (id uuid);')), []);
  assert.deepEqual(roleNamesFrom(java('class RoleSeeder { String x = "DOCTOR"; }')), []);
  // …and one with a literal only inside a DO block still yields it.
  assert.deepEqual(
    roleNamesFrom(sql("DO $$ BEGIN INSERT INTO roles (name) VALUES ('ROLE_MIDWIFE'); END $$;")),
    ['MIDWIFE'],
  );
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
