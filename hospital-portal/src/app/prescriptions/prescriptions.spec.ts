import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
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

  beforeEach(async () => {
    const prescriptionService = jasmine.createSpyObj<PrescriptionService>('PrescriptionService', [
      'list',
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
        provideHttpClient(),
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
        provideHttpClient(),
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
