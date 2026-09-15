#!/usr/bin/env node
/**
 * The enum gate is only as honest as its Java parser, and the first version
 * was not: it ended the constant list at the first `;`, including one inside a
 * Javadoc sentence, and so reported seven real PrescriptionStatus values as
 * keys "the enum cannot emit" — exit 0, no warning. That is the exact
 * false-negative the gate exists to prevent, so every shape that has fooled it
 * (or could) gets a case here.
 *
 * Run: npm run test:scripts   (node's built-in runner, no dependency)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { javaEnumConstants, groupOf } from './java-enum.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_DIR = resolve(SCRIPT_DIR, '..', '..', '..');

const enumSource = (...lines) => ['public enum E {', ...lines, '}'].join('\n');

test('reads a plain enum', () => {
  assert.deepEqual(javaEnumConstants('public enum Color {\n  RED,\n  GREEN,\n  BLUE\n}', 'Color'), [
    'RED',
    'GREEN',
    'BLUE',
  ]);
});

test('a semicolon inside a Javadoc does not end the constant list', () => {
  const src = enumSource(
    '  DRAFT,',
    '  /** Not in stock; awaiting restock before dispensing. */',
    '  PENDING_STOCK,',
    '  SENT_TO_PARTNER;',
    '',
    '  public boolean isLive() { return this == DRAFT; }',
  );
  assert.deepEqual(javaEnumConstants(src, 'E'), ['DRAFT', 'PENDING_STOCK', 'SENT_TO_PARTNER']);
});

test('a line comment containing a semicolon is stripped too', () => {
  assert.deepEqual(javaEnumConstants(enumSource('  A,', '  // first; second', '  B'), 'E'), [
    'A',
    'B',
  ]);
});

test('a semicolon inside a constant argument does not end the list', () => {
  // The comment fix alone moved this bug from comments into string literals.
  assert.deepEqual(javaEnumConstants(enumSource('  A("x;y"),', '  B("z");'), 'E'), ['A', 'B']);
});

test('an annotated constant is still a constant', () => {
  // @Deprecated / @JsonProperty / @Schema on a constant is routine.
  assert.deepEqual(javaEnumConstants(enumSource('  @Deprecated A,', '  B'), 'E'), ['A', 'B']);
});

test('several constants on one line are all found', () => {
  assert.deepEqual(javaEnumConstants(enumSource('  A, B,', '  C'), 'E'), ['A', 'B', 'C']);
});

test('a constant carrying a class body is still a constant', () => {
  const src = enumSource('  A {', '    void f() {}', '  },', '  B;');
  assert.deepEqual(javaEnumConstants(src, 'E'), ['A', 'B']);
});

test('a semicolon inside a constant body does not end the list early', () => {
  const src = enumSource('  A {', '    void f() { int x = 1; }', '  },', '  B;');
  assert.deepEqual(javaEnumConstants(src, 'E'), ['A', 'B']);
});

test('reads constants that carry arguments', () => {
  assert.deepEqual(javaEnumConstants(enumSource('  A("a"),', '  B("b");'), 'E'), ['A', 'B']);
});

test('stops at the end of the constant list and takes nothing from the methods', () => {
  const src = enumSource(
    '  A,',
    '  B;',
    '',
    '  static final String NOT_A_CONSTANT = "x";',
    '  public static final int ALSO_NOT = 2;',
    '  public void f() { String LOCAL_LOOKING = "y"; }',
  );
  // The over-capture direction: an UPPER_SNAKE identifier after the `;` is a
  // field or a local, never a constant, and must not be reported as one.
  assert.deepEqual(javaEnumConstants(src, 'E'), ['A', 'B']);
});

test('returns null when the named enum is not in the file', () => {
  assert.equal(javaEnumConstants('public enum Other { X }', 'Missing'), null);
});

test('the real PrescriptionStatus keeps its partner-pharmacy statuses', () => {
  // The regression itself, against the file that exposed it.
  const src = readFileSync(
    resolve(REPO_DIR, 'hospital-core/src/main/java/com/example/hms/enums/PrescriptionStatus.java'),
    'utf8',
  );
  const constants = javaEnumConstants(src, 'PrescriptionStatus');
  for (const value of [
    'PENDING_STOCK',
    'SENT_TO_PARTNER',
    'PARTNER_ACCEPTED',
    'PRINTED_FOR_PATIENT',
  ]) {
    assert.ok(constants.includes(value), `${value} missing from the parse`);
  }
  // ...and nothing from the methods below the `;`.
  assert.deepEqual(
    constants.filter((c) => !/^[A-Z][A-Z0-9_]*$/.test(c)),
    [],
  );
  assert.equal(constants.length, new Set(constants).size, 'constants must not repeat');
});

test('an astral character in a comment does not swallow the next constant', () => {
  // blankCommentsAndStrings blanks by offset. Building its buffer with
  // [...source] splits by CODE POINT while every offset is UTF-16, so one
  // emoji shifted the mapping and silently dropped a constant — a dropped
  // constant is never checked for a key and the gate still exits 0.
  const plain = enumSource('  /* x */A,', '  B;');
  const astral = enumSource('  /* \u{1F691} */A,', '  B;');
  assert.deepEqual(javaEnumConstants(plain, 'E'), ['A', 'B']);
  assert.deepEqual(javaEnumConstants(astral, 'E'), ['A', 'B']);
});

test('a comment that quotes the declaration does not hijack the parse', () => {
  // The declaration regex used to run on the raw source while every offset
  // after it ran on the blanked copy, so a see-also comment naming the enum
  // sent the walk into a blanked region and failed a valid file.
  const src = [
    '/** Mirrors public enum E { OLD_A, OLD_B } in the legacy module. */',
    'public enum E {',
    '  A,',
    '  B;',
    '}',
  ].join('\n');
  assert.deepEqual(javaEnumConstants(src, 'E'), ['A', 'B']);
});

test('groupOf matches EnumLabelService.toUpperSnake, including on acronyms', () => {
  // The pipe splits at a lower/digit-to-upper boundary only. Splitting before
  // every capital would send the gate to PATIENT_M_R_N while the pipe reads
  // PATIENT_MRN — green gate, English screen.
  const service = readFileSync(
    resolve(SCRIPT_DIR, '..', '..', 'src/app/core/enum-label.service.ts'),
    'utf8',
  );
  const toUpperSnake =
    /toUpperSnake\(camel: string\): string \{\s*return camel\.replaceAll\(([^;]+)\)\.toUpperCase\(\);/.exec(
      service,
    );
  assert.ok(toUpperSnake, 'could not find EnumLabelService.toUpperSnake — keep this test honest');
  assert.match(toUpperSnake[1], /\(\[a-z0-9\]\)\(\[A-Z\]\)/, 'the lookup changed its snake rule');

  assert.equal(groupOf('prescriptionStatus'), 'PRESCRIPTION_STATUS');
  assert.equal(groupOf('status'), 'STATUS');
  assert.equal(groupOf('patientMRN'), 'PATIENT_MRN');
  assert.equal(groupOf('roi2Status'), 'ROI2_STATUS');
});
