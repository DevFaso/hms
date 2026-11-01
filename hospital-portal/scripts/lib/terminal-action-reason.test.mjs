// QA D1157 / D1160 / D1159 guard (round 21), widened for D1180 (round 22): an
// action that ends a record sent with nothing in it.
//
// Round 21: "Fermer" on a waitlist entry and "Annuler" on a recall posted `{}`
// on one click: no confirmation, no reason, and the server accepted it.
//
// Round 22 (D1180, a REGRESSION): #1001 made the server require a reason to
// revoke an advance directive, and the portal still sent `{}` — an active DNR
// could no longer be revoked. This file had pinned that exact call "for the
// backend wave", and nothing failed when the backend wave shipped.
//
// Two rules now:
//
// 1. No portal call to an endpoint that cancels, closes, voids, revokes,
//    discontinues, withdraws or rejects sends an empty body (`{}`, `null`,
//    `undefined`, nothing), unless the site is pinned with why it needs none.
// 2. The server's own list is the contract. hospital-core's
//    CancelVoidReasonGuardTest lists every handler that requires a reason and
//    where it reads it from (`DTO.field`, `body.field`, `@RequestParam name`).
//    Every portal call to such a handler must send it: a body that is not
//    empty (and, when it is an object literal, names the field), or the query
//    parameter. A reason the server starts requiring is therefore required of
//    the portal in the same build — a half-shipped contract fails here.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';
import { balanced, endOfLiteralOrComment } from './js-scan.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');
const SERVER_GUARD = join(
  here,
  '..',
  '..',
  '..',
  'hospital-core',
  'src',
  'test',
  'java',
  'com',
  'example',
  'hms',
  'architecture',
  'CancelVoidReasonGuardTest.java',
);

const TERMINAL = /\/(?:cancel|close|void|revoke|discontinue|withdraw|reject)\b/;

/** Sites that end a record legitimately without a reason, each with why. */
const PINS = new Map([
  [
    'reception/reception.service.ts::/recalls/{}/close',
    'closing a recall records that it was FULFILLED (the patient came back); "Annuler" is the cancel and takes a reason (QA D1157)',
  ],
  [
    'services/hospital-lifecycle.service.ts::/super-admin/hospitals/{}/cancel-purge',
    'a platform administrator stopping a scheduled purge (it keeps the record), not ending one',
  ],
  [
    'services/integration-keys.service.ts::/api-keys/{}/revoke',
    'an integration credential revoked by an administrator, not a clinical record',
  ],
  [
    'services/integration-keys.service.ts::/webhook-endpoints/{}/revoke',
    'an integration endpoint revoked by an administrator, not a clinical record',
  ],
  [
    'services/staff-scheduling.service.ts::/staff/scheduling/leaves/{}/cancel',
    'a staff member withdrawing their own leave request, not a clinical record',
  ],
]);

/** The top-level, comma-separated arguments inside a balanced `( … )` group. */
export function topLevelArgs(group) {
  const inner = group.slice(1, -1);
  const out = [];
  let depth = 0;
  let start = 0;
  for (let i = 0; i < inner.length; i++) {
    const skipped = endOfLiteralOrComment(inner, i);
    if (skipped >= 0) {
      i = skipped;
      continue;
    }
    const ch = inner[i];
    if ('([{'.includes(ch)) depth++;
    else if (')]}'.includes(ch)) depth--;
    else if (ch === ',' && depth === 0) {
      out.push(inner.slice(start, i).trim());
      start = i + 1;
    }
  }
  const last = inner.slice(start).trim();
  if (last !== '') out.push(last);
  return out;
}

/**
 * A URL argument as a path pattern: `${…}` and every non-literal part of a
 * concatenation become `{}` (`\`/recalls/${id}/cancel\`` → `/recalls/{}/cancel`).
 */
