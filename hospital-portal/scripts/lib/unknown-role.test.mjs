#!/usr/bin/env node
/**
 * The portal's `UNKNOWN_ROLE` sentinel must stay the exact sentence the
 * backend stamps.
 *
 * `bareRole` (core/role-token.ts) maps the literal `"Unknown Role"` to null so
 * a role chip hides instead of rendering an English placeholder. The backend
 * writes that sentence into `audit_event_logs.role_name` when it cannot
 * resolve a role, and three Java files spell it independently
 * (AuditEventLogServiceImpl, AuditEventLogMapper,
 * DashboardConfigurationServiceImpl). Reword any of them — "Unknown role",
 * "Role unknown" — and the sentinel silently stops matching: the chip renders
 * the new English sentence to a French clinician and no gate notices. Rows
 * already written keep the old spelling forever, so the portal constant has
 * to outlive any backend change to the WRITE path; that is why this is a
 * guard rather than a deletion.
 *
 * Same shape as MigrationRegistrationTest: read the source, compare the
 * literal. Runs for as long as the TS constant exists.
 *
 * Run: npm run test:scripts
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';

import { walk } from './walk.mjs';

const PORTAL_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const REPO_DIR = resolve(PORTAL_DIR, '..');
const ROLE_TOKEN = resolve(PORTAL_DIR, 'src', 'app', 'core', 'role-token.ts');
const JAVA_MAIN = resolve(REPO_DIR, 'hospital-core', 'src', 'main', 'java');

/** The initializer of `const UNKNOWN_ROLE = '…'`, or undefined once it is gone. */
function portalSentinel() {
  const file = ts.createSourceFile(
    ROLE_TOKEN,
    readFileSync(ROLE_TOKEN, 'utf8'),
    ts.ScriptTarget.Latest,
    true,
  );
  let value;
  const visit = (node) => {
    if (
      ts.isVariableDeclaration(node) &&
      ts.isIdentifier(node.name) &&
      node.name.text === 'UNKNOWN_ROLE' &&
      node.initializer &&
      ts.isStringLiteralLike(node.initializer)
    ) {
      value = node.initializer.text;
    }
    ts.forEachChild(node, visit);
  };
  visit(file);
  return value;
}

/**
 * Every Java string literal that reads as the "unknown role" sentence, in any
 * casing or word order, with where it is. An upper-snake TOKEN — the
 * `ROLE_UNKNOWN` role code in DashboardConfigurationServiceImpl, the
 * `UNKNOWN_ROLE` error code in UserRoleHospitalAssignmentServiceImpl — is
 * machinery, not the sentence a person reads, and does not match.
 */
function javaSentences() {
  const SENTENCE = /^(?:unknown[\s_-]+role|role[\s_-]+unknown)$/i;
  const TOKEN = /^[A-Z][A-Z0-9_]*$/;
  const found = [];
  for (const file of walk(JAVA_MAIN, ['.java'])) {
    const text = readFileSync(file, 'utf8');
    if (!/unknown/i.test(text)) continue;
    for (const [, literal] of text.matchAll(/"((?:[^"\\\n]|\\.)*)"/g)) {
      if (SENTENCE.test(literal.trim()) && !TOKEN.test(literal)) {
        found.push({ literal, file: relative(REPO_DIR, file).replaceAll('\\', '/') });
      }
    }
  }
  return found;
}

const sentinel = portalSentinel();

test('the portal still declares the sentinel this guard protects', { skip: !sentinel }, () => {
  assert.equal(typeof sentinel, 'string');
});

test(
  'every Java spelling of the sentence is the one the portal maps to null',
  {
    skip: sentinel === undefined && 'UNKNOWN_ROLE is gone from role-token.ts',
  },
  () => {
    const found = javaSentences();
    // Zero is not a pass. Legacy rows still carry the sentence, so as long as
    // the portal constant exists the backend is expected to write it or to map
    // it on read — either way the literal is in Java. None means the scan broke
    // or every writer and reader moved on; in the second case delete
    // UNKNOWN_ROLE from role-token.ts (only if the read paths provably map
    // legacy rows to null) and this test skips.
    assert.ok(found.length > 0, 'no Java literal spells the "Unknown Role" sentence any more');
    for (const { literal, file } of found) {
      assert.equal(
        literal,
        sentinel,
        `${file} spells the sentence ${JSON.stringify(literal)}; role-token.ts maps ` +
          `${JSON.stringify(sentinel)} to null, so this row would render as English text`,
      );
    }
  },
);
