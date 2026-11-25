import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';

/**
 * QA D3901 (r77, High): the record printed the group a doctor TYPED (« A+ ») in red bold, as if verified, for a
 * patient the laboratory had grouped B+, and never showed the laboratory's grouping.
 *
 * The class: a template that prints the typed group (`bloodType`, `patientBloodType`) itself. Every screen shows
 * the group through <app-blood-group-status>, which shows the laboratory's confirmed group, labels a typed one
 * « déclaré, non vérifié » and warns when they differ. The one exception is a translation that already names the
 * value as declared (the blood bank's « Dossier : A+ (non vérifié) », keys `*.DECLARED_*`).
 */
const APP = fileURLToPath(new URL('../src/app', import.meta.url));
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

const templates = readdirSync(APP, { recursive: true })
  .map(String)
  .filter((file) => file.endsWith('.html'));

const TYPED_GROUP = /\b(?:\w+\.)?(?:patientB|b)loodType\b/;

test('no template prints the typed blood group itself', () => {
  const offenders = [];
  for (const file of templates) {
    const html = readFileSync(join(APP, file), 'utf8');
    for (const match of html.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
      const inner = match[1];
      if (TYPED_GROUP.test(inner) && !/\.DECLARED_[A-Z_]+'/.test(inner)) {
        const line = html.slice(0, match.index).split('\n').length;
        offenders.push(`${file}:${line}`);
      }
    }
  }
  assert.deepEqual(
    offenders,
    [],
    'print the group through <app-blood-group-status [status]=… [declared]=…> (QA D3901)',
  );
});

const SCREENS = {
  'patient header, essentials and medical details': ['../src/app/patients/patient-detail.html', 3],
  'safety strip': [
    '../src/app/shared/patient-safety-header/patient-safety-header.component.html',
    1,
  ],
  'chart banner': ['../src/app/patients/storyboard-banner/storyboard-banner.component.html', 1],
  'patient list column': ['../src/app/patients/patient-list.html', 1],
  'nurse station (board and oversight)': ['../src/app/nurse-station/nurse-station.html', 2],
  'visit summary': [
    '../src/app/encounters/visit-summary-sections/visit-summary-sections.component.html',
    1,
  ],
  'patient portal record': ['../src/app/patient-portal/my-records/my-records.component.html', 1],
};

for (const [name, [path, count]] of Object.entries(SCREENS)) {
  test(`${name} shows the group through the shared component, with its status`, () => {
    const html = read(path);
    const uses = html.match(/<app-blood-group-status\b[^>]*\[status\]=/g) ?? [];
    assert.ok(uses.length >= count, `${path}: ${uses.length} of ${count}`);
  });
}

test('the chart reads show the antibody alert; the component renders it', () => {
  const component = read('../src/app/shared/blood-group-status/blood-group-status.component.html');
  assert.match(component, /'BLOOD_GROUP\.ALLO_IMMUNISED'\s*\|/);
  assert.match(component, /'BLOOD_GROUP\.DECLARED_UNVERIFIED' \| translate/);
  assert.match(component, /'BLOOD_GROUP\.DIFFERS_FROM_DECLARED'\s*\|/);
  const strip = read(
    '../src/app/shared/patient-safety-header/patient-safety-header.component.html',
  );
  assert.doesNotMatch(strip, /\[showAntibodies\]="false"/);
});

test('the forms label the typed group as declared', () => {
  for (const path of [
    '../src/app/patients/patient-form.html',
    '../src/app/patients/patient-demographics-edit/patient-demographics-edit.component.html',
  ]) {
    assert.match(read(path), /'BLOOD_GROUP\.DECLARED_FIELD' \| translate/, path);
  }
});
