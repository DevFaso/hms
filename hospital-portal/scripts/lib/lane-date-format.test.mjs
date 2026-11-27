// QA D4161 guard: the laboratory lane (laboratory, imaging, microbiology, blood
// bank, in-basket) writes every timestamp with the house `dateTime` pipe and every
// date-only value with `date: 'mediumDate'`. The lane showed three formats side by
// side ("27 nov. 2026, 05:31", "27/11/2026 05:30", "27 nov., 5:27") and a list
// with no time at all, because each screen picked its own Angular `date` format.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { walk } from './walk.mjs';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const LANE = ['lab', 'imaging', 'micro', 'transfusion', 'in-basket'];

/** Date formats a lane screen may pass to `| date:`. */
const ALLOWED = new Set(['mediumDate']);

/** The offenders in one source text. */
export function laneDateOffenders(text, file = 'source') {
  const offenders = [];
  for (const m of text.matchAll(/\|\s*date\s*:\s*(['"])([^'"]*)\1/g)) {
    if (!ALLOWED.has(m[2])) {
      const line = text.slice(0, m.index).split('\n').length;
      offenders.push(
        `${file}:${line} date format '${m[2]}' (use | dateTime, or 'mediumDate' for a date)`,
      );
    }
  }
  return offenders;
}

test('QA D4161: lane screens use the house date formats only', () => {
  const files = LANE.flatMap((d) => walk(join(appDir, d), ['.html', '.ts'])).filter(
    (f) => !f.endsWith('.spec.ts'),
  );
  assert.ok(files.length > 50);
  const offenders = files.flatMap((f) =>
    laneDateOffenders(readFileSync(f, 'utf8'), relative(appDir, f).replaceAll('\\', '/')),
  );
  assert.deepEqual(offenders, []);
});

test('QA D4161: the guard flags a short or hand-written format', () => {
  assert.equal(laneDateOffenders("{{ a | serverTime | date: 'short' }}").length, 1);
  assert.equal(laneDateOffenders("{{ a | date: 'dd/MM/yyyy' }}").length, 1);
  assert.equal(laneDateOffenders("{{ a | date: 'medium' }}").length, 1);
  assert.equal(laneDateOffenders("{{ a | date: 'mediumDate' }}").length, 0);
  assert.equal(laneDateOffenders('{{ a | serverTime | dateTime }}').length, 0);
});
