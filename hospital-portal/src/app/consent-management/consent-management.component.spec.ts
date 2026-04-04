import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { ConsentManagementComponent } from './consent-management.component';
import { RecordSharingService, PatientConsentResponse } from '../services/record-sharing.service';
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

describe('ConsentManagementComponent', () => {
  let component: ConsentManagementComponent;
  let fixture: ComponentFixture<ConsentManagementComponent>;
  let sharingStub: jasmine.SpyObj<RecordSharingService>;
  let toastStub: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    sharingStub = jasmine.createSpyObj('RecordSharingService', [
      'listConsents',
      'grantConsent',
      'revokeConsent',
    ]);
    toastStub = jasmine.createSpyObj('ToastService', ['success', 'error']);

    sharingStub.listConsents.and.returnValue(of(PAGE_RESPONSE([])));

    await TestBed.configureTestingModule({
      imports: [ConsentManagementComponent, TranslateModule.forRoot()],
      providers: [
        { provide: RecordSharingService, useValue: sharingStub },
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

  it('opens and resets grant form', () => {
    component.grantForm.patientId = 'existing-id';
    component.openGrantForm();
    expect(component.showGrantForm()).toBeTrue();
    expect(component.grantForm.patientId).toBe('');
    expect(component.grantForm.consentType).toBe('TREATMENT');
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
});
