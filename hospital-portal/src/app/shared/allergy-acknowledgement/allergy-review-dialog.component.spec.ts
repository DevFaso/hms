import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { AllergyConflict, PrescriptionService } from '../../services/prescription.service';
import { AllergyReviewDialogComponent } from './allergy-review-dialog.component';
import { AllergyHoldDialogComponent } from './allergy-hold-dialog.component';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

/**
 * Policy 4 (pending clinical approval): the prescriber's review of an allergy conflict. The dialog
 * shows the conflict as it stands, asks for a decision, a rationale (and a plan to continue) and the
 * confirmation the allergy was read, and sends the token of the conflict shown.
 */
describe('AllergyReviewDialogComponent (Policy 4)', () => {
  let fixture: ComponentFixture<AllergyReviewDialogComponent>;
  let prescriptions: jasmine.SpyObj<PrescriptionService>;

  const conflict: AllergyConflict = {
    conflict: true,
    allergen: 'Pénicilline',
    cards: [{ summary: 'Allergy alert', indicator: 'critical', source: { label: 'HMS' } }],
    reviewToken: 'tok-1',
    reviewCurrent: false,
  };

  function el(testId: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  function type(testId: string, value: string): void {
    const field = el(testId) as HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  function click(testId: string): void {
    el(testId)!.click();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    prescriptions = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'getAllergyConflict',
      'reviewAllergyConflict',
    ]);
    prescriptions.getAllergyConflict.and.returnValue(of(conflict));
    await TestBed.configureTestingModule({
      imports: [AllergyReviewDialogComponent, TranslateModule.forRoot()],
      providers: [{ provide: PrescriptionService, useValue: prescriptions }],
    }).compileComponents();
    fixture = TestBed.createComponent(AllergyReviewDialogComponent);
    fixture.componentRef.setInput('prescriptionId', 'rx-1');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  });

  it('is a labelled modal dialog that loads the conflict of its order', () => {
    const box = el('allergy-review-dialog')!;
    expect(box.getAttribute('role')).toBe('dialog');
    expect(box.getAttribute('aria-modal')).toBe('true');
    expect(box.getAttribute('aria-labelledby')).toBeTruthy();
    expect(prescriptions.getAllergyConflict).toHaveBeenCalledWith('rx-1');
    expect(fixture.nativeElement.textContent).toContain('Allergy alert');
  });

  it('sends nothing until a decision, a rationale, a plan to continue and the confirmation are given', () => {
    click('allergy-review-submit');
    expect(prescriptions.reviewAllergyConflict).not.toHaveBeenCalled();

    click('allergy-review-continue');
    type('allergy-review-rationale', 'Rash only in 2019');
    click('allergy-review-confirm');
    click('allergy-review-submit');
    expect(prescriptions.reviewAllergyConflict).withContext('no plan yet').not.toHaveBeenCalled();
  });

  it('refuses an empty review on its fields, never a toast alone', () => {
    click('allergy-review-continue');
    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="allergy-review-submit"]',
    });
    expect(marked.length).toBe(2);
    expect(prescriptions.reviewAllergyConflict).not.toHaveBeenCalled();
  });

  it('marks the field a 400 names with the server reason', () => {
    prescriptions.reviewAllergyConflict.and.returnValue(
      throwError(() => fieldErrorsResponse({ rationale: 'State the clinical rationale.' })),
    );
    click('allergy-review-stop');
    type('allergy-review-rationale', 'x');
    click('allergy-review-confirm');
    click('allergy-review-submit');
    const id = (el('allergy-review-rationale') as HTMLElement).id;
    expectFieldsMarked(fixture, { [id]: 'State the clinical rationale.' });
  });

  it('records CONTINUE with the plan, the rationale and the token of the conflict shown', () => {
    prescriptions.reviewAllergyConflict.and.returnValue(
      of({ ...conflict, decision: 'CONTINUE', reviewCurrent: true }),
    );
    const reviewed = jasmine.createSpy('reviewed');
    fixture.componentInstance.reviewed.subscribe(reviewed);

    click('allergy-review-continue');
    type('allergy-review-plan', 'Observe 30 min');
    type('allergy-review-rationale', 'Rash only in 2019');
    click('allergy-review-confirm');
    click('allergy-review-submit');

    expect(prescriptions.reviewAllergyConflict).toHaveBeenCalledWith('rx-1', {
      decision: 'CONTINUE',
      plan: 'Observe 30 min',
      rationale: 'Rash only in 2019',
      allergyToken: 'tok-1',
    });
    expect(reviewed).toHaveBeenCalled();
  });

  it('records STOP without a plan', () => {
    prescriptions.reviewAllergyConflict.and.returnValue(
      of({ ...conflict, decision: 'STOP', reviewCurrent: true }),
    );
    click('allergy-review-stop');
    expect(el('allergy-review-plan')).toBeNull();
    type('allergy-review-rationale', 'Anaphylaxis risk');
    click('allergy-review-confirm');
    click('allergy-review-submit');
    expect(prescriptions.reviewAllergyConflict).toHaveBeenCalledWith(
      'rx-1',
      jasmine.objectContaining({ decision: 'STOP', allergyToken: 'tok-1' }),
    );
  });

  it('shows the current conflict again when it changed since it was shown', () => {
    prescriptions.reviewAllergyConflict.and.returnValue(
      throwError(() => ({
        status: 400,
        error: { field: 'allergyToken', message: 'The allergy conflict changed' },
      })),
    );
    click('allergy-review-stop');
    type('allergy-review-rationale', 'Anaphylaxis risk');
    click('allergy-review-confirm');
    click('allergy-review-submit');

    expect(el('allergy-review-error')?.textContent).toContain('changed');
    expect(prescriptions.getAllergyConflict).toHaveBeenCalledTimes(2);
  });

  it('closes on Escape and on its cancel button', () => {
    const closed = jasmine.createSpy('closed');
    fixture.componentInstance.closed.subscribe(closed);
    el('allergy-review-dialog')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    click('allergy-review-close');
    expect(closed).toHaveBeenCalled();
  });
});

describe('AllergyHoldDialogComponent (Policy 4)', () => {
  it('shows the hold and who was notified, and only closes: no reason field, no release', async () => {
    await TestBed.configureTestingModule({
      imports: [AllergyHoldDialogComponent, TranslateModule.forRoot()],
    }).compileComponents();
    const fixture = TestBed.createComponent(AllergyHoldDialogComponent);
    fixture.componentRef.setInput('refusal', {
      why: 'Allergy conflict awaiting prescriber review. Notified: Awa Traore.',
      cards: [],
    });
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[role="dialog"]')).not.toBeNull();
    expect(root.textContent).toContain('Notified: Awa Traore');
    expect(root.querySelector('textarea, input')).toBeNull();
    expect(root.querySelectorAll('button').length).toBe(1);
  });
});
