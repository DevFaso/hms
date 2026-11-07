// QA D1422 guard: the demographics edit set a refused birth date's message at the foot of the
// dialog and marked nothing (no aria-invalid, focus left on the button), though it marked the
// blank fields one line above: a check "refuseFields is called in this method" passed. So the two
// identity rules are behind identityRefusals, which answers per field for refuseFields.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const ROOT = join(import.meta.dirname, '..', '..', 'src', 'app');
const RULES = /\b(?:birthDateRefusalKey|isMadeOfDigits)\s*\(/;

/** The rules' own home, and the one form whose template binds aria-invalid and a message per field. */
const MAY_READ_THE_RULES = new Set(['shared/patient-identity.ts', 'patients/patient-form.ts']);

function sources(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) sources(path, out);
    else if (name.endsWith('.ts') && !name.endsWith('.spec.ts')) out.push(path);
  }
  return out;
}

test('QA D1422: no form reads the identity rules except through identityRefusals', () => {
  const readers = sources(ROOT)
    .filter((file) => RULES.test(readFileSync(file, 'utf8')))
    .map((file) => file.slice(ROOT.length + 1).replaceAll('\\', '/'))
    .filter((file) => !MAY_READ_THE_RULES.has(file));
  assert.deepEqual(readers, []);
});

test('QA D1422: every form that takes identityRefusals marks them with refuseFields', () => {
  const unmarked = sources(ROOT)
    .filter((file) => !MAY_READ_THE_RULES.has(file.slice(ROOT.length + 1).replaceAll('\\', '/')))
    .filter((file) => {
      const text = readFileSync(file, 'utf8');
      return text.includes('identityRefusals(') && !/refuseFields\([^;]*\brefused\b/.test(text);
    })
    .map((file) => file.slice(ROOT.length + 1));
  assert.deepEqual(unmarked, []);
});
