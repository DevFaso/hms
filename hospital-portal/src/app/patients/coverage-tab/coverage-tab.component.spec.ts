import { TestBed, ComponentFixture } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { CoverageTabComponent } from './coverage-tab.component';
import { PatientInsuranceService } from '../../services/patient-insurance.service';
import { RegistrationService } from '../../services/registration.service';
import {
  Guarantor,
  RegistrationExtrasService,
  TreatmentConsent,
} from '../../services/registration-extras.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';

describe('CoverageTabComponent (P3 #21 additions)', () => {
  let fixture: ComponentFixture<CoverageTabComponent>;
  let component: CoverageTabComponent;
  let extrasSpy: jasmine.SpyObj<RegistrationExtrasService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  const guarantor = (overrides: Partial<Guarantor> = {}): Guarantor => ({
    id: 'g1',
    patientId: 'p1',
    hospitalId: 'h1',
    fullName: 'Mariam Kaboré',
    relationship: 'Mother',
    phone: '+22670000000',
    email: null,
    address: null,
    primary: true,
    active: true,
    notes: null,
    createdAt: '2026-08-22T10:00:00',
    updatedAt: null,
    ...overrides,
  });

  const consent = (overrides: Partial<TreatmentConsent> = {}): TreatmentConsent => ({
    id: 'c1',
    patientId: 'p1',
    hospitalId: 'h1',
    hospitalName: 'CHU',
    appointmentId: null,
    encounterId: null,
    status: 'ACTIVE',
    method: 'ELECTRONIC',
    source: 'CHECK_IN',
    signedName: 'Awa Kaboré',
    signatureHash: 'a'.repeat(64),
    consentedAt: '2026-08-22T09:00:00',
    expiresAt: null,
    recordedByName: 'Reception A',
    revokedAt: null,
    revocationReason: null,
    notes: null,
    createdAt: '2026-08-22T09:00:00',
    ...overrides,
  });

  beforeEach(async () => {
    const insuranceSpy = jasmine.createSpyObj('PatientInsuranceService', [
      'forPatient',
      'link',
      'update',
      'delete',
    ]);
    insuranceSpy.forPatient.and.returnValue(of([]));
    const registrationSpy = jasmine.createSpyObj('RegistrationService', ['list']);
    registrationSpy.list.and.returnValue(of([]));
    extrasSpy = jasmine.createSpyObj('RegistrationExtrasService', [
      'listGuarantors',
      'addGuarantor',
      'updateGuarantor',
      'deactivateGuarantor',
      'reactivateGuarantor',
      'listConsents',
      'recordConsent',
      'revokeConsent',
    ]);
    extrasSpy.listGuarantors.and.returnValue(of([]));
    extrasSpy.listConsents.and.returnValue(of([]));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const roleCtxSpy = jasmine.createSpyObj('RoleContextService', ['hasAnyActiveRole']);
    roleCtxSpy.hasAnyActiveRole.and.returnValue(true);

    await TestBed.configureTestingModule({
      imports: [CoverageTabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PatientInsuranceService, useValue: insuranceSpy },
        { provide: RegistrationService, useValue: registrationSpy },
        { provide: RegistrationExtrasService, useValue: extrasSpy },
        { provide: RoleContextService, useValue: roleCtxSpy },
        { provide: ToastService, useValue: toastSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(CoverageTabComponent);
    component = fixture.componentInstance;
    component.patientId = 'p1';
  });

  it('loads guarantors and consents alongside insurance on init', () => {
    fixture.detectChanges();

    expect(extrasSpy.listGuarantors).toHaveBeenCalledWith('p1');
    expect(extrasSpy.listConsents).toHaveBeenCalledWith('p1');
  });

  it('renders guarantor rows with the primary badge', () => {
    extrasSpy.listGuarantors.and.returnValue(of([guarantor()]));
    fixture.detectChanges();

    const table = fixture.nativeElement.querySelector('[data-testid="guarantors-table"]');
    expect(table).toBeTruthy();
    expect(table.textContent).toContain('Mariam Kaboré');
    expect(table.querySelector('.primary-badge')).toBeTruthy();
  });

  it('adding a guarantor requires a name and posts the form', () => {
    fixture.detectChanges();
    component.openGuarantor(null);

    component.submitGuarantor();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(extrasSpy.addGuarantor).not.toHaveBeenCalled();

    component.guarantorForm.fullName = 'Mariam Kaboré';
    extrasSpy.addGuarantor.and.returnValue(of(guarantor()));
    component.submitGuarantor();

    expect(extrasSpy.addGuarantor).toHaveBeenCalledWith(
      'p1',
      jasmine.objectContaining({ fullName: 'Mariam Kaboré' }),
    );
  });

  it('editing routes through updateGuarantor', () => {
    fixture.detectChanges();
    const existing = guarantor();
    component.openGuarantor(existing);
    extrasSpy.updateGuarantor.and.returnValue(of(existing));

    component.submitGuarantor();

    expect(extrasSpy.updateGuarantor).toHaveBeenCalledWith('p1', 'g1', jasmine.anything());
  });

  it('toggling an active guarantor deactivates it (never deletes)', () => {
    fixture.detectChanges();
    extrasSpy.deactivateGuarantor.and.returnValue(of(guarantor({ active: false })));

    component.toggleGuarantorActive(guarantor());

    expect(extrasSpy.deactivateGuarantor).toHaveBeenCalledWith('p1', 'g1');
  });

  it('records a consent with the chosen method', () => {
    fixture.detectChanges();
    component.openConsent();
    component.consentForm.method = 'VERBAL';
    component.consentForm.signedName = 'Awa Kaboré';
    extrasSpy.recordConsent.and.returnValue(of(consent({ method: 'VERBAL' })));

    component.submitConsent();

    expect(extrasSpy.recordConsent).toHaveBeenCalledWith(
      'p1',
      jasmine.objectContaining({ method: 'VERBAL', signedName: 'Awa Kaboré' }),
    );
  });

  it('revoking demands a reason client-side before calling the backend', () => {
    fixture.detectChanges();
    component.openRevoke(consent());

    component.submitRevoke();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(extrasSpy.revokeConsent).not.toHaveBeenCalled();

    component.revokeReason.set('Patient withdrew consent');
    extrasSpy.revokeConsent.and.returnValue(of(consent({ status: 'REVOKED' })));
    component.submitRevoke();

    expect(extrasSpy.revokeConsent).toHaveBeenCalledWith('p1', 'c1', 'Patient withdrew consent');
  });

  it('surfaces backend refusals verbatim on consent save', () => {
    fixture.detectChanges();
    component.openConsent();
    extrasSpy.recordConsent.and.returnValue(
      throwError(() => ({ error: { message: 'Patient is not registered at this hospital.' } })),
    );

    component.submitConsent();

    expect(toastSpy.error).toHaveBeenCalledWith('Patient is not registered at this hospital.');
  });
});
