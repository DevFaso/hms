import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { BillingDeferralsComponent } from './billing-deferrals';
import { BillingDeferralChoiceComponent } from './billing-deferral-choice';
import {
  BillingDeferral,
  BillingDeferralService,
  HospitalBillingPolicy,
} from '../services/billing-deferral.service';
import { ToastService } from '../core/toast.service';
import { ConfirmService } from '../shared/confirm-dialog/confirm.service';
import { expectEmptySubmitRefusal } from '../testing/form-refusal-behaviour';

/**
 * Policy rule 6 (provisional, V345): « à facturer » is asked for by one person, approved by another, executed by
 * the pharmacist; an urgency override is reviewed afterwards. Policy rules 1 and 6: the hospital's billing policy.
 */

function deferral(overrides: Partial<BillingDeferral> = {}): BillingDeferral {
  return {
    id: 'd-1',
    prescriptionId: 'rx-1',
    patientId: 'p-1',
    patientName: 'Awa Traoré',
    medicationName: 'Amoxicilline 500 mg',
    kind: 'OUTPATIENT_CREDIT',
    status: 'REQUESTED',
    reason: 'Ne peut pas payer aujourd’hui',
    requestedByName: 'Issa Pharmacien',
    requestedAt: '2026-11-20T09:00:00',
    canDecide: true,
    canReview: false,
    canExecute: false,
    ...overrides,
  };
}

function policy(overrides: Partial<HospitalBillingPolicy> = {}): HospitalBillingPolicy {
  return {
    autoConsultationOnPlannedAdmission: false,
    pharmacistExceptionalRenewal: false,
    canEdit: true,
    ...overrides,
  };
}

function apiSpy() {
  return jasmine.createSpyObj<BillingDeferralService>('BillingDeferralService', [
    'list',
    'counter',
    'request',
    'approve',
    'reject',
    'cancel',
    'review',
    'policy',
    'updatePolicy',
  ]);
}

function toastSpy() {
  return jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);
}

