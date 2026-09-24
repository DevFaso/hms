import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { signal } from '@angular/core';
import { of, throwError } from 'rxjs';

import { PrescriptionsComponent } from './prescriptions';
import {
  PrescriptionService,
  CommunityPharmacyService,
  PrescriptionResponse,
  PrescriptionSmsDispatchResult,
} from '../services/prescription.service';
import { StaffService } from '../services/staff.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';

/**
 * Regression guard for the "Send prescription by SMS" dialog.
 *
 * The dispatch modal shipped as a SIBLING of its own `.modal-backdrop`
 * instead of a child. Because the backdrop is `position: fixed; z-index: 1000`
 * with `backdrop-filter: blur(4px)` and `.modal` carries no position or
 * z-index, the backdrop painted on top of the dialog — blurring it along with
 * the page, dropping it out of the backdrop's flex centring, and swallowing
 * every click (the backdrop's own handler closes the modal).
 */
describe('PrescriptionsComponent — SMS dispatch modal', () => {
  let fixture: ComponentFixture<PrescriptionsComponent>;
  let component: PrescriptionsComponent;
  let prescriptionService: jasmine.SpyObj<PrescriptionService>;

  beforeEach(async () => {
    prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
      'dispatchSms',
    ]);
    prescriptionService.list.and.returnValue(of([]));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));

    const communityPharmacyService = jasmine.createSpyObj<CommunityPharmacyService>(
      'CommunityPharmacyService',
      ['list'],
    );
    communityPharmacyService.list.and.returnValue(of([]));

    const scopeUrl = jasmine.createSpyObj<HospitalScopeUrlService>('HospitalScopeUrlService', [
      'applyUrlScopeSync',
    ]);

    await TestBed.configureTestingModule({
      imports: [PrescriptionsComponent, TranslateModule.forRoot()],
      providers: [
        // The scope chip pulls in HospitalService, which needs HttpClient.
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PrescriptionService, useValue: prescriptionService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: CommunityPharmacyService, useValue: communityPharmacyService },
        { provide: HospitalScopeUrlService, useValue: scopeUrl },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            hasAnyActiveRole: () => true,
            // The real service exposes these too, and the clarification
            // control reads them for doctor equivalence. A stub carrying only
            // hasAnyActiveRole threw once the control started expanding roles.
            activeRoles: ['ROLE_DOCTOR'],
            activeRole: 'ROLE_DOCTOR',
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrescriptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  function openDispatch(): void {
    component.showDispatchModal.set(true);
    fixture.detectChanges();
  }

  it('does not render the dispatch modal until it is opened', () => {
    expect(fixture.nativeElement.querySelector('[data-testid="rx-dispatch-modal"]')).toBeNull();
  });

  it('renders the dialog INSIDE its backdrop, not as a sibling', () => {
    openDispatch();

    const backdrop = fixture.nativeElement.querySelector(
      '[data-testid="rx-dispatch-modal"]',
    ) as HTMLElement;
    expect(backdrop).not.toBeNull();

    const dialog = backdrop.querySelector('.modal');
    expect(dialog)
      .withContext(
        '.modal must be a descendant of .modal-backdrop, or the blurred backdrop paints over it',
      )
      .not.toBeNull();
  });

  it('keeps clicks inside the dialog from reaching the backdrop and closing it', () => {
    openDispatch();

    const dialog = fixture.nativeElement.querySelector(
      '[data-testid="rx-dispatch-modal"] .modal',
    ) as HTMLElement;
    dialog.click();
    fixture.detectChanges();

    expect(component.showDispatchModal()).toBeTrue();
  });

  it('closes when the backdrop itself is clicked', () => {
    openDispatch();

    const backdrop = fixture.nativeElement.querySelector(
      '[data-testid="rx-dispatch-modal"]',
    ) as HTMLElement;
    backdrop.click();
    fixture.detectChanges();

    expect(component.showDispatchModal()).toBeFalse();
  });

  it('reloads the list after a successful dispatch so the row leaves the dispatchable states', () => {
    prescriptionService.dispatchSms.and.returnValue(
      of({ pharmacyName: 'Pharmacie Centrale' } as PrescriptionSmsDispatchResult),
    );
    component.dispatchTarget.set({ id: 'rx-1', status: 'SIGNED' } as PrescriptionResponse);
    component.dispatchPharmacyId = 'ph-1';
    const listCallsBefore = prescriptionService.list.calls.count();

    component.submitDispatch();

    expect(prescriptionService.dispatchSms).toHaveBeenCalledWith('rx-1', 'ph-1', undefined);
    expect(prescriptionService.list.calls.count())
      .withContext(
        'without a reload the row keeps its pre-dispatch status and its SMS button, and a second click supersedes the decision just made',
      )
      .toBe(listCallsBefore + 1);
    expect(component.dispatching()).toBeFalse();
  });

  it('does not fire a second dispatch while one is in flight', () => {
    component.dispatchTarget.set({ id: 'rx-1', status: 'SIGNED' } as PrescriptionResponse);
    component.dispatchPharmacyId = 'ph-1';
    component.dispatching.set(true);

    component.submitDispatch();

    expect(prescriptionService.dispatchSms).not.toHaveBeenCalled();
  });
});

