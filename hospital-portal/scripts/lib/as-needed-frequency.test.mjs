// QA D1378 / D1360 (round 32, coordinator decision 2026-11-03): as-needed
// (PRN) is the prescriber's EXPLICIT tick, never read from the frequency text.
// Rounds 30-31 kept a word list on both sides (hospital-core
// MarDoseSchedule.AS_NEEDED_PHRASES and the portal's isAsNeededFrequency) and
// round 32 found ~45 wordings it missed (SOS, p.r.n., sur demande, si precisa,
// when necessary ...): a PRN order, controlled morphine included, was created
// with no minimum interval. The class is "a screen decides PRN from the
// frequency text". This guard keeps it out of the portal:
//  1. no source under src/app reads the frequency for PRN (no isAsNeededFrequency,
//     no phrase list, no PRN/"si besoin" test on a frequency);
//  2. the prescription form and the order-set item editor offer the explicit
//     tick, marked by the server's fieldErrors key, and show the interval from it;
//  3. the shared fixture holds every wording QA filed (the server rule
//     FrequencyWording must refuse them: FrequencyWordingTest).
// QA D1391 / D1395 (round 33): the portal holds a MIRROR of that server rule
// about an order NOT ticked, in shared/as-needed-frequency.ts, for a hint under
// the field. It is the one reader of the text; it never makes an order as-needed
// (rule 4), and only the prescribing form reads it (rule 5).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const APP = join(here, '..', '..', 'src', 'app');
const CASES = join(APP, 'shared', 'as-needed-frequency.cases.json');
const RX_HTML = join(APP, 'prescriptions', 'prescriptions.html');
const RX_TS = join(APP, 'prescriptions', 'prescriptions.ts');
const OS_HTML = join(APP, 'admin', 'order-sets', 'order-set-edit.component.html');
const OS_TS = join(APP, 'admin', 'order-sets', 'order-set-edit.component.ts');

/** A frequency text read for PRN: the old helper, a phrase list, or a PRN word tested on a frequency. */
const READS_FREQUENCY_FOR_PRN = [
  /\bisAsNeededFrequency\b/,
  /\bAS_NEEDED_PHRASES\b/,
  /frequency\W[^;\n]{0,60}\.(?:includes|match|startsWith|test|indexOf)\([^;\n]{0,30}(?:\bPRN\b|BESOIN|AS NEEDED|NECESARIO)/i,
  /\/[^/\n]*(?:\\bPRN|si besoin|as needed)[^/\n]*\/[gimsuy]*\.test\(\s*[\w.?!]*frequency/i,
];

/** Who may read the mirror's verdict, and the one file it lives in. */
const MIRROR = join('shared', 'as-needed-frequency.ts');
const MIRROR_READERS = new Set([MIRROR, join('prescriptions', 'prescriptions.ts')]);

function sources(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) out.push(...sources(path));
    else if (/\.(ts|html)$/.test(name) && !name.endsWith('.spec.ts')) out.push(path);
  }
  return out;
}

/** The files that decide PRN from a frequency text, with the matching rule. */
export function frequencyReaders(files) {
  const found = [];
  for (const [path, text] of files) {
    const rule = READS_FREQUENCY_FOR_PRN.find((r) => r.test(text));
    if (rule) found.push(`${path}: ${rule}`);
  }
  return found;
}

test('no portal source decides PRN from the frequency text (QA D1378)', () => {
  const files = sources(APP).map((p) => [relative(APP, p), readFileSync(p, 'utf8')]);
  assert.ok(files.length > 500, `only ${files.length} sources read: the scan went blind`);
  assert.deepEqual(frequencyReaders(files), []);
});

