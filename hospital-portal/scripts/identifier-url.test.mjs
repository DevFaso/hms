import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

/*
 * Personal identifiers never travel in a URL the portal builds.
 *
 * A name, a birth date, a phone number, an e-mail address, an MRN, a national id or a login in a
 * query string or a path segment lands in proxy and access logs, in browser history and in
 * front-end telemetry. Searches and lookups keyed by one POST them in the body
 * (shared/identifier-body.ts); the backend twin is PersonalIdentifierUrlGuardTest.
 *
 * QA D3750: a search box's typed term (search, q, query, keyword, …) is very often one of those,
 * so a list's term is posted to `…/term-search`. Only a box that searches a catalogue of things
 * (drugs, tests, order sets, facilities) is allowlisted below, each with its reason.
 *
 * And every telemetry item is scrubbed before it leaves the browser: main.ts wires the
 * sanitizer as Faro's beforeSend.
 */

const portal = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const NAMES = [
  'name',
  'firstName',
  'lastName',
  'middleName',
  'fullName',
  'patientName',
  'dob',
  'dateOfBirth',
  'birthDate',
  'phone',
  'phoneNumber',
  'mobile',
  'msisdn',
  'email',
  'emailAddress',
  'mrn',
  'mri',
  'nationalId',
  'nationalUniqueId',
  'identifier',
  'username',
  'userName',
  'patientUsername',
  // QA D3750: a search box's free text is whatever was typed, very often one of the above.
  'search',
  'q',
  'query',
  'term',
  'searchTerm',
  'keyword',
  'keywords',
  'filter',
].join('|');

/** What builds a URL with one of those names, and how to recognise it. */
const SHAPES = [
  [
    'an HttpParams entry',
    new RegExp(
      String.raw`(?:\b(?:params|httpParams|queryParams)|new HttpParams\(\))(?:\s*\.(?:set|append)\([^)]*\))*\s*\.(?:set|append)\(\s*['"\x60](?:${NAMES})['"\x60]`,
      'g',
    ),
  ],
  [
    'a params object',
    new RegExp(
      String.raw`\bhttp\s*\.\w+(?:<[^;]*?>)?\([^;]*?\bparams:\s*\{[^}]*\b(?:${NAMES})\b\s*[:,}]`,
      'g',
    ),
  ],
  ['a query string', new RegExp(String.raw`[?&](?:${NAMES})=`, 'g')],
  [
    'a path segment',
    new RegExp(String.raw`/(?:email|phone|username|mrn|mri|identifier|dob|national-id)/\$\{`, 'g'),
  ],
  ['a path segment', new RegExp(String.raw`/\$\{(?:${NAMES})\}`, 'g')],
  [
    'a query builder',
    new RegExp(
      String.raw`\b(?:buildParams|toParams|queryParams)\(\{[^}]*\b(?:${NAMES})\b\s*[:,}]`,
      'g',
    ),
  ],
];

/** The source without its comments: a doc comment may describe a URL, a request may not build one. */
function code(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, (block) => block.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1');
}

/**
 * Where a match is not a request URL, and why. A new entry needs a reason.
 * `file` is relative to hospital-portal/, `text` must occur in the matched source.
 */
const CATALOGUE =
  "searches a catalogue of things, where a person's identifier has nothing to match";
const ALLOWED = [
  {
    file: 'src/app/audit-logs/audit-description-patterns.ts',
    text: '?q=',
    reason:
      'a pattern that reads a stored audit description (a hospital-name search), not a request URL',
  },
  {
    file: 'src/app/services/hospital.service.ts',
    text: "new HttpParams().set('q'",
    reason: `hospital names for the super-admin scope chip: ${CATALOGUE}`,
  },
  {
    file: 'src/app/services/lab.service.ts',
    text: "httpParams.set('keyword'",
    reason: `lab test definitions: ${CATALOGUE}`,
  },
  {
    file: 'src/app/services/order-set.service.ts',
    text: "params.set('search'",
    reason: `admission order-set names: ${CATALOGUE}`,
  },
  {
    file: 'src/app/services/pharmacy.service.ts',
    text: "new HttpParams().set('q'",
    reason: `the drug catalogue and the pharmacy registry (facilities): ${CATALOGUE}`,
  },
];

