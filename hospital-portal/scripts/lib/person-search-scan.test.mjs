import { test } from 'node:test';
import assert from 'node:assert/strict';

import { personSearchFindings } from './person-search-scan.mjs';

test('QA D1020: the patient list shape is caught (raw name compare)', () => {
  const hits = personSearchFindings(
    'result.filter((p) => p.firstName.toLowerCase().includes(term) || p.lastName.toLowerCase().includes(term))',
  );
  assert.deepEqual(
    hits.map((h) => [h.rule, h.anchor]),
    [
      ['raw-name', 'p.firstName'],
      ['raw-name', 'p.lastName'],
    ],
  );
});

test('the ?? fallback and ?. shapes are caught', () => {
  const hits = personSearchFindings(
    "(a.patientName ?? '').toLowerCase().includes(term) || s.signedByName?.toLowerCase().includes(q)",
  );
  assert.deepEqual(
    hits.map((h) => h.anchor),
    ['a.patientName', 's.signedByName'],
  );
});

test('QA D1019: a raw phone compare is caught', () => {
  const hits = personSearchFindings('(p.phoneNumberPrimary?.includes(term) ?? false)');
  assert.deepEqual(
    hits.map((h) => [h.rule, h.anchor]),
    [['raw-phone', 'p.phoneNumberPrimary']],
  );
});

test('the shared helpers and non-person fields pass', () => {
  assert.deepEqual(
    personSearchFindings(
      "textMatches(a.patientName, term) || (o.labTestName ?? '').toLowerCase().includes(term) || h.name.toLowerCase().includes(term)",
    ),
    [],
  );
});
