import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { SubscriptionsComponent } from './subscriptions';
import { SubscriptionService } from '../../services/subscription.service';
import { SubscriptionPlan } from '../../services/subscription.model';

describe('SubscriptionsComponent', () => {
  let service: jasmine.SpyObj<SubscriptionService>;

  function setup(): SubscriptionsComponent {
    TestBed.configureTestingModule({
      imports: [SubscriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
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

    expect(cmp.formError()).toBe('SUBSCRIPTIONS.ERROR.REQUIRED_FIELDS');
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
});
