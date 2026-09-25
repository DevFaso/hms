import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { signal } from '@angular/core';
import { Observable, of, Subject, throwError } from 'rxjs';

import {
  ATTENTION_REASONS,
  PRESCRIPTION_TABS,
  PrescriptionsComponent,
  TAB_BY_STATUS,
} from './prescriptions';
import {
  PrescriptionService,
  CommunityPharmacyService,
  PrescriptionResponse,
  PrescriptionSmsDispatchResult,
  PRESCRIPTION_STATUSES,
} from '../services/prescription.service';
import {
  ApiResponse,
  DispenseResponse,
  Page,
  PharmacyService,
  RoutingDecisionResponse,
} from '../services/pharmacy.service';
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
      // Two calls per reload since G12: the unfiltered page and the
      // status-filtered "Needs attention" bucket.
      .toBe(listCallsBefore + 2);
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
 * Gaps G7, G10 and G11 — what the prescriber can see once the pharmacy has
 * the order.
 *
 * All three were the same defect wearing three hats: wave 1 gave the backend a
 * hospital→pharmacy round trip, and the prescriber's screen kept rendering the
 * four statuses it knew in August. A refused prescription vanished from every
 * tab, the response's pharmacy columns were never read, and two endpoints that
 * admit DOCTOR had no client at all.
 */
