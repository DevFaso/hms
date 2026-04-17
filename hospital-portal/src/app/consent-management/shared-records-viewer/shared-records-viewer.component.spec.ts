import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';
import { SharedRecordsViewerComponent } from './shared-records-viewer.component';
import { RecordSharingService, PatientRecord } from '../../services/record-sharing.service';

const MOCK_RECORD: PatientRecord = {
  patientId: 'p1',
  firstName: 'Jane',
  lastName: 'Doe',
  middleName: 'M',
  dateOfBirth: '1990-05-15',
  gender: 'Female',
  bloodType: 'A+',
  email: 'jane@example.com',
  phoneNumberPrimary: '+1234567890',
  fromHospitalId: 'h1',
  fromHospitalName: 'Hospital Alpha',
  toHospitalId: 'h2',
  toHospitalName: 'Hospital Beta',
  consentPurpose: 'Treatment',
  encounters: [
    {
      id: 'e1',
      encounterType: 'OUTPATIENT',
      status: 'COMPLETED',
      encounterDate: '2026-01-10',
      chiefComplaint: 'Headache',
      departmentName: 'Neurology',
      staffName: 'Dr. Smith',
      notes: 'Patient reports recurring headaches.',
    },
  ],
  treatments: [
    {
      id: 't1',
      treatmentName: 'IV Therapy',
      performedAt: '2026-01-10',
      outcome: 'Improved',
      staffFullName: 'Dr. Smith',
      notes: 'Administered saline drip',
    },
  ],
  prescriptions: [
    {
      id: 'rx1',
      medicationDisplayName: 'Ibuprofen 400mg',
      dosage: '400mg',
      frequency: 'Twice daily',
      duration: '7 days',
      status: 'ACTIVE',
      staffFullName: 'Dr. Smith',
      createdAt: '2026-01-10',
    },
  ],
  labOrders: [
    {
      id: 'lo1',
      labTestName: 'Complete Blood Count',
      labOrderCode: 'CBC-001',
      status: 'COMPLETED',
      priority: 'ROUTINE',
      orderDatetime: '2026-01-10',
    },
  ],
  labResults: [
    {
      id: 'lr1',
      labTestName: 'Hemoglobin',
      resultValue: '14.2',
      resultUnit: 'g/dL',
      resultDate: '2026-01-11',
      severityFlag: undefined,
      labOrderCode: 'CBC-001',
    },
  ],
  allergiesDetailed: [
    {
      id: 'a1',
      allergenDisplay: 'Penicillin',
      category: 'MEDICATION',
      severity: 'SEVERE',
      reaction: 'Anaphylaxis',
      verificationStatus: 'CONFIRMED',
    },
  ],
  problems: [
    {
      id: 'pr1',
      problemDisplay: 'Migraine',
      status: 'ACTIVE',
      severity: 'MODERATE',
      chronic: true,
      onsetDate: '2020-03-01',
    },
  ],
  surgicalHistory: [
    {
      id: 'sh1',
      procedureDisplay: 'Appendectomy',
      procedureDate: '2018-06-15',
      outcome: 'Successful',
      performedBy: 'Dr. Lee',
    },
  ],
  advanceDirectives: [],
  vitalSigns: [
    {
      id: 'vs1',
      systolicBpMmHg: 120,
      diastolicBpMmHg: 80,
      heartRateBpm: 72,
      temperatureCelsius: 36.6,
      respiratoryRateBpm: 16,
      spo2Percent: 98,
      bloodGlucoseMgDl: 95,
      weightKg: 70,
      recordedByName: 'Nurse Joy',
      recordedAt: '2026-01-10T10:00:00',
      clinicallySignificant: false,
    },
  ],
  immunizations: [
    {
      id: 'imm1',
      vaccineDisplay: 'Influenza Vaccine',
      targetDisease: 'Influenza',
      administrationDate: '2025-10-01',
      doseNumber: 1,
      totalDosesInSeries: 1,
      route: 'Intramuscular',
      site: 'Left Deltoid',
      manufacturer: 'Pfizer',
      lotNumber: 'LOT-12345',
      status: 'COMPLETED',
      administeredByName: 'Nurse Joy',
    },
  ],
  insurances: [
    {
      id: 'ins1',
      providerName: 'Blue Cross',
      policyNumber: 'POL-9999',
      groupNumber: 'GRP-100',
      subscriberName: 'Jane Doe',
      subscriberRelationship: 'Self',
      effectiveDate: '2025-01-01',
      primary: true,
    },
  ],
  encounterHistory: [
    {
      id: 'eh1',
      encounterId: 'e1',
      changeType: 'STATUS_CHANGE',
      changedBy: 'Dr. Smith',
      changedAt: '2026-01-10T12:00:00',
      status: 'COMPLETED',
      encounterType: 'OUTPATIENT',
    },
  ],
};

