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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
    },
    {
      id: 'e2',
      encounterType: 'INPATIENT',
      status: 'COMPLETED',
      encounterDate: '2026-01-08',
      chiefComplaint: 'Fever',
      departmentName: 'Internal Medicine',
      staffName: 'Dr. Jones',
      hospitalId: 'h1',
      hospitalName: 'Hospital Alpha',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
    },
  ],
  surgicalHistory: [
    {
      id: 'sh1',
      procedureDisplay: 'Appendectomy',
      procedureDate: '2018-06-15',
      outcome: 'Successful',
      performedBy: 'Dr. Lee',
      hospitalId: 'h1',
      hospitalName: 'Hospital Alpha',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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
      hospitalId: 'h2',
      hospitalName: 'Hospital Beta',
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

  it('goBack navigates to consent-management', () => {
    component.goBack();
    expect(routerStub.navigate).toHaveBeenCalledWith(['/consent-management']);
  });

  describe('partitioned signal', () => {
    it('partitions encounters into to and from', () => {
      const p = component.partitioned()!;
      expect(p.encounters.to.length).toBe(1);
      expect(p.encounters.to[0].id).toBe('e1');
      expect(p.encounters.from.length).toBe(1);
      expect(p.encounters.from[0].id).toBe('e2');
    });

    it('places treatments belonging to toHospital in to array', () => {
      const p = component.partitioned()!;
      expect(p.treatments.to.length).toBe(1);
      expect(p.treatments.from.length).toBe(0);
    });

    it('places surgicalHistory from other hospital in from array', () => {
      const p = component.partitioned()!;
      expect(p.surgicalHistory.to.length).toBe(0);
      expect(p.surgicalHistory.from.length).toBe(1);
      expect(p.surgicalHistory.from[0].procedureDisplay).toBe('Appendectomy');
    });

    it('handles empty arrays', () => {
      const p = component.partitioned()!;
      expect(p.advanceDirectives.to.length).toBe(0);
      expect(p.advanceDirectives.from.length).toBe(0);
    });

    it('returns null when record is null', () => {
      component.record.set(null);
      expect(component.partitioned()).toBeNull();
    });
  });

  describe('DOM rendering', () => {
    it('renders patient banner with name', () => {
      const name = fixture.nativeElement.querySelector('.patient-name');
      expect(name.textContent).toContain('Jane');
      expect(name.textContent).toContain('Doe');
    });

    it('renders consent strip with hospital names', () => {
      const strip = fixture.nativeElement.querySelector('.consent-strip');
      expect(strip.textContent).toContain('Hospital Beta');
    });

    it('renders comparison sections for all categories', () => {
      const sections = fixture.nativeElement.querySelectorAll('.comparison-section');
      expect(sections.length).toBe(13);
    });

    it('renders encounter cards in both columns', () => {
      const sections = fixture.nativeElement.querySelectorAll('.comparison-section');
      const encounterSection = sections[0];
      const columns = encounterSection.querySelectorAll('.hospital-column');
      expect(columns.length).toBe(2);

      const toCards = columns[0].querySelectorAll('.record-card');
      expect(toCards.length).toBe(1);
      expect(toCards[0].textContent).toContain('Headache');

      const fromCards = columns[1].querySelectorAll('.record-card');
      expect(fromCards.length).toBe(1);
      expect(fromCards[0].textContent).toContain('Fever');
    });

    it('marks from-column cards with from-card class', () => {
      const fromCards = fixture.nativeElement.querySelectorAll('.from-card');
      expect(fromCards.length).toBeGreaterThan(0);
    });

    it('shows empty-column when no data in a column', () => {
      const empties = fixture.nativeElement.querySelectorAll('.empty-column');
      expect(empties.length).toBeGreaterThan(0);
    });
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

      expect(fix.componentInstance.error()).toBe('SHARED_RECORDS.ERRORS.MISSING_PARAMS');
      expect(fix.componentInstance.loading()).toBeFalse();
      expect(sharingStub.getAggregatedRecord).toHaveBeenCalledTimes(1);
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

  describe('uniqueFromNames', () => {
    it('returns unique hospital names joined by comma', () => {
      const items = [
        { hospitalName: 'Hospital Alpha' },
        { hospitalName: 'Hospital Alpha' },
        { hospitalName: 'Hospital Beta' },
      ];
      expect(component.uniqueFromNames(items)).toBe('Hospital Alpha, Hospital Beta');
    });

    it('returns empty string when all hospitalNames are undefined', () => {
      expect(component.uniqueFromNames([{}, {}])).toBe('');
    });

    it('returns empty string for empty array', () => {
      expect(component.uniqueFromNames([])).toBe('');
    });

    it('returns single name when all items share one hospital', () => {
      const items = [{ hospitalName: 'Hospital Alpha' }, { hospitalName: 'Hospital Alpha' }];
      expect(component.uniqueFromNames(items)).toBe('Hospital Alpha');
    });
  });

  describe('from-column headers', () => {
    it('renders source hospital name in from-column header when records have hospitalName', () => {
      const fromHeaders = fixture.nativeElement.querySelectorAll('.from-header');
      const encounterHeader = fromHeaders[0].textContent as string;
      expect(encounterHeader).toContain('Hospital Alpha');
    });
  });

  describe('lab result fields', () => {
    it('shows labTestCode when present', async () => {
      sharingStub.getAggregatedRecord.and.returnValue(
        of({
          ...MOCK_RECORD,
          labResults: [
            {
              id: 'lr2',
              labTestName: 'CBC',
              labTestCode: 'CBC-TEST',
              resultValue: '5.3',
              resultUnit: 'g/dL',
              resultDate: '2026-01-11',
              hospitalId: 'h1',
              hospitalName: 'Hospital Alpha',
            },
          ],
        }),
      );

      await TestBed.resetTestingModule();
      await TestBed.configureTestingModule({
        imports: [SharedRecordsViewerComponent, TranslateModule.forRoot()],
        providers: [
          { provide: RecordSharingService, useValue: sharingStub },
          { provide: Router, useValue: routerStub },
          {
            provide: ActivatedRoute,
            useValue: createRouteSnapshot({ patientId: 'p1', toHospitalId: 'h2' }),
          },
        ],
      }).compileComponents();

      const fix = TestBed.createComponent(SharedRecordsViewerComponent);
      fix.detectChanges();
      expect(fix.nativeElement.textContent).toContain('CBC-TEST');
    });
  });

  describe('legacy allergy fallback', () => {
    it('renders legacy allergy text when allergiesDetailed is empty', async () => {
      sharingStub.getAggregatedRecord.and.returnValue(
        of({
          ...MOCK_RECORD,
          allergiesDetailed: [],
          allergies: 'peanuts, garlic',
        }),
      );

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

      const body = fix.nativeElement.textContent;
      expect(body).toContain('peanuts, garlic');
    });
  });
});
