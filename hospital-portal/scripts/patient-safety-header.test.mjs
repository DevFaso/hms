import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';
import assert from 'node:assert/strict';

/**
 * QA r70 D3220 (owner priority): the blood group was recorded on the chart and shown on no screen where a
 * clinical decision is made. The class: a clinical screen that names a patient without the patient-safety
 * header. Every screen below works on ONE patient at a time (a consultation, a lab order, a dispensing, a
 * discharge, a vitals capture, a care note, a visit) and must carry <app-patient-safety-header>, which must
 * render the blood group, labelled, with « Non renseigné » when none is recorded.
 *
 * A new clinical screen joins CLINICAL_SCREENS; the front desk's screens never do (RCPT-CLIN: the blood group is
 * clinical, and the storyboard the header reads refuses the desk).
 */
const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

const CLINICAL_SCREENS = {
  'consultation detail': '../src/app/consultations/consultations.html',
  'encounter detail (the doctor’s visit)': '../src/app/encounters/encounters.html',
  'lab order detail': '../src/app/lab/lab.html',
  'pharmacy dispensing': '../src/app/pharmacy/dispensing.html',
  'discharge summary (write and read)': '../src/app/discharge/discharge.html',
  'nurse station (vitals, care note)': '../src/app/nurse-station/nurse-station.html',
  // QA D3867: the prescription dialog shows the allergies and the dated weight before the order is created.
  'new prescription dialog': '../src/app/prescriptions/prescriptions.html',
};

const HEADER_TEMPLATE =
  '../src/app/shared/patient-safety-header/patient-safety-header.component.html';

for (const [name, path] of Object.entries(CLINICAL_SCREENS)) {
  test(`${name} carries the patient-safety header`, () => {
    const html = read(path);
    assert.match(
      html,
      /<app-patient-safety-header\b[^>]*\[patientId\]=/,
      `${path} has no safety header`,
    );
  });
}

test('the discharge summary shows the header where it is written and where it is read', () => {
  const html = read(CLINICAL_SCREENS['discharge summary (write and read)']);
  assert.ok((html.match(/<app-patient-safety-header\b/g) ?? []).length >= 2);
});

test('the safety header renders the blood group, labelled, and says when none is recorded', () => {
  const html = read(HEADER_TEMPLATE);
  assert.match(html, /'PATIENT_SAFETY\.BLOOD_GROUP' \| translate/);
  assert.match(html, /p\.bloodType/);
  // QA D3901: through the shared component, which says « Non renseigné » when no group is recorded.
  assert.match(html, /<app-blood-group-status\b[^>]*\[status\]="p\.bloodGroup"/);
  assert.match(
    read('../src/app/shared/blood-group-status/blood-group-status.component.html'),
    /'PATIENT_SAFETY\.NOT_DOCUMENTED' \| translate/,
  );
  // The other safety items the brief names: allergies and weight.
  assert.match(html, /'PATIENT_SAFETY\.ALLERGIES' \| translate/);
  assert.match(html, /latestWeightKg/);
});

test('the chart banner and the printed visit summary name the blood group too', () => {
  assert.match(
    read('../src/app/patients/storyboard-banner/storyboard-banner.component.html'),
    /'PATIENT_SAFETY\.BLOOD_GROUP' \| translate/,
  );
  assert.match(
    read('../src/app/encounters/visit-summary-sections/visit-summary-sections.component.html'),
    /patientBloodType/,
  );
  assert.match(read('../src/app/nurse-station/nurse-station.html'), /p\.bloodType/);
});

test('the front desk screens never carry the clinical header', () => {
  for (const path of [
    '../src/app/reception/checkin-dialog/checkin-dialog.component.html',
    '../src/app/reception/flow-board/flow-board.component.html',
    '../src/app/patients/patient-form.html',
  ]) {
    const html = read(path);
    assert.doesNotMatch(html, /<app-patient-safety-header\b/, `${path} is a desk screen`);
  }
});
