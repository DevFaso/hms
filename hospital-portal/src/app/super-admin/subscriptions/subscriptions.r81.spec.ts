import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ToastService } from '../../core/toast.service';
import { SubscriptionPlan } from '../../services/subscription.model';
import { SubscriptionService } from '../../services/subscription.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';
import { SubscriptionsComponent } from './subscriptions';

function plan(overrides: Partial<SubscriptionPlan>): SubscriptionPlan {
  return {
    id: 'p1',
    name: 'Essentiel',
    tierCode: 'ESS',
    description: null,
    monthlyPriceCents: 7_500_000,
    currency: 'XOF',
    includedSeats: 1,
    featureKeys: 'ff.platform.billing',
    active: true,
    activeOrganizationCount: 1,
    createdAt: null,
    updatedAt: null,
    ...overrides,
  };
}

describe('SubscriptionsComponent (QA r81)', () => {
  let service: jasmine.SpyObj<SubscriptionService>;
  let confirm: jasmine.SpyObj<ConfirmService>;

  function render(plans: SubscriptionPlan[]) {
    service = jasmine.createSpyObj<SubscriptionService>('SubscriptionService', [
      'listPlans',
      'createPlan',
      'updatePlan',
      'deactivatePlan',
      'reactivatePlan',
      'listFeatureKeys',
      'listForOrganization',
    ]);
    service.listPlans.and.returnValue(of(plans));
    service.listFeatureKeys.and.returnValue(of(['ff.platform.billing', 'ff.platform.ehr']));
    service.reactivatePlan.and.returnValue(of(plan({ active: true })));
    confirm = jasmine.createSpyObj<ConfirmService>('ConfirmService', ['ask']);
    confirm.ask.and.resolveTo(true);
    TestBed.configureTestingModule({
      imports: [SubscriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: SubscriptionService, useValue: service },
        { provide: ConfirmService, useValue: confirm },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
    const fixture = TestBed.createComponent(SubscriptionsComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('D4070: feature keys are ticked from the server catalogue; nothing can be typed', () => {
    const fixture = render([plan({})]);
    const component = fixture.componentInstance;
    component.startCreate();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('#plan-feature-keys input[type="text"]')).toBeNull();
    const boxes = host.querySelectorAll<HTMLInputElement>(
      '[data-test="plan-feature-keys"] input[type="checkbox"]',
    );
    expect(boxes.length).toBe(2);

    component.toggleFeatureKey('ff.platform.ehr', true);
    component.toggleFeatureKey('ff.platform.billing', true);
    component.toggleFeatureKey('ff.platform.ehr', false);
    expect(component.form().featureKeys).toBe('ff.platform.billing');
  });

  it('D4070: a stored key the catalogue no longer has is shown as unknown, to be unticked', () => {
    const fixture = render([plan({ featureKeys: 'ff.platform.billing,cle_bidon' })]);
    const component = fixture.componentInstance;
    component.startEdit(component.plans()[0]);
    fixture.detectChanges();

    expect(component.unknownFeatureKeys()).toEqual(['cle_bidon']);
    expect(
      (fixture.nativeElement as HTMLElement).querySelector(
        '[data-test="plan-feature-unknown-cle_bidon"]',
      ),
    ).not.toBeNull();
  });

  it('D4310: an inactive plan is labelled, can be listed alone, and is reactivated after a confirmation', async () => {
    const fixture = render([plan({ id: 'a' }), plan({ id: 'b', name: 'Ancien', active: false })]);
    const component = fixture.componentInstance;
    component.setStatusFilter('INACTIVE');
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;

    expect(service.listPlans).toHaveBeenCalledWith(false);
    expect(component.visiblePlans().map((p) => p.id)).toEqual(['b']);
    expect(host.querySelector('[data-test="plan-status-b"]')).not.toBeNull();
    expect(host.querySelector('[data-test="plan-toggle-b"]')).not.toBeNull();

    await component.reactivate(component.visiblePlans()[0]);
    expect(confirm.ask).toHaveBeenCalled();
    expect(service.reactivatePlan).toHaveBeenCalledOnceWith('b');
  });

  it('D4071: a card names its features and says how many organizations use the plan', () => {
    const fixture = render([plan({ activeOrganizationCount: 3 })]);
    const host = fixture.nativeElement as HTMLElement;

    expect(host.querySelector('.feature-chip')?.getAttribute('title')).toBe('ff.platform.billing');
    expect(host.querySelector('[data-test="plan-usage-p1"]')?.textContent).toContain(
      'SUBSCRIPTIONS.USED_BY_OTHER',
    );
  });
});