describe('BillingDeferralsComponent (policy rule 6: the work list)', () => {
  let api: jasmine.SpyObj<BillingDeferralService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirm: jasmine.SpyObj<ConfirmService>;
  let fixture: ComponentFixture<BillingDeferralsComponent>;

  function render(rows: BillingDeferral[], p: HospitalBillingPolicy | null = policy()): HTMLElement {
    api.list.and.returnValue(of({ data: rows }) as never);
    api.policy.and.returnValue(p ? (of({ data: p }) as never) : throwError(() => ({ status: 403 })));
    TestBed.configureTestingModule({
      imports: [BillingDeferralsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BillingDeferralService, useValue: api },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: confirm },
      ],
    });
    fixture = TestBed.createComponent(BillingDeferralsComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function click(host: HTMLElement, testid: string): void {
    host.querySelector<HTMLElement>(`[data-testid="${testid}"]`)!.click();
    fixture.detectChanges();
  }

  /** A control of a form registers in a microtask: wait for it before typing into it or reading it. */
  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function type(host: HTMLElement, testid: string, value: string): void {
    const el = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(`[data-testid="${testid}"]`)!;
    el.value = value;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  }

  beforeEach(() => {
    api = apiSpy();
    toast = toastSpy();
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
  });

  afterEach(() => fixture?.nativeElement.remove());

  it('shows the provisional rule, the request, and only the steps the server gives this reader', () => {
    const host = render([
      deferral(),
      deferral({ id: 'd-2', canDecide: false }),
      deferral({ id: 'd-3', kind: 'URGENCY_OVERRIDE', status: 'EXECUTED', canDecide: false, canReview: true }),
    ]);
    expect(host.querySelector('[data-testid="deferrals-provisional"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="deferral-approve-d-1"]')).not.toBeNull();
    // Whoever asked, or a pharmacist, gets no decision: the row says another person decides.
    expect(host.querySelector('[data-testid="deferral-approve-d-2"]')).toBeNull();
    expect(host.querySelector('[data-testid="deferral-d-2"] [data-testid="deferral-not-yours"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="deferral-review-d-3"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="deferral-cancel-d-3"]')).toBeNull();
  });

  it('an empty step marks its required field and sends nothing', () => {
    const host = render([deferral()]);
    click(host, 'deferral-reject-d-1');
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="deferral-step-confirm"]',
      sent: api.reject,
    });
    expect(confirm.ask).not.toHaveBeenCalled();
  });

  it('a credit needs the approver’s reason; the limit is optional and whole francs', async () => {
    const host = render([deferral()]);
    api.approve.and.returnValue(of({ data: deferral({ status: 'APPROVED' }) }) as never);
    click(host, 'deferral-approve-d-1');
    await settle();
    expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="deferral-step-confirm"]',
      sent: api.approve,
    });

    type(host, 'deferral-step-note', 'Famille connue, paie en fin de mois');
    type(host, 'deferral-step-limit', '12.5');
    click(host, 'deferral-step-confirm');
    expect(api.approve).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY_MONEY.DEFERRAL.LIMIT_INVALID');

    type(host, 'deferral-step-limit', '5000');
    click(host, 'deferral-step-confirm');
    expect(api.approve).toHaveBeenCalledOnceWith('d-1', {
      creditLimit: 5000,
      note: 'Famille connue, paie en fin de mois',
    });
    expect(api.list).toHaveBeenCalledTimes(2);
  });

  it('a third-party cover is approved against its voucher number', async () => {
    const host = render([deferral({ kind: 'THIRD_PARTY_COVER', coverReference: 'AMU-42' })]);
    api.approve.and.returnValue(of({ data: deferral({ status: 'APPROVED' }) }) as never);
    click(host, 'deferral-approve-d-1');
    await settle();
    // The number the counter typed is offered; billing confirms or corrects it.
    expect(host.querySelector<HTMLInputElement>('[data-testid="deferral-step-cover"]')!.value).toBe('AMU-42');
    type(host, 'deferral-step-cover', '');
    click(host, 'deferral-step-confirm');
    expect(api.approve).not.toHaveBeenCalled();
    type(host, 'deferral-step-cover', 'AMU-2026-0042');
    click(host, 'deferral-step-confirm');
    expect(api.approve).toHaveBeenCalledOnceWith('d-1', { coverReference: 'AMU-2026-0042' });
  });

  it('a rejection asks first, and a no sends nothing', async () => {
    const host = render([deferral()]);
    api.reject.and.returnValue(of({ data: deferral({ status: 'REJECTED' }) }) as never);
    click(host, 'deferral-reject-d-1');
    await settle();
    type(host, 'deferral-step-note', 'Aucun justificatif');
    confirm.ask.and.resolveTo(false);
    await fixture.componentInstance.submitStep(deferral(), 'reject');
    expect(api.reject).not.toHaveBeenCalled();

    confirm.ask.and.resolveTo(true);
    await fixture.componentInstance.submitStep(deferral(), 'reject');
    expect(api.reject).toHaveBeenCalledOnceWith('d-1', 'Aucun justificatif');
  });

  it('an urgency override is reviewed with a note; the server’s refusal is shown, never a generic failure', async () => {
    const override = deferral({
      id: 'd-3',
      kind: 'URGENCY_OVERRIDE',
      status: 'EXECUTED',
      canDecide: false,
      canReview: true,
    });
    const host = render([override]);
    click(host, 'deferral-review-d-3');
    await settle();
    type(host, 'deferral-step-note', 'Justifiée, facture émise');
    api.review.and.returnValue(
      throwError(() => ({ status: 409, error: { message: 'Vous avez fait cette dérogation' } })),
    );
    await fixture.componentInstance.submitStep(override, 'review');
    expect(api.review).toHaveBeenCalledOnceWith('d-3', 'Justifiée, facture émise');
    expect(toast.error).toHaveBeenCalledWith('Vous avez fait cette dérogation');
  });

  it('the policy: a hospital admin switches the consultation default; the pharmacist renewal stays off', () => {
    const host = render([], policy());
    api.updatePolicy.and.returnValue(
      of({ data: policy({ autoConsultationOnPlannedAdmission: true }) }) as never,
    );
    const renewal = host.querySelector<HTMLInputElement>('[data-testid="policy-pharmacist-renewal"]')!;
    expect(renewal.disabled).toBeTrue();
    expect(renewal.checked).toBeFalse();

    const consultation = host.querySelector<HTMLInputElement>('[data-testid="policy-auto-consultation"]')!;
    expect(consultation.checked).toBeFalse();
    consultation.click();
    fixture.detectChanges();
    expect(api.updatePolicy).toHaveBeenCalledOnceWith({ autoConsultationOnPlannedAdmission: true });
    expect(fixture.componentInstance.policy()?.autoConsultationOnPlannedAdmission).toBeTrue();
  });

  it('the policy is read-only for whoever is not a hospital admin', () => {
    const host = render([], policy({ canEdit: false }));
    expect(
      host.querySelector<HTMLInputElement>('[data-testid="policy-auto-consultation"]')!.disabled,
    ).toBeTrue();
    fixture.componentInstance.setAutoConsultation(true);
    expect(api.updatePolicy).not.toHaveBeenCalled();
  });

  it('fits 1366, 1024, 768 and 390 px without a sideways scroll', () => {
    const host = render([
      deferral({
        reason: 'Motif-très-long-sans-espace-'.repeat(12),
        medicationName: 'Amoxicilline-acide-clavulanique-'.repeat(6),
      }),
    ]);
    click(host, 'deferral-approve-d-1');
    for (const width of [1366, 1024, 768, 390]) {
      host.style.width = `${width}px`;
      expect(host.scrollWidth).withContext(`${width}px`).toBeLessThanOrEqual(host.clientWidth + 1);
    }
  });
});

