import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';

import { AllergyHold, AllergyReviewState, allergyHoldRefusal } from './allergy-acknowledgement';
import { AllergyHoldDialogComponent } from './allergy-hold-dialog.component';
import { AllergyReviewStateComponent } from './allergy-review-state.component';

/**
 * QA D1584 / D1585: the review state beside a gate's allergy card, and the hold dialog's title, follow
 * the state the server reads on the gate's own token: awaiting review, CONTINUE (who, when, the plan),
 * or STOP (contact the prescriber).
 */
describe('AllergyReviewStateComponent (QA D1584)', () => {
  let fixture: ComponentFixture<AllergyReviewStateComponent>;

  function el(testId: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(`[data-testid="${testId}"]`);
  }

  function show(review: AllergyReviewState | null): void {
    fixture.componentRef.setInput('review', review);
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AllergyReviewStateComponent, TranslateModule.forRoot()],
    }).compileComponents();
    fixture = TestBed.createComponent(AllergyReviewStateComponent);
  });

  it('says nothing when no card stops the drug', () => {
    show(null);
    expect(el('allergy-review-state')).toBeNull();
  });

  it('unreviewed: awaiting the prescriber review, with no reviewer and no plan', () => {
    show({ state: 'AWAITING_REVIEW' });
    expect(el('allergy-review-state')?.getAttribute('data-state')).toBe('AWAITING_REVIEW');
    expect(el('allergy-review-state-title')?.textContent).toContain(
      'ALLERGY_REVIEW_STATE.TITLE_AWAITING',
    );
    expect(el('allergy-review-state-by')).toBeNull();
    expect(el('allergy-review-state-plan')).toBeNull();
  });

  it('CONTINUE: who reviewed it and when, the plan and the rationale', () => {
    show({
      state: 'CONTINUE',
      reviewedByName: 'Aminata Ouedraogo',
      reviewedAt: '2026-11-10T09:30:00',
      plan: 'Surveillance 30 min après chaque dose',
      rationale: 'Éruption cutanée ancienne',
    });
    expect(el('allergy-review-state-title')?.textContent).toContain(
      'ALLERGY_REVIEW_STATE.TITLE_CONTINUE',
    );
    expect(el('allergy-review-state-by')?.textContent).toContain('ALLERGY_REVIEW_STATE.BY');
    expect(el('allergy-review-state-plan')?.textContent).toContain(
      'Surveillance 30 min après chaque dose',
    );
    expect(el('allergy-review-state-rationale')?.textContent).toContain(
      'Éruption cutanée ancienne',
    );
  });

  it('CONTINUE for a reader the plan does not reach: the state and reviewer, no empty plan line', () => {
    show({
      state: 'CONTINUE',
      reviewedByName: 'Aminata Ouedraogo',
      reviewedAt: '2026-11-10T09:30:00',
    });
    expect(el('allergy-review-state-by')).not.toBeNull();
    expect(el('allergy-review-state-plan')).toBeNull();
    expect(el('allergy-review-state-rationale')).toBeNull();
  });

  it('STOP: the prescriber decided to stop, contact the prescriber', () => {
    show({ state: 'STOP', reviewedByName: null, reviewedAt: '2026-11-10T09:30:00' });
    expect(el('allergy-review-state')?.getAttribute('data-state')).toBe('STOP');
    expect(el('allergy-review-state-title')?.textContent).toContain(
      'ALLERGY_REVIEW_STATE.TITLE_STOP',
    );
    expect(el('allergy-review-state-by')?.textContent).toContain('ALLERGY_REVIEW_STATE.BY_UNKNOWN');
    expect(el('allergy-review-state-plan')).toBeNull();
  });
});

describe('AllergyHoldDialogComponent title (QA D1585)', () => {
  let fixture: ComponentFixture<AllergyHoldDialogComponent>;

  function hold(reviewState?: string): AllergyHold {
    const why = 'Conflit allergique';
    return allergyHoldRefusal({
      status: 400,
      error: {
        field: 'allergyPrescriberReview',
        message: why,
        fieldErrors: { allergyPrescriberReview: why },
        cdsAdvisories: [],
        reviewState,
      },
    })!;
  }

  function title(): string {
    return (
      fixture.nativeElement.querySelector('[data-testid="allergy-hold-title"]')?.textContent ?? ''
    );
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AllergyHoldDialogComponent, TranslateModule.forRoot()],
    }).compileComponents();
    fixture = TestBed.createComponent(AllergyHoldDialogComponent);
  });

  it('awaiting review: titled for the review it waits for', () => {
    fixture.componentRef.setInput('refusal', hold('AWAITING_REVIEW'));
    fixture.detectChanges();
    expect(title()).toContain('ALLERGY_HOLD.TITLE');
    expect(title()).not.toContain('ALLERGY_HOLD.TITLE_STOP');
    expect(fixture.nativeElement.textContent).toContain('ALLERGY_HOLD.WHAT_NOW');
  });

  it("STOP: titled for the prescriber's decision, not as awaiting review", () => {
    fixture.componentRef.setInput('refusal', hold('STOP'));
    fixture.detectChanges();
    expect(title()).toContain('ALLERGY_HOLD.TITLE_STOP');
    expect(fixture.nativeElement.textContent).toContain('ALLERGY_HOLD.WHAT_NOW_STOP');
  });

  it('an older server with no state reads as awaiting review', () => {
    expect(hold(undefined).state).toBe('AWAITING_REVIEW');
    expect(hold('anything else').state).toBe('AWAITING_REVIEW');
  });
});
