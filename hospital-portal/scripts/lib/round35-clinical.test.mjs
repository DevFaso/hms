// QA round 35 guards with no gate of their own.
//
// D1479: a wait written as raw minutes (`{{ item.waitMinutes }}m` read "7151m"). A template shows
// a count of minutes through the `elapsed` pipe. Allowlist: none.
//
// D1480: a button escape-keeps-typed-text.ts builds at run time is outside every component's
// encapsulated styles, so each class it sets must have a rule in the global stylesheet.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const src = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'src');
const RAW_MINUTES = /\{\{[^}|]*[mM]inutes[^}|]*\}\}\s*(?:m|min)\b/g;

function templates(dir) {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return templates(path);
    return entry.name.endsWith('.html') ? [path] : [];
  });
}

export function rawMinutes(html) {
  return [...html.matchAll(RAW_MINUTES)].map((m) => m[0]);
}

test('no template writes a count of minutes by hand', () => {
  const found = templates(join(src, 'app')).flatMap((path) =>
    rawMinutes(readFileSync(path, 'utf8')).map((hit) => `${path}: ${hit}`),
  );
  assert.deepEqual(found, []);
});

test('the scan catches the round-35 shape, and leaves the pipe alone', () => {
  assert.equal(rawMinutes('{{ item.waitMinutes }}m').length, 1);
  assert.equal(rawMinutes('{{ item.elapsedMinutes }} min').length, 1);
  assert.equal(rawMinutes('{{ item.waitMinutes | elapsed }}').length, 0);
});

// QA D2126 (round 59): the same class through a translation. « En retard de 3608 min » came from
// `'CARE_DASH.OVERDUE_MINUTES' | translate: { count: overdueMinutes(r) }` with the value
// "En retard de {{count}} min". A param printed right before "min" in the French value is a raw
// minute count unless it goes through `elapsed`. Allowlist: configured durations a person chose
// (a slot length, a reading time), never a clock running since or past something.
const CONFIGURED_DURATION_KEYS = new Set([
  'COMMON.MINUTES_SHORT', // patient-education: estimated reading time
  'PORTAL.EDUCATION.DURATION', // my-education: estimated reading time
  'SLOT_ADMIN.MINUTES', // slot and visit-type lengths
]);
const TRANSLATE_WITH_PARAMS = /'([A-Z0-9_.]+)'\s*\|\s*translate\s*:\s*\{([^}]*)\}/g;

export function minuteParamsByHand(html, frValue) {
  const hits = [];
  for (const m of html.matchAll(TRANSLATE_WITH_PARAMS)) {
    if (CONFIGURED_DURATION_KEYS.has(m[1])) continue;
    const value = frValue(m[1]);
    if (typeof value !== 'string') continue;
    for (const p of m[2].matchAll(/(\w+)\s*:\s*([^,]+)/g)) {
      const beforeMin = new RegExp(String.raw`\{\{\s*` + p[1] + String.raw`\s*\}\}\s*min\b`);
      if (beforeMin.test(value) && !/\|\s*elapsed\b/.test(p[2])) hits.push(`${m[1]} ${p[2].trim()}`);
    }
  }
  return hits;
}

test('no translated label prints an elapsed count of minutes by hand (QA D2126)', () => {
  const fr = JSON.parse(readFileSync(join(src, 'assets', 'i18n', 'fr.json'), 'utf8'));
  const frValue = (key) => key.split('.').reduce((o, k) => (o == null ? undefined : o[k]), fr);
  const found = templates(join(src, 'app')).flatMap((path) =>
    minuteParamsByHand(readFileSync(path, 'utf8'), frValue).map((hit) => `${path}: ${hit}`),
  );
  assert.deepEqual(found, []);
});

test('the D2126 scan catches the round-59 shape, and leaves the pipe alone', () => {
  const frValue = (key) => (key === 'X.OVERDUE' ? 'En retard de {{count}} min' : 'Attente {{d}}');
  assert.equal(
    minuteParamsByHand("{{ 'X.OVERDUE' | translate: { count: overdueMinutes(r) } }}", frValue).length,
    1,
  );
  assert.equal(
    minuteParamsByHand("{{ 'X.WAIT' | translate: { d: r.waitMinutes | elapsed } }}", frValue).length,
    0,
  );
});

test('every button the Escape question builds at run time has a global style', () => {
  const script = readFileSync(
    join(src, 'app', 'shared', 'a11y', 'escape-keeps-typed-text.ts'),
    'utf8',
  );
  const styles = readFileSync(join(src, 'styles.scss'), 'utf8');
  const classes = [...script.matchAll(/(?:keep|discard)\.className = '([\w-]+)'/g)].map(
    (m) => m[1],
  );
  assert.deepEqual(classes.sort(), ['btn-danger', 'btn-secondary']);
  for (const name of classes) {
    assert.ok(
      styles.includes(`.dialog-discard .${name}`),
      `.dialog-discard .${name} in styles.scss`,
    );
  }
});