export function urlPattern(arg) {
  const pieces = [];
  let rest = arg.trim();
  while (rest !== '') {
    const ch = rest[0];
    if (ch === '`' || ch === "'" || ch === '"') {
      const end = endOfLiteralOrComment(rest, 0);
      pieces.push(rest.slice(1, end).replace(/\$\{\s*([^}]*?)\s*\}/g, (_, v) => `{${v}}`));
      rest = rest.slice(end + 1).trim();
    } else {
      const plus = rest.indexOf('+');
      pieces.push(`{${(plus < 0 ? rest : rest.slice(0, plus)).trim()}}`);
      rest = plus < 0 ? '' : rest.slice(plus).trim();
    }
    if (rest.startsWith('+')) rest = rest.slice(1).trim();
  }
  return pieces.join('');
}

/** `{this.baseUrl}` → `baseUrl`: the name a URL piece reads, when it is a plain member or constant. */
function variableName(piece) {
  const m = /^\{(?:this\.)?([A-Za-z_$][\w$]*)\}$/.exec(piece);
  return m ? m[1] : null;
}

/**
 * The URL pattern with a leading base variable (`${this.baseUrl}`, `${BASE}`)
 * replaced by its value when the file declares it as a string
 * (`private readonly baseUrl = '/consultations'`), then every other
 * interpolation as `{}`. An unresolved leading variable stays `{?}`.
 */
export function resolveUrl(url, source) {
  let out = url;
  for (let i = 0; i < 3; i++) {
    const lead = /^\{[^}]*\}/.exec(out);
    const name = lead ? variableName(lead[0]) : null;
    if (!name) break;
    const decl = new RegExp(
      `\\b${name}\\s*(?::\\s*string\\s*)?=\\s*['\`]([^'\`$]*)['\`]`,
    ).exec(source);
    if (!decl) break;
    out = decl[1].replace(/^\/api(?=\/)/, '') + out.slice(lead[0].length);
  }
  const unresolved = /^\{[^}]*\}/.test(out);
  return (unresolved ? '{?}' + out.replace(/^\{[^}]*\}/, '') : out).replace(/\{(?!\?\})[^}]*\}/g, '{}');
}

