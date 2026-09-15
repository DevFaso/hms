#!/usr/bin/env node
/**
 * Every branch here is a way for a domain to stop being checked, which is why
 * the validator was moved out of the gate: the gate runs `main()` on import,
 * so anything left inside it can only be covered by a manual probe, and a
 * manual probe does not fail a future PR that reverts this.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { validateDeclaration, enumNameOf } from './enum-domains.mjs';

const check = (entry) => {
  const errors = [];
  const ok = validateDeclaration('d', entry, errors);
  return { ok, errors };
};

const JAVA = 'hospital-core/src/main/java/com/example/hms/enums/Foo.java';

const MIGRATIONS = 'hospital-core/src/main/resources/db/migration';

test('accepts the four declared forms', () => {
  assert.equal(check({ enum: JAVA }).ok, true);
  assert.equal(check({ enums: [JAVA, JAVA] }).ok, true);
  assert.equal(check({ roles: [MIGRATIONS] }).ok, true);
  assert.equal(check({ reason: 'a String column, not an enum' }).ok, true);
});

test('a roles declaration must name somewhere to read them from', () => {
  // Roles are rows, not a Java type, so the paths are a folder and a seeder
  // rather than a .java file — but an empty list still checks nothing.
  assert.equal(check({ roles: [] }).ok, false);
  assert.equal(check({ roles: MIGRATIONS }).ok, false);
  assert.equal(check({ roles: [''] }).ok, false);
  assert.equal(check({ roles: [null] }).ok, false);
  assert.match(check({ roles: [] }).errors[0], /non-empty array/);
});

test('roles is a source like the others, not an extra', () => {
  assert.equal(check({ enum: JAVA, roles: [MIGRATIONS] }).ok, false);
  assert.match(check({}).errors[0], /enum \/ enums \/ roles \/ reason/);
});

test('an empty enums array does not silence a domain', () => {
  // It declared exactly one source and checked nothing, which made it cheaper
  // than the `{}` this function exists to reject.
  const { ok, errors } = check({ enums: [] });
  assert.equal(ok, false);
  assert.match(errors[0], /non-empty array/);
});

test('a hollow or over-full declaration is rejected', () => {
  assert.equal(check({}).ok, false);
  assert.equal(check({ enum: JAVA, reason: 'both' }).ok, false);
  assert.equal(check(null).ok, false);
  assert.equal(check([JAVA]).ok, false);
});

test('a misspelled key is a typo, not an exemption', () => {
  // `group` is included on purpose: it used to be accepted, and accepting it
  // again would re-open a validated way to point the gate at a group the pipe
  // never reads.
  for (const entry of [{ enumm: JAVA }, { reasons: 'x' }, { enum: JAVA, group: 'X' }]) {
    const { ok, errors } = check(entry);
    assert.equal(ok, false);
    assert.match(errors[0], /unknown propert/);
  }
});

test('a non-string source is reported, not thrown', () => {
  // `{"enum": null}` used to reach resolve() and die on an unhandled TypeError.
  for (const entry of [{ enum: null }, { enum: 123 }, { enum: '  ' }]) {
    const { ok, errors } = check(entry);
    assert.equal(ok, false);
    assert.match(errors[0], /must be a path ending in \.java/);
  }
  assert.equal(check({ enums: [JAVA, null] }).ok, false);
});

test('the .java extension the message promises is actually required', () => {
  // Otherwise the name reaches `new RegExp` as "Foo.txt", where `.` matches
  // any character, and the failure surfaces later as MISSING ENUM FILE.
  const { ok, errors } = check({ enum: 'hospital-core/.../Foo.txt' });
  assert.equal(ok, false);
  assert.match(errors[0], /\.java/);
});

test('an exemption needs a reason a person can read', () => {
  assert.equal(check({ reason: '' }).ok, false);
  assert.equal(check({ reason: '   ' }).ok, false);
  assert.equal(check({ reason: 42 }).ok, false);
});

test('enumNameOf takes the type name off the path', () => {
  assert.equal(enumNameOf(JAVA), 'Foo');
  assert.equal(enumNameOf('a/b/StaffLeaveStatus.java'), 'StaffLeaveStatus');
});
