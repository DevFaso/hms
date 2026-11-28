import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import assert from 'node:assert/strict';

// QA r81 D4114: wording that read twice or disagreed with itself on the same screen.
const fr = JSON.parse(readFileSync('src/assets/i18n/fr.json', 'utf8'));

function walk(node, path, visit) {
  if (node && typeof node === 'object') {
    for (const [key, value] of Object.entries(node)) walk(value, [...path, key], visit);
  } else {
    visit(path, node);
  }
}

test('an addendum is called « addendum » everywhere, never « complément »', () => {
  const offenders = [];
  walk(fr, [], (path, value) => {
    if (path.at(-1).includes('ADDEND') && /compl[ée]ment/i.test(String(value))) {
      offenders.push(path.join('.'));
    }
  });
  assert.deepEqual(offenders, []);
});

test('an emergency and an urgent choice never read « Urgence » beside « Urgente »', () => {
  const offenders = [];
  walk(fr, [], (path) => {
    if (path.at(-1) !== 'EMERGENCY') return;
    const parent = path.slice(0, -1).reduce((node, key) => node[key], fr);
    if (
      typeof parent.URGENT === 'string' &&
      /^urgence$/i.test(parent.EMERGENCY.trim()) &&
      /^urgente?$/i.test(parent.URGENT.trim())
    ) {
      offenders.push(path.slice(0, -1).join('.'));
    }
  });
  assert.deepEqual(offenders, []);
});

test('the labour card names the gestational age unit once', () => {
  const html = readFileSync('src/app/maternity/labor-tab.html', 'utf8');
  const line = html.split('\n').find((value) => value.includes("· {{ 'MATERNITY.GESTATIONAL_AGE"));
  assert.ok(line, 'labour card: missing gestational age');
  assert.match(line, /GESTATIONAL_AGE_SHORT/);
  assert.doesNotMatch(fr.MATERNITY.GESTATIONAL_AGE_SHORT, /semaine/i);
});

test('the visit, nursing-note and labour panels show one date-time format', () => {
  // « 27 nov. 2026, 05:31 » beside « 27/11/2026 05:32 » in the same visit panel.
  for (const file of [
    'src/app/encounters/encounters.html',
    'src/app/encounters/nursing-notes-panel/nursing-notes-panel.component.html',
    'src/app/maternity/labor-tab.html',
  ]) {
    assert.doesNotMatch(readFileSync(file, 'utf8'), /\|\s*date:\s*'short'/, file);
  }
});

test('the maternity pickers filter the mother, but never the ultrasound order picker', () => {
  // QA r81 D4116; review of #1378 F5: an URGENT/STAT or OTHER ultrasound is never refused by the server,
  // so its picker offers every patient.
  const pickerTag = (file, id) => {
    const html = readFileSync(file, 'utf8');
    const at = html.indexOf(`inputId="${id}"`);
    assert.ok(at > 0, `${file}: missing picker ${id}`);
    const start = html.lastIndexOf('<app-patient-picker', at);
    return html.slice(start, html.indexOf('/>', at));
  };
  for (const [file, id] of [
    ['src/app/maternity/labor-tab.html', 'labor-patient'],
    ['src/app/maternity/postpartum-tab.html', 'pp-patient'],
    ['src/app/maternity/prenatal-tab.html', 'pn-patient'],
    ['src/app/maternity/birth-plan-tab.html', 'bp-filter-patient'],
    ['src/app/maternity/birth-plan-tab.html', 'bp-patient'],
    ['src/app/maternity/maternity.html', 'history-patient'],
  ]) {
    assert.match(pickerTag(file, id), /\bmaternalOnly\b/, `${file} ${id}`);
  }
  assert.doesNotMatch(
    pickerTag('src/app/maternity/ultrasound-tab.html', 'us-patient'),
    /\bmaternalOnly\b/,
  );
});
