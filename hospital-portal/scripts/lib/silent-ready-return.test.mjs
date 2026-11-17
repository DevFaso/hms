// QA D2032 guard (round 58): a save refused by a readiness flag with no word.
//
// The registration form and the identity edit dialog started their save with
// `if (!this.nationalUniqueIdReady) return;`. With an unresolved duplicate
// identifier (« Enregistrer quand même » not ticked, or no reason), pressing
// « Enregistrer le patient » did nothing: no request, no toast, no field error.
//
// Rule, over every component `.ts` of the portal: a statement
// `if (!this.<flag>) return;` whose flag reads as readiness or validity
// (`…Ready`, `…Valid`, `canSubmit`, `canSave`) must not end the handler
// silently. Say why first (a toast, a field error, the editor's
// `refuseSubmit()`), or keep the button behind `[appSubmitGuard]` bound to the
// same flag, which explains the refusal next to the button. Allowlist: none.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');

const SILENT =
  /if\s*\(\s*!\s*this\.(\w*(?:Ready|Valid|ready|valid)\w*|canSubmit|canSave)(\(\))?\s*\)\s*return\s*;/g;

/** The silent readiness returns of a component: `{ line, flag }`. */
export function silentReadyReturns(ts, template = '') {
  const out = [];
  for (const m of ts.matchAll(SILENT)) {
    const flag = m[1] + (m[2] ?? '');
    // The button is kept behind the submit guard, which says why.
    const guarded = new RegExp(`\\[appSubmitGuard\\]="!\\s*${m[1]}(\\(\\))?"`).test(template + ts);
    if (guarded) continue;
    out.push({ line: ts.slice(0, m.index).split('\n').length, flag });
  }
  return out;
}

test('QA D2032: no save ends on a readiness flag without saying why', () => {
  const found = [];
  let files = 0;
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    files++;
    const html = file.replace(/\.ts$/, '.html');
    const template = existsSync(html) ? readFileSync(html, 'utf8') : '';
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const s of silentReadyReturns(readFileSync(file, 'utf8'), template)) {
      found.push(`${rel}:${s.line} ${s.flag}`);
    }
  }
  assert.ok(files > 300, `every component must be read, saw ${files}`);
  assert.deepEqual(found, [], 'say why before returning (toast, field error, refuseSubmit())');
});

test('QA D2032 guard: the scan sees the silent IU return, not an explained or guarded one', () => {
  assert.deepEqual(
    silentReadyReturns('submit() {\n  if (!this.nationalUniqueIdReady) return;\n}'),
    [{ line: 2, flag: 'nationalUniqueIdReady' }],
  );
  const explained = `submit() {
    if (!this.nationalUniqueIdReady) {
      this.toast.error(this.translate.instant(this.iuEditor()?.refuseSubmit()));
      return;
    }
  }`;
  assert.deepEqual(silentReadyReturns(explained), []);
  assert.deepEqual(
    silentReadyReturns(
      'save() { if (!this.canSave()) return; }',
      '<button [appSubmitGuard]="!canSave()">',
    ),
    [],
  );
  assert.equal(silentReadyReturns('save() { if (!this.canSave()) return; }').length, 1);
});
