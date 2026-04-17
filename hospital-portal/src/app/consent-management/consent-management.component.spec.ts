import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ConsentManagementComponent } from './consent-management.component';
import { RecordSharingService, PatientConsentResponse } from '../services/record-sharing.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';

const PAGE_RESPONSE = (content: PatientConsentResponse[]) => ({
  content,
  totalElements: content.length,
  totalPages: 1,
  page: 0,
  size: 20,
});

const makeConsent = (overrides: Partial<PatientConsentResponse> = {}): PatientConsentResponse => ({
  id: 'c1',
  patient: { id: 'p1', firstName: 'Jane', lastName: 'Doe' },
  fromHospital: { id: 'h1', name: 'Hosp A' },
  toHospital: { id: 'h2', name: 'Hosp B' },
  consentGiven: true,
  consentTimestamp: '',
  purpose: 'Testing',
  consentExpiration: null,
  consentType: 'TREATMENT',
  scope: null,
  ...overrides,
});

const MOCK_PATIENT: PatientResponse = {
  id: 'p1',
  firstName: 'Jane',
  lastName: 'Doe',
  email: 'jane@example.com',
  mrn: 'MRN-001',
  active: true,
};

const MOCK_HOSPITALS: HospitalResponse[] = [
  { id: 'h1', name: 'Hospital Alpha' } as HospitalResponse,
  { id: 'h2', name: 'Hospital Beta' } as HospitalResponse,
];

