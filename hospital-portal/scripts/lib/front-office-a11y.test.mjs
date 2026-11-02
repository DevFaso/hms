// QA D947/D988 guards on the front-office templates (reception, appointments,
// users, the patient registration and demographics forms): every modal is a
// real dialog with the keyboard contract, every icon-only button has a name,
// and no picker option is reachable by mouse only.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const read = (p) => readFileSync(p, 'utf8');

const SCOPE = [
  'reception',
  'appointments',
  'users',
  'patients/patient-form.html',
  'patients/patient-demographics-edit',
  'patients/coverage-tab',
];

function htmlFiles(p) {
  if (statSync(p).isFile()) return p.endsWith('.html') ? [p] : [];
  return readdirSync(p).flatMap((name) => htmlFiles(join(p, name)));
}

const files = SCOPE.flatMap((s) => htmlFiles(join(appDir, s)));
const rel = (f) => relative(appDir, f).replaceAll('\\', '/');

/** Opening tags matching `re`, with the file they are in. */
function tags(re) {
  return files.flatMap((f) => [...read(f).matchAll(re)].map((m) => ({ file: rel(f), tag: m[0] })));
}

test('D988: every modal is a dialog with aria-modal and the focus/Escape/Tab contract', () => {
  const dialogs = [...tags(/<div\b[^>]*role="dialog"[^>]*>/gs), ...tags(/<dialog\b[^>]*>/gs)];
  assert.ok(dialogs.length >= 10, `found ${dialogs.length} dialogs`);
  const bad = dialogs.filter(
    ({ tag }) => !tag.includes('appDialogFocus') || !tag.includes('aria-modal="true"'),
  );
  assert.deepEqual(bad, []);
});

test('D988: every icon-only button has an accessible name', () => {
  const offenders = [];
  for (const f of files) {
    const html = read(f);
    for (const m of html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)) {
      const [, attrs, body] = m;
      // The visible text outside the icon span: what is left between tags
      // (split, not strip: this reads a template, it sanitizes nothing).
      const text = body
        .split(/<span\b[^>]*material-symbols-outlined[^>]*>[^<]*<\/span>/)
        .join(' ')
        .split(/<[^>]*>/)
        .join(' ')
        .split('&times;')
        .join(' ')
        .trim();
      const iconOnly = text === '' && /material-symbols-outlined|&times;/.test(body);
      if (iconOnly && !/aria-label/.test(attrs)) {
        offenders.push(`${rel(f)}: <button${attrs.slice(0, 80)}>`);
      }
    }
  }
  assert.deepEqual(offenders, []);
});

test('D947: no picker option is reachable by mouse only', () => {
  const bad = tags(/<li\b[^>]*\((mousedown|click)\)=[^>]*>/gs).filter(
    ({ tag }) => !/role="option"|tabindex="0"/.test(tag),
  );
  assert.deepEqual(bad, []);
});