const HTTP_CALL = /\bhttp\.(post|put|patch|delete)\s*(?:<[^>()]*(?:<[^>()]*>[^>()]*)*>)?\s*\(/g;

/** The text of the class member around `index` (from its header to its end). */
function enclosingMethod(source, index) {
  const header =
    /^ {2}(?:(?:public|private|protected|static|async|override)\s+)*[A-Za-z_$][\w$]*\s*(?:<[^>\n]*>)?\(/gm;
  let start = 0;
  for (const h of source.matchAll(header)) {
    if (h.index > index) break;
    start = h.index;
  }
  const brace = source.indexOf('{', source.indexOf(')', start));
  if (brace < 0) return source.slice(start);
  return source.slice(start, brace) + balanced(source, brace, '{', '}');
}

/** Every `http.post|put|patch|delete(…)` of a source: verb, URL pattern, body, options, method text. */
export function httpCalls(source) {
  const out = [];
  for (const m of source.matchAll(HTTP_CALL)) {
    const open = m.index + m[0].length - 1;
    const args = topLevelArgs(balanced(source, open));
    if (args.length === 0) continue;
    const verb = m[1].toUpperCase();
    const body = verb === 'DELETE' ? '' : (args[1] ?? '');
    const options = verb === 'DELETE' ? (args[1] ?? '') : (args[2] ?? '');
    out.push({
      verb,
      url: resolveUrl(urlPattern(args[0]), source),
      body,
      options,
      line: source.slice(0, m.index).split('\n').length,
      method: enclosingMethod(source, m.index),
    });
  }
  return out;
}

/** Whether a body argument sends nothing. */
export function isEmptyBody(body) {
  return /^(?:\{\s*\}|null|undefined|)$/.test(body.trim());
}

/**
 * The handlers hospital-core requires a reason for, with where it reads it:
 * `{ verb, path, kind: 'body' | 'param', field }`, parsed from
 * CancelVoidReasonGuardTest's HANDLERS and CLINICAL_HANDLERS maps (an entry
 * whose value names a proof with ` -> `; "NOT A …", "OWN REASON" and
 * "KNOWN GAP" entries need none).
 */
export function serverReasonContracts(java) {
  const entry =
    /(?:CLINICAL_)?HANDLERS\.put\(\s*"([A-Z]+) ([^"]+)",\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)\)/g;
  const out = [];
  for (const m of java.matchAll(entry)) {
    const value = [...m[3].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((s) => s[1]).join('');
    if (!value.includes(' -> ')) continue;
    const source = value.slice(0, value.indexOf(' -> ')).trim();
    const param = /^@RequestParam\s+(\w+)/.exec(source);
    if (param) {
      out.push({ verb: m[1], path: m[2], kind: 'param', field: param[1] });
      continue;
    }
    const field = /^\w+\.(\w+)/.exec(source);
    out.push({ verb: m[1], path: m[2], kind: 'body', field: field ? field[1] : null });
  }
  return out;
}

/**
 * Whether portal URL pattern `url` (its base resolved, {@link resolveUrl}) is
 * server path `path`: segment by segment, a `{x}` server segment matches any.
 */
export function urlMatches(url, path) {
  // A base the file does not declare cannot be compared: it is not a match.
  if (url.startsWith('{?}')) return false;
  const portal = url.replace(/\?.*$/, '').split('/').filter(Boolean);
  const server = path.split('/').filter(Boolean);
  if (portal.length === 0 || portal.length !== server.length) return false;
  return portal.every((seg, i) => /^\{\w+\}$/.test(server[i]) || seg === server[i]);
}

/** Why a call to a reason-requiring handler does not send the reason, or null. */
export function missingReason(call, contract) {
  if (contract.kind === 'param') {
    const named = new RegExp(`\\b${contract.field}\\b`);
    if (call.url.includes(`${contract.field}=`)) return null;
    if (call.options && named.test(call.method)) return null;
    return `sends no '${contract.field}' query parameter`;
  }
  if (isEmptyBody(call.body)) {
    return `sends an empty body; the server reads ${contract.field ?? 'a reason'} from it`;
  }
  if (call.body.trim().startsWith('{') && contract.field) {
    const named = new RegExp(`\\b${contract.field}\\b`);
    if (!named.test(call.body)) return `its body does not carry '${contract.field}'`;
  }
  return null;
}

function portalCalls() {
  const calls = [];
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const call of httpCalls(readFileSync(file, 'utf8'))) calls.push({ ...call, rel });
  }
  return calls;
}

test('QA D1157: no portal call ends a record with an empty body', () => {
  const found = new Set();
  for (const call of portalCalls()) {
    if (call.verb === 'DELETE' || !TERMINAL.test(call.url)) continue;
    if (isEmptyBody(call.body) && !call.options) found.add(`${call.rel}::${call.url}`);
  }
  const unpinned = [...found].filter((site) => !PINS.has(site));
  assert.deepEqual(
    unpinned,
    [],
    'send the reason the user gave (a reason dialog), or pin the site with why',
  );
  const stale = [...PINS.keys()].filter((site) => !found.has(site));
  assert.deepEqual(stale, [], 'a pinned site is gone: delete its pin');
});

test('QA D1180: every portal call to a handler the server requires a reason for sends it', () => {
  const contracts = serverReasonContracts(readFileSync(SERVER_GUARD, 'utf8'));
  assert.ok(contracts.length >= 25, `the server list must be read, saw ${contracts.length}`);
  assert.deepEqual(
    contracts.find((c) => c.path === '/advance-directives/{id}/revoke'),
    { verb: 'PUT', path: '/advance-directives/{id}/revoke', kind: 'body', field: 'reason' },
  );
  const problems = [];
  let matched = 0;
  for (const call of portalCalls()) {
    for (const contract of contracts) {
      if (call.verb !== contract.verb || !urlMatches(call.url, contract.path)) continue;
      matched++;
      const why = missingReason(call, contract);
      if (why) problems.push(`${call.rel}:${call.line} ${contract.verb} ${contract.path} ${why}`);
    }
  }
  assert.ok(matched >= 15, `the portal callers must be found, saw ${matched}`);
  assert.deepEqual(problems, [], 'the server requires a reason here: ask for it and send it');
});