describe('ConsentManagementComponent', () => {
  let component: ConsentManagementComponent;
  let fixture: ComponentFixture<ConsentManagementComponent>;
  let sharingStub: jasmine.SpyObj<RecordSharingService>;
  let patientStub: jasmine.SpyObj<PatientService>;
  let hospitalStub: jasmine.SpyObj<HospitalService>;
  let toastStub: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    sharingStub = jasmine.createSpyObj('RecordSharingService', [
      'listConsents',
      'grantConsent',
      'revokeConsent',
    ]);
    patientStub = jasmine.createSpyObj('PatientService', ['list']);
    hospitalStub = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    toastStub = jasmine.createSpyObj('ToastService', ['success', 'error']);

    sharingStub.listConsents.and.returnValue(of(PAGE_RESPONSE([])));
    patientStub.list.and.returnValue(of([]));
    hospitalStub.list.and.returnValue(of(MOCK_HOSPITALS));
    hospitalStub.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'Hospital Alpha' } as HospitalResponse),
    );

    await TestBed.configureTestingModule({
      imports: [ConsentManagementComponent, TranslateModule.forRoot()],
      providers: [
        { provide: RecordSharingService, useValue: sharingStub },
        { provide: PatientService, useValue: patientStub },
        { provide: HospitalService, useValue: hospitalStub },
        { provide: ToastService, useValue: toastStub },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ConsentManagementComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('calls listConsents on init', () => {
    expect(sharingStub.listConsents).toHaveBeenCalledWith({ page: 0, size: 20 });
  });

  it('populates consents signal from response', () => {
    const consent = makeConsent();
    sharingStub.listConsents.and.returnValue(of(PAGE_RESPONSE([consent])));
    component.load();
    expect(component.consents()).toEqual([consent]);
    expect(component.totalElements()).toBe(1);
  });

  it('shows error toast when listConsents fails', () => {
    sharingStub.listConsents.and.returnValue(throwError(() => new Error('fail')));
    component.load();
    expect(toastStub.error).toHaveBeenCalledWith('CONSENT.ERRORS.LOAD_FAILED');
  });

  it('sets loadError signal when listConsents fails', () => {
    sharingStub.listConsents.and.returnValue(throwError(() => new Error('fail')));
    component.load();
    expect(component.loadError()).toBeTruthy();
    expect(component.loading()).toBeFalse();
  });

  it('clears loadError on successful reload', () => {
    sharingStub.listConsents.and.returnValue(throwError(() => new Error('fail')));
    component.load();
    expect(component.loadError()).toBeTruthy();

    sharingStub.listConsents.and.returnValue(of(PAGE_RESPONSE([])));
    component.load();
    expect(component.loadError()).toBeNull();
  });

  it('renders error-state banner when loadError is set', () => {
    sharingStub.listConsents.and.returnValue(throwError(() => new Error('fail')));
    component.load();
    fixture.detectChanges();

    const errorEl = fixture.nativeElement.querySelector('.error-state');
    expect(errorEl).toBeTruthy();

    const retryBtn = errorEl.querySelector('button');
    expect(retryBtn).toBeTruthy();
  });

  it('hides empty-state when loadError is set', () => {
    sharingStub.listConsents.and.returnValue(throwError(() => new Error('fail')));
    component.load();
    fixture.detectChanges();

    const emptyEl = fixture.nativeElement.querySelector('.empty-state');
    expect(emptyEl).toBeFalsy();
  });

  it('opens and resets grant form with toHospitalId locked', () => {
    component.grantForm.patientId = 'existing-id';
    component.selectedPatient.set(MOCK_PATIENT);
    component.openGrantForm();
    expect(component.showGrantForm()).toBeTrue();
    expect(component.grantForm.patientId).toBe('');
    expect(component.grantForm.consentType).toBe('TREATMENT');
    expect(component.grantForm.toHospitalId).toBe('h1');
    expect(component.selectedPatient()).toBeNull();
    expect(component.patientQuery()).toBe('');
  });

  it('cancelGrant hides the form', () => {
    component.showGrantForm.set(true);
    component.cancelGrant();
    expect(component.showGrantForm()).toBeFalse();
  });

  it('submitGrant shows required-fields toast when fields missing', () => {
    component.grantForm = { patientId: '', fromHospitalId: '', toHospitalId: '', purpose: '' };
    component.submitGrant();
    expect(toastStub.error).toHaveBeenCalledWith('CONSENT.ERRORS.REQUIRED_FIELDS');
    expect(sharingStub.grantConsent).not.toHaveBeenCalled();
  });

  it('submitGrant calls service and shows success toast on success', () => {
    const consent = makeConsent();
    sharingStub.grantConsent.and.returnValue(of(consent));
    component.grantForm = {
      patientId: 'p1',
      fromHospitalId: 'h1',
      toHospitalId: 'h2',
      purpose: 'Research',
      consentType: 'RESEARCH',
    };
    component.submitGrant();
    expect(sharingStub.grantConsent).toHaveBeenCalled();
    expect(toastStub.success).toHaveBeenCalledWith('CONSENT.GRANTED');
    expect(component.showGrantForm()).toBeFalse();
  });

  it('submitGrant shows error toast on failure', () => {
    sharingStub.grantConsent.and.returnValue(throwError(() => new Error('fail')));
    component.grantForm = {
      patientId: 'p1',
      fromHospitalId: 'h1',
      toHospitalId: 'h2',
      purpose: 'Research',
    };
    component.submitGrant();
    expect(toastStub.error).toHaveBeenCalledWith('CONSENT.ERRORS.GRANT_FAILED');
  });

  it('revoke skips when user cancels confirm', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    component.revoke(makeConsent());
    expect(sharingStub.revokeConsent).not.toHaveBeenCalled();
  });

  it('revoke calls service and reloads on confirm', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    sharingStub.revokeConsent.and.returnValue(of(undefined));
    component.revoke(makeConsent());
    expect(sharingStub.revokeConsent).toHaveBeenCalledWith('p1', 'h1', 'h2');
    expect(toastStub.success).toHaveBeenCalledWith('CONSENT.REVOKED');
  });

  it('revoke shows error toast on failure', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    sharingStub.revokeConsent.and.returnValue(throwError(() => new Error('fail')));
    component.revoke(makeConsent());
    expect(toastStub.error).toHaveBeenCalledWith('CONSENT.ERRORS.REVOKE_FAILED');
  });

  // ── Patient picker tests ──────────────────────────────────
  describe('patient picker', () => {
    it('selectPatient sets selectedPatient and grantForm.patientId', () => {
      component.selectPatient(MOCK_PATIENT);
      expect(component.selectedPatient()).toEqual(MOCK_PATIENT);
      expect(component.grantForm.patientId).toBe('p1');
      expect(component.patientDropdownOpen()).toBeFalse();
    });

    it('clearPatient resets selectedPatient and patientId', () => {
      component.selectPatient(MOCK_PATIENT);
      component.clearPatient();
      expect(component.selectedPatient()).toBeNull();
      expect(component.grantForm.patientId).toBe('');
    });

    it('patientInitials returns correct initials', () => {
      expect(component.patientInitials(MOCK_PATIENT)).toBe('JD');
    });

    it('onPatientQueryChange updates patientQuery signal', () => {
      component.onPatientQueryChange('Jan');
      expect(component.patientQuery()).toBe('Jan');
    });

    it('onPatientQueryChange clears suggestions for empty input', () => {
      component.patientSuggestions.set([MOCK_PATIENT]);
      component.patientDropdownOpen.set(true);
      component.onPatientQueryChange('');
      expect(component.patientSuggestions()).toEqual([]);
      expect(component.patientDropdownOpen()).toBeFalse();
    });

    it('renders patient chip when patient is selected', () => {
      component.selectPatient(MOCK_PATIENT);
      component.showGrantForm.set(true);
      fixture.detectChanges();
      const chip = fixture.nativeElement.querySelector('.picker-chip');
      expect(chip).toBeTruthy();
      expect(chip.textContent).toContain('Jane');
      expect(chip.textContent).toContain('Doe');
    });

    it('renders search input when no patient selected', () => {
      component.showGrantForm.set(true);
      fixture.detectChanges();
      const input = fixture.nativeElement.querySelector('.picker-input');
      expect(input).toBeTruthy();
    });
  });

  // ── Hospital dropdown tests ───────────────────────────────
  describe('hospital dropdowns', () => {
    it('loads hospitals on init for super admin', () => {
      expect(hospitalStub.getMyHospitalAsResponse).toHaveBeenCalled();
      expect(hospitalStub.list).toHaveBeenCalled();
      expect(component.hospitals().length).toBe(2);
    });

    it('sets currentHospital from getMyHospitalAsResponse', () => {
      expect(component.currentHospital()).toEqual(
        jasmine.objectContaining({ id: 'h1', name: 'Hospital Alpha' }),
      );
    });

    it('locks toHospitalId to currentHospital on openGrantForm', () => {
      component.openGrantForm();
      expect(component.grantForm.toHospitalId).toBe('h1');
    });

    it('fromHospitalOptions excludes the currentHospital', () => {
      const options = component.fromHospitalOptions();
      expect(options.some((h) => h.id === 'h1')).toBeFalse();
      expect(options.some((h) => h.id === 'h2')).toBeTrue();
    });

    it('renders locked To Hospital in grant form', () => {
      component.showGrantForm.set(true);
      fixture.detectChanges();
      const locked = fixture.nativeElement.querySelector('.locked-hospital');
      expect(locked).toBeTruthy();
      expect(locked.textContent).toContain('Hospital Alpha');
    });

    it('renders From Hospital select without current hospital', () => {
      component.showGrantForm.set(true);
      fixture.detectChanges();
      const select = fixture.nativeElement.querySelector('#fromHospital');
      expect(select).toBeTruthy();
      const options = select.querySelectorAll('option');
      // placeholder + filtered hospitals (only h2)
      const optionTexts = Array.from(options).map((o: any) => o.textContent.trim());
      expect(optionTexts.some((t: string) => t.includes('Hospital Alpha'))).toBeFalse();
      expect(optionTexts.some((t: string) => t.includes('Hospital Beta'))).toBeTrue();
    });

    it('falls back to own hospital when list() fails', () => {
      hospitalStub.list.and.returnValue(throwError(() => new Error('forbidden')));
      hospitalStub.getMyHospitalAsResponse.and.returnValue(
        of({ id: 'h1', name: 'My Hospital' } as HospitalResponse),
      );

      const fix2 = TestBed.createComponent(ConsentManagementComponent);
      fix2.componentInstance.ngOnInit();
      fix2.detectChanges();

      expect(fix2.componentInstance.hospitals().length).toBe(1);
      expect(fix2.componentInstance.hospitals()[0].name).toBe('My Hospital');
      expect(fix2.componentInstance.currentHospital()?.id).toBe('h1');
    });
  });

  describe('pagination', () => {
    it('prevPage does nothing on page 0', () => {
      component.currentPage.set(0);
      component.prevPage();
      expect(component.currentPage()).toBe(0);
      expect(sharingStub.listConsents).toHaveBeenCalledTimes(1); // only init call
    });

    it('prevPage decrements page and reloads', () => {
      component.currentPage.set(2);
      component.totalPages.set(5);
      component.prevPage();
      expect(component.currentPage()).toBe(1);
      expect(sharingStub.listConsents).toHaveBeenCalledTimes(2);
    });

    it('nextPage does nothing on last page', () => {
      component.currentPage.set(2);
      component.totalPages.set(3);
      component.nextPage();
      expect(component.currentPage()).toBe(2);
      expect(sharingStub.listConsents).toHaveBeenCalledTimes(1);
    });

    it('nextPage increments page and reloads', () => {
      component.currentPage.set(0);
      component.totalPages.set(3);
      component.nextPage();
      expect(component.currentPage()).toBe(1);
      expect(sharingStub.listConsents).toHaveBeenCalledTimes(2);
    });
  });

  describe('filteredConsents', () => {
    beforeEach(() => {
      const active = makeConsent({ consentGiven: true });
      const revoked = makeConsent({ id: 'c2', consentGiven: false });
      component.consents.set([active, revoked]);
    });

    it('returns all when no filter set', () => {
      component.filterActive.set('');
      expect(component.filteredConsents().length).toBe(2);
    });

    it('returns only active consents when filter is true', () => {
      component.filterActive.set('true');
      expect(component.filteredConsents().every((c) => c.consentGiven)).toBeTrue();
    });

    it('returns only revoked consents when filter is false', () => {
      component.filterActive.set('false');
      expect(component.filteredConsents().every((c) => !c.consentGiven)).toBeTrue();
    });
  });

  describe('isActive', () => {
    it('returns false for revoked consent', () => {
      expect(component.isActive(makeConsent({ consentGiven: false }))).toBeFalse();
    });

    it('returns true for active consent with no expiry', () => {
      expect(
        component.isActive(makeConsent({ consentGiven: true, consentExpiration: null })),
      ).toBeTrue();
    });

    it('returns false for expired consent', () => {
      const past = new Date(Date.now() - 1000).toISOString();
      expect(
        component.isActive(makeConsent({ consentGiven: true, consentExpiration: past })),
      ).toBeFalse();
    });

    it('returns true for future expiry', () => {
      const future = new Date(Date.now() + 999999).toISOString();
      expect(
        component.isActive(makeConsent({ consentGiven: true, consentExpiration: future })),
      ).toBeTrue();
    });
  });

  describe('consentTypeLabel', () => {
    it('returns -- for null', () => {
      expect(component.consentTypeLabel(null)).toBe('--');
    });

    it('replaces underscore with space', () => {
      expect(component.consentTypeLabel('ALL_PURPOSES')).toBe('ALL PURPOSES');
    });
  });

  describe('viewRecords', () => {
    it('navigates to shared-records with correct query params', () => {
      const router = TestBed.inject(Router);
      spyOn(router, 'navigate');
      const consent = makeConsent();
      component.viewRecords(consent);
      expect(router.navigate).toHaveBeenCalledWith(['/consent-management/shared-records'], {
        queryParams: {
          patientId: 'p1',
          fromHospitalId: 'h1',
          toHospitalId: 'h2',
        },
      });
    });
  });

  describe('scope checkboxes', () => {
    it('defaults to shareAll true', () => {
      expect(component.shareAll()).toBeTrue();
    });

    it('all scope selections default to false', () => {
      const selections = component.scopeSelections();
      expect(Object.values(selections).every((v) => v === false)).toBeTrue();
    });

    it('toggleShareAll(true) resets all selections', () => {
      component.toggleScope('ENCOUNTERS', true);
      component.toggleShareAll(true);
      expect(component.shareAll()).toBeTrue();
      expect(component.scopeSelections()['ENCOUNTERS']).toBeFalse();
    });

    it('toggleScope checks a domain and unchecks shareAll', () => {
      component.toggleScope('LAB_ORDERS', true);
      expect(component.scopeSelections()['LAB_ORDERS']).toBeTrue();
      expect(component.shareAll()).toBeFalse();
    });

    it('toggleScope unchecking all re-enables shareAll', () => {
      component.toggleScope('ALLERGIES', true);
      expect(component.shareAll()).toBeFalse();
      component.toggleScope('ALLERGIES', false);
      expect(component.shareAll()).toBeTrue();
    });

    it('openGrantForm resets scope to shareAll', () => {
      component.toggleScope('ENCOUNTERS', true);
      component.openGrantForm();
      expect(component.shareAll()).toBeTrue();
      expect(component.scopeSelections()['ENCOUNTERS']).toBeFalse();
    });

    it('submitGrant sends undefined scope when shareAll', () => {
      const consent = makeConsent();
      sharingStub.grantConsent.and.returnValue(of(consent));
      component.grantForm = {
        patientId: 'p1',
        fromHospitalId: 'h1',
        toHospitalId: 'h2',
        purpose: 'Test',
      };
      component.shareAll.set(true);
      component.submitGrant();
      const arg = sharingStub.grantConsent.calls.mostRecent().args[0];
      expect(arg.scope).toBeUndefined();
    });

    it('submitGrant sends comma-separated scope for selected domains', () => {
      const consent = makeConsent();
      sharingStub.grantConsent.and.returnValue(of(consent));
      component.grantForm = {
        patientId: 'p1',
        fromHospitalId: 'h1',
        toHospitalId: 'h2',
        purpose: 'Test',
      };
      component.shareAll.set(false);
      component.scopeSelections.set({
        ENCOUNTERS: true,
        TREATMENTS: false,
        PRESCRIPTIONS: true,
        LAB_ORDERS: false,
        LAB_RESULTS: false,
        ALLERGIES: false,
        PROBLEMS: false,
        SURGICAL_HISTORY: false,
        ADVANCE_DIRECTIVES: false,
      });
      component.submitGrant();
      const arg = sharingStub.grantConsent.calls.mostRecent().args[0];
      expect(arg.scope).toBe('ENCOUNTERS,PRESCRIPTIONS');
    });
  });
});
