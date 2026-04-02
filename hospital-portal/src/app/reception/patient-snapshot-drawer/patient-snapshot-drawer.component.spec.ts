import { ComponentFixture, TestBed } from '@angular/core/testing';
import { PatientSnapshotDrawerComponent } from './patient-snapshot-drawer.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReceptionService } from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';

describe('PatientSnapshotDrawerComponent', () => {
  let component: PatientSnapshotDrawerComponent;
  let fixture: ComponentFixture<PatientSnapshotDrawerComponent>;

  const mockSnapshot = {
    fullName: 'John Doe',
    mrn: 'MRN001',
    dob: '1990-01-01',
    phone: '555-0100',
    email: 'john@example.com',
    address: '123 Main St',
    alerts: {
      incompleteDemographics: false,
      missingInsurance: false,
      expiredInsurance: false,
      noPrimaryInsurance: false,
      outstandingBalance: false,
    },
    insurance: {
      insuranceId: 'ins-1',
      primaryPayer: 'Acme Insurance',
      policyNumber: 'POL-123',
      expiresOn: '2027-12-31',
      expired: false,
      hasActiveCoverage: true,
      verifiedAt: null,
      verifiedBy: null,
    },
    billing: {
      openInvoiceCount: 0,
      totalBalanceDue: 0,
    },
  };

  const mockReceptionService = {
    getPatientSnapshot: () => of(mockSnapshot),
    attestEligibility: () => of({}),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [PatientSnapshotDrawerComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PatientSnapshotDrawerComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load snapshot when patientId changes', () => {
    component.patientId = 'p1';
    component.ngOnChanges({
      patientId: {
        currentValue: 'p1',
        previousValue: null,
        firstChange: true,
        isFirstChange: () => true,
      },
    });
    expect(component.snapshot()).toBeTruthy();
    expect(component.snapshot()?.fullName).toBe('John Doe');
  });

  it('should emit panelClosed', () => {
    spyOn(component.panelClosed, 'emit');
    component.panelClosed.emit();
    expect(component.panelClosed.emit).toHaveBeenCalled();
  });

  it('should open attest modal', () => {
    component.openAttestModal();
    expect(component.showAttestModal()).toBe(true);
    expect(component.attestNotes()).toBe('');
  });
});