function createRouteSnapshot(params: Record<string, string | null>) {
  return {
    snapshot: {
      queryParamMap: convertToParamMap(
        Object.fromEntries(Object.entries(params).filter(([, v]) => v !== null)),
      ),
    },
  };
}

describe('SharedRecordsViewerComponent', () => {
  let component: SharedRecordsViewerComponent;
  let fixture: ComponentFixture<SharedRecordsViewerComponent>;
  let sharingStub: jasmine.SpyObj<RecordSharingService>;
  let routerStub: jasmine.SpyObj<Router>;

  beforeEach(async () => {
    sharingStub = jasmine.createSpyObj('RecordSharingService', [
      'getPatientRecord',
      'getAggregatedRecord',
    ]);
    routerStub = jasmine.createSpyObj('Router', ['navigate']);
    sharingStub.getAggregatedRecord.and.returnValue(of(MOCK_RECORD));

    await TestBed.configureTestingModule({
      imports: [SharedRecordsViewerComponent, TranslateModule.forRoot()],
      providers: [
        { provide: RecordSharingService, useValue: sharingStub },
        { provide: Router, useValue: routerStub },
        {
          provide: ActivatedRoute,
          useValue: createRouteSnapshot({
            patientId: 'p1',
            toHospitalId: 'h2',
          }),
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(SharedRecordsViewerComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('calls getAggregatedRecord with query params on init', () => {
    expect(sharingStub.getAggregatedRecord).toHaveBeenCalledWith('p1', 'h2');
  });

  it('sets record signal after successful load', () => {
    expect(component.record()).toEqual(MOCK_RECORD);
    expect(component.loading()).toBeFalse();
    expect(component.error()).toBeNull();
  });

  it('computes patient name correctly', () => {
    expect(component.patientName()).toBe('Jane M Doe');
  });

  it('computes patient initials', () => {
    expect(component.patientInitials()).toBe('JD');
  });

  it('computes tab counts from record', () => {
    const counts = component.tabCounts();
    expect(counts['encounters']).toBe(1);
    expect(counts['treatments']).toBe(1);
    expect(counts['prescriptions']).toBe(1);
    expect(counts['labOrders']).toBe(1);
    expect(counts['labResults']).toBe(1);
    expect(counts['allergies']).toBe(1);
    expect(counts['problems']).toBe(1);
    expect(counts['surgicalHistory']).toBe(1);
    expect(counts['advanceDirectives']).toBe(0);
    expect(counts['vitalSigns']).toBe(1);
    expect(counts['immunizations']).toBe(1);
    expect(counts['insurances']).toBe(1);
    expect(counts['encounterHistory']).toBe(1);
  });

  it('defaults to encounters tab', () => {
    expect(component.activeTab()).toBe('encounters');
  });

  it('setTab changes active tab', () => {
    component.setTab('prescriptions');
    expect(component.activeTab()).toBe('prescriptions');
  });

  it('goBack navigates to consent-management', () => {
    component.goBack();
    expect(routerStub.navigate).toHaveBeenCalledWith(['/consent-management']);
  });

  it('renders patient banner with name', () => {
    const name = fixture.nativeElement.querySelector('.patient-name');
    expect(name.textContent).toContain('Jane');
    expect(name.textContent).toContain('Doe');
  });

  it('renders consent strip with hospital names', () => {
    const strip = fixture.nativeElement.querySelector('.consent-strip');
    expect(strip.textContent).toContain('Hospital Alpha');
    expect(strip.textContent).toContain('Hospital Beta');
  });

  it('renders tab buttons', () => {
    const tabs = fixture.nativeElement.querySelectorAll('.tab-btn');
    expect(tabs.length).toBe(13);
  });

  it('renders encounter cards when encounters tab is active', () => {
    const cards = fixture.nativeElement.querySelectorAll('.record-card');
    expect(cards.length).toBe(1);
    expect(cards[0].textContent).toContain('OUTPATIENT');
    expect(cards[0].textContent).toContain('Headache');
  });

  it('shows empty state when tab has no data', () => {
    component.setTab('advanceDirectives');
    fixture.detectChanges();
    const empty = fixture.nativeElement.querySelector('.empty-tab');
    expect(empty).toBeTruthy();
  });

  describe('error handling', () => {
    it('sets error on API failure', async () => {
      sharingStub.getAggregatedRecord.and.returnValue(throwError(() => new Error('fail')));

      await TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [SharedRecordsViewerComponent, TranslateModule.forRoot()],
        providers: [
          { provide: RecordSharingService, useValue: sharingStub },
          { provide: Router, useValue: routerStub },
          {
            provide: ActivatedRoute,
            useValue: createRouteSnapshot({
              patientId: 'p1',
              toHospitalId: 'h2',
            }),
          },
        ],
      }).compileComponents();

      const fix = TestBed.createComponent(SharedRecordsViewerComponent);
      fix.detectChanges();

      expect(fix.componentInstance.error()).toBe('SHARED_RECORDS.ERRORS.LOAD_FAILED');
      expect(fix.componentInstance.loading()).toBeFalse();
    });

    it('sets error when query params are missing', async () => {
      await TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [SharedRecordsViewerComponent, TranslateModule.forRoot()],
        providers: [
          { provide: RecordSharingService, useValue: sharingStub },
          { provide: Router, useValue: routerStub },
          {
            provide: ActivatedRoute,
            useValue: createRouteSnapshot({
              patientId: null,
              toHospitalId: null,
            }),
          },
        ],
      }).compileComponents();

      const fix = TestBed.createComponent(SharedRecordsViewerComponent);
      fix.detectChanges();

      expect(fix.componentInstance.error()).toBe('Missing required parameters');
      expect(fix.componentInstance.loading()).toBeFalse();
      expect(sharingStub.getAggregatedRecord).toHaveBeenCalledTimes(1); // only first test call
    });
  });

  describe('severityClass', () => {
    it('returns severity-high for SEVERE', () => {
      expect(component.severityClass('SEVERE')).toBe('severity-high');
    });

    it('returns severity-medium for MODERATE', () => {
      expect(component.severityClass('MODERATE')).toBe('severity-medium');
    });

    it('returns severity-low for MILD', () => {
      expect(component.severityClass('MILD')).toBe('severity-low');
    });

    it('returns empty for unknown', () => {
      expect(component.severityClass('UNKNOWN')).toBe('');
    });

    it('returns empty for undefined', () => {
      expect(component.severityClass(undefined)).toBe('');
    });
  });

  describe('statusClass', () => {
    it('returns status-success for COMPLETED', () => {
      expect(component.statusClass('COMPLETED')).toBe('status-success');
    });

    it('returns status-pending for PENDING', () => {
      expect(component.statusClass('PENDING')).toBe('status-pending');
    });

    it('returns status-neutral for CANCELLED', () => {
      expect(component.statusClass('CANCELLED')).toBe('status-neutral');
    });

    it('returns empty for unknown', () => {
      expect(component.statusClass('SOMETHING_ELSE')).toBe('');
    });

    it('returns empty for undefined', () => {
      expect(component.statusClass(undefined)).toBe('');
    });
  });

  describe('tab content rendering', () => {
    it('renders treatment cards', () => {
      component.setTab('treatments');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('IV Therapy');
    });

    it('renders prescription cards', () => {
      component.setTab('prescriptions');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Ibuprofen 400mg');
    });

    it('renders lab order cards', () => {
      component.setTab('labOrders');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Complete Blood Count');
    });

    it('renders lab result cards', () => {
      component.setTab('labResults');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('14.2');
    });

    it('renders allergy cards with severity', () => {
      component.setTab('allergies');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Penicillin');
    });

    it('renders problem cards with chronic badge', () => {
      component.setTab('problems');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Migraine');
    });

    it('renders surgical history cards', () => {
      component.setTab('surgicalHistory');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Appendectomy');
    });

    it('renders vital sign cards with measurements', () => {
      component.setTab('vitalSigns');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('120');
      expect(cards[0].textContent).toContain('80');
      expect(cards[0].textContent).toContain('72');
    });

    it('renders immunization cards', () => {
      component.setTab('immunizations');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Influenza Vaccine');
      expect(cards[0].textContent).toContain('Pfizer');
    });

    it('renders insurance cards', () => {
      component.setTab('insurances');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('Blue Cross');
      expect(cards[0].textContent).toContain('POL-9999');
    });

    it('renders encounter history cards', () => {
      component.setTab('encounterHistory');
      fixture.detectChanges();
      const cards = fixture.nativeElement.querySelectorAll('.record-card');
      expect(cards.length).toBe(1);
      expect(cards[0].textContent).toContain('STATUS_CHANGE');
      expect(cards[0].textContent).toContain('Dr. Smith');
    });
  });
});
