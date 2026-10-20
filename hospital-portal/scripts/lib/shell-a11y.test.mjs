// D173 / D200 guards on the templates themselves (the shell is not rendered in
// Karma: it pulls websockets and idle timers).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const appDir = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src', 'app');
const read = (p) => readFileSync(p, 'utf8');

function htmlFiles(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) out.push(...htmlFiles(p));
    else if (name.endsWith('.html')) out.push(p);
  }
  return out;
}

test('D173: every Material icon in the shell is aria-hidden, so link names are the label alone', () => {
  const html = read(join(appDir, 'shell', 'shell.html'));
  const opening =
    html.match(/<span\b[^>]*class="[^"]*material-symbols-outlined[^"]*"[^>]*>/g) ?? [];
  assert.ok(opening.length > 0);
  const bare = opening.filter((tag) => !tag.includes('aria-hidden="true"'));
  assert.deepEqual(bare, []);
});

test('D173/D436: every sidebar link is named after its translated label, collapsed or not', () => {
  const html = read(join(appDir, 'shell', 'shell.html'));
  assert.match(
    html,
    /class="nav-item"[\s\S]*?\[attr\.aria-label\]="\s*entry\.item\.translationKey\s*\?\s*\(entry\.item\.translationKey \| translate\)\s*:\s*entry\.item\.label\s*"/,
  );
});

test('D200: the dark sidebar uses no text colour below WCAG AA', () => {
  const scss = read(join(appDir, 'shell', 'shell.scss'));
  const sidebar = scss.slice(0, scss.indexOf('// ---------- Main Area ----------'));
  assert.doesNotMatch(sidebar, /color:\s*#64748b/);
  assert.doesNotMatch(sidebar, /&\.active\s*\{[^}]*color:\s*var\(--primary\)/);
});

test('D200: no search box relies on its placeholder for a name', () => {
  const offenders = [];
  for (const file of htmlFiles(appDir)) {
    const html = read(file);
    const fors = new Set([...html.matchAll(/\bfor="([^"]+)"/g)].map((m) => m[1]));
    for (const m of html.matchAll(/<input\b([^>]*)>/g)) {
      const attrs = m[1];
      if (!/placeholder/.test(attrs) || !/search/i.test(attrs)) continue;
      if (/aria-label/.test(attrs)) continue;
      const id = /\bid="([^"]+)"/.exec(attrs);
      if (id && fors.has(id[1])) continue;
      const before = html.slice(0, m.index);
      if (before.lastIndexOf('<label') > before.lastIndexOf('</label>')) continue;
      offenders.push(`${file.slice(appDir.length + 1)}:${before.split('\n').length}`);
    }
  }
  assert.deepEqual(offenders, []);
});