describe('BillingDeferralChoiceComponent (policy rule 6: the counter)', () => {
  let api: jasmine.SpyObj<BillingDeferralService>;
  let toast: jasmine.SpyObj<ToastService>;
  let fixture: ComponentFixture<BillingDeferralChoiceComponent>;

  function render(open: BillingDeferral[], onOpenStay = false): HTMLElement {
    api.counter.and.returnValue(of({ data: { onOpenStay, open } }) as never);
    TestBed.configureTestingModule({
      imports: [BillingDeferralChoiceComponent, TranslateModule.forRoot()],
      providers: [
        { provide: BillingDeferralService, useValue: api },
        { provide: ToastService, useValue: toast },
      ],
    });
    fixture = TestBed.createComponent(BillingDeferralChoiceComponent);
    fixture.componentRef.setInput('prescriptionId', 'rx-1');
    fixture.componentRef.setInput('idPrefix', 'hand-over-deferral');
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    api = apiSpy();
    toast = toastSpy();
  });

  afterEach(() => fixture?.nativeElement.remove());

  it('no deferral without an approver: with nothing chosen the fill does not leave, and the option is marked', () => {
    const host = render([]);
    expect(api.counter).toHaveBeenCalledOnceWith('rx-1');
    expect(fixture.componentInstance.choice()).toBeNull();
    expect(toast.error).toHaveBeenCalledWith('PHARMACY_MONEY.DEFERRAL.CHOICE_REQUIRED');
    expect(
      host.querySelector('#hand-over-deferral-mode-urgency')!.getAttribute('aria-invalid'),
    ).toBe('true');
    // The pharmacist has no « à facturer » reason box of their own any more.
    expect(host.querySelector('[data-testid="hand-over-defer-reason"]')).toBeNull();
    expect(host.querySelector('[data-testid="deferral-provisional"]')).not.toBeNull();
  });

  it('an inpatient item goes on the stay: nothing to choose, nothing to approve', () => {
    const host = render([], true);
    expect(host.querySelector('[data-testid="deferral-open-stay"]')).not.toBeNull();
    expect(host.querySelector('[data-testid="deferral-use-urgency"]')).toBeNull();
    expect(fixture.componentInstance.choice()).toEqual({});
  });

  it('a deferral somebody else approved is offered and travels with the fill', () => {
    const host = render([
      deferral({ status: 'APPROVED', canDecide: false, canExecute: true, decidedByName: 'Mariam Comptable' }),
    ]);
    const use = host.querySelector<HTMLInputElement>('[data-testid="deferral-use-approved"]')!;
    expect(use.checked).toBeTrue();
    expect(fixture.componentInstance.choice()).toEqual({ billingDeferralId: 'd-1' });
  });

  it('never the same person: whoever approved it cannot hand the fill over on it', () => {
    const host = render([deferral({ status: 'APPROVED', canDecide: false, canExecute: false })]);
    expect(host.querySelector<HTMLInputElement>('[data-testid="deferral-use-approved"]')!.disabled).toBeTrue();
    expect(host.querySelector('[data-testid="deferral-own-approval"]')).not.toBeNull();
    expect(fixture.componentInstance.choice()).toBeNull();
  });

  it('the pharmacist asks; the request waits for another person and is not a way out', () => {
    const host = render([]);
    api.request.and.returnValue(of({ data: deferral({ canDecide: false }) }) as never);
    const component = fixture.componentInstance;

    component.ask();
    expect(api.request).not.toHaveBeenCalled();
    expect(
      host.querySelector('#hand-over-deferral-request-reason')!.getAttribute('aria-invalid'),
    ).toBe('true');

    component.requestKind = 'THIRD_PARTY_COVER';
    component.requestReason = '  Bon AMU présenté ';
    component.requestCover = ' AMU-42 ';
    api.counter.and.returnValue(
      of({ data: { onOpenStay: false, open: [deferral({ canDecide: false, kind: 'THIRD_PARTY_COVER' })] } }) as never,
    );
    component.ask();
    fixture.detectChanges();
    expect(api.request).toHaveBeenCalledOnceWith({
      prescriptionId: 'rx-1',
      kind: 'THIRD_PARTY_COVER',
      reason: 'Bon AMU présenté',
      coverReference: 'AMU-42',
    });
    expect(host.querySelector('[data-testid="deferral-pending"]')).not.toBeNull();
    expect(component.choice()).toBeNull();
  });

  it('an urgency override needs its reason, and travels as an override', () => {
    const host = render([]);
    const component = fixture.componentInstance;
    host.querySelector<HTMLInputElement>('[data-testid="deferral-use-urgency"]')!.click();
    fixture.detectChanges();
    expect(component.choice()).toBeNull();
    expect(
      host.querySelector('#hand-over-deferral-urgency-reason')!.getAttribute('aria-invalid'),
    ).toBe('true');

    component.urgencyReason = '  Crise d’asthme, caisse fermée ';
    expect(component.choice()).toEqual({ billingUrgencyReason: 'Crise d’asthme, caisse fermée' });
  });

  it('fits 390 px without a sideways scroll', () => {
    const host = render([]);
    host.querySelector<HTMLInputElement>('[data-testid="deferral-use-urgency"]')!.click();
    fixture.detectChanges();
    for (const width of [768, 390]) {
      host.style.width = `${width}px`;
      expect(host.scrollWidth).withContext(`${width}px`).toBeLessThanOrEqual(host.clientWidth + 1);
    }
  });
});
