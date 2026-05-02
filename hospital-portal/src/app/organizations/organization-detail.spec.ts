import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { OrganizationDetailComponent } from './organization-detail';
import {
  OrganizationResponse,
  OrganizationService,
  TenantLifecycleResponse,
} from '../services/organization.service';
import { ToastService } from '../core/toast.service';

const fakeOrg = (): OrganizationResponse => ({
  id: 'o1',
  name: 'Acme Health',
  code: 'ACME',
  description: '',
  type: 'HEALTHCARE_NETWORK',
  active: true,
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
  primaryContactEmail: 'ops@acme.example',
  primaryContactPhone: '',
  defaultTimezone: 'UTC',
  onboardingNotes: '',
  hospitals: [],
});

const fakeLifecycle = (
  overrides: Partial<TenantLifecycleResponse> = {},
): TenantLifecycleResponse => ({
  organizationId: 'o1',
  organizationName: 'Acme Health',
  organizationCode: 'ACME',
  lifecycleState: 'ACTIVE',
  canSuspend: true,
  canRestore: false,
  canArchive: true,
  canSchedulePurge: false,
  canCancelPurge: false,
  ...overrides,
});

describe('OrganizationDetailComponent', () => {
  let orgService: jasmine.SpyObj<OrganizationService>;
  let toast: jasmine.SpyObj<ToastService>;

  function setup(): OrganizationDetailComponent {
    TestBed.configureTestingModule({
      imports: [OrganizationDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: OrganizationService, useValue: orgService },
        { provide: ToastService, useValue: toast },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: (_: string) => 'o1' } } },
        },
      ],
    });
    const fixture = TestBed.createComponent(OrganizationDetailComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  beforeEach(() => {
    orgService = jasmine.createSpyObj<OrganizationService>('OrganizationService', [
      'getById',
      'getLifecycle',
      'suspend',
      'restoreLifecycle',
      'archive',
      'schedulePurge',
      'cancelPurge',
    ]);
    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error']);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('loads org + lifecycle on init', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(of(fakeLifecycle()));

    const c = setup();

    expect(c.organization()?.code).toBe('ACME');
    expect(c.lifecycle()?.lifecycleState).toBe('ACTIVE');
    expect(c.loading()).toBeFalse();
  });

  it('blocks confirm when reason is required but blank', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(of(fakeLifecycle()));

    const c = setup();
    c.openAction('suspend');

    expect(c.canConfirm()).toBeFalse();
  });

  it('blocks confirm when typed-confirm code does not match', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(of(fakeLifecycle()));

    const c = setup();
    c.openAction('suspend');
    c.modalReason.set('non-payment');
    c.modalConfirmText.set('WRONG');

    expect(c.canConfirm()).toBeFalse();

    c.modalConfirmText.set('ACME');
    expect(c.canConfirm()).toBeTrue();
  });

  it('dispatches suspend with reason and updates lifecycle on success', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(of(fakeLifecycle()));
    orgService.suspend.and.returnValue(
      of(
        fakeLifecycle({
          lifecycleState: 'SUSPENDED',
          canSuspend: false,
          canRestore: true,
          suspensionReason: 'non-payment',
        }),
      ),
    );

    const c = setup();
    c.openAction('suspend');
    c.modalReason.set('non-payment');
    c.modalConfirmText.set('ACME');
    c.confirm();

    expect(orgService.suspend).toHaveBeenCalledWith(
      'o1',
      jasmine.objectContaining({ reason: 'non-payment' }),
    );
    expect(c.lifecycle()?.lifecycleState).toBe('SUSPENDED');
    expect(c.activeAction()).toBeNull();
    expect(toast.success).toHaveBeenCalled();
  });

  it('does not change state and surfaces a toast when the action fails', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(of(fakeLifecycle()));
    orgService.suspend.and.returnValue(throwError(() => ({ error: { message: 'denied' } })));

    const c = setup();
    c.openAction('suspend');
    c.modalReason.set('non-payment');
    c.modalConfirmText.set('ACME');
    c.confirm();

    expect(c.lifecycle()?.lifecycleState).toBe('ACTIVE');
    expect(c.submitting()).toBeFalse();
    expect(toast.error).toHaveBeenCalledWith('denied');
  });

  it('allows restore without reason and posts an empty body', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(
      of(fakeLifecycle({ lifecycleState: 'SUSPENDED', canSuspend: false, canRestore: true })),
    );
    orgService.restoreLifecycle.and.returnValue(of(fakeLifecycle()));

    const c = setup();
    c.openAction('restore');

    expect(c.canConfirm()).toBeTrue();
    c.confirm();

    expect(orgService.restoreLifecycle).toHaveBeenCalled();
    expect(c.lifecycle()?.lifecycleState).toBe('ACTIVE');
  });

  it('schedule-purge sends an explicit purgeScheduledFor when chosen', () => {
    orgService.getById.and.returnValue(of(fakeOrg()));
    orgService.getLifecycle.and.returnValue(
      of(
        fakeLifecycle({
          lifecycleState: 'ARCHIVED',
          canSuspend: false,
          canArchive: false,
          canSchedulePurge: true,
        }),
      ),
    );
    orgService.schedulePurge.and.returnValue(
      of(
        fakeLifecycle({
          lifecycleState: 'PENDING_PURGE',
          canCancelPurge: true,
          canSuspend: false,
          canArchive: false,
        }),
      ),
    );

    const c = setup();
    c.openAction('schedule-purge');
    c.modalReason.set('retention');
    c.modalConfirmText.set('ACME');
    c.modalPurgeAt.set('2026-07-01T00:00');
    c.confirm();

    expect(orgService.schedulePurge).toHaveBeenCalledWith(
      'o1',
      jasmine.objectContaining({
        reason: 'retention',
        purgeScheduledFor: jasmine.stringMatching(/2026-07-01/),
      }),
    );
    expect(c.lifecycle()?.lifecycleState).toBe('PENDING_PURGE');
  });
});