describe('PrescriptionsComponent — prescriber pharmacy visibility (G7/G10/G11)', () => {
  let fixture: ComponentFixture<PrescriptionsComponent>;
  let component: PrescriptionsComponent;
  let pharmacyService: jasmine.SpyObj<PharmacyService>;
  let prescriptionServiceSpy: jasmine.SpyObj<PrescriptionService>;
  let staffServiceSpy: jasmine.SpyObj<StaffService>;

  function makeRx(over: Partial<PrescriptionResponse> = {}): PrescriptionResponse {
    return {
      id: 'rx-1',
      patientId: 'p-1',
      patientFullName: 'Ada Lovelace',
      patientEmail: 'ada@example.test',
      staffId: 's-1',
      staffFullName: 'Dr Grace Hopper',
      encounterId: 'e-1',
      hospitalId: 'h-1',
      medicationName: 'Amoxicillin',
      medicationDisplayName: 'Amoxicillin 500 mg',
      dosage: '500 mg',
      frequency: 'BD',
      duration: '7 days',
      notes: '',
      status: 'SIGNED',
      createdAt: '2026-09-01T10:00:00',
      updatedAt: '2026-09-01T10:00:00',
      ...over,
    };
  }

  function makeDispense(over: Partial<DispenseResponse> = {}): DispenseResponse {
    return {
      id: 'd-1',
      prescriptionId: 'rx-1',
      patientId: 'p-1',
      pharmacyId: 'ph-1',
      dispensedById: 'u-1',
      medicationName: 'Amoxicillin',
      quantityRequested: 30,
      quantityDispensed: 30,
      unit: 'tablets',
      substitution: false,
      status: 'COMPLETED',
      dispensedAt: '2026-09-02T09:00:00',
      createdAt: '2026-09-02T09:00:00',
      updatedAt: '2026-09-02T09:00:00',
      ...over,
    };
  }

  function makeRouting(over: Partial<RoutingDecisionResponse> = {}): RoutingDecisionResponse {
    return {
      id: 'rd-1',
      prescriptionId: 'rx-1',
      routingType: 'PARTNER',
      decidedByUserId: 'u-1',
      patientId: 'p-1',
      status: 'PENDING',
      decidedAt: '2026-09-02T08:00:00',
      createdAt: '2026-09-02T08:00:00',
      updatedAt: '2026-09-02T08:00:00',
      ...over,
    };
  }

  function page<T>(content: T[], totalElements = content.length): ApiResponse<Page<T>> {
    return {
      data: { content, totalElements, totalPages: 1, size: 20, number: 0 },
    };
  }

  interface SetupOptions {
    list?: PrescriptionResponse[];
    /**
     * G12 — what the status-filtered "Needs attention" request returns.
     * Defaults to an empty page; pass a throwing observable to exercise the
     * fallback.
     */
    attention?: Observable<PrescriptionResponse[]>;
    roles?: string[];
    superAdmin?: boolean;
    globalView?: boolean;
    dispenses?: Observable<ApiResponse<Page<DispenseResponse>>>;
    routings?: Observable<ApiResponse<Page<RoutingDecisionResponse>>>;
  }

  async function setup(opts: SetupOptions = {}): Promise<void> {
    const roles = opts.roles ?? ['ROLE_DOCTOR'];

    const prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
    ]);
    // G12: the page and the status-filtered attention bucket are two calls
    // against the same method, told apart by the `statuses` filter.
    prescriptionService.list.and.callFake((filters?: { statuses?: string[] }) =>
      filters?.statuses ? (opts.attention ?? of([])) : of(opts.list ?? []),
    );

    prescriptionServiceSpy = prescriptionService;

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));
    staffServiceSpy = staffService;

    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));

    const communityPharmacyService = jasmine.createSpyObj<CommunityPharmacyService>(
      'CommunityPharmacyService',
      ['list'],
    );
    communityPharmacyService.list.and.returnValue(of([]));

    pharmacyService = jasmine.createSpyObj<PharmacyService>('PharmacyService', [
      'listDispensesByPrescription',
      'listRoutingDecisionsByPrescription',
    ]);
    pharmacyService.listDispensesByPrescription.and.returnValue(opts.dispenses ?? of(page([])));
    pharmacyService.listRoutingDecisionsByPrescription.and.returnValue(
      opts.routings ?? of(page([])),
    );

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
        { provide: PharmacyService, useValue: pharmacyService },
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
            isSuperAdmin: signal(opts.superAdmin ?? false),
            globalView: signal(opts.globalView ?? false),
            activeHospitalId: opts.globalView ? null : 'h-1',
            // Mirrors RoleContextService.hasAnyActiveRole: when an active
            // role is set, only THAT role counts. A stub answering off the
            // whole held set makes every active-role gate on this page
            // permissive, and untestable.
            hasAnyActiveRole: (wanted: string[]) => {
              const active = roles.length === 1 ? roles[0] : null;
              return active ? wanted.includes(active) : wanted.some((r) => roles.includes(r));
            },
            get activeRoles() {
              return roles;
            },
            get activeRole() {
              return roles.length === 1 ? roles[0] : null;
            },
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    // The interpolating keys in this block, loaded for real so the assertions
    // below test the PARAMETER NAMES too: ngx-translate renders a missing key
    // as the key itself, which would pass either way.
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(
      'en',
      {
        PRESCRIPTIONS: {
          PHARMACY: { AT: 'at {{pharmacy}}' },
          QUANTITY_VALUE: '{{value}} {{unit}}',
          REFILLS_VALUE: '{{remaining}} of {{allowed}} remaining',
        },
      },
      true,
    );
    translate.use('en');

    fixture = TestBed.createComponent(PrescriptionsComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  function el(selector: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(selector) as HTMLElement | null;
  }

  /* ── G10: no status falls outside every tab ────────────────────── */

  it('files EVERY PrescriptionStatus under exactly one tab', async () => {
    // The whole enum, not a sample: the defect was that thirteen of seventeen
    // statuses matched no filter, and any sample small enough to hand-pick
    // would have missed most of them.
    await setup({
      list: PRESCRIPTION_STATUSES.map((status, i) => makeRx({ id: 'rx-' + i, status })),
    });

    const buckets = PRESCRIPTION_TABS.map((t) => t.id).filter((id) => id !== 'all');

    for (const status of PRESCRIPTION_STATUSES) {
      const hits = buckets.filter((bucket) => {
        component.setTab(bucket);
        return component.filtered().some((p) => p.status === status);
      });
      expect(hits.length)
        .withContext(`${status} is reachable from ${hits.length} tab(s): [${hits.join(', ')}]`)
        .toBe(1);
    }
  });

  it('keeps a status this build has never heard of visible instead of dropping it', async () => {
    await setup({ list: [makeRx({ status: 'SOME_STATUS_SHIPPED_AFTER_THIS_BUILD' })] });

    component.setTab('attention');
    expect(component.filtered().length).toBe(1);
    expect(component.attentionReasonKey(component.filtered()[0])).toBe(
      'PRESCRIPTIONS.ATTENTION.UNRECOGNISED_STATUS',
    );
  });

  it('gives a reason to exactly the statuses the attention tab holds', async () => {
    await setup();

    const attentionStatuses = PRESCRIPTION_STATUSES.filter(
      (s) => TAB_BY_STATUS[s] === 'attention',
    ).sort();
    expect(ATTENTION_REASONS.map((r) => r.status).sort())
      .withContext('the reason map and the attention bucket must not drift apart')
      .toEqual(attentionStatuses);

    for (const status of PRESCRIPTION_STATUSES) {
      const key = component.attentionReasonKey(makeRx({ status }));
      if (TAB_BY_STATUS[status] === 'attention') {
        expect(key)
          .withContext(`${status} needs a reason`)
          .toMatch(/^PRESCRIPTIONS\.ATTENTION\./);
      } else {
        expect(key).withContext(`${status} must not claim to need attention`).toBeNull();
      }
    }
  });

  it('renders the Needs attention tab for a prescriber and collects the states waiting on them', async () => {
    await setup({
      list: [
        makeRx({ id: 'a', status: 'PARTNER_REJECTED' }),
        makeRx({ id: 'b', status: 'PENDING_CLARIFICATION' }),
        makeRx({ id: 'c', status: 'DISPENSED' }),
      ],
    });

    const tab = el('[data-testid="rx-tab-attention"]');
    expect(tab).withContext('the prescriber has no way to reach the tab').not.toBeNull();
    expect(tab!.textContent).toContain('2');

    component.setTab('attention');
    fixture.detectChanges();
    expect(component.filtered().map((p) => p.id)).toEqual(['a', 'b']);
    expect(el('[data-testid="rx-attention-count"]')!.textContent!.trim()).toBe('2');
  });

  it('never puts a raw enum name on screen for a pharmacy status', async () => {
    await setup({ list: [makeRx({ status: 'PARTNER_REJECTED' })] });

    const badge = el('.status-badge')!;
    expect(badge.textContent!.trim()).not.toBe('PARTNER_REJECTED');
    expect(badge.textContent!.trim()).toBe('Partner Rejected');
  });

  /* ── G7: where the order went ────────────────────────────── */

  it('names the pharmacy holding the order, and why the row needs attention, on the row', async () => {
    await setup({
      list: [makeRx({ id: 'a', status: 'PENDING_STOCK', pharmacyName: 'Pharmacie du Marché' })],
    });

    expect(el('[data-testid="rx-pharmacy-a"]')!.textContent).toContain('Pharmacie du Marché');
    expect(el('[data-testid="rx-attention-a"]')).not.toBeNull();
  });

  it('renders the pharmacy and dispatch columns the response has always carried', async () => {
    const rx = makeRx({
      status: 'SENT_TO_PARTNER',
      pharmacyName: 'Pharmacie du Marché',
      pharmacyContact: '+226 70 00 00 00',
      dispatchChannel: 'SMS',
      dispatchStatus: 'SENT',
      dispatchedAt: '2026-09-02T08:30:00',
      lastPharmacyEvent: 'SENT_TO_PARTNER',
      lastPharmacyEventAt: '2026-09-02T08:30:00',
    });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-pharmacy-name"]')!.textContent).toContain('Pharmacie du Marché');
    expect(el('[data-testid="rx-dispatched-at"]')!.textContent!.trim()).not.toBe('');
    expect(el('[data-testid="rx-dispatch-state"]')!.textContent!.trim()).not.toContain('SENT');
    expect(el('[data-testid="rx-last-pharmacy-event"]')!.textContent).toContain('Sent to Partner');
  });

  it('names the partner that refused, which the prescription itself no longer carries', async () => {
    const rx = makeRx({ status: 'PARTNER_REJECTED', pharmacyName: null });
    await setup({
      list: [rx],
      routings: of(
        page([
          makeRouting({
            id: 'rd-2',
            status: 'REJECTED',
            targetPharmacyName: 'Pharmacie Centrale',
            decidedAt: '2026-09-03T08:00:00',
          }),
          makeRouting({ id: 'rd-1', status: 'COMPLETED', decidedAt: '2026-09-01T08:00:00' }),
        ]),
      ),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.lastRefusedBy()).toBe('Pharmacie Centrale');
    expect(el('[data-testid="rx-last-refused-by"]')!.textContent).toContain('Pharmacie Centrale');
  });

  it('shows what is still owed, as the server computed it on the routing decision', async () => {
    const rx = makeRx({ status: 'PENDING_STOCK' });
    await setup({
      list: [rx],
      routings: of(
        page([
          makeRouting({
            routingType: 'BACKORDER',
            remainingQuantity: 20,
            decidedAt: '2026-09-05T08:00:00',
          }),
        ]),
      ),
      dispenses: of(
        page([makeDispense({ quantityDispensed: 10, dispensedAt: '2026-09-04T08:00:00' })]),
      ),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBe(20);
    expect(el('[data-testid="rx-outstanding-quantity"]')!.textContent).toContain('20');
  });

  it('stops claiming a remainder once the order is complete', async () => {
    // Regression guard. An earlier draft summed (requested − dispensed) per
    // dispense row, but the backend records every fill as a NEW row and
    // compares the SUM of quantityDispensed against the lifetime expected
    // quantity. A 10-of-30 partial followed by a 20-of-20 second fill
    // completes the order — and left the first row's shortfall of 20 in the
    // client's sum forever, so a DISPENSED prescription went on saying that
    // 20 tablets were owed.
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      routings: of(page([makeRouting({ remainingQuantity: 20 })])),
      dispenses: of(
        page([
          makeDispense({
            id: 'd-2',
            quantityRequested: 20,
            quantityDispensed: 20,
            dispensedAt: '2026-09-06T08:00:00',
          }),
          makeDispense({
            id: 'd-1',
            quantityRequested: 30,
            quantityDispensed: 10,
            dispensedAt: '2026-09-04T08:00:00',
          }),
        ]),
      ),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBeNull();
    expect(el('[data-testid="rx-outstanding-quantity"]')).toBeNull();
  });

  /* ── G13: the remainder off the prescription, with its unit ───────── */

  it('computes the remainder from the order itself and renders its unit', async () => {
    // 30 ordered, one refill released, so 60 are expected over the order's
    // life; 25 have been dispensed. The routing snapshot deliberately
    // disagrees — the live balance is what must win.
    const rx = makeRx({
      status: 'PARTIALLY_FILLED',
      quantity: 30,
      quantityUnit: 'comprimés',
      refillsUsed: 1,
    });
    await setup({
      list: [rx],
      routings: of(page([makeRouting({ remainingQuantity: 99 })])),
      dispenses: of(page([makeDispense({ quantityDispensed: 25, unit: 'comprimés' })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBe(35);
    expect(component.outstandingQuantityUnit()).toBe('comprimés');
    const rendered = el('[data-testid="rx-outstanding-quantity"]')!.textContent!;
    expect(rendered).toContain('35');
    expect(rendered)
      .withContext('a bare number could as easily be millilitres as tablets')
      .toContain('comprimés');
  });

  it('will not subtract a fill with a unit from an order that declares none', async () => {
    const rx = makeRx({ status: 'PARTIALLY_FILLED', quantity: 200, quantityUnit: null });
    await setup({
      list: [rx],
      routings: of(
        page([
          makeRouting({
            remainingQuantity: 100,
            decidedAt: '2026-09-10T08:00:00',
            createdAt: '2026-09-10T08:00:00',
          }),
        ]),
      ),
      dispenses: of(page([makeDispense({ quantityDispensed: 2, unit: 'flacon' })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity())
      .withContext('a missing order unit is not a licence to subtract bottles from millilitres')
      .toBe(100);
  });

  it('uses the server figure when the fills already exceed the quantity on the row', async () => {
    // The fills are fetched when the panel opens; quantity/refillsUsed came
    // with the list. A refill approved and filled in between leaves
    // refillsUsed behind, and "nothing owed" would hide a real remainder.
    const rx = makeRx({
      status: 'PARTIALLY_FILLED',
      quantity: 30,
      quantityUnit: 'comprimés',
      refillsUsed: 0,
    });
    await setup({
      list: [rx],
      routings: of(
        page([
          makeRouting({
            remainingQuantity: 25,
            decidedAt: '2026-09-10T08:00:00',
            createdAt: '2026-09-10T08:00:00',
          }),
        ]),
      ),
      dispenses: of(page([makeDispense({ quantityDispensed: 35, unit: 'comprimés' })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBe(25);
  });

  it('hides the refills row when the row does not say how many are left', async () => {
    const rx = makeRx({ status: 'SIGNED', refillsAllowed: 2, refillsRemaining: null });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.hasRefills(rx)).toBeFalse();
    expect(el('[data-testid="rx-refills"]')).toBeNull();
  });

  it('will not sum a fill list the server has more of', async () => {
    // The fills are fetched 20 at a time. Summing the page would understate
    // what has been dispensed and overstate what is owed — and this figure
    // reads as an authoritative balance, not a snapshot.
    const rx = makeRx({ status: 'PARTIALLY_FILLED', quantity: 300, quantityUnit: 'comprimés' });
    await setup({
      list: [rx],
      // Decided after the fill, so the snapshot is not stale and the
      // fallback has something to report.
      routings: of(
        page([
          makeRouting({
            remainingQuantity: 7,
            decidedAt: '2026-09-10T08:00:00',
            createdAt: '2026-09-10T08:00:00',
          }),
        ]),
      ),
      dispenses: of(page([makeDispense({ quantityDispensed: 20, unit: 'comprimés' })], 40)),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity())
      .withContext('the routing snapshot, not 300 − 20')
      .toBe(7);
  });

  it('will not subtract a fill counted in a different unit', async () => {
    // 200 ml dispensed as 2 bottles is not "198 ml outstanding".
    const rx = makeRx({ status: 'PARTIALLY_FILLED', quantity: 200, quantityUnit: 'ml' });
    await setup({
      list: [rx],
      routings: of(
        page([
          makeRouting({
            remainingQuantity: 100,
            decidedAt: '2026-09-10T08:00:00',
            createdAt: '2026-09-10T08:00:00',
          }),
        ]),
      ),
      dispenses: of(page([makeDispense({ quantityDispensed: 2, unit: 'flacon' })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBe(100);
  });

  it('claims no remainder on an order nothing has been filled against', async () => {
    // "Expected minus nothing" is the whole order, which would put an
    // outstanding row on every signed prescription and flash onto the panel
    // while the fill list was still in flight.
    const rx = makeRx({ status: 'SIGNED', quantity: 30, quantityUnit: 'comprimés' });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBeNull();
    expect(el('[data-testid="rx-outstanding-quantity"]')).toBeNull();
  });

  it('ignores a cancelled fill when computing the remainder', async () => {
    const rx = makeRx({ status: 'PARTIALLY_FILLED', quantity: 30, quantityUnit: 'comprimés' });
    await setup({
      list: [rx],
      dispenses: of(
        page([
          makeDispense({ id: 'd-1', quantityDispensed: 10, unit: 'comprimés' }),
          makeDispense({
            id: 'd-2',
            quantityDispensed: 20,
            unit: 'comprimés',
            status: 'CANCELLED',
          }),
        ]),
      ),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    // The backend excludes a cancelled fill from the dispensed total too;
    // counting it here would hide 20 tablets that are genuinely still owed.
    expect(component.outstandingQuantity()).toBe(20);
  });

  it('falls back to the routing snapshot when the fills could not be loaded', async () => {
    // "Expected minus nothing" would claim the whole order is outstanding on
    // a prescription that may be nearly complete.
    const rx = makeRx({ status: 'PENDING_STOCK', quantity: 30, quantityUnit: 'comprimés' });
    await setup({
      list: [rx],
      routings: of(page([makeRouting({ remainingQuantity: 12 })])),
      dispenses: throwError(() => new Error('boom')),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBe(12);
    // The unit is the ORDER's, so it labels the snapshot just as correctly.
    expect(el('[data-testid="rx-outstanding-quantity"]')!.textContent).toContain('comprimés');
  });

  it('shows the ordered quantity and the refills granted', async () => {
    const rx = makeRx({
      status: 'SIGNED',
      quantity: 30,
      quantityUnit: 'comprimés',
      refillsAllowed: 2,
      refillsRemaining: 1,
    });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-quantity"]')!.textContent).toContain('comprimés');
    expect(component.hasRefills(rx)).toBeTrue();
    expect(el('[data-testid="rx-refills"]')).not.toBeNull();
  });

  it('hides the refills row when none were granted', async () => {
    const rx = makeRx({ status: 'SIGNED', quantity: 30, refillsAllowed: 0 });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.hasRefills(rx)).toBeFalse();
    expect(el('[data-testid="rx-refills"]')).toBeNull();
  });

  it('does not ask for the staff list as a role the /staff matcher rejects', async () => {
    // SecurityConfig's GET /staff matcher admits neither PHARMACIST nor
    // PHARMACY_VERIFIER, and matchers are terminal. Firing it anyway is a 403
    // on page open for every verifier the G9 nav entry sends here.
    await setup({ roles: ['ROLE_PHARMACY_VERIFIER'] });

    expect(staffServiceSpy.list).not.toHaveBeenCalled();
    expect(component.staffMembers()).toEqual([]);
  });

  it('still asks for the staff list as a prescriber', async () => {
    await setup({ roles: ['ROLE_DOCTOR'] });

    expect(staffServiceSpy.list).toHaveBeenCalled();
  });

  it('re-asks for the staff list on a scope change', async () => {
    // canReadStaff is read live so a scope switch can change the answer, and
    // it only ever ran at ngOnInit: someone who opened the page as a
    // pharmacist and switched to their doctor role kept the empty prescriber
    // dropdown they started with.
    await setup({ roles: ['ROLE_DOCTOR'] });
    const before = staffServiceSpy.list.calls.count();

    component.onScopeChange('h-2');

    expect(staffServiceSpy.list.calls.count()).toBe(before + 1);
  });

  it('asks for the staff list as a physician, who IS a doctor', async () => {
    // Role audit C2. The JWT carries ROLE_PHYSICIAN and the backend adds
    // ROLE_DOCTOR before its matcher runs, so the request is served — a
    // literal role check here would leave the prescriber dropdown empty for
    // exactly the population that writes prescriptions.
    await setup({ roles: ['ROLE_PHYSICIAN'] });

    expect(staffServiceSpy.list).toHaveBeenCalled();
  });

  /* ── G12: the status filter ───────────────────────────────────────── */

  it('asks the backend for the whole attention bucket, by status', async () => {
    await setup();

    const statuses = prescriptionServiceSpy.list.calls
      .allArgs()
      .map((args) => (args[0] as { statuses?: string[] } | undefined)?.statuses)
      .find((s) => !!s);

    expect(statuses)
      .withContext('the page is a slice of the tenant; the attention tab must not be')
      .toEqual(ATTENTION_REASONS.map((r) => r.status));
  });

  it('merges an attention row the page never reached and counts it', async () => {
    // The defect: the clinical inbox says an order awaits clarification and
    // the page, being the newest 200 rows, does not contain it.
    const onPage = makeRx({ id: 'rx-page', status: 'SIGNED', createdAt: '2026-09-20T08:00:00' });
    const older = makeRx({
      id: 'rx-old',
      status: 'PENDING_CLARIFICATION',
      createdAt: '2026-01-02T08:00:00',
    });
    await setup({ list: [onPage], attention: of([older]) });

    expect(component.prescriptions().map((p) => p.id)).toEqual(['rx-page', 'rx-old']);
    expect(component.countInTab('attention')).toBe(1);

    component.setTab('attention');
    expect(component.filtered().map((p) => p.id)).toEqual(['rx-old']);
  });

  it('does not double-count a row both queries returned', async () => {
    const shared = makeRx({ id: 'rx-1', status: 'PENDING_CLARIFICATION' });
    await setup({ list: [shared], attention: of([shared]) });

    expect(component.prescriptions().length).toBe(1);
    expect(component.countInTab('attention')).toBe(1);
  });

  it('keeps the page when the attention query fails', async () => {
    await setup({
      list: [makeRx({ id: 'rx-1', status: 'SIGNED' })],
      attention: throwError(() => new Error('boom')),
    });

    // Nothing is swallowed as empty data: the page is still there, and the
    // truncation banner goes on saying the counts are a lower bound — which
    // is what this page did before the filter existed.
    expect(component.prescriptions().map((p) => p.id)).toEqual(['rx-1']);
  });

  it('does not let the merged attention rows make an untruncated page look truncated', async () => {
    const page200 = Array.from({ length: PrescriptionService.LIST_PAGE_SIZE - 1 }, (_, i) =>
      makeRx({ id: 'rx-' + i, status: 'SIGNED' }),
    );
    await setup({
      list: page200,
      attention: of([makeRx({ id: 'rx-old', status: 'PENDING_CLARIFICATION' })]),
    });

    expect(component.prescriptions().length).toBe(PrescriptionService.LIST_PAGE_SIZE);
    expect(component.listTruncated()).toBeFalse();
  });

  it('drops a routing remainder that a later fill has made stale', async () => {
    const rx = makeRx({ status: 'PARTIALLY_FILLED' });
    await setup({
      list: [rx],
      routings: of(
        page([makeRouting({ remainingQuantity: 20, decidedAt: '2026-09-04T08:00:00' })]),
      ),
      dispenses: of(page([makeDispense({ dispensedAt: '2026-09-06T08:00:00' })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    // The server's figure is a snapshot, not a live balance: it has not been
    // recomputed since the fill, and guessing is what the row above forbids.
    expect(component.outstandingQuantity()).toBeNull();
  });

  it('asks for the NEWEST page, because neither endpoint orders its results', async () => {
    // Both repository methods are unordered derived queries and the
    // controllers' @PageableDefault sets no sort, so page 0 is an arbitrary
    // subset: without this the table can omit the newest fill and present
    // older ones as current.
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({ list: [rx] });

    component.viewDetail(rx);

    expect(pharmacyService.listDispensesByPrescription).toHaveBeenCalledWith(
      'rx-1',
      0,
      20,
      'dispensedAt,desc',
    );
    expect(pharmacyService.listRoutingDecisionsByPrescription).toHaveBeenCalledWith(
      'rx-1',
      0,
      20,
      'decidedAt,desc',
    );
  });

  it('cancels the in-flight history when another prescription is opened', async () => {
    const a = makeRx({ id: 'rx-a', status: 'DISPENSED' });
    const b = makeRx({ id: 'rx-b', status: 'DISPENSED' });
    const first = new Subject<ApiResponse<Page<DispenseResponse>>>();
    const second = new Subject<ApiResponse<Page<DispenseResponse>>>();
    await setup({ list: [a, b] });
    pharmacyService.listDispensesByPrescription.and.returnValues(first, second);

    component.viewDetail(a);
    expect(first.observed).toBeTrue();

    component.viewDetail(b);
    expect(first.observed)
      .withContext('opening B must cancel A, not leave two histories racing')
      .toBeFalse();
  });

  it('never lands one prescription history under another', async () => {
    // switchMap cannot cancel what never emitted: opening a DRAFT fires no
    // request at all, so A's response would still have arrived and filled the
    // panel with another order's dispense records.
    const a = makeRx({ id: 'rx-a', status: 'DISPENSED' });
    const draft = makeRx({ id: 'rx-b', status: 'DRAFT' });
    const slow = new Subject<ApiResponse<Page<DispenseResponse>>>();
    await setup({ list: [a, draft] });
    pharmacyService.listDispensesByPrescription.and.returnValue(slow);

    component.viewDetail(a);
    component.viewDetail(draft);

    slow.next(page([makeDispense({ id: 'd-a' })]));
    slow.complete();

    expect(component.dispenseHistory()).toEqual([]);
    expect(component.historyLoading()).toBeFalse();
  });

  /* ── G11: the dispense and routing history ──────────────────── */

  it('renders both histories in the detail panel for a prescriber', async () => {
    const rx = makeRx({ status: 'PARTNER_DISPENSED' });
    await setup({
      list: [rx],
      dispenses: of(page([makeDispense()])),
      routings: of(page([makeRouting({ status: 'COMPLETED', remainingQuantity: 12 })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-pharmacy-history"]')).not.toBeNull();
    expect(el('[data-testid="rx-dispense-history"]')).not.toBeNull();
    expect(el('[data-testid="rx-routing-history"]')).not.toBeNull();
    expect(el('[data-testid="rx-routing-history"]')!.textContent).toContain('12');
    // The routing type and decision status are labels, never wire tokens.
    expect(el('[data-testid="rx-routing-history"]')!.textContent).toContain('Partner pharmacy');
    expect(el('[data-testid="rx-routing-history"]')!.textContent).not.toContain('BACKORDER');
  });

  it("says a partner never delivered in the reader's language, not in stored English", async () => {
    // The server used to compose "Partner no-show: " into the reason column
    // and a French or Spanish prescriber read it in English. It now arrives
    // as a flag plus whatever the pharmacist actually typed.
    const rx = makeRx({ status: 'SIGNED' });
    await setup({
      list: [rx],
      routings: of(
        page([
          makeRouting({
            status: 'CANCELLED',
            partnerNoShow: true,
            noShowReason: 'nobody at the counter',
            reason: 'Nearest partner has stock',
          }),
        ]),
      ),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    const history = el('[data-testid="rx-routing-history"]')!;
    expect(el('[data-testid="rx-routing-no-show-rd-1"]')).not.toBeNull();
    expect(history.textContent).toContain('PRESCRIPTIONS.HISTORY.PARTNER_NO_SHOW');
    expect(history.textContent).toContain('nobody at the counter');
    expect(history.textContent).not.toContain('Partner no-show:');
  });

  it('hides the history from a role the endpoints refuse, instead of 403-ing at them', async () => {
    // The prescriptions ROUTE admits nurses; neither history endpoint does.
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({ list: [rx], roles: ['ROLE_NURSE'] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-pharmacy-history"]')).toBeNull();
    expect(pharmacyService.listDispensesByPrescription).not.toHaveBeenCalled();
    expect(pharmacyService.listRoutingDecisionsByPrescription).not.toHaveBeenCalled();
  });

  it('says so when there is no history rather than leaving the panel blank', async () => {
    const rx = makeRx({ status: 'SIGNED' });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-history-empty"]')).not.toBeNull();
    expect(el('[data-testid="rx-history-error"]')).toBeNull();
  });

  it('renders a refusal or an outage as an error, NEVER as an empty history', async () => {
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      dispenses: throwError(() => ({ status: 403 })),
      routings: throwError(() => ({ status: 403 })),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-history-error"]')).not.toBeNull();
    expect(el('[data-testid="rx-history-empty"]'))
      .withContext('a 403 rendered as "no fills recorded" is the defect, not the fallback')
      .toBeNull();

    // And it is retryable rather than terminal.
    pharmacyService.listDispensesByPrescription.and.returnValue(of(page([makeDispense()])));
    pharmacyService.listRoutingDecisionsByPrescription.and.returnValue(of(page([])));
    el('[data-testid="rx-history-retry"]')!.click();
    fixture.detectChanges();
    expect(el('[data-testid="rx-history-error"]')).toBeNull();
    expect(el('[data-testid="rx-history-partial-error"]')).toBeNull();
    expect(el('[data-testid="rx-dispense-history"]')).not.toBeNull();
  });

  it('does not spend two requests opening a draft, which can have no history', async () => {
    const rx = makeRx({ status: 'DRAFT' });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(pharmacyService.listDispensesByPrescription).not.toHaveBeenCalled();
    expect(el('[data-testid="rx-pharmacy-history"]')).toBeNull();
  });

  it('loads the history in global view — the services no longer 500 without a hospital', async () => {
    // Both services used to open with roleValidator.requireActiveHospitalId()
    // and dereference its result, which is NULL for a super-admin in global
    // view: two 500s on a page that is explicitly cross-tenant. #740 made them
    // treat that caller the way the rest of the read surface does, so the panel
    // fires its calls instead of asking for a hospital.
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      roles: ['ROLE_SUPER_ADMIN'],
      superAdmin: true,
      globalView: true,
      dispenses: of(page([makeDispense()])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(pharmacyService.listDispensesByPrescription).toHaveBeenCalled();
    expect(pharmacyService.listRoutingDecisionsByPrescription).toHaveBeenCalled();
    expect(el('[data-testid="rx-dispense-history"]')).not.toBeNull();
  });

  it('loads the history for a super-admin who has picked a hospital', async () => {
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      roles: ['ROLE_SUPER_ADMIN'],
      superAdmin: true,
      globalView: false,
      dispenses: of(page([makeDispense()])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-dispense-history"]')).not.toBeNull();
  });

  it('claims no remainder on a prescription printed for the patient to take away', async () => {
    // printForPatient writes remainingQuantity on its PRINT decision and
    // creates no dispense row at all, so nothing could ever clear it: the
    // guard is the tab bucket, not a pair of status names.
    const rx = makeRx({ status: 'PRINTED_FOR_PATIENT' });
    await setup({
      list: [rx],
      routings: of(page([makeRouting({ routingType: 'PRINT', remainingQuantity: 30 })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBeNull();
    expect(el('[data-testid="rx-outstanding-quantity"]')).toBeNull();
  });

  it('claims no remainder on a prescription the prescriber has cancelled', async () => {
    const rx = makeRx({ status: 'CANCELLED' });
    await setup({
      list: [rx],
      routings: of(page([makeRouting({ routingType: 'BACKORDER', remainingQuantity: 30 })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBeNull();
  });

  it('does not let a CANCELLED fill hide a remainder that is still owed', async () => {
    // A cancelled dispense is reversed: the backend leaves the row in the
    // list but excludes it from the dispensed total, so it cannot make the
    // routing decision's remainder stale either.
    const rx = makeRx({ status: 'PENDING_STOCK' });
    await setup({
      list: [rx],
      routings: of(
        page([makeRouting({ remainingQuantity: 30, decidedAt: '2026-09-04T08:00:00' })]),
      ),
      dispenses: of(
        page([
          makeDispense({
            id: 'd-cancelled',
            status: 'CANCELLED',
            dispensedAt: '2026-09-06T08:00:00',
          }),
        ]),
      ),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.outstandingQuantity()).toBe(30);
  });

  it('counts the same thing in the summary card as in the tab beside it', async () => {
    await setup({
      list: [
        makeRx({ id: 'a', status: 'DRAFT' }),
        makeRx({ id: 'b', status: 'PENDING_SIGNATURE' }),
      ],
    });

    expect(el('[data-testid="rx-draft-count"]')!.textContent!.trim()).toBe('2');
    expect(el('[data-testid="rx-tab-draft"]')!.textContent).toContain('2');
  });

  it('colours the badge by the tab, so the two can never disagree', async () => {
    // A seventeen-case switch put TRANSMITTED (untouched in the pharmacy
    // queue) in the same green as DISPENSED, and PARTNER_REJECTED (a live
    // order to re-route) in the same red as CANCELLED. A prescriber scanning
    // a list of controlled drugs reads colour before text.
    await setup();

    const classByTab: Record<string, string> = {};
    for (const status of PRESCRIPTION_STATUSES) {
      const tab = TAB_BY_STATUS[status];
      const cls = component.getStatusClass(status);
      expect(cls).withContext(`${status} renders an unstyled badge`).toBeTruthy();
      if (classByTab[tab]) {
        expect(cls).withContext(`${status} disagrees with its own tab`).toBe(classByTab[tab]);
      } else {
        classByTab[tab] = cls;
      }
    }
    // Five buckets, five distinct colours — no two tabs share one.
    expect(new Set(Object.values(classByTab)).size).toBe(Object.keys(classByTab).length);
  });

  it('keeps the clarification exchange readable after the prescriber answers it', async () => {
    // resolveClarification restores the status the prescription held BEFORE
    // the query — often SIGNED, which is not pharmacy-owned, so every
    // pharmacy column goes back to null.
    const rx = makeRx({
      status: 'SIGNED',
      clarificationReason: 'Dose looks high for this weight',
      clarificationResponse: 'Confirmed, weight-based dosing is intended',
    });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-pharmacy-state"]')).not.toBeNull();
    expect(el('[data-testid="rx-clarification-reason"]')!.textContent).toContain('Dose looks high');
    expect(el('[data-testid="rx-clarification-response"]')!.textContent).toContain('Confirmed');
  });

  it('keeps the fill history when only the routing half fails, and says so', async () => {
    // forkJoin is all-or-nothing, so a transient 500 on the routing decisions
    // used to discard a fill list that had loaded perfectly well. On a
    // controlled drug the fills are the half that matters most.
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      dispenses: of(page([makeDispense()])),
      routings: throwError(() => ({ status: 500 })),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-dispense-history"]')).not.toBeNull();
    expect(el('[data-testid="rx-history-partial-error"]'))
      .withContext('a missing half must say so, not read as "nothing recorded"')
      .not.toBeNull();
    expect(el('[data-testid="rx-history-empty"]')).toBeNull();
    expect(el('[data-testid="rx-history-error"]')).toBeNull();
    expect(component.historyError()).toBeFalse();
  });

  it('labels a dispatch the pharmacy has since refused as past, not current', async () => {
    // clearPharmacy nulls the three pharmacy columns on a refusal but leaves
    // dispatchChannel / dispatchStatus / dispatchedAt as the SMS path wrote
    // them, so a flat render reads as a live, successful dispatch.
    const rx = makeRx({
      status: 'PARTNER_REJECTED',
      pharmacyName: null,
      dispatchChannel: 'SMS',
      dispatchStatus: 'SENT',
      dispatchedAt: '2026-09-02T08:30:00',
    });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.dispatchIsCurrent(rx)).toBeFalse();
    expect(el('[data-testid="rx-dispatched-at"]')).not.toBeNull();
    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="rx-pharmacy-state"] .field-label'),
    ).map((n) => (n as HTMLElement).textContent!.trim());
    expect(labels).toContain('PRESCRIPTIONS.PHARMACY.LAST_DISPATCHED_AT');
    expect(labels).toContain('PRESCRIPTIONS.PHARMACY.LAST_DISPATCH');
    expect(labels).not.toContain('PRESCRIPTIONS.PHARMACY.DISPATCHED_AT');
  });

  it('labels a live dispatch as current', async () => {
    const rx = makeRx({
      status: 'SENT_TO_PARTNER',
      pharmacyName: 'Pharmacie du Marché',
      dispatchChannel: 'SMS',
      dispatchStatus: 'SENT',
      dispatchedAt: '2026-09-02T08:30:00',
    });
    await setup({ list: [rx] });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.dispatchIsCurrent(rx)).toBeTrue();
    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="rx-pharmacy-state"] .field-label'),
    ).map((n) => (n as HTMLElement).textContent!.trim());
    expect(labels).toContain('PRESCRIPTIONS.PHARMACY.DISPATCHED_AT');
    expect(labels).not.toContain('PRESCRIPTIONS.PHARMACY.LAST_DISPATCHED_AT');
  });

  it('says so when the list is capped, instead of letting a count lie', async () => {
    const full = Array.from({ length: PrescriptionService.LIST_PAGE_SIZE }, (_, i) =>
      makeRx({ id: 'rx-' + i, status: 'SIGNED' }),
    );
    await setup({ list: full });

    expect(component.listTruncated()).toBeTrue();
    expect(el('[data-testid="rx-list-truncated"]')).not.toBeNull();
  });

  it('says nothing about a cap when the list came back short of one', async () => {
    await setup({ list: [makeRx({ status: 'SIGNED' })] });

    expect(component.listTruncated()).toBeFalse();
    expect(el('[data-testid="rx-list-truncated"]')).toBeNull();
  });

  it('treats a dispatch older than the latest re-route as past, not current', async () => {
    // routeToPartner sets the pharmacy columns to the NEW partner and never
    // touches the dispatch ones, so "Held by B / Dispatched T1 / SMS Sent"
    // would say B had been SMS'd at a time that belongs to A.
    const rx = makeRx({
      status: 'PARTNER_ACCEPTED',
      pharmacyName: 'Pharmacie B',
      dispatchChannel: 'SMS',
      dispatchStatus: 'SENT',
      dispatchedAt: '2026-09-02T08:30:00',
    });
    await setup({
      list: [rx],
      routings: of(page([makeRouting({ decidedAt: '2026-09-05T09:00:00' })])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(component.dispatchIsCurrent(rx)).toBeFalse();
    const labels = Array.from(
      fixture.nativeElement.querySelectorAll('[data-testid="rx-pharmacy-state"] .field-label'),
    ).map((n) => (n as HTMLElement).textContent!.trim());
    expect(labels).toContain('PRESCRIPTIONS.PHARMACY.LAST_DISPATCHED_AT');
  });

  it('names the half of the history that is missing', async () => {
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      dispenses: throwError(() => ({ status: 500 })),
      routings: of(page([makeRouting()])),
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-history-partial-error"]')!.textContent).toContain(
      'PRESCRIPTIONS.HISTORY.PARTIAL_ERROR_DISPENSES',
    );
  });

  it('keeps the half that already loaded when a retry loses the other one', async () => {
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({
      list: [rx],
      dispenses: of(page([makeDispense()])),
      routings: throwError(() => ({ status: 500 })),
    });

    component.viewDetail(rx);
    fixture.detectChanges();
    expect(component.dispenseHistory().length).toBe(1);

    // The session is expiring: this time it is the fills that fail.
    pharmacyService.listDispensesByPrescription.and.returnValue(
      throwError(() => ({ status: 500 })),
    );
    pharmacyService.listRoutingDecisionsByPrescription.and.returnValue(of(page([makeRouting()])));
    component.retryPharmacyHistory();
    fixture.detectChanges();

    expect(component.dispenseHistory().length)
      .withContext('rows the prescriber could read a second ago must not vanish on a retry')
      .toBe(1);
    expect(component.routingHistory().length).toBe(1);
  });

  it('drops the previous prescription history when the panel closes', async () => {
    const rx = makeRx({ status: 'DISPENSED' });
    await setup({ list: [rx], dispenses: of(page([makeDispense()])) });

    component.viewDetail(rx);
    fixture.detectChanges();
    expect(component.dispenseHistory().length).toBe(1);

    component.closeDetail();
    fixture.detectChanges();
    expect(component.dispenseHistory()).toEqual([]);
    expect(component.routingHistory()).toEqual([]);
    expect(component.historyError()).toBeFalse();
  });
});
/**
 * `GET /prescriptions` declares no `@PageableDefault`, so Spring served page 0
 * of 20 from an unordered derived query. That was survivable while the page
 * only listed what it had; it stopped being survivable when the tabs started
 * counting, because "Needs attention 0" is a confident claim that nothing is
 * waiting.
 */
describe('PrescriptionService — list paging', () => {
  let service: PrescriptionService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(PrescriptionService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('asks for a real page of the newest prescriptions, not an arbitrary twenty', () => {
    service.list().subscribe();

    const req = httpMock.expectOne((r) => r.url === '/prescriptions');
    expect(req.request.params.get('size')).toBe(String(PrescriptionService.LIST_PAGE_SIZE));
    expect(req.request.params.get('sort')).toBe('createdAt,desc');
    req.flush({ content: [] });
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
