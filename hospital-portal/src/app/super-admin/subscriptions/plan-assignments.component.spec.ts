import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { PlanAssignmentsComponent } from './plan-assignments.component';
import { ToastService } from '../../core/toast.service';
import { OrganizationService } from '../../services/organization.service';
import { OrganizationSubscription, SubscriptionPlan } from '../../services/subscription.model';
import { SubscriptionService } from '../../services/subscription.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import {
  expectEmptySubmitRefusal,
  expectFieldsMarked,
  fieldErrorsResponse,
} from '../../testing/form-refusal-behaviour';

/** QA r65 D2766: a plan can be assigned to an organisation from the plans page. */
describe('PlanAssignmentsComponent (QA r65 D2766)', () => {
  let subscriptions: jasmine.SpyObj<SubscriptionService>;
  let toast: jasmine.SpyObj<ToastService>;
  let confirmAnswer: boolean;

  const plan = (over: Partial<SubscriptionPlan> = {}): SubscriptionPlan => ({
    id: 'p-1',
    name: 'Essentiel',
    tierCode: 'ESS',
    description: null,
    monthlyPriceCents: 25000,
    currency: 'XOF',
    includedSeats: 10,
    featureKeys: '',
    active: true,
    createdAt: null,
    updatedAt: null,
    ...over,
  });
  const sub = (over: Partial<OrganizationSubscription> = {}): OrganizationSubscription => ({
    id: 's-1',
    organizationId: 'o-1',
    organizationName: 'Org Test D',
    planId: 'p-1',
    planName: 'Essentiel',
    planTierCode: 'ESS',
    seatLimit: 10,
    billingPeriod: 'MONTHLY',
    status: 'ACTIVE',
    startedAt: '2026-11-01T08:00:00Z',
    endsAt: null,
    ...over,
  });

  function render(
    plans: SubscriptionPlan[] = [
      plan(),
      plan({ id: 'p-2', name: 'Ancien', tierCode: 'OLD', active: false }),
    ],
  ) {
    TestBed.configureTestingModule({
      imports: [PlanAssignmentsComponent, TranslateModule.forRoot()],
      providers: [
        { provide: SubscriptionService, useValue: subscriptions },
        {
          provide: OrganizationService,
          useValue: {
            list: () =>
              of({
                content: [{ id: 'o-1', name: 'Org Test D', code: 'OTD' }],
                totalElements: 1,
                totalPages: 1,
                number: 0,
                size: 100,
                last: true,
              }),
          },
        },
        { provide: ToastService, useValue: toast },
        { provide: ConfirmService, useValue: { ask: () => Promise.resolve(confirmAnswer) } },
      ],
    });
    const fixture = TestBed.createComponent(PlanAssignmentsComponent);
    fixture.componentRef.setInput('plans', plans);
    fixture.detectChanges();
    return fixture;
  }

  const el = (fixture: ComponentFixture<unknown>, id: string) =>
    (fixture.nativeElement as HTMLElement).querySelector<HTMLElement>(`[data-testid="${id}"]`);

  beforeEach(() => {
    subscriptions = jasmine.createSpyObj<SubscriptionService>('SubscriptionService', [
      'listForOrganization',
      'assignPlan',
      'cancel',
    ]);
    subscriptions.listForOrganization.and.returnValue(of([]));
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
    confirmAnswer = true;
  });

  afterEach(() => TestBed.resetTestingModule());

  it('lists the organisations, and asks nothing about plans before one is chosen', () => {
    const fixture = render();
    const options = el(fixture, 'plan-assignments-organisation')!.querySelectorAll('option');
    expect(options.length).toBe(2);
    expect(subscriptions.listForOrganization).not.toHaveBeenCalled();
    expect(el(fixture, 'plan-assignments-current')).toBeNull();
  });

  it('an organisation with no plan says so, and offers only the ACTIVE plans', () => {
    const fixture = render();
    fixture.componentInstance.selectOrganisation('o-1');
    fixture.detectChanges();

    expect(subscriptions.listForOrganization).toHaveBeenCalledOnceWith('o-1');
    expect(el(fixture, 'plan-assignments-none')).not.toBeNull();
    const offered = Array.from(
      el(fixture, 'plan-assignments-plan')!.querySelectorAll('option'),
    ).map((option) => option.getAttribute('value') ?? option.value);
    expect(offered).toContain('p-1');
    expect(offered).not.toContain('p-2');
  });

  it('assigns the chosen plan with its seats and period, then reads the organisation again', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.selectOrganisation('o-1');
    subscriptions.assignPlan.and.returnValue(of(sub()));
    subscriptions.listForOrganization.and.returnValue(of([sub()]));

    component.selectPlan('p-1');
    expect(component.seatLimit()).toBe(10); // the plan's own seat count is proposed
    component.seatLimit.set(25);
    component.billingPeriod.set('ANNUAL');
    component.assign();
    fixture.detectChanges();

    expect(subscriptions.assignPlan).toHaveBeenCalledOnceWith('o-1', {
      planId: 'p-1',
      seatLimit: 25,
      billingPeriod: 'ANNUAL',
    });
    expect(toast.success).toHaveBeenCalled();
    expect(el(fixture, 'plan-assignments-current')!.textContent).toContain('Essentiel');
    expect(el(fixture, 'plan-assignments-end')).not.toBeNull();
  });

  it('pressed with nothing chosen, the plan and the seats are marked and nothing is sent', () => {
    const fixture = render();
    document.body.appendChild(fixture.nativeElement);
    fixture.componentInstance.selectOrganisation('o-1');
    fixture.detectChanges();

    expect(
      expectEmptySubmitRefusal(fixture, {
        button: '[data-testid="plan-assignments-assign"]',
        sent: subscriptions.assignPlan,
      }),
    ).toEqual(['pa-plan', 'pa-seats']);
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('refuses a fractional or negative seat count on its field, before anything is sent', () => {
    const fixture = render();
    document.body.appendChild(fixture.nativeElement);
    const component = fixture.componentInstance;
    component.selectOrganisation('o-1');
    component.selectPlan('p-1');
    fixture.detectChanges();

    component.seatLimit.set(2.5);
    component.assign();
    expectFieldsMarked(fixture, { 'pa-seats': 'PLATFORM_SETUP.PLANS.SEATS_INVALID' });
    component.seatLimit.set(-1);
    component.assign();

    expect(subscriptions.assignPlan).not.toHaveBeenCalled();
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('a refusal the server pins on a field (a deactivated plan) is marked on that field', () => {
    const fixture = render();
    document.body.appendChild(fixture.nativeElement);
    const component = fixture.componentInstance;
    component.selectOrganisation('o-1');
    component.selectPlan('p-1');
    fixture.detectChanges();
    subscriptions.assignPlan.and.returnValue(
      throwError(() => fieldErrorsResponse({ planId: 'Le plan ESS est désactivé.' })),
    );

    component.assign();
    fixture.detectChanges();

    expectFieldsMarked(fixture, { 'pa-plan': 'Le plan ESS est désactivé.' });
    expect(component.saving()).toBeFalse();
    (fixture.nativeElement as HTMLElement).remove();
  });

  it('a refused assignment shows the server reason and keeps the form', () => {
    const fixture = render();
    const component = fixture.componentInstance;
    component.selectOrganisation('o-1');
    component.selectPlan('p-1');
    subscriptions.assignPlan.and.returnValue(
      throwError(
        () => new HttpErrorResponse({ status: 400, error: { message: 'Ce plan est désactivé.' } }),
      ),
    );

    component.assign();

    expect(toast.error).toHaveBeenCalledWith('Ce plan est désactivé.');
    expect(component.planId()).toBe('p-1');
    expect(component.saving()).toBeFalse();
  });

  it('ends the current plan only after the confirmation, on the organisation it belongs to', async () => {
    subscriptions.listForOrganization.and.returnValue(of([sub()]));
    subscriptions.cancel.and.returnValue(of(sub({ status: 'CANCELLED' })));
    const fixture = render();
    const component = fixture.componentInstance;
    component.selectOrganisation('o-1');

    confirmAnswer = false;
    await component.endCurrent(sub());
    expect(subscriptions.cancel).not.toHaveBeenCalled();

    confirmAnswer = true;
    await component.endCurrent(sub());
    expect(subscriptions.cancel).toHaveBeenCalledOnceWith('o-1', 's-1');
  });

  it('shows the plans the organisation was on before, most recent first', () => {
    subscriptions.listForOrganization.and.returnValue(
      of([
        sub({
          id: 's-old',
          status: 'CANCELLED',
          planName: 'Découverte',
          startedAt: '2026-01-01T00:00:00Z',
          endsAt: '2026-06-01T00:00:00Z',
        }),
        sub({
          id: 's-mid',
          status: 'CANCELLED',
          planName: 'Standard',
          startedAt: '2026-06-01T00:00:00Z',
          endsAt: '2026-11-01T00:00:00Z',
        }),
        sub(),
      ]),
    );
    const fixture = render();
    fixture.componentInstance.selectOrganisation('o-1');
    fixture.detectChanges();

    const lines = Array.from(el(fixture, 'plan-assignments-history')!.querySelectorAll('li')).map(
      (li) => li.textContent ?? '',
    );
    expect(lines.length).toBe(2);
    expect(lines[0]).toContain('Standard');
    expect(lines[1]).toContain('Découverte');
  });

  it('fits its box at 1366, 1024, 768 and 390 px wide', () => {
    subscriptions.listForOrganization.and.returnValue(of([sub()]));
    const fixture = render();
    fixture.componentInstance.selectOrganisation('o-1');
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    for (const width of [1366, 1024, 768, 390]) {
      host.style.width = `${width - 32}px`;
      fixture.detectChanges();
      const card = el(fixture, 'plan-assignments')!;
      expect(card.scrollWidth)
        .withContext(`plan assignments at ${width}px`)
        .toBeLessThanOrEqual(card.clientWidth + 1);
    }
  });
});