/**
 * The signing ceremony (P2 #16).
 *
 * SIGNED used to be an option in the edit form's status `<select>`, so "signed"
 * meant a clinician picked a word from a dropdown — no signer, no timestamp, no
 * digest. The backend now refuses a client-asserted SIGNED, which makes leaving
 * that option in place a control that always fails.
 */
describe('PrescriptionsComponent — signing', () => {
  let fixture: ComponentFixture<PrescriptionsComponent>;
  let component: PrescriptionsComponent;
  let prescriptionService: jasmine.SpyObj<PrescriptionService>;
  let toast: jasmine.SpyObj<ToastService>;

  function rx(id: string, status: string): PrescriptionResponse {
    return { id, status } as PrescriptionResponse;
  }

  beforeEach(async () => {
    prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
      'sign',
      'cosign',
    ]);
    prescriptionService.list.and.returnValue(of([]));
    prescriptionService.sign.and.returnValue(of(rx('rx-1', 'SIGNED')));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));

    const communityPharmacyService = jasmine.createSpyObj<CommunityPharmacyService>(
      'CommunityPharmacyService',
      ['list'],
    );
    communityPharmacyService.list.and.returnValue(of([]));

    const scopeUrl = jasmine.createSpyObj<HospitalScopeUrlService>('HospitalScopeUrlService', [
      'applyUrlScopeSync',
    ]);

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [PrescriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PrescriptionService, useValue: prescriptionService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: CommunityPharmacyService, useValue: communityPharmacyService },
        { provide: HospitalScopeUrlService, useValue: scopeUrl },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            hasAnyActiveRole: () => true,
            // The real service exposes these too, and the clarification
            // control reads them for doctor equivalence. A stub carrying only
            // hasAnyActiveRole threw once the control started expanding roles.
            activeRoles: ['ROLE_DOCTOR'],
            activeRole: 'ROLE_DOCTOR',
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrescriptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('does not offer SIGNED in the editable status list', () => {
    // The whole point of the ceremony: this status is reachable only by signing.
    expect(component.prescriptionStatuses.map((s) => s.value)).not.toContain('SIGNED');
  });

  it('offers signing only for a prescription still awaiting a signature', () => {
    expect(component.canSign(rx('a', 'DRAFT'))).toBeTrue();
    expect(component.canSign(rx('b', 'PENDING_SIGNATURE'))).toBeTrue();
    expect(component.canSign(rx('c', 'SIGNED'))).toBeFalse();
    expect(component.canSign(rx('d', 'DISPENSED'))).toBeFalse();
    expect(component.canSign(rx('e', 'CANCELLED'))).toBeFalse();
  });

  it('renders the sign button only on signable rows', () => {
    component.filtered.set([rx('rx-1', 'DRAFT'), rx('rx-2', 'SIGNED')]);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="rx-sign-rx-1"]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('[data-testid="rx-sign-rx-2"]')).toBeNull();
  });

  it('offers SMS dispatch on exactly the backend DISPATCHABLE_STATUSES', () => {
    component.filtered.set([
      rx('rx-1', 'DRAFT'),
      rx('rx-2', 'SIGNED'),
      rx('rx-3', 'TRANSMITTED'),
      // A silent pharmacy is the ordinary case to escape from: re-sending
      // supersedes the offer it is holding.
      rx('rx-4', 'SENT_TO_PARTNER'),
      rx('rx-5', 'DISPENSED'),
      // A refusal or a back order must leave a way to send it elsewhere.
      rx('rx-6', 'PARTNER_REJECTED'),
      rx('rx-7', 'PENDING_STOCK'),
    ]);
    fixture.detectChanges();

    const dispatch = (id: string) =>
      fixture.nativeElement.querySelector(`[data-testid="rx-dispatch-sms-${id}"]`);
    expect(dispatch('rx-1')).toBeNull();
    expect(dispatch('rx-2')).not.toBeNull();
    expect(dispatch('rx-3')).not.toBeNull();
    expect(dispatch('rx-4')).not.toBeNull();
    expect(dispatch('rx-5')).toBeNull();
    expect(dispatch('rx-6')).not.toBeNull();
    expect(dispatch('rx-7')).not.toBeNull();
  });

  it('does not offer TRANSMITTED in the editable status list', () => {
    // A dispensable state nothing in the backend ever writes — the only
    // writer it ever had was the client-asserted-status hole, now refused.
    expect(component.prescriptionStatuses.map((s) => s.value)).not.toContain('TRANSMITTED');
  });

  it('offers co-signing only while the declared requirement is unmet', () => {
    const base = rx('a', 'DRAFT');
    expect(component.canCosign({ ...base, requiresCosign: true })).toBeTrue();
    expect(
      component.canCosign({ ...base, requiresCosign: true, cosignedAt: '2026-08-21T10:00:00' }),
    ).toBeFalse();
    expect(component.canCosign(base)).toBeFalse();
    expect(component.canCosign({ ...rx('b', 'SIGNED'), requiresCosign: true })).toBeFalse();
  });

  it('co-signs through the ceremony endpoint and reloads', () => {
    prescriptionService.cosign.and.returnValue(of(rx('rx-1', 'DRAFT')));

    component.cosignPrescription({ ...rx('rx-1', 'DRAFT'), requiresCosign: true });

    expect(prescriptionService.cosign).toHaveBeenCalledWith('rx-1');
    expect(toast.success).toHaveBeenCalled();
    expect(component.signingId()).toBeNull();
  });

  it('signs through the ceremony endpoint and reloads', () => {
    component.signPrescription(rx('rx-1', 'DRAFT'));

    expect(prescriptionService.sign).toHaveBeenCalledWith('rx-1');
    expect(toast.success).toHaveBeenCalled();
    // Cleared so the button is usable again rather than stuck spinning.
    expect(component.signingId()).toBeNull();
  });

  it('surfaces the backend refusal verbatim rather than a generic failure', () => {
    // "Only the prescribing clinician can sign this prescription" and
    // "CONTROLLED_SUBSTANCE: ... two-factor verification" tell a prescriber what
    // to do next; "Signing failed" tells them nothing.
    const message = 'Only the prescribing clinician can sign this prescription.';
    prescriptionService.sign.and.returnValue(throwError(() => ({ error: { message } }) as unknown));

    component.signPrescription(rx('rx-1', 'DRAFT'));

    expect(toast.error).toHaveBeenCalledWith(message);
    expect(component.signingId()).toBeNull();
  });
});

