#!/usr/bin/env node
/**
 * RBAC inline-role gate (QA D527).
 *
 * The write buttons of the clinical pages are gated from ONE map,
 * src/app/core/action-permissions.ts, which ActionPermissionsMirrorTest
 * (hospital-core) holds to each endpoint's @PreAuthorize. A page that hands a
 * hand-copied `['ROLE_…']` list to a role check instead drifts from the API
 * unseen — the per-page drift behind the D427 → D527 regression.
 *
 * This gate fails on any role check in the pages below whose argument names a
 * `'ROLE_…'` literal. Use `actionRoles('<action>')` (or a named list from a
 * role-map file such as chart-access.ts). A check that is not about a write
 * may stay inline when the line above it says why:
 *
 *     // rbac-inline-ok: <reason>
 *
 * Usage: node scripts/check-rbac-inline-roles.mjs [file ...]
 */
import { readFileSync } from 'node:fs';
import { resolve, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

export const GUARDED_PAGES = [
  'src/app/lab/lab.ts',
  'src/app/imaging/imaging.ts',
  'src/app/admissions/admissions.ts',
  'src/app/encounters/encounters.ts',
  'src/app/referrals/referrals.ts',
  'src/app/patients/patient-chart/patient-chart.component.ts',
];

const CHECK = /\b(hasAnyActiveRole|hasAnyActiveRoleExactly|hasAnyRole|hasRole)\s*\(/g;
const ALLOW = 'rbac-inline-ok:';

/** The argument text of the call whose `(` is at `open`. */
function argumentAt(source, open) {
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    const c = source[i];
    if (c === '(') depth++;
    else if (c === ')' && --depth === 0) return source.slice(open + 1, i);
  }
  return source.slice(open + 1);
}

export function inlineRoleChecks(source) {
  const lines = source.split('\n');
  const found = [];
  for (const m of source.matchAll(CHECK)) {
    const arg = argumentAt(source, m.index + m[0].length - 1);
    if (!/['"`]ROLE_/.test(arg)) continue;
    const line = source.slice(0, m.index).split('\n').length;
    if ((lines[line - 2] ?? '').includes(ALLOW)) continue;
    found.push({ line, call: m[1] });
  }
  return found;
}

const files = process.argv.slice(2).length ? process.argv.slice(2) : GUARDED_PAGES;
let failures = 0;
for (const file of files) {
  const path = resolve(root, file);
  for (const hit of inlineRoleChecks(readFileSync(path, 'utf8'))) {
    failures++;
    console.error(
      `${relative(root, path)}:${hit.line}: ${hit.call}() with an inline 'ROLE_' list — ` +
        `use actionRoles() from core/action-permissions.ts (or "// ${ALLOW} <reason>" above a non-write check)`,
    );
  }
}
if (failures) {
  console.error(`\nrbac:inline-roles — ${failures} inline role list(s) in guarded pages.`);
  process.exit(1);
}
console.log(`rbac:inline-roles — ${files.length} guarded page(s) use the action map.`);
