// QA D1252 guard (round 25): a list filter that cannot reach a type the
// server returns.
//
// /my-documents filtered with the upload choices, which leave out the
// doctor's medical certificate: five certificates showed under "Tous les
// types" and under no other choice. The filter now offers every key of
// DOCUMENT_TYPE_LABELS, a Record over the portal's PatientDocumentType union
// (a missing label does not compile). This test holds the union to the
// server's enum, so a type the server adds is a type the patient can filter.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const portal = join(here, '..', '..', 'src', 'app');
const javaEnum = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'main',
  'java',
  'com',
  'example',
  'hms',
  'enums',
  'PatientDocumentType.java',
);

/** The constants of a Java enum (comments removed). */
export function javaEnumValues(java) {
  const body = java
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/.*$/gm, '')
    .replace(/^[\s\S]*?\benum\s+\w+\s*\{/, '');
  return body
    .slice(0, body.search(/[;}]/))
    .split(',')
    .map((v) => v.trim())
    .filter(Boolean);
}

/** The members of a TS string-literal union `export type Name = | 'A' | 'B';`. */
export function unionValues(ts, name) {
  const code = ts.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
  const m = new RegExp(`export type ${name}\\s*=([^;]*);`).exec(code);
  return m ? [...m[1].matchAll(/'([A-Z_]+)'/g)].map((x) => x[1]) : [];
}

test('QA D1252: the portal knows every document type the server lists, and the filter offers them all', () => {
  const server = javaEnumValues(readFileSync(javaEnum, 'utf8'));
  assert.ok(server.includes('MEDICAL_CERTIFICATE'), `the enum must be read, saw ${server}`);
  const service = readFileSync(join(portal, 'services', 'patient-portal.service.ts'), 'utf8');
  assert.deepEqual(unionValues(service, 'PatientDocumentType').sort(), [...server].sort());
  const page = readFileSync(
    join(portal, 'patient-portal', 'my-documents', 'my-documents.component.ts'),
    'utf8',
  );
  const html = readFileSync(
    join(portal, 'patient-portal', 'my-documents', 'my-documents.component.html'),
    'utf8',
  );
  assert.match(page, /DOCUMENT_TYPE_LABELS: Readonly<Record<PatientDocumentType, string>>/);
  assert.match(page, /readonly filterTypes = ALL_TYPES;/);
  const filter = html.slice(html.indexOf('FILTER_TYPE'));
  assert.match(filter, /@for \(dt of filterTypes;/, 'the filter iterates every type');
});

test('QA D1252 guard: the parsers read the enum and the union', () => {
  assert.deepEqual(javaEnumValues('public enum T {\n  A,\n  /** x */\n  B,\n  C\n}'), [
    'A',
    'B',
    'C',
  ]);
  assert.deepEqual(unionValues("export type T =\n  | 'A'\n  /** y */\n  | 'B';", 'T'), ['A', 'B']);
});
