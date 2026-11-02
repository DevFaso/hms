import { test } from 'node:test';
import assert from 'node:assert/strict';

import { requiredRefusalFindings } from './required-refusal-scan.mjs';

test('QA D1072: a "required" refusal that marks no field is caught, by method', () => {
  const src = `
export class F {
  onSubmit(): void {
    if (!this.form.phone) {
      this.toast.error(this.translate.instant('PATIENTS.REQUIRED_FIELDS'));
      return;
    }
  }
}
`;
  assert.deepEqual(
    requiredRefusalFindings(src).map((f) => f.anchor),
    ['onSubmit::PATIENTS.REQUIRED_FIELDS'],
  );
});

test('a refusal that calls refuseFields or focusFirstInvalid in the same method passes', () => {
  for (const helper of [
    'refuseFields(this.host.nativeElement, ids, all, msg);',
    'focusFirstInvalid(el, ids);',
  ]) {
    const src = `
export class F {
  submit(): void {
    if (!this.a) {
      ${helper}
      this.error.set(this.translate.instant('RECEPTION.WALKIN_VALIDATION_ERROR'));
      return;
    }
  }
}
`;
    assert.deepEqual(requiredRefusalFindings(src), [], helper);
  }
});

test('the helper in ANOTHER method does not excuse the refusal', () => {
  const src = `
export class F {
  mark(): void {
    refuseFields(this.host.nativeElement, [], [], '');
  }

  submit(): void {
    this.toast.error(this.translate.instant('X.MISSING_FIELDS'));
  }
}
`;
  assert.deepEqual(
    requiredRefusalFindings(src).map((f) => f.anchor),
    ['submit::X.MISSING_FIELDS'],
  );
});

test('a message that is not a refusal is not a finding', () => {
  const src = `
export class F {
  load(): void {
    this.toast.success(this.translate.instant('X.SAVED'));
  }
}
`;
  assert.deepEqual(requiredRefusalFindings(src), []);
});

test('QA D1158: a submit guard reason RETURNED to [appSubmitGuard] (which marks the fields) passes', () => {
  const src = `
export class F {
  submitBlockReason(): string | null {
    if (!this.hasVitalSign()) return this.translate.instant('TRIAGE.VITAL_REQUIRED') as string;
    return null;
  }
  onSubmit(): void {
    this.toast.error(this.translate.instant('TRIAGE.VITAL_REQUIRED'));
  }
}
`;
  // The toast in onSubmit still counts; only the returned reason is exempt.
  assert.deepEqual(
    requiredRefusalFindings(src).map((f) => f.anchor),
    ['onSubmit::TRIAGE.VITAL_REQUIRED'],
  );
});
