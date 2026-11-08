// QA D1111 guard (round 19): a write refused by the server shown as a generic
// toast. The second discharge request was refused with a reason ("a request is
// already awaiting the doctor's decision"), and the nurse read only "Échec de
// la soumission de la demande": the handler was `error: () => toast(generic)`.
//
// An error handler that shows a toast for a WRITE must pass the server's
// reason first (`refusalReason(err) ?? generic`, src/app/shared/refusal-reason.ts).
// A handler that shows only a translated generic text (with or without the
// error parameter) is allowed only for a read (a key naming a load, a missing
// record or an empty result).
//
// Round 22 (QA D1189, D1124 NOT_FIXED twice): the rule read only the ten
// order/encounter screens of round 19. The nurse station's empty care note
// was refused with "Rédigez la note…" and the nurse read "Échec de
// l'enregistrement de la note de soins"; 105 such handlers sat in 50 other
// files. It now reads EVERY component and service of the portal.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';

import { walk } from './walk.mjs';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appDir = join(here, '..', '..', 'src', 'app');

/** A read's generic message: a load, a missing record, an empty answer. */
const READ_KEY = /(?<!UP)LOAD|NOT_FOUND|NO_|_NONE|TEMPLATE/;

/**
 * An error handler (with or without the error parameter) whose toast shows a
 * translated generic text and nothing from the server:
 * `error: (…) => …toast.error(this.translate.instant('KEY'))`.
 */
const BLIND_HANDLER =
  /error:\s*\([^)]*\)\s*=>\s*\{?[^}]{0,300}?this\.toast\.error\(\s*this\.translate\.instant\('([A-Z_.]+)'/g;

export function blindWriteHandlers(source) {
  const out = [];
  for (const m of source.matchAll(BLIND_HANDLER)) {
    if (!READ_KEY.test(m[1])) {
      out.push({ key: m[1], line: source.slice(0, m.index).split('\n').length });
    }
  }
  return out;
}

test('QA D1111 / D1189: a refused write anywhere in the portal shows the server reason', () => {
  const problems = [];
  let files = 0;
  for (const file of walk(appDir, ['.ts'])) {
    if (file.endsWith('.spec.ts')) continue;
    files++;
    const rel = relative(appDir, file).replaceAll('\\', '/');
    for (const h of blindWriteHandlers(readFileSync(file, 'utf8'))) {
      problems.push(
        `${rel}:${h.line}: a write's error handler shows only '${h.key}' ` +
          "(take the error and show refusalReason(err) ?? this.translate.instant('…'))",
      );
    }
  }
  assert.ok(files > 400, `every source file must be read, saw ${files}`);
  assert.deepEqual(problems, [], problems.join('\n'));
});

test('QA D1111 guard: the scan sees the round-19 shape', () => {
  const before = `      error: () => {\n        this.toast.error(this.translate.instant('DISCHARGE.REQUEST_ERROR'));\n      },`;
  assert.deepEqual(
    blindWriteHandlers(before).map((h) => h.key),
    ['DISCHARGE.REQUEST_ERROR'],
  );
  const read = `      error: () => {\n        this.toast.error(this.translate.instant('DISCHARGE.LOAD_APPROVALS_ERROR'));\n      },`;
  assert.deepEqual(blindWriteHandlers(read), []);
  const fixed = `      error: (err: unknown) => {\n        this.toast.error(refusalReason(err) ?? this.translate.instant('DISCHARGE.REQUEST_ERROR'));\n      },`;
  assert.deepEqual(blindWriteHandlers(fixed), []);
  // Taking the error and still showing only the generic text is the same defect.
  const ignored = `      error: (err: unknown) => {\n        this.toast.error(this.translate.instant('DISCHARGE.REQUEST_ERROR'));\n      },`;
  assert.deepEqual(
    blindWriteHandlers(ignored).map((h) => h.key),
    ['DISCHARGE.REQUEST_ERROR'],
  );
});
