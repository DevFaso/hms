import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
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

  function page<T>(content: T[]): ApiResponse<Page<T>> {
    return {
      data: { content, totalElements: content.length, totalPages: 1, size: 20, number: 0 },
    };
  }

  interface SetupOptions {
    list?: PrescriptionResponse[];
    roles?: string[];
    dispenses?: Observable<ApiResponse<Page<DispenseResponse>>>;
    routings?: Observable<ApiResponse<Page<RoutingDecisionResponse>>>;
  }

  async function setup(opts: SetupOptions = {}): Promise<void> {
    const roles = opts.roles ?? ['ROLE_DOCTOR'];

    const prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
    ]);
    prescriptionService.list.and.returnValue(of(opts.list ?? []));

    const staffService = jasmine.createSpyObj<StaffService>('StaffService', ['list']);
    staffService.list.and.returnValue(of([]));

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
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            hasAnyActiveRole: (wanted: string[]) => wanted.some((r) => roles.includes(r)),
          },
        },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
      ],
    }).compileComponents();

    // The one key in this block that interpolates. Loaded for real so the
    // assertion below tests the PARAMETER NAME too: ngx-translate renders a
    // missing key as the key itself, which would pass either way.
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(
      'en',
      { PRESCRIPTIONS: { PHARMACY: { AT: 'at {{pharmacy}}' } } },
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
    });

    component.viewDetail(rx);
    fixture.detectChanges();

    expect(el('[data-testid="rx-history-error"]')).not.toBeNull();
    expect(el('[data-testid="rx-history-empty"]'))
      .withContext('a 403 rendered as "no fills recorded" is the defect, not the fallback')
      .toBeNull();

    // And it is retryable rather than terminal.
    pharmacyService.listDispensesByPrescription.and.returnValue(of(page([makeDispense()])));
    el('[data-testid="rx-history-retry"]')!.click();
    fixture.detectChanges();
    expect(el('[data-testid="rx-history-error"]')).toBeNull();
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
