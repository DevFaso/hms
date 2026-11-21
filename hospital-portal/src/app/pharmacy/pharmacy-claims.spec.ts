import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { PharmacyClaimsComponent } from './pharmacy-claims';
import { PharmacyClaimResponse, PharmacyService } from '../services/pharmacy.service';
import { ToastService } from '../core/toast.service';
import { answerReasonDialog, reasonDialog } from '../shared/reason-dialog/reason-dialog.testing';

describe('PharmacyClaimsComponent', () => {
  let fixture: ComponentFixture<PharmacyClaimsComponent>;
  let pharmacy: jasmine.SpyObj<PharmacyService>;

  function claim(overrides: Partial<PharmacyClaimResponse> = {}): PharmacyClaimResponse {
    return {
      id: 'c-1',
      dispenseId: 'd-1',
      patientId: '3f2a9c1b-1111-4000-8000-000000000000',
      hospitalId: 'h-1',
      claimStatus: 'DRAFT',
      amount: 1500,
      currency: 'XOF',
      createdAt: '2026-10-27T09:00:00',
      updatedAt: '2026-10-27T09:00:00',
      ...overrides,
    };
  }

  async function render(rows: PharmacyClaimResponse[]): Promise<HTMLElement> {
    pharmacy = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listClaimsByHospital',
      'rejectClaim',
      'acceptClaim',
    ]);
    pharmacy.listClaimsByHospital.and.returnValue(of({ data: { content: rows } }) as never);
    await TestBed.configureTestingModule({
      imports: [PharmacyClaimsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PharmacyService, useValue: pharmacy },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(PharmacyClaimsComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('QA D992 class: names the patient, never a slice of the patient id', async () => {
    const el = await render([claim({ patientName: 'Awa Test' })]);
    const text = el.querySelector('tbody')?.textContent ?? '';
    expect(text).toContain('Awa Test');
    expect(text).not.toContain('3f2a9c1b');
  });

  it('QA D992 class: a claim whose patient has no name says so', async () => {
    const el = await render([claim({ patientName: null })]);
    const text = el.querySelector('tbody')?.textContent ?? '';
    expect(text).toContain('PHARMACY_CLAIMS.PATIENT_UNNAMED');
    expect(text).not.toContain('3f2a9c1b');
  });

  // QA D1201 (round 22): the reject reason is asked in the shared dialog, not window.prompt.
  it('QA D1201: rejecting a claim asks the reason in the page; blank sends nothing', async () => {
    await render([claim({ claimStatus: 'SUBMITTED' })]);
    pharmacy.rejectClaim.and.returnValue(of({ data: claim() }) as never);
    fixture.componentInstance.reject(claim());
    expect(reasonDialog(fixture)).not.toBeNull();
    answerReasonDialog(fixture, ' ');
    expect(pharmacy.rejectClaim).not.toHaveBeenCalled();
    answerReasonDialog(fixture, 'Ordonnance illisible');
    expect(pharmacy.rejectClaim).toHaveBeenCalledWith('c-1', 'Ordonnance illisible');
    expect(reasonDialog(fixture)).toBeNull();
  });

  // QA D2925 (r66): the payer's answer is the money desks'; the pharmacy creates and sends.
  it('QA D2925: the pharmacy sees no accept, reject or pay, and is told who answers', async () => {
    const el = await render([
      claim({ id: 'c-sub', claimStatus: 'SUBMITTED', canRecordPayerDecision: false }),
      claim({ id: 'c-acc', claimStatus: 'ACCEPTED', canRecordPayerDecision: false }),
      claim({ id: 'c-draft', claimStatus: 'DRAFT', canRecordPayerDecision: false }),
    ]);
    for (const step of ['accept', 'reject', 'pay', 'confirm']) {
      expect(el.querySelector(`[data-testid^="claim-${step}-"]`)).toBeNull();
    }
    expect(el.querySelector('[data-testid="claim-waiting-c-sub"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="claim-waiting-c-acc"]')).not.toBeNull();
    // A draft is still the pharmacy's to send.
    expect(el.querySelector('[data-testid="claim-waiting-c-draft"]')).toBeNull();
    expect(el.querySelector('tbody')!.textContent).toContain('PHARMACY_CLAIMS.SUBMIT');
  });

  it('QA D2925: a money desk records the payer’s answer, and confirms an acceptance nobody stands behind', async () => {
    const el = await render([
      claim({ id: 'c-sub', claimStatus: 'SUBMITTED', canRecordPayerDecision: true }),
      claim({
        id: 'c-stuck',
        claimStatus: 'ACCEPTED',
        canRecordPayerDecision: true,
        acceptanceUnconfirmed: true,
      }),
      claim({ id: 'c-acc', claimStatus: 'ACCEPTED', canRecordPayerDecision: true }),
    ]);
    expect(el.querySelector('[data-testid="claim-accept-c-sub"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="claim-reject-c-sub"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="claim-pay-c-acc"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="claim-confirm-c-acc"]')).toBeNull();
    expect(el.querySelector('[data-testid="claim-unconfirmed-c-stuck"]')).not.toBeNull();
    expect(el.querySelector('[data-testid^="claim-waiting-"]')).toBeNull();

    pharmacy.acceptClaim.and.returnValue(of({ data: claim() }) as never);
    el.querySelector<HTMLButtonElement>('[data-testid="claim-confirm-c-stuck"]')!.click();
    expect(pharmacy.acceptClaim).toHaveBeenCalledWith('c-stuck');
  });

  it('keeps status chips, rendered rows and the mobile select in sync', async () => {
    const el = await render([
      claim({ patientName: 'Draft fixture' }),
      claim({ id: 'c-2', patientName: 'Paid fixture', claimStatus: 'PAID' }),
    ]);
    const buttons = Array.from(el.querySelectorAll<HTMLButtonElement>('.claim-status-tabs button'));
    const paid = buttons.find((b) => b.textContent?.includes('PHARMACY_CLAIMS.STATUS.PAID'))!;
    paid.click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(el.querySelector('tbody')?.textContent).toContain('Paid fixture');
    expect(el.querySelector('tbody')?.textContent).not.toContain('Draft fixture');
    expect(paid.getAttribute('aria-pressed')).toBe('true');
    const select = el.querySelector<HTMLSelectElement>('select[name="filter"]')!;
    expect(select.value).toBe('PAID');
    select.value = 'DRAFT';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(paid.getAttribute('aria-pressed')).toBe('false');
    expect(el.querySelector('tbody')?.textContent).toContain('Draft fixture');
    expect(el.querySelector('tbody')?.textContent).not.toContain('Paid fixture');
  });

  it('distinguishes no claims yet from a filter with no matches', async () => {
    const el = await render([]);
    expect(el.querySelector('.claims-empty')?.textContent).toContain(
      'PHARMACY_WORKSPACE.CLAIMS_NONE_HINT',
    );
    fixture.componentInstance.filter.set('PAID');
    fixture.detectChanges();
    expect(el.querySelector('.claims-empty')?.textContent).toContain(
      'PHARMACY_WORKSPACE.CLAIMS_EMPTY_HINT',
    );
  });
});