function files(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(dir, entry.name);
    if (entry.isDirectory()) return entry.name === 'node_modules' ? [] : files(path);
    return /\.ts$/.test(entry.name) && !/\.spec\.ts$/.test(entry.name) ? [path] : [];
  });
}

export function findIdentifierUrls(source) {
  const found = [];
  const scanned = code(source);
  for (const [shape, pattern] of SHAPES) {
    for (const match of scanned.matchAll(pattern)) {
      found.push({ shape, text: match[0], line: scanned.slice(0, match.index).split('\n').length });
    }
  }
  return found;
}

test('the portal never puts a personal identifier in a request URL', () => {
  const violations = [];
  const used = new Set();
  for (const path of files(resolve(portal, 'src'))) {
    const name = relative(portal, path).split('\\').join('/');
    for (const hit of findIdentifierUrls(readFileSync(path, 'utf8'))) {
      const allowed = ALLOWED.find((a) => a.file === name && hit.text.includes(a.text));
      if (allowed) {
        used.add(allowed);
        continue;
      }
      violations.push(`${name}:${hit.line} ${hit.shape}: ${hit.text}`);
    }
  }
  assert.deepEqual(
    violations,
    [],
    'POST the identifier or the typed term in the body (compactBody, …/term-search), never in the URL',
  );
  // A stale entry could hide a new offender in the same file.
  assert.deepEqual(
    ALLOWED.filter((a) => !used.has(a)).map((a) => `${a.file}: ${a.text}`),
    [],
    'remove the allowlist entries that no longer match anything',
  );
  for (const a of ALLOWED)
    assert.ok(a.reason?.trim(), `${a.file}: an allowlist entry needs a reason`);
});

test('the guard recognises each shape it forbids', () => {
  for (const source of [
    "let params = new HttpParams(); params = params.set('email', value);",
    "const p = new HttpParams().set('page', '0').set('name', typed);",
    "this.http.get('/patients/lookup', { params: { email, token } })",
    "this.http.get<X[]>('/patients/lookup', { params: { phone: typed } })",
    'this.http.get(`/patients/search?name=${typed}`)',
    'this.http.get(`/lookup/appointment/patient/phone/${phone}`)',
    'this.http.get(`${this.baseUrl}/doctor/${identifier}`)',
    "this.http\n      .get<{ ok: boolean }>('/auth/verify-email', {\n        params: { email, token },\n      })",
    'const params = buildParams({ page: 0, userName: filter.userName });',
    // QA D3750: a search box's typed term.
    "if (search) params = params.set('search', search);",
    "let httpParams = new HttpParams().set('q', query).set('page', page);",
    "this.http.get('/patients', { params: { hospitalId, search } })",
    'this.http.get(`/appointments?search=${term}`)',
  ]) {
    assert.equal(findIdentifierUrls(source).length > 0, true, source);
  }
  for (const source of [
    "params = params.set('page', '0').set('size', '20');",
    "this.sortOrder.set('name');",
    "await this.confirm.ask({ titleKey: 'X', params: { name: ward.name } });",
    '// the link is /verify?email=…&token=…',
    "this.http.post('/patients/search', { name: typed }, { params: httpParams });",
    "this.http.post('/patients/term-search', { search: term }, { params });",
  ]) {
    assert.deepEqual(findIdentifierUrls(source), [], source);
  }
});

test('every telemetry item is scrubbed before it leaves the browser', () => {
  const main = readFileSync(resolve(portal, 'src/main.ts'), 'utf8');
  assert.match(
    main,
    /initializeFaro\(\{[\s\S]*beforeSend:\s*\(item\)\s*=>\s*sanitizeTelemetryItem\(item\)/,
  );
  assert.match(main, /from '\.\/app\/shared\/telemetry\/telemetry-sanitizer'/);
});
