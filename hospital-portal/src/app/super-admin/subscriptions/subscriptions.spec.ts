import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { SubscriptionsComponent } from './subscriptions';
import { SubscriptionService } from '../../services/subscription.service';
import { SubscriptionPlan } from '../../services/subscription.model';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { ToastService } from '../../core/toast.service';
import { expectEmptySubmitRefusal } from '../../testing/form-refusal-behaviour';

describe('SubscriptionsComponent', () => {
  let service: jasmine.SpyObj<SubscriptionService>;

  function setup(): SubscriptionsComponent {
    TestBed.configureTestingModule({
      imports: [SubscriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SubscriptionService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(SubscriptionsComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<SubscriptionService>('SubscriptionService', [
      'listPlans',
      'createPlan',
      'updatePlan',
      'deactivatePlan',
    ]);
    service.listPlans.and.returnValue(of([] as SubscriptionPlan[]));
  });

  it('opens the form when New plan is clicked even though every field is empty', () => {
    const cmp = setup();

    expect(cmp.formOpen()).toBeFalse();
    cmp.startCreate();

    // The bug we are guarding against: the original implementation gated the
    // form on `editing() || form().name !== ''` which is false right after
    // a reset, so the form silently never opened.
    expect(cmp.formOpen()).toBeTrue();
    expect(cmp.editing()).toBeNull();
    expect(cmp.form().name).toBe('');
  });

  it('cancelEdit closes the form and clears any error', () => {
    const cmp = setup();

    cmp.startCreate();
    cmp.cancelEdit();

    expect(cmp.formOpen()).toBeFalse();
    expect(cmp.formError()).toBeNull();
  });

  it('submit emits a translation-key error (not a hardcoded English message) when required fields are missing', () => {
    const cmp = setup();
    cmp.startCreate();
    cmp.submit();

    // QA D1358: the refusal names the blank fields (it was a fixed sentence).
    expect(cmp.formError()).toBe('COMMON.MISSING_REQUIRED_FIELDS');
  });

  it('submit error path stores a translation key, not a raw English string', () => {
    service.createPlan.and.returnValue(throwError(() => new Error('boom')));

    const cmp = setup();
    cmp.startCreate();
    cmp.updateForm('name', 'Pro');
    cmp.updateForm('tierCode', 'PRO');
    cmp.submit();

    expect(cmp.formError()).toBe('SUBSCRIPTIONS.ERROR.SAVE_FAILED');
  });

  it('QA D865: a new plan is priced in F CFA, typed in francs, stored in hundredths', () => {
    service.createPlan.and.returnValue(of({} as SubscriptionPlan));
    const cmp = setup();
    cmp.startCreate();
    expect(cmp.form().currency).toBe('XOF');
    expect(cmp.priceStep()).toBe(1);

    cmp.updateForm('name', 'Essentiel');
    cmp.updateForm('tierCode', 'ESS');
    cmp.updatePrice(25000);
    expect(cmp.priceAmount()).toBe(25000);
    cmp.submit();

    expect(service.createPlan).toHaveBeenCalledWith(
      jasmine.objectContaining({ currency: 'XOF', monthlyPriceCents: 2_500_000 }),
    );
  });

  it('QA D865: a fractional franc is refused before the call; euros keep their cents', () => {
    const cmp = setup();
    cmp.startCreate();
    cmp.updateForm('name', 'Essentiel');
    cmp.updateForm('tierCode', 'ESS');
    cmp.updatePrice(25000.5);
    cmp.submit();
    expect(service.createPlan).not.toHaveBeenCalled();
    expect(cmp.formError()).toBe('SUBSCRIPTIONS.ERROR.WHOLE_UNITS');

    cmp.updateForm('currency', 'EUR');
    expect(cmp.priceStep()).toBe(0.01);
    cmp.updatePrice(49.99);
    expect(cmp.form().monthlyPriceCents).toBe(4999);
  });

  it('QA D621: the price label names francs CFA as F CFA, never the raw XOF code', () => {
    const cmp = setup();
    expect(cmp.currencyUnit('XOF')).toBe('F CFA');
    expect(cmp.currencyUnit('xof')).toBe('F CFA');
    expect(cmp.currencyUnit('EUR')).toBe('EUR');

    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      SUBSCRIPTIONS: { FORM: { PRICE: 'Prix mensuel ({{currency}})' } },
    });
    translate.use('fr');
    const fixture = TestBed.createComponent(SubscriptionsComponent);
    fixture.componentInstance.startCreate();
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('Prix mensuel (F CFA)');
    expect(text).not.toContain('(XOF)');
  });

  it('QA D865: the currency is picked from ISO codes; a legacy code stays selectable', () => {
    const cmp = setup();
    cmp.startCreate();
    expect(cmp.currencyOptions()).toContain('XOF');
    expect(cmp.currencyOptions()).not.toContain('ZZZZ');

    cmp.updateForm('currency', 'ZZZZ');
    expect(cmp.currencyOptions()).toContain('ZZZZ');
  });

  it('QA CX-010: deactivating asks first; a cancel sends nothing, a yes sends it and says so', async () => {
    service.deactivatePlan.and.returnValue(of(void 0));
    const cmp = setup();
    const ask = spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);
    const plan = { id: 'p1', name: 'Pro', active: true } as SubscriptionPlan;

    await cmp.deactivate(plan);
    expect(ask).toHaveBeenCalledTimes(1);
    expect(service.deactivatePlan).not.toHaveBeenCalled();

    const success = spyOn(TestBed.inject(ToastService), 'success');
    ask.and.resolveTo(true);
    await cmp.deactivate(plan);
    expect(service.deactivatePlan).toHaveBeenCalledOnceWith('p1');
    expect(success).toHaveBeenCalledTimes(1);
  });

  it('QA CX-004: an empty plan marks name and tier code, starred, and sends nothing', () => {
    TestBed.configureTestingModule({
      imports: [SubscriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: SubscriptionService, useValue: service },
      ],
    });
    const fixture = TestBed.createComponent(SubscriptionsComponent);
    document.body.appendChild(fixture.nativeElement);
    fixture.detectChanges();
    fixture.componentInstance.startCreate();
    fixture.detectChanges();

    const marked = expectEmptySubmitRefusal(fixture, {
      button: '[data-testid="plan-save"]',
      sent: service.createPlan,
    });

    expect(marked).toEqual(['plan-name', 'plan-tier-code']);
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('QA CX-007: a save says so, new and edited', () => {
    service.createPlan.and.returnValue(of({} as SubscriptionPlan));
    service.updatePlan.and.returnValue(of({} as SubscriptionPlan));
    const cmp = setup();
    const success = spyOn(TestBed.inject(ToastService), 'success');
    cmp.startCreate();
    cmp.updateForm('name', 'Pro');
    cmp.updateForm('tierCode', 'PRO');
    cmp.submit();
    expect(success).toHaveBeenCalledWith('SUBSCRIPTIONS.CREATED');

    cmp.startEdit({
      id: 'p1',
      name: 'Pro',
      tierCode: 'PRO',
      currency: 'XOF',
      monthlyPriceCents: 0,
      active: true,
    } as SubscriptionPlan);
    cmp.submit();
    expect(success).toHaveBeenCalledWith('SUBSCRIPTIONS.UPDATED');
  });

  it('QA D850: a refused deactivation shows the server reason', async () => {
    const reason = "Cette formule est encore l'abonnement actif de 1 organisation(s).";
    service.deactivatePlan.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { message: reason } })),
    );
    const cmp = setup();
    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);
    await cmp.deactivate({ id: 'p1', active: true } as SubscriptionPlan);

    expect(cmp.deactivateError()).toBe(reason);
    expect(service.listPlans).toHaveBeenCalledTimes(1);
  });
});
