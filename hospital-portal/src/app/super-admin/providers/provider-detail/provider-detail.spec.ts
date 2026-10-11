import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { ToastService } from '../../../core/toast.service';
import { HospitalLifecycleResponse } from '../../../services/hospital-lifecycle.model';
import { HospitalLifecycleService } from '../../../services/hospital-lifecycle.service';
import {
  ProviderResponse,
  ProviderVerificationHistoryEntry,
} from '../../../services/provider.model';
import { SuperAdminProviderService } from '../../../services/super-admin-provider.service';
import { UserDetail } from '../../../services/user.service';
import { providerFixture } from '../../../testing/provider-fixtures';
import { ProviderDetailComponent } from './provider-detail';

describe('ProviderDetailComponent', () => {
  let service: jasmine.SpyObj<SuperAdminProviderService>;
  let lifecycle: jasmine.SpyObj<HospitalLifecycleService>;
  let toast: jasmine.SpyObj<ToastService>;
  let fixture: ComponentFixture<ProviderDetailComponent>;

  function setup(provider: ProviderResponse = providerFixture()): ProviderDetailComponent {
    service.get.and.returnValue(of(provider));
    TestBed.configureTestingModule({
      imports: [ProviderDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: SuperAdminProviderService, useValue: service },
        { provide: HospitalLifecycleService, useValue: lifecycle },
        { provide: ToastService, useValue: toast },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: provider.id }) } },
        },
      ],
    });
    fixture = TestBed.createComponent(ProviderDetailComponent);
    fixture.detectChanges();
    return fixture.componentInstance;
  }

  function host(): HTMLElement {
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    service = jasmine.createSpyObj<SuperAdminProviderService>('SuperAdminProviderService', [
      'get',
      'history',
      'verify',
      'reject',
      'revoke',
    ]);
    service.history.and.returnValue(
      of([
        {
          verificationId: 'v-2',
          status: 'SUBMITTED',
          submittedAt: '2026-10-09T10:00:00',
          decidedAt: null,
          decidedByUserId: null,
          decisionReason: null,
          evidenceNote: 'Checked at the counter',
          legalName: 'Pharmacie SARL',
          licenceNumber: 'LIC-77',
          licenceAuthority: 'DGPML',
        },
        {
          verificationId: 'v-1',
          status: 'REJECTED',
          submittedAt: '2026-10-08T10:00:00',
          decidedAt: '2026-10-08T12:00:00',
          decidedByUserId: 'sa-1',
          decisionReason: 'Unreadable extract',
          evidenceNote: null,
          legalName: 'Pharmacie SARL',
          licenceNumber: 'LIC-77',
          licenceAuthority: 'DGPML',
        },
      ]),
    );
    const done = providerFixture({ verificationStatus: 'VERIFIED', lifecycleState: 'ACTIVE' });
    service.verify.and.returnValue(of(done));
    service.reject.and.returnValue(of(done));
    service.revoke.and.returnValue(of(done));
    lifecycle = jasmine.createSpyObj<HospitalLifecycleService>('HospitalLifecycleService', [
      'suspend',
      'restore',
      'archive',
    ]);
    const lifecycleDone = { state: 'SUSPENDED' } as HospitalLifecycleResponse;
    lifecycle.suspend.and.returnValue(of(lifecycleDone));
    lifecycle.restore.and.returnValue(of(lifecycleDone));
    lifecycle.archive.and.returnValue(of(lifecycleDone));
    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'warning',
      'error',
      'info',
    ]);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('shows the evidence and the verification history, newest first', () => {
    const cmp = setup();
    expect(service.history).toHaveBeenCalledWith('p-1');
    expect(cmp.history().length).toBe(2);
    expect(host().querySelectorAll('[data-test="history-row"]').length).toBe(2);
    expect(host().textContent).toContain('BF-OUA-1');
  });

  it('offers verify and reject on submitted evidence, nothing that needs a verified provider', () => {
    setup();
    const page = host();
    expect(page.querySelector('[data-test="action-verify"]')).not.toBeNull();
    expect(page.querySelector('[data-test="action-reject"]')).not.toBeNull();
    expect(page.querySelector('[data-test="action-revoke"]')).toBeNull();
    expect(page.querySelector('[data-test="action-resubmit"]')).toBeNull();
    expect(page.querySelector('[data-test="action-suspend"]')).toBeNull();
    // SUSPENDED and not verified: restore would be refused (409 provider.not-verified).
    expect(page.querySelector('[data-test="action-restore"]')).toBeNull();
    expect(page.querySelector('[data-test="action-archive"]')).not.toBeNull();
  });

  it('offers revoke and suspend on a verified active provider, resubmit after a rejection', () => {
    const cmp = setup(
      providerFixture({ verificationStatus: 'VERIFIED', lifecycleState: 'ACTIVE' }),
    );
    expect(cmp.canRevoke()).toBeTrue();
    expect(cmp.canSuspend()).toBeTrue();
    expect(cmp.canVerify()).toBeFalse();
    expect(cmp.canRestore()).toBeFalse();
    TestBed.resetTestingModule();

    const rejected = setup(providerFixture({ verificationStatus: 'REJECTED' }));
    expect(rejected.canResubmit()).toBeTrue();
    expect(rejected.canReject()).toBeFalse();
    TestBed.resetTestingModule();

    const verifiedSuspended = setup(
      providerFixture({ verificationStatus: 'VERIFIED', lifecycleState: 'SUSPENDED' }),
    );
    expect(verifiedSuspended.canRestore()).toBeTrue();
    TestBed.resetTestingModule();

    const archived = setup(providerFixture({ lifecycleState: 'ARCHIVED' }));
    expect(archived.canRestore()).toBeTrue();
    expect(archived.canArchive()).toBeFalse();
  });

  it('verify stays disabled until both consistency boxes are ticked', () => {
    const cmp = setup();
    cmp.openVerify();
    const confirm = () =>
      host().querySelector<HTMLButtonElement>('[data-test="verify-confirm"]')!.disabled;
    expect(confirm()).toBeTrue();
    cmp.patchVerify({ ifuMatchesRccm: true });
    expect(confirm()).toBeTrue();
    cmp.submitVerify();
    expect(service.verify).not.toHaveBeenCalled();
    cmp.patchVerify({ cnssMatchesRccm: true });
    expect(confirm()).toBeFalse();
  });

  it('verifies with both confirmations, the note, and no corrections unless asked', () => {
    const cmp = setup();
    cmp.openVerify();
    cmp.patchVerify({ ifuMatchesRccm: true, cnssMatchesRccm: true, evidenceNote: ' checked ' });
    cmp.submitVerify();

    expect(service.verify).toHaveBeenCalledWith('p-1', {
      ifuMatchesRccm: true,
      cnssMatchesRccm: true,
      evidenceNote: 'checked',
      corrections: null,
    });
    expect(toast.success).toHaveBeenCalled();
    expect(cmp.verifyDialog()).toBeNull();
    expect(service.get).toHaveBeenCalledTimes(2);
  });

  it('sends corrections when the documents differ, and only complete ones', () => {
    const cmp = setup();
    cmp.openVerify();
    const corrections = structuredClone(cmp.verifyDialog()!.corrections);
    corrections.business.ifuNumber = 'IFU-CORRECTED';
    cmp.patchVerify({ ifuMatchesRccm: true, cnssMatchesRccm: true, correcting: true, corrections });
    host();
    expect(host().querySelector('#verify-corrections-b-ifuNumber')).not.toBeNull();

    cmp.submitVerify();
    const body = service.verify.calls.mostRecent().args[1];
    expect(body.corrections?.business.ifuNumber).toBe('IFU-CORRECTED');

    cmp.openVerify();
    const incomplete = structuredClone(cmp.verifyDialog()!.corrections);
    incomplete.business.rccmNumber = ' ';
    cmp.patchVerify({
      ifuMatchesRccm: true,
      cnssMatchesRccm: true,
      correcting: true,
      corrections: incomplete,
    });
    expect(cmp.verifyReady()).toBeFalse();
  });

  it('keeps the dialog open with the server message when verify is refused', () => {
    service.verify.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: { message: 'Another verified facility already holds this RCCM or IFU number.' },
          }),
      ),
    );
    const cmp = setup();
    cmp.openVerify();
    cmp.patchVerify({ ifuMatchesRccm: true, cnssMatchesRccm: true });
    cmp.submitVerify();

    expect(cmp.verifyDialog()).not.toBeNull();
    expect(cmp.dialogError()).toContain('RCCM');
    expect(host().querySelector('[data-test="dialog-error"]')).not.toBeNull();
  });

  it('rejects and revokes with a reason, never without one', () => {
    const cmp = setup();
    cmp.openDecision('reject');
    cmp.submitDecision();
    expect(service.reject).not.toHaveBeenCalled();
    cmp.setDecisionReason(' Unreadable extract ');
    cmp.submitDecision();
    expect(service.reject).toHaveBeenCalledWith('p-1', { reason: 'Unreadable extract' });

    cmp.openDecision('revoke');
    cmp.setDecisionReason('Licence withdrawn');
    cmp.submitDecision();
    expect(service.revoke).toHaveBeenCalledWith('p-1', { reason: 'Licence withdrawn' });
  });

  it('runs the hospital lifecycle actions, with a reason where the backend needs one', () => {
    const cmp = setup(
      providerFixture({ verificationStatus: 'VERIFIED', lifecycleState: 'ACTIVE' }),
    );
    cmp.openLifecycle('suspend');
    cmp.setLifecycleReason('abc');
    expect(cmp.lifecycleReady()).toBeFalse();
    cmp.submitLifecycle();
    expect(lifecycle.suspend).not.toHaveBeenCalled();
    cmp.setLifecycleReason('Licence under review');
    expect(cmp.lifecycleNeedsStepUp('suspend')).toBeTrue();
    cmp.setLifecycleMfaCode('123456');
    cmp.submitLifecycle();
    // The current code goes along as the step-up (X-Mfa-Token).
    expect(lifecycle.suspend).toHaveBeenCalledWith(
      'p-1',
      { reason: 'Licence under review' },
      '123456',
    );

    cmp.openLifecycle('archive');
    cmp.setLifecycleReason('Closed for good');
    cmp.submitLifecycle();
    expect(lifecycle.archive).toHaveBeenCalledWith('p-1', { reason: 'Closed for good' }, '');

    cmp.openLifecycle('restore');
    expect(cmp.lifecycleNeedsReason('restore')).toBeFalse();
    cmp.submitLifecycle();
    expect(lifecycle.restore).toHaveBeenCalledWith('p-1');
  });

  it('a refused step-up keeps the lifecycle dialog open with the reason (401 or 403)', () => {
    const cmp = setup(
      providerFixture({ verificationStatus: 'VERIFIED', lifecycleState: 'ACTIVE' }),
    );
    lifecycle.suspend.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 403,
            error: { reason: 'mfa.stepup.invalid-code', message: 'Wrong code' },
          }),
      ),
    );
    cmp.openLifecycle('suspend');
    cmp.setLifecycleReason('Licence under review');
    cmp.submitLifecycle();
    expect(cmp.lifecycleDialog()).not.toBeNull();
    // Translated, never the server's English code text.
    expect(cmp.dialogError()).toBe('HOSPITAL_LIFECYCLE.ERROR.STEP_UP_FAILED');
    expect(host().querySelector('#lifecycle-mfa-code')).not.toBeNull();

    // A 403 that is not a step-up refusal (no mfa.stepup.* reason): an ordinary failure.
    lifecycle.suspend.and.returnValue(throwError(() => new HttpErrorResponse({ status: 403 })));
    cmp.submitLifecycle();
    expect(cmp.dialogError()).toBe('PROVIDER.DETAIL.ACTION_FAILED');
    lifecycle.suspend.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({ status: 401, error: { message: 'mfa_required: missing code' } }),
      ),
    );
    cmp.submitLifecycle();
    expect(cmp.dialogError()).toBe('HOSPITAL_LIFECYCLE.ERROR.STEP_UP_FAILED');
    expect(cmp.lifecycleNeedsStepUp('restore')).toBeFalse();
  });

  it('a 403 on verify (no step-up sent) is an ordinary refusal with the server reason', () => {
    service.verify.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 403, error: { message: 'Access denied' } })),
    );
    const cmp = setup();
    cmp.openVerify();
    cmp.patchVerify({ ifuMatchesRccm: true, cnssMatchesRccm: true });
    cmp.submitVerify();
    expect(cmp.dialogError()).toBe('Access denied');
  });

  it('the history shows the decision reason and the evidence note in their own columns', () => {
    setup();
    const page = host();
    const reasons = [...page.querySelectorAll('[data-test="history-reason"]')].map((c) =>
      c.textContent?.trim(),
    );
    expect(reasons).toEqual(['—', 'Unreadable extract']);
    const notes = [...page.querySelectorAll('[data-test="history-note"]')].map((c) =>
      c.textContent?.trim(),
    );
    expect(notes).toEqual(['Checked at the counter', '—']);
  });

  it('offers the facility admin and its staff roles to register, and reports where the code went', () => {
    const cmp = setup(providerFixture({ facilityType: 'LABORATORY' }));
    expect(cmp.registerRoles()).toEqual([
      'PROVIDER_ADMIN',
      'LAB_TECHNICIAN',
      'LAB_SCIENTIST',
      'LAB_MANAGER',
      'LAB_DIRECTOR',
    ]);
    cmp.openRegister();
    expect(host().querySelector('app-provider-staff-register')).not.toBeNull();
    cmp.onRegistered({
      activationDelivery: [{ channel: 'EMAIL', outcome: 'NOT_CONFIGURED' }],
    } as UserDetail);
    expect(cmp.registerOpen()).toBeFalse();
    expect(toast.success).toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalled();
    cmp.openRegister();
    cmp.closeRegister();
    expect(cmp.registerOpen()).toBeFalse();
  });

  it('an unknown id shows the calm not-found state; a failed history does not hide the page', () => {
    service.history.and.returnValue(throwError(() => new HttpErrorResponse({ status: 500 })));
    const cmp = setup();
    expect(cmp.historyFailed()).toBeTrue();
    expect(host().querySelector('[data-test="history-failed"]')).not.toBeNull();
    TestBed.resetTestingModule();

    service.get.and.returnValue(throwError(() => new HttpErrorResponse({ status: 404 })));
    TestBed.configureTestingModule({
      imports: [ProviderDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        { provide: SuperAdminProviderService, useValue: service },
        { provide: HospitalLifecycleService, useValue: lifecycle },
        { provide: ToastService, useValue: toast },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: convertToParamMap({ id: 'x' }) } },
        },
      ],
    });
    fixture = TestBed.createComponent(ProviderDetailComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.notFound()).toBeTrue();
    expect(host().querySelector('[data-test="not-found"]')).not.toBeNull();
  });

  it('a newer refresh cancels the older one: a late answer never overwrites a newer one', () => {
    const slow = new Subject<ProviderResponse>();
    const slowHistory = new Subject<ProviderVerificationHistoryEntry[]>();
    const cmp = setup();
    service.get.and.returnValue(slow);
    cmp.refresh();
    expect(slow.observed).toBeTrue();
    service.get.and.returnValue(of(providerFixture({ verificationStatus: 'VERIFIED' })));
    service.history.and.returnValue(slowHistory);
    cmp.refresh();

    expect(slow.observed).toBeFalse();
    slow.next(providerFixture({ verificationStatus: 'REJECTED' }));
    expect(cmp.status()).toBe('VERIFIED');
    // A newer history read cancels the pending one too.
    service.history.and.returnValue(of([]));
    cmp.refresh();
    expect(slowHistory.observed).toBeFalse();
  });

  it('closeDialogs waits while an action runs', () => {
    const cmp = setup();
    cmp.openDecision('reject');
    cmp.busy.set(true);
    cmp.closeDialogs();
    expect(cmp.decisionDialog()).not.toBeNull();
    cmp.busy.set(false);
    cmp.closeDialogs();
    expect(cmp.decisionDialog()).toBeNull();
  });
});