test('QA D1180 guard: the scan sees the regression and the shapes a reason travels in', () => {
  const broken = 'return this.http.put<T>(`/advance-directives/${id}/revoke`, {});';
  const [call] = httpCalls(broken);
  assert.equal(call.url, '/advance-directives/{}/revoke');
  const contract = {
    verb: 'PUT',
    path: '/advance-directives/{id}/revoke',
    kind: 'body',
    field: 'reason',
  };
  assert.ok(urlMatches(call.url, contract.path));
  assert.match(missingReason(call, contract), /empty body/);
  const fixed = 'return this.http.put<T>(`/advance-directives/${id}/revoke`, {\n  reason,\n});';
  assert.equal(missingReason(httpCalls(fixed)[0], contract), null);
  const wrongField = 'return this.http.put<T>(`/advance-directives/${id}/revoke`, { note });';
  assert.match(missingReason(httpCalls(wrongField)[0], contract), /does not carry 'reason'/);
  // A base-URL variable stands for the leading segments; the reason may be a query parameter.
  const param = {
    verb: 'POST',
    path: '/discharge-approvals/{id}/cancel',
    kind: 'param',
    field: 'reason',
  };
  const declared = "  private readonly baseUrl = '/discharge-approvals';\n";
  const based =
    declared +
    '  cancel(id: string, reason: string) {\n    return this.http.post<T>(`${this.baseUrl}/${id}/cancel`, null, { params: { reason } });\n  }';
  const [p] = httpCalls(based);
  assert.ok(urlMatches(p.url, param.path));
  assert.equal(missingReason(p, param), null);
  const noParam =
    declared +
    '  cancel(id: string) {\n    return this.http.post<T>(`${this.baseUrl}/${id}/cancel`, null);\n  }';
  assert.match(missingReason(httpCalls(noParam)[0], param), /no 'reason' query parameter/);
  // A concatenated URL and a `null` body are read too.
  const concat = "return this.http.post<void>('/recalls/' + id + '/cancel', null);";
  assert.equal(httpCalls(concat)[0].url, '/recalls/{}/cancel');
  assert.ok(isEmptyBody(httpCalls(concat)[0].body));
  // Paths of another resource never match, nor does a base the file does not declare.
  assert.equal(urlMatches('/consents/{}/revoke', '/advance-directives/{id}/revoke'), false);
  assert.equal(httpCalls('return this.http.post(`${this.other}/${id}/cancel`, {});')[0].url, '{?}/{}/cancel');
  assert.equal(urlMatches('{?}/{}/cancel', '/recalls/{id}/cancel'), false);
});

test('QA D1180 guard: the server list is parsed with its reason source', () => {
  const java = `
    CLINICAL_HANDLERS.put("PUT /advance-directives/{id}/revoke",
        "CloseReasonRequestDTO.reason -> service/impl/AdvanceDirectiveServiceImplTest#aRevoke");
    CLINICAL_HANDLERS.put("POST /discharge-approvals/{id}/cancel",
        "@RequestParam reason -> service/DischargeApprovalServiceImplTest#cancel");
    CLINICAL_HANDLERS.put("PUT /refills/{refillId}/reject",
        "KNOWN GAP (D1157 class, standing debt): optional"
            + " for now");
    HANDLERS.put("POST /pharmacy/claims/{id}/reject",
        "body.rejectionReason -> service/pharmacy/PharmacyClaimServiceImplTest#rejectRequiresReason");`;
  assert.deepEqual(serverReasonContracts(java), [
    { verb: 'PUT', path: '/advance-directives/{id}/revoke', kind: 'body', field: 'reason' },
    { verb: 'POST', path: '/discharge-approvals/{id}/cancel', kind: 'param', field: 'reason' },
    { verb: 'POST', path: '/pharmacy/claims/{id}/reject', kind: 'body', field: 'rejectionReason' },
  ]);
});
