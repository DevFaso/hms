import { TestBed, ComponentFixture, fakeAsync, tick } from '@angular/core/testing';
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
import { Payer, PayerCatalogueService } from '../../services/payer-catalogue.service';

describe('CoverageTabComponent (P3 #21 additions)', () => {
  let fixture: ComponentFixture<CoverageTabComponent>;
  let component: CoverageTabComponent;
  let extrasSpy: jasmine.SpyObj<RegistrationExtrasService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let insuranceSpy: jasmine.SpyObj<PatientInsuranceService>;
  let payersSpy: jasmine.SpyObj<PayerCatalogueService>;

  /** Billing-money plan PR 6: the test clinic's fictional payer (never a real insurer). */
  const MUT_A = {
    id: 'payer-a',
    hospitalId: 'h1',
    code: 'MUT-TEST-A',
    name: 'Mutuelle Test A (FICTIVE)',
    payerType: 'MUTUELLE',
    active: true,
    plans: [
      {
        id: 'plan-a',
        payerId: 'payer-a',
        code: 'MTA-ESS',
        name: 'Formule Test Essentielle',
        defaultCoveragePercent: 75,
        effectiveFrom: '2026-12-01',
        status: 'SCHEDULED',
        linkable: true,
        rules: [],
      },
    ],
  } as Payer;

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
    insuranceSpy = jasmine.createSpyObj('PatientInsuranceService', [
      'forPatient',
      'link',
      'update',
      'delete',
      'setPayerPlan',
    ]);
    payersSpy = jasmine.createSpyObj('PayerCatalogueService', ['list']);
    payersSpy.list.and.returnValue(of([MUT_A]));
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
        { provide: PayerCatalogueService, useValue: payersSpy },
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
    // QA D1247: a confirmation asks the reason first; nothing is sent on the click.
    expect(extrasSpy.deactivateGuarantor).not.toHaveBeenCalled();
    expect(component.deactivatingGuarantor()?.id).toBe('g1');
    component.confirmDeactivateGuarantor(guarantor(), 'Plus solvable');

    expect(extrasSpy.deactivateGuarantor).toHaveBeenCalledWith('p1', 'g1', 'Plus solvable');
  });

  it('QA D1247: the deactivation dialog asks a reason, then a toast says it and focus returns to the row', fakeAsync(() => {
    extrasSpy.listGuarantors.and.returnValue(of([guarantor()]));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    document.body.appendChild(el);
    el.querySelector<HTMLButtonElement>('[data-testid="toggle-guarantor"]')!.click();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid="reason-dialog"]')).not.toBeNull();
    expect(extrasSpy.deactivateGuarantor).not.toHaveBeenCalled();

    extrasSpy.deactivateGuarantor.and.returnValue(of(guarantor({ active: false })));
    extrasSpy.listGuarantors.and.returnValue(of([guarantor({ active: false })]));
    component.confirmDeactivateGuarantor(guarantor(), 'Plus solvable');
    fixture.detectChanges();
    tick();
    fixture.detectChanges();

    expect(toastSpy.success).toHaveBeenCalledWith('COVERAGE.GUARANTOR_DEACTIVATED');
    expect(component.deactivatingGuarantor()).toBeNull();
    expect(el.querySelector('[data-testid="reason-dialog"]')).toBeNull();
    expect(document.activeElement?.getAttribute('data-testid')).toBe('toggle-guarantor');
    el.remove();
  }));

  it('QA D1247: an inactive guarantor shows when and why it was deactivated', () => {
    extrasSpy.listGuarantors.and.returnValue(
      of([
        guarantor({
          active: false,
          deactivationReason: 'Plus solvable',
          deactivatedAt: '2026-11-02T05:00:00',
        }),
      ]),
    );
    fixture.detectChanges();
    const note = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="guarantor-deactivation-g1"]',
    );
    expect(note?.textContent).toContain('Plus solvable');
    // QA D1288: the time and the reason are not glued ("12:27Motif").
    expect(note?.textContent?.replaceAll(/\s+/g, ' ')).toContain(' · COVERAGE.DEACTIVATION_REASON');
  });

  it('QA D1247: reactivating acts at once and says so', () => {
    fixture.detectChanges();
    extrasSpy.reactivateGuarantor.and.returnValue(of(guarantor()));
    component.toggleGuarantorActive(guarantor({ active: false }));
    expect(extrasSpy.reactivateGuarantor).toHaveBeenCalledWith('p1', 'g1');
    expect(toastSpy.success).toHaveBeenCalledWith('COVERAGE.GUARANTOR_REACTIVATED');
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
      throwError(() => ({
        status: 400,
        error: { message: 'Patient is not registered at this hospital.' },
      })),
    );

    component.submitConsent();

    expect(toastSpy.error).toHaveBeenCalledWith('Patient is not registered at this hospital.');
  });

  // ── Billing-money plan PR 6: the "Formule" picker ──

  it('shows the plan a coverage is billed under, and free-text coverage as no plan', () => {
    insuranceSpy.forPatient.and.returnValue(
      of([
        {
          id: 'i1',
          patientId: 'p1',
          providerName: 'Mutuelle Test A',
          policyNumber: 'MTA-TEST-0002',
          payerPlanId: 'plan-a',
          payerPlanName: 'Formule Test Essentielle',
          planPayerName: 'Mutuelle Test A (FICTIVE)',
        },
        { id: 'i2', patientId: 'p1', providerName: 'Ancienne assurance', policyNumber: 'X-1' },
      ]),
    );
    fixture.detectChanges();
    const cells = fixture.nativeElement.querySelectorAll('[data-testid="coverage-plan"]');
    expect(cells.length).toBe(2);
    expect(cells[0].textContent).toContain('Mutuelle Test A (FICTIVE) — Formule Test Essentielle');
    expect(cells[1].textContent).toContain('COVERAGE.NO_PLAN');
  });

  it('QA D3150: a verified coverage says « Assurance vérifiée » with who verified it and when', () => {
    insuranceSpy.forPatient.and.returnValue(
      of([
        {
          id: 'i1',
          patientId: 'p1',
          providerName: 'Mutuelle Test A',
          policyNumber: 'MTA-TEST-0002',
          verifiedAt: '2026-11-22T02:52:30',
          verifiedByName: 'Awa Ouedraogo',
        },
        { id: 'i2', patientId: 'p1', providerName: 'Ancienne assurance', policyNumber: 'X-1' },
      ]),
    );
    fixture.detectChanges();
    const lines = fixture.nativeElement.querySelectorAll('[data-testid="coverage-verified"]');
    expect(lines.length).toBe(1);
    expect(lines[0].textContent).toContain('COVERAGE.VERIFIED');
    expect(lines[0].textContent).toContain('Awa Ouedraogo');
    expect(lines[0].textContent).toContain('2026');
    // It wraps inside its cell at every width, a long name included.
    const host = fixture.nativeElement as HTMLElement;
    host.style.display = 'block';
    for (const width of [1366, 768, 390]) {
      host.style.width = `${width - 32}px`;
      fixture.detectChanges();
      const cell = (lines[0] as HTMLElement).closest('td')!;
      expect((lines[0] as HTMLElement).getBoundingClientRect().width)
        .withContext(`verified line at ${width}px`)
        .toBeLessThanOrEqual(cell.getBoundingClientRect().width + 1);
    }
  });

  it('links a coverage to a plan of the hospital and shows the answer', () => {
    insuranceSpy.forPatient.and.returnValue(
      of([{ id: 'i1', patientId: 'p1', providerName: 'Mutuelle Test A', policyNumber: 'P-1' }]),
    );
    insuranceSpy.setPayerPlan.and.returnValue(
      of({
        id: 'i1',
        patientId: 'p1',
        providerName: 'Mutuelle Test A',
        policyNumber: 'P-1',
        payerPlanId: 'plan-a',
        payerPlanName: 'Formule Test Essentielle',
        planPayerName: 'Mutuelle Test A (FICTIVE)',
      }),
    );
    fixture.detectChanges();
    fixture.nativeElement.querySelector('[data-testid="coverage-plan-pick"]').click();
    fixture.detectChanges();
    expect(payersSpy.list).toHaveBeenCalledWith(false);
    expect(component.planOptions()).toEqual([
      { id: 'plan-a', label: 'Mutuelle Test A (FICTIVE) — Formule Test Essentielle' },
    ]);
    component.planChoice = 'plan-a';
    fixture.nativeElement.querySelector('[data-testid="coverage-plan-save"]').click();
    fixture.detectChanges();

    expect(insuranceSpy.setPayerPlan).toHaveBeenCalledWith('i1', 'plan-a');
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.planTarget()).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="coverage-plan"]').textContent,
    ).toContain('Formule Test Essentielle');
  });

  it('removing the plan sends null, and a refusal keeps the dialog open with the reason', () => {
    insuranceSpy.forPatient.and.returnValue(
      of([{ id: 'i1', patientId: 'p1', payerPlanId: 'plan-a', payerPlanName: 'Formule' }]),
    );
    insuranceSpy.setPayerPlan.and.returnValue(
      throwError(() => ({ status: 404, error: { message: 'Formule introuvable : plan-b' } })),
    );
    fixture.detectChanges();
    fixture.nativeElement.querySelector('[data-testid="coverage-plan-pick"]').click();
    fixture.detectChanges();
    component.planChoice = null;
    component.submitPlan();
    fixture.detectChanges();

    expect(insuranceSpy.setPayerPlan).toHaveBeenCalledWith('i1', null);
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.planTarget()).not.toBeNull();
  });
});
