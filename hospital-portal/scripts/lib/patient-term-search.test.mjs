// QA D1274 guard (round 27; D1253 NOT_FIXED: birth date and e-mail): a patient
// search box that sends what was typed without reading what it is.
//
// Round 26 made the server's phone and MRN matching work in every picker; a
// birth date still found nobody anywhere, in any format, and a piece of an
// e-mail found nobody either. The shared picker sent the date as a NAME
// (`patientService.search({ name: q })`), the eleven hand-written typeaheads
// sent it to `?search=` as typed, and the patient list filtered on everything
// but the birth date.
//
// The one reading of a search term is shared/patient-query.ts; the portal
// reaches the server's patient search only through PatientService
// (`list(hospitalId, term)` and `searchPatientsByTerm(service, term, …)`, which add the
// birth-date or e-mail search the term calls for), and a page filters the
// patients it holds only through `patientMatchesTerm` (person-search.ts).
//
// Rules, over every non-spec `.ts` of src/app:
//  - `raw-name-search`: `patientService.search({ … name: … })` outside the
//    service — the term goes out as a name only (use `searchPatientsByTerm`).
//  - `direct-patient-search`: an HTTP call to `/patients/search` or
//    `/patients?search` outside services/patient.service.ts.
//  - `hand-composed-filter`: a page that builds its own patient filter from
//    `personNameMatches(` and `phoneMatches(` instead of `patientMatchesTerm(`.
// Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const SERVICE = 'services/patient.service.ts';
const SHARED = 'shared/person-search.ts';

const RAW_NAME_SEARCH = /\bpatientService\s*\.\s*search\(\s*\{[^}]*?\bname\s*:/g;
const DIRECT_SEARCH = /['"`]\/patients(?:\/search\b|\?search=)/g;

/** The term-search offenders of one source: `{ line, rule }`. */
export function patientTermFindings(source, rel = 'source') {
  const out = [];
  const lineOf = (i) => source.slice(0, i).split('\n').length;
  if (rel !== SERVICE) {
    for (const m of source.matchAll(RAW_NAME_SEARCH))
      out.push({ line: lineOf(m.index), rule: 'raw-name-search' });
    for (const m of source.matchAll(DIRECT_SEARCH))
      out.push({ line: lineOf(m.index), rule: 'direct-patient-search' });
  }
  if (rel !== SHARED && /\bpersonNameMatches\(/.test(source) && /\bphoneMatches\(/.test(source)) {
    out.push({
      line: lineOf(source.search(/\bpersonNameMatches\(/)),
      rule: 'hand-composed-filter',
    });
  }
  return out;
}

test('QA D1274: every patient search reads a birth date and an e-mail', () => {
  const files = walk(appDir, ['.ts']).filter((f) => !f.endsWith('.spec.ts'));
  assert.ok(files.length > 300, `scanned ${files.length} files`);
  const found = [];
  let typeaheads = 0;
  for (const f of files) {
    const rel = relative(appDir, f).replaceAll('\\', '/');
    const text = readFileSync(f, 'utf8');
    // The typeaheads served through the service (list(hid, term)): counted, so a
    // refactor that drops them from the service shows here.
    typeaheads += [...text.matchAll(/patientService\.list\((?:[^(),]|\([^()]*\))*,\s*[A-Za-z']/g)]
      .length;
    for (const x of patientTermFindings(text, rel)) found.push(`${rel}:${x.line} ${x.rule}`);
  }
  assert.ok(
    typeaheads >= 10,
    `the list(hospitalId, term) typeaheads must be seen, saw ${typeaheads}`,
  );
  assert.deepEqual(
    found,
    [],
    'search patients through searchPatientsByTerm / PatientService.list(hospitalId, term); filter through patientMatchesTerm',
  );
});

test('QA D1274 guard: the scan sees the round-27 shapes', () => {
  // The shared picker before the fix.
  const picker = `const scoped = this.patientService
            .search({
              name: q,
              hospitalId: this.hospitalId ?? undefined,
            })`;
  assert.deepEqual(
    patientTermFindings(picker, 'shared/patient-picker/patient-picker.component.ts').map(
      (f) => f.rule,
    ),
    ['raw-name-search'],
  );
  assert.deepEqual(
    patientTermFindings('searchPatientsByTerm(this.patientService, q, { size: 20 })'),
    [],
  );
  // The patient list before the fix.
  const list = 'result.filter((p) => personNameMatches(p, t) || phoneMatches(p.phone, t))';
  assert.deepEqual(
    patientTermFindings(list, 'patients/patient-list.ts').map((f) => f.rule),
    ['hand-composed-filter'],
  );
  assert.deepEqual(patientTermFindings('result.filter((p) => patientMatchesTerm(p, t))'), []);
  // A page calling the endpoint by hand.
  assert.deepEqual(
    patientTermFindings("this.http.get('/patients/search', { params })", 'x/y.ts').map(
      (f) => f.rule,
    ),
    ['direct-patient-search'],
  );
  assert.deepEqual(
    patientTermFindings("this.http.get('/patients/search', { params })", SERVICE),
    [],
  );
});
