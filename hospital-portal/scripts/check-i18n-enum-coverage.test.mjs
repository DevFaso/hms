#!/usr/bin/env node
/**
 * The enum gate is only as honest as its Java parser, and the first version of
 * that parser was not: it ended the constant list at the first `;`, including
 * one that lived inside a Javadoc sentence, and so reported seven real
 * PrescriptionStatus values as keys "the enum cannot emit" — the exact
 * false-negative the gate exists to prevent. These cases pin that.
 *
 * Run: npm run test:scripts   (node's built-in runner, no dependency)
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { javaEnumConstants } from './check-i18n-enum-coverage.mjs';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_DIR = resolve(SCRIPT_DIR, '..', '..');

test('reads a plain enum', () => {
    const src = 'public enum Color {\n    RED,\n    GREEN,\n    BLUE\n}\n';
    assert.deepEqual(javaEnumConstants(src, 'Color'), ['RED', 'GREEN', 'BLUE']);
});

test('a semicolon inside a Javadoc does not end the constant list', () => {
    const src = [
        'public enum Status {',
        '    DRAFT,',
        '    /** Not in stock; awaiting restock before dispensing. */',
        '    PENDING_STOCK,',
        '    SENT_TO_PARTNER;',
        '',
        '    public boolean isLive() { return this == DRAFT; }',
        '}',
    ].join('\n');
    assert.deepEqual(javaEnumConstants(src, 'Status'),
        ['DRAFT', 'PENDING_STOCK', 'SENT_TO_PARTNER']);
});

test('a line comment containing a semicolon is stripped too', () => {
    const src = 'public enum E {\n    A,\n    // first; second\n    B\n}\n';
    assert.deepEqual(javaEnumConstants(src, 'E'), ['A', 'B']);
});

test('stops at the real end of the constant list, not at the methods', () => {
    const src = [
        'public enum E {',
        '    A,',
        '    B;',
        '',
        '    static final String NOT_A_CONSTANT = "x";',
        '    public void f() {}',
        '}',
    ].join('\n');
    assert.deepEqual(javaEnumConstants(src, 'E'), ['A', 'B']);
});

test('reads constants that carry arguments', () => {
    const src = 'public enum E {\n    A("a"),\n    B("b");\n}\n';
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
    for (const value of ['PENDING_STOCK', 'SENT_TO_PARTNER', 'PARTNER_ACCEPTED', 'PRINTED_FOR_PATIENT']) {
        assert.ok(constants.includes(value), `${value} missing from the parse`);
    }
    assert.ok(!constants.includes('NOT_A_STATUS'));
});