/**
 * Pharmacist verification (Tier 2 item 33).
 *
 * The check that stands between a prescriber and a nurse giving a controlled
 * drug. The backend owns the rule; these guard the surface — that the action
 * is offered exactly when the endpoint would accept it, that the refusals a
 * pharmacist needs to read are not collapsed into a generic string, and that
 * the optional note actually reaches the wire rather than being a field
 * nothing sends.
 */
describe('PrescriptionsComponent — pharmacist verification', () => {
  let fixture: ComponentFixture<PrescriptionsComponent>;
  let component: PrescriptionsComponent;
  let prescriptionService: jasmine.SpyObj<PrescriptionService>;
  let toast: jasmine.SpyObj<ToastService>;
  let activeRoles: string[];

  function rx(id: string, status: string, extra: Partial<PrescriptionResponse> = {}) {
    return { id, status, ...extra } as PrescriptionResponse;
  }

  /** In scope for the gate, signed, and nobody has verified it yet. */
  function verifiable(id = 'rx-1'): PrescriptionResponse {
    return rx(id, 'SIGNED', { requiresPharmacistVerification: true });
  }

  beforeEach(async () => {
    activeRoles = ['ROLE_PHARMACIST'];

    prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
      'pharmacistVerify',
    ]);
    prescriptionService.list.and.returnValue(of([]));
    prescriptionService.pharmacistVerify.and.returnValue(of(verifiable()));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));

    const communityPharmacyService = jasmine.createSpyObj<CommunityPharmacyService>(
      'CommunityPharmacyService',
      ['list'],
    );
    communityPharmacyService.list.and.returnValue(of([]));

    const scopeUrl = jasmine.createSpyObj<HospitalScopeUrlService>('HospitalScopeUrlService', [
      'applyUrlScopeSync',
    ]);

    toast = jasmine.createSpyObj<ToastService>('ToastService', ['success', 'error', 'info']);

    await TestBed.configureTestingModule({
      imports: [PrescriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PrescriptionService, useValue: prescriptionService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: CommunityPharmacyService, useValue: communityPharmacyService },
        { provide: HospitalScopeUrlService, useValue: scopeUrl },
        { provide: ToastService, useValue: toast },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            // Mirrors the real service: the caller's active role decides.
            hasAnyActiveRole: (roles: string[]) => roles.some((r) => activeRoles.includes(r)),
            get activeRoles() {
              return activeRoles;
            },
            // As the real service does: a single active role is pinned only
            // when the account holds exactly one.
            get activeRole() {
              return activeRoles.length === 1 ? activeRoles[0] : null;
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrescriptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('offers verification only for an in-scope prescription that reached SIGNED', () => {
    expect(component.canPharmacistVerify(verifiable())).toBeTrue();
    expect(
      component.canPharmacistVerify(
        rx('b', 'TRANSMITTED', { requiresPharmacistVerification: true }),
      ),
    ).toBeTrue();

    // Out of scope entirely — everything else administers as before.
    expect(component.canPharmacistVerify(rx('c', 'SIGNED'))).toBeFalse();

    // A draft is still freely rewritable, and the edit would clear the stamp
    // the moment it happened.
    expect(
      component.canPharmacistVerify(rx('d', 'DRAFT', { requiresPharmacistVerification: true })),
    ).toBeFalse();

    // Already verified — the backend refuses a second verification.
    expect(
      component.canPharmacistVerify(
        rx('e', 'SIGNED', {
          requiresPharmacistVerification: true,
          pharmacistVerifiedAt: '2026-08-26T09:00:00',
        }),
      ),
    ).toBeFalse();
  });

  it('does not offer verification to a role the endpoint would reject', () => {
    activeRoles = ['ROLE_DOCTOR'];
    expect(component.canPharmacistVerify(verifiable())).toBeFalse();

    activeRoles = ['ROLE_NURSE'];
    expect(component.canPharmacistVerify(verifiable())).toBeFalse();

    // The seeded pharmacy-verifier role is on the backend endpoint and must
    // reach the action here too — it is the role that exists to do this job.
    activeRoles = ['ROLE_PHARMACY_VERIFIER'];
    expect(component.canPharmacistVerify(verifiable())).toBeTrue();
  });

  it('renders the verify button only on verifiable rows', () => {
    component.filtered.set([verifiable('rx-1'), rx('rx-2', 'SIGNED')]);
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-pharmacist-verify-rx-1"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-pharmacist-verify-rx-2"]'),
    ).toBeNull();
  });

  it('sends the optional note to the ceremony endpoint and reloads', () => {
    component.openVerifyModal(verifiable());
    component.verifyNote = 'Dose confirmed against the ward protocol.';

    component.submitPharmacistVerify();

    expect(prescriptionService.pharmacistVerify).toHaveBeenCalledWith(
      'rx-1',
      'Dose confirmed against the ward protocol.',
    );
    expect(toast.success).toHaveBeenCalled();
    expect(component.showVerifyModal()).toBeFalse();
    expect(component.verifying()).toBeFalse();
  });

  it('surfaces the backend refusal verbatim rather than a generic failure', () => {
    // "A prescription cannot be verified by the clinician who prescribed it"
    // tells a pharmacist to fetch a colleague; "Verification failed" does not.
    const message = 'A prescription cannot be verified by the clinician who prescribed it.';
    prescriptionService.pharmacistVerify.and.returnValue(
      throwError(() => ({ error: { message } }) as unknown),
    );

    component.openVerifyModal(verifiable());
    component.submitPharmacistVerify();

    expect(toast.error).toHaveBeenCalledWith(message);
    // The modal stays open on failure — closing it would discard a note the
    // pharmacist may want to keep while they resolve the refusal.
    expect(component.showVerifyModal()).toBeTrue();
    expect(component.verifying()).toBeFalse();
  });

  it('renders the verification state in the detail panel, pending included', () => {
    component.selectedPrescription.set(verifiable());
    fixture.detectChanges();

    const field = fixture.nativeElement.querySelector(
      '[data-testid="rx-pharmacist-verification"]',
    ) as HTMLElement;
    expect(field)
      .withContext('a nurse needs to see PENDING — it is the state that refuses the dose')
      .not.toBeNull();
  });
});