test('only the prescribing form reads the mirror, and it never ticks an order from it (QA D1391)', () => {
  const files = sources(APP).map((p) => [relative(APP, p), readFileSync(p, 'utf8')]);
  const readers = files
    .filter(([, text]) => /\bunTickedFrequencyVerdict\b/.test(text))
    .map(([p]) => p);
  assert.deepEqual(new Set(readers), MIRROR_READERS);
  const ts = readFileSync(RX_TS, 'utf8');
  assert.doesNotMatch(
    ts,
    /asNeeded\s*=[^=][^;\n]*unTickedFrequencyVerdict/,
    'the verdict never sets the tick',
  );
  const body = /frequencyHintKey\(\)[^{]*\{([\s\S]*?)\n {2}\}/.exec(ts);
  assert.ok(body, 'the hint method exists');
  assert.doesNotMatch(body[1], /form\.asNeeded\s*=[^=]/);
});

test('the prescription form offers the explicit tick and shows the interval from it', () => {
  const html = readFileSync(RX_HTML, 'utf8');
  const ts = readFileSync(RX_TS, 'utf8');
  assert.match(html, /id="rx-asNeeded"[\s\S]{0,200}data-server-field="asNeeded"/);
  assert.match(ts, /prnIntervalShown\(\): boolean \{\s*return !!this\.form\.asNeeded;/);
  assert.match(ts, /asNeeded: !!this\.form\.asNeeded/, 'the tick is sent');
});

test('QA D1419: the question is answered on its own field, in both forms', () => {
  const rx = readFileSync(RX_HTML, 'utf8');
  assert.match(rx, /id="rx-frequencyConfirmed"[\s\S]{0,120}data-server-field="frequencyConfirmed"/);
  const os = readFileSync(OS_HTML, 'utf8');
  assert.match(os, /data-server-field\]="'orderItems\[' \+ i \+ '\]\.frequencyConfirmed'"/);
  // A question is never answered by ticking as needed: no form sets the tick from the refusal.
  for (const file of [RX_TS, OS_TS]) {
    assert.doesNotMatch(readFileSync(file, 'utf8'), /frequencyConfirmed[^;\n]*asNeeded\s*=[^=]/);
  }
});

test('the order-set item editor offers the explicit tick and shows the interval from it', () => {
  const html = readFileSync(OS_HTML, 'utf8');
  const ts = readFileSync(OS_TS, 'utf8');
  assert.match(html, /data-server-field\]="'orderItems\[' \+ i \+ '\]\.asNeeded'"/);
  assert.match(html, /data-server-field\]="'orderItems\[' \+ i \+ '\]\.prnMinIntervalHours'"/);
  assert.match(ts, /isMedicationLike\(it\.orderType\) && it\.asNeeded === true/);
});

test('the shared fixture holds every wording QA filed under D1360 and D1378', () => {
  const fixture = JSON.parse(readFileSync(CASES, 'utf8'));
  for (const wording of [
    'au besoin',
    'à la demande',
    'si nécessaire',
    'si douleur',
    'SOS',
    'p.r.n.',
    'pro re nata',
    'sur demande',
    'si EVA > 4',
    'si precisa',
    's/p',
    'según sea necesario',
    'a necesidad',
    'when necessary',
    'as necessary',
    'for pain',
    'if nauseous',
  ]) {
    assert.ok(fixture.conditional.includes(wording), `fixture lost "${wording}"`);
  }
  assert.ok(fixture.fixedSchedule.includes('3 fois par jour'));
  // QA D1391 and D1395 (round 33): both directions, word for word.
  // QA D1452 (changed): "3 fois par jour selon ordonnance" says more than a form of the grammar: asked once.
  for (const wording of ['TPI-SP', 'Prise unique (TPI-SP 2)', 'SP dose unique']) {
    assert.ok(fixture.fixedSchedule.includes(wording), `fixture lost "${wording}"`);
  }
  assert.ok(fixture.unreadable.includes('3 fois par jour selon ordonnance'));
  for (const wording of ['pour douleur', 'toutes les 6 h pour douleur', 'q6h si fièvre']) {
    assert.ok(fixture.conditional.includes(wording), `fixture lost "${wording}"`);
  }
  for (const wording of [
    'max 4/j',
    '1 à 3 fois par jour',
    'selon prescription médicale',
    'SP + AQ',
  ]) {
    assert.ok(fixture.unreadable.includes(wording), `fixture lost "${wording}"`);
  }
});

test('QA D1472: the form offers the schedules of the grammar, the same in every language', () => {
  const html = readFileSync(RX_HTML, 'utf8');
  const ts = readFileSync(RX_TS, 'utf8');
  assert.match(html, /id="rx-frequency"[\s\S]{0,300}list="rx-frequency-choices"/);
  assert.match(html, /<datalist id="rx-frequency-choices"/);
  const list = /const FREQUENCY_CHOICES = \[([\s\S]*?)\]/.exec(ts);
  assert.ok(list, 'the list of choices exists');
  const offered = [...list[1].matchAll(/'([A-Z0-9_]+)'/g)].map((m) => m[1]).sort();
  assert.ok(offered.length > 10);
  for (const language of ['en', 'fr', 'es']) {
    const bundle = JSON.parse(
      readFileSync(join(here, '..', '..', 'src', 'assets', 'i18n', `${language}.json`), 'utf8'),
    );
    // FrequencyWordingTest holds each of these to a schedule, and to the same one in every language.
    assert.deepEqual(Object.keys(bundle.PRESCRIPTIONS.FREQUENCY_CHOICES).sort(), offered, language);
  }
});

test('guard: the scan sees the shapes it guards', () => {
  assert.equal(
    frequencyReaders([['a.ts', 'if (isAsNeededFrequency(this.form.frequency)) {}']]).length,
    1,
  );
  assert.equal(
    frequencyReaders([['b.ts', "const prn = it.frequency?.toUpperCase().includes('PRN');"]]).length,
    1,
  );
  assert.equal(
    frequencyReaders([['c.ts', 'const prn = /\\bPRN\\b/i.test(rx.frequency);']]).length,
    1,
  );
  assert.equal(
    frequencyReaders([['d.ts', 'return !!this.form.asNeeded; // frequency is free text']]).length,
    0,
  );
});
