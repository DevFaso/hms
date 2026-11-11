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

test('QA D1281 (round 27): a "too short" refusal is a field refusal too', () => {
  // The portal's "Poser une question" refused a blank question with
  // QUESTION_TOO_SHORT and marked nothing; the rule read only REQUIRED,
  // MISSING and VALIDATION_ERROR keys.
  const src = `
export class F {
  submitQuestion(): void {
    if (this.askText.trim().length < 5) {
      this.toast.error(this.translate.instant('PORTAL.EDUCATION.QUESTION_TOO_SHORT'));
      return;
    }
  }
}
`;
  assert.deepEqual(
    requiredRefusalFindings(src).map((f) => f.anchor),
    ['submitQuestion::PORTAL.EDUCATION.QUESTION_TOO_SHORT'],
  );
});

test('QA D1302 (round 28): an early refusal is caught even when the method marks a field later', () => {
  // The patient payment dialog refused a blank amount with a banner and
  // returned before the reference's refuseFields: one helper anywhere in the
  // method excused every refusal in it, and the amount's key was not
  // REQUIRED-shaped (cfaAmountMessageKey).
  const src = `
export class F {
  submitPayment() {
    const amountProblem = cfaAmountProblem(this.payAmount);
    if (amountProblem) {
      this.payError.set(
        this.translate.instant(cfaAmountMessageKey(amountProblem, CFA_AMOUNT_REQUIRED_KEY)),
      );
      return;
    }
    if (this.payMethod !== 'CASH' && !this.payReference.trim()) {
      refuseFields(this.host.nativeElement, ['payRef'], PAY_FIELDS, message);
      return;
    }
  }
}
`;
  assert.deepEqual(
    requiredRefusalFindings(src).map((f) => `${f.rule}::${f.anchor}`),
    ['toast-only-refusal::submitPayment::if(amountProblem)'],
  );
});

test('QA D1306 (round 28): a toast refusal whatever its key, one statement or a block', () => {
  const src = `
export class F {
  submit(): void {
    if (amountProblem || amount === null) {
      this.toast.error(this.translate.instant(cfaAmountMessageKey(amountProblem, 'X.INVALID')));
      return;
    }
    if (!this.code) return this.toast.warning(this.translate.instant('X.CODE_FORMAT'));
  }
}
`;
  assert.deepEqual(
    requiredRefusalFindings(src).map((f) => f.anchor),
    ['submit::if(amountProblem || amount === null)', 'submit::X.CODE_FORMAT'],
  );
});

test('round 28: marking in the same block, a page-level message, or a builder of refusals passes', () => {
  const src = `
export class F {
  submit(): void {
    if (!this.ref.trim()) {
      refuseFields(this.host.nativeElement, ['ref'], ['ref'], m);
      this.toast.error(m);
      return;
    }
    if (!hospitalId) {
      this.toast.error(this.translate.instant('X.NO_HOSPITAL_CONTEXT'));
      return;
    }
  }
  private refusals(): FieldRefusal[] {
    return [{ id: 'a', message: this.translate.instant('X.A_REQUIRED') }];
  }
}
`;
  assert.deepEqual(requiredRefusalFindings(src), []);
});