/**
 * Gap G5, prescriber half. The pharmacist's question arrives as a
 * PENDING_CLARIFICATION row on this list, and until now the only way out of
 * that status was a client-asserted PUT no pharmacist could call. The control
 * is `<app-prescription-clarification>` in PRESCRIBER mode; its own spec
 * covers the dialog, so what is guarded here is that the list row wires it
 * and that only a doctor is offered it.
 */
describe('PrescriptionsComponent — answering a pharmacist clarification', () => {
  let fixture: ComponentFixture<PrescriptionsComponent>;
  let component: PrescriptionsComponent;
  let prescriptionService: jasmine.SpyObj<PrescriptionService>;
  let activeRoles: string[];

  function pendingClarification(): PrescriptionResponse {
    return {
      id: 'rx-1',
      status: 'PENDING_CLARIFICATION',
      medicationName: 'Metformin',
      clarificationReason: 'Is the 1 g strength intended for this weight?',
      clarificationRequestedAt: '2026-09-20T09:00:00',
    } as PrescriptionResponse;
  }

  async function render(roles: string[]): Promise<void> {
    activeRoles = roles;
    prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
      'resolveClarification',
      'getById',
    ]);
    prescriptionService.list.and.returnValue(of([pendingClarification()]));
    prescriptionService.resolveClarification.and.returnValue(of(pendingClarification()));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));
    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));
    const communityPharmacyService = jasmine.createSpyObj<CommunityPharmacyService>(
      'CommunityPharmacyService',
      ['list'],
    );
    communityPharmacyService.list.and.returnValue(of([]));
    const scopeUrl = jasmine.createSpyObj<HospitalScopeUrlService>('HospitalScopeUrlService', [
      'applyUrlScopeSync',
    ]);

    await TestBed.configureTestingModule({
      imports: [PrescriptionsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PrescriptionService, useValue: prescriptionService },
        { provide: StaffService, useValue: staffService },
        { provide: PatientService, useValue: patientService },
        { provide: CommunityPharmacyService, useValue: communityPharmacyService },
        { provide: HospitalScopeUrlService, useValue: scopeUrl },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => activeRoles.includes(r)),
            get activeRoles() {
              return activeRoles;
            },
            get activeRole() {
              return activeRoles.length === 1 ? activeRoles[0] : null;
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrescriptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  afterEach(() => TestBed.resetTestingModule());

  it('offers the answer control to a doctor on a PENDING_CLARIFICATION row', async () => {
    await render(['ROLE_DOCTOR']);

    expect(component.filtered().length).toBe(1);
    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-clarification-open-rx-1"]'),
    ).not.toBeNull();
  });

  it('withholds it from a nurse — /resolve-clarification is ROLE_DOCTOR only', async () => {
    await render(['ROLE_NURSE']);

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-clarification-open-rx-1"]'),
    ).toBeNull();
  });

  it('shows the pharmacist question and sends the answer', async () => {
    await render(['ROLE_DOCTOR']);

    (
      fixture.nativeElement.querySelector(
        '[data-testid="rx-clarification-open-rx-1"]',
      ) as HTMLElement
    ).click();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="rx-clarification-question"]').textContent,
    ).toContain('Is the 1 g strength intended for this weight?');

    const textarea = fixture.nativeElement.querySelector(
      '[data-testid="rx-clarification-text-rx-1"]',
    ) as HTMLTextAreaElement;
    textarea.value = 'Yes — 1 g is intended, the weight on file is stale.';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    (
      fixture.nativeElement.querySelector(
        '[data-testid="rx-clarification-submit-rx-1"]',
      ) as HTMLElement
    ).click();
    fixture.detectChanges();

    expect(prescriptionService.resolveClarification).toHaveBeenCalledWith(
      'rx-1',
      'Yes — 1 g is intended, the weight on file is stale.',
    );
    // The list reloads so the row leaves PENDING_CLARIFICATION on screen too.
    expect(prescriptionService.list.calls.count()).toBeGreaterThan(1);
  });
});
