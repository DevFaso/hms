import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject } from 'rxjs';

import { LabComponent } from './lab';
import {
  LabService,
  LabOrderResponse,
  LabResultResponse,
  LabSpecimen,
} from '../services/lab.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ProfileService } from '../services/profile.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';
import { PrintLabelService } from '../services/print-label.service';
import { roleContextStub } from '../testing/role-context.stub';
import {
  LAB_ORDER_STATUSES,
  isLabOrderFinished,
  isLabOrderOpen,
  labOrderState,
} from './shared/lab-order-state';

/**
 * Laboratoire → Demandes: one status vocabulary, the prescriber and hospital
 * columns, and a drawer that shows the validated result, the order's context
 * and its recorded timeline.
 */
describe('LabComponent — lab requests list and drawer', () => {
  const HOSPITAL_A = 'hosp-a';
  let fixture: ComponentFixture<LabComponent>;
  let component: LabComponent;
  let labService: jasmine.SpyObj<LabService>;
  let host: HTMLElement;

  function order(overrides: Partial<LabOrderResponse>): LabOrderResponse {
    return {
      id: 'o-1',
      labOrderCode: 'LAB-000031',
      patientId: 'p-1',
      patientFullName: 'Aminata Diallo',
      hospitalId: HOSPITAL_A,
      hospitalName: 'Hôpital A',
      performingHospitalId: null,
      performingHospitalName: null,
      labTestName: 'Glycémie à jeun',
      labTestCode: 'GLU',
      orderDatetime: '2026-11-12T08:00:00',
      status: 'ORDERED',
      clinicalIndication: 'Polyurie',
      medicalNecessityNote: '',
      notes: '',
      primaryDiagnosisCode: 'R35',
      additionalDiagnosisCodes: [],
      orderChannel: 'PORTAL',
      priority: 'ROUTINE',
      orderingStaffName: 'Dr Awa Traoré',
      patientMrn: 'MRN-0042',
      patientDateOfBirth: '1990-04-02',
      createdAt: '2026-11-12T08:00:00',
      updatedAt: '2026-11-12T08:00:00',
      ...overrides,
    };
  }

  function result(overrides: Partial<LabResultResponse>): LabResultResponse {
    return {
      id: 'r-1',
      labOrderId: 'o-1',
      labOrderCode: 'LAB-000031',
      patientId: 'p-1',
      patientFullName: 'Aminata Diallo',
      hospitalName: 'Hôpital A',
      labTestName: 'Glycémie à jeun',
      resultValue: '7.8',
      resultUnit: 'mmol/L',
      resultDate: '2026-11-12T10:00:00',
      notes: '',
      referenceRanges: [],
      gradedReferenceRange: {
        minValue: 3.9,
        maxValue: 6.1,
        unit: 'mmol/L',
        ageMin: null,
        ageMax: null,
        gender: null,
        notes: null,
      },
      severityFlag: 'HIGH',
      acknowledged: false,
      acknowledgedAt: null,
      acknowledgedBy: null,
      released: true,
      releasedAt: '2026-11-12T10:30:00',
      releasedByFullName: 'Issa Kaboré',
      signedAt: null,
      signedBy: null,
      signatureValue: null,
      signatureNotes: null,
      createdAt: '2026-11-12T10:00:00',
      updatedAt: '2026-11-12T10:30:00',
      ...overrides,
    };
  }

  async function setup(
    orders: LabOrderResponse[],
    opts: {
      results?: LabResultResponse[];
      specimens?: LabSpecimen[];
      detail?: LabOrderResponse;
    } = {},
  ) {
    labService = jasmine.createSpyObj<LabService>('LabService', [
      'listOrders',
      'listTestDefinitions',
      'listPerformingLabs',
      'listSpecimens',
      'getOrder',
      'listOrderResults',
    ]);
    labService.listOrders.and.returnValue(of(orders));
    labService.listTestDefinitions.and.returnValue(of([]));
    labService.listPerformingLabs.and.returnValue(of([]));
    labService.listSpecimens.and.returnValue(of(opts.specimens ?? []));
    labService.getOrder.and.callFake((id: string) =>
      of(opts.detail ?? orders.find((o) => o.id === id) ?? order({ id })),
    );
    labService.listOrderResults.and.returnValue(of(opts.results ?? []));

    const hospitalService = jasmine.createSpyObj<HospitalService>('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalService.getMyHospitalAsResponse.and.returnValue(
      of({ id: HOSPITAL_A, name: 'Hôpital A' } as unknown as HospitalResponse),
    );
    const patientService = jasmine.createSpyObj<PatientService>('PatientService', ['list']);
    patientService.list.and.returnValue(of([]));
    const profileService = jasmine.createSpyObj<ProfileService>('ProfileService', [
      'getAssignments',
    ]);
    profileService.getAssignments.and.returnValue(of([]));
    const auth = jasmine.createSpyObj<AuthService>('AuthService', ['hasAnyRole', 'getUserProfile']);
    auth.hasAnyRole.and.returnValue(false);
    auth.getUserProfile.and.returnValue(
      null as unknown as ReturnType<AuthService['getUserProfile']>,
    );

    await TestBed.configureTestingModule({
      imports: [LabComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: LabService, useValue: labService },
        { provide: HospitalService, useValue: hospitalService },
        { provide: PatientService, useValue: patientService },
        { provide: ProfileService, useValue: profileService },
        { provide: ToastService, useValue: jasmine.createSpyObj('ToastService', ['error']) },
        { provide: PrintLabelService, useValue: {} },
        { provide: AuthService, useValue: auth },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: HOSPITAL_A,
            roles: ['ROLE_DOCTOR'],
          }),
        },
      ],
    }).compileComponents();

    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(
      'en',
      {
        COMMON: { COLON: ':' },
        LAB: {
          AGE_YEARS: '{{count}} years',
          AGE_MONTHS: '{{count}} months',
          AGE_DAYS: '{{count}} days',
          AGE_DAY: '{{count}} day',
          AGE_MONTH: '{{count}} month',
          TAT_MINUTES: '{{minutes}} min',
          TAT_HOURS_MINUTES: '{{hours}} h {{minutes}} min',
          TAT_DAYS_HOURS: '{{days}} d {{hours}} h',
          STATE: { AWAITING_VALIDATION: 'Awaiting validation', COMPLETED: 'Completed' },
        },
      },
      true,
    );
    translate.use('en');

    fixture = TestBed.createComponent(LabComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
    host = fixture.nativeElement as HTMLElement;
  }

  function open(o: LabOrderResponse): void {
    component.viewOrder(o);
    fixture.detectChanges();
  }

  const q = (testId: string) => host.querySelector(`[data-testid="${testId}"]`);

  describe('one status vocabulary', () => {
    it('maps every backend status to a state, and only COMPLETED to finished', () => {
      for (const status of LAB_ORDER_STATUSES) {
        expect(labOrderState(status)).withContext(status).not.toBeNull();
      }
      expect(isLabOrderFinished('RESULTED')).toBeFalse();
      expect(isLabOrderFinished('VERIFIED')).toBeFalse();
      expect(isLabOrderOpen('RESULTED')).toBeTrue();
      expect(isLabOrderFinished('COMPLETED')).toBeTrue();
    });

    it('reads LAB-000015 (COMPLETED) and LAB-000031 (RESULTED) as two different states', async () => {
      await setup([
        order({ id: 'o-15', labOrderCode: 'LAB-000015', status: 'COMPLETED' }),
        order({ id: 'o-31', labOrderCode: 'LAB-000031', status: 'RESULTED' }),
      ]);
      expect(q('lab-state-o-15')?.textContent?.trim()).toBe('Completed');
      expect(q('lab-state-o-31')?.textContent?.trim()).toBe('Awaiting validation');
      expect(component.stats().completed).toBe(1);
      expect(component.stats().awaitingValidation).toBe(1);
      expect(component.stats().pending).toBe(1);
    });
  });

  describe('list columns', () => {
    it('shows the prescriber on every row', async () => {
      await setup([order({ id: 'o-1' }), order({ id: 'o-2', orderingStaffName: null })]);
      expect(q('lab-prescriber-o-1')?.textContent?.trim()).toBe('Dr Awa Traoré');
      expect(q('lab-prescriber-o-2')?.textContent?.trim()).toBe('—');
    });

    it('hides the hospital column for a single-hospital list', async () => {
      await setup([order({ id: 'o-1' }), order({ id: 'o-2' })]);
      expect(component.showHospitalColumn()).toBeFalse();
      expect(q('lab-hospital-column')).toBeNull();
    });

    it('keeps it when the list spans two hospitals, or an order was routed', async () => {
      await setup([order({ id: 'o-1' }), order({ id: 'o-2', hospitalId: 'hosp-b' })]);
      expect(q('lab-hospital-column')).not.toBeNull();

      TestBed.resetTestingModule();
      await setup([order({ id: 'o-1', performingHospitalId: 'lab-b' })]);
      expect(component.showHospitalColumn()).toBeTrue();
    });
  });

  describe('drawer: the result', () => {
    it('keeps B results and specimens when A fails after B succeeds', async () => {
      const first = order({ status: 'COMPLETED' });
      const second = order({ id: 'o-2', status: 'COMPLETED' });
      await setup([first, second]);
      const firstResults = new Subject<LabResultResponse[]>();
      const secondResults = new Subject<LabResultResponse[]>();
      const firstSpecimens = new Subject<LabSpecimen[]>();
      const secondSpecimens = new Subject<LabSpecimen[]>();
      labService.listOrderResults.and.callFake((id) =>
        id === first.id ? firstResults : secondResults,
      );
      labService.listSpecimens.and.callFake((id) =>
        id === first.id ? firstSpecimens : secondSpecimens,
      );
      open(first);
      open(second);
      const currentResults = [result({ labOrderId: second.id })];
      secondResults.next(currentResults);
      secondSpecimens.next([{ id: 'specimen-b', labOrderId: second.id } as LabSpecimen]);
      firstResults.error(new Error('stale results'));
      firstSpecimens.error(new Error('stale specimens'));
      fixture.detectChanges();

      expect(component.selectedOrder()?.id).toBe(second.id);
      expect(component.detailResults()).toEqual(currentResults);
      expect(component.detailSpecimens().map((s) => s.id)).toEqual(['specimen-b']);
      expect(component.detailResultsLoading()).toBeFalse();
      expect(q('detail-result-r-1')).not.toBeNull();
    });

    it('ignores stale detail, result and specimen successes after reopening the same order', async () => {
      const current = order({ status: 'COMPLETED' });
      await setup([current]);
      const oldDetail = new Subject<LabOrderResponse>();
      const oldResults = new Subject<LabResultResponse[]>();
      const oldSpecimens = new Subject<LabSpecimen[]>();
      const newResults = [result({})];
      labService.getOrder.and.returnValues(oldDetail, of(current));
      labService.listOrderResults.and.returnValues(oldResults, of(newResults));
      labService.listSpecimens.and.returnValues(oldSpecimens, of([]));
      open(current);
      component.closeDetail();
      expect(component.detailResultsLoading()).toBeFalse();
      open(current);
      oldDetail.next({ ...current, primaryDiagnosisLabel: 'stale' });
      oldResults.next([result({ id: 'stale' })]);
      oldSpecimens.next([{ id: 'stale' } as LabSpecimen]);

      expect(component.selectedOrder()).toEqual(current);
      expect(component.detailResults()).toEqual(newResults);
      expect(component.detailSpecimens()).toEqual([]);
    });

    it('resets loading for a non-result order and ignores errors after closing', async () => {
      const completed = order({ status: 'COMPLETED' });
      const pending = order({ id: 'pending' });
      await setup([completed, pending]);
      const oldResults = new Subject<LabResultResponse[]>();
      labService.listOrderResults.and.returnValue(oldResults);
      open(completed);
      expect(component.detailResultsLoading()).toBeTrue();
      open(pending);
      expect(component.detailResultsLoading()).toBeFalse();
      component.closeDetail();
      oldResults.error(new Error('closed request'));
      expect(component.selectedOrder()).toBeNull();
      expect(component.detailResults()).toEqual([]);
      expect(component.detailResultsLoading()).toBeFalse();
    });

    it('shows a validated value with the server grade and graded range, and links the result', async () => {
      const o = order({ status: 'COMPLETED' });
      await setup([o], { results: [result({})] });
      open(o);

      expect(labService.listOrderResults).toHaveBeenCalledWith('o-1');
      expect(q('detail-result-r-1')?.textContent).toMatch(/7[.,]8/);
      // The grade the server sent (LabResultMapper), labelled; never recomputed here.
      expect(q('detail-flag-r-1')?.classList.contains('severity-high')).toBeTrue();
      expect(q('detail-reference-r-1')?.textContent).toMatch(/3[.,]9 – 6[.,]1/);
      const link = q('detail-result-link-r-1') as HTMLAnchorElement;
      expect(link.getAttribute('href')).toBe('/lab-results?resultId=r-1');
      expect(q('detail-awaiting-validation')).toBeNull();
    });

    it('before validation says so and shows no value, even to the laboratory', async () => {
      const o = order({ status: 'RESULTED' });
      await setup([o], { results: [result({ released: false, releasedAt: null })] });
      open(o);

      expect(q('detail-awaiting-validation')).not.toBeNull();
      expect(q('detail-result-r-1')).toBeNull();
      expect(host.querySelector('.detail-panel')?.textContent).not.toMatch(/7[.,]8/);
    });

    it('never shows a result a released correction replaced', async () => {
      const o = order({ status: 'COMPLETED' });
      await setup([o], {
        results: [
          result({ id: 'r-old', resultValue: '9.9', supersededByResultId: 'r-new' }),
          result({ id: 'r-new', resultValue: '7.8', supersedesResultId: 'r-old' }),
        ],
      });
      open(o);
      expect(q('detail-result-r-old')).toBeNull();
      expect(q('detail-result-r-new')).not.toBeNull();
    });

    it('does not read results for an order still awaiting collection', async () => {
      const o = order({ status: 'ORDERED' });
      await setup([o]);
      open(o);
      expect(labService.listOrderResults).not.toHaveBeenCalled();
      expect(q('detail-result')).toBeNull();
    });
  });

  describe('drawer: the order context', () => {
    it('shows the prescriber, the MRN and age, and the diagnosis with its label', async () => {
      const o = order({ status: 'ORDERED' });
      await setup([o], { detail: order({ primaryDiagnosisLabel: 'Polyurie' }) });
      open(o);

      expect(q('detail-prescriber')?.textContent).toContain('Dr Awa Traoré');
      const identity = q('detail-patient-identity')?.textContent ?? '';
      expect(identity).toContain('MRN-0042');
      expect(identity).toMatch(/\d+ years/);
      expect(q('detail-diagnosis')?.textContent).toContain('R35 — Polyurie');
    });

    it('shows the code alone when no label is recorded', async () => {
      const o = order({});
      await setup([o]);
      open(o);
      expect(q('detail-diagnosis')?.textContent?.trim()).toContain('R35');
      expect(q('detail-diagnosis')?.textContent).not.toContain('—  ');
      expect(component.diagnosisText(o)).toBe('R35');
    });

    it('reads an infant age in months and a newborn in days', async () => {
      await setup([]);
      const today = new Date(2026, 10, 13);
      expect(component.patientAge('2025-11-01', today)).toBe('12 months');
      expect(component.patientAge('2026-11-03', today)).toBe('10 days');
      expect(component.patientAge('2026-11-12', today)).toBe('1 day');
      expect(component.patientAge('2026-10-13', today)).toBe('1 month');
      expect(component.patientAge('2026-11-13', today)).toBe('0 days');
      expect(component.patientAge('1990-04-02', today)).toBe('36 years');
      expect(component.patientAge(null, today)).toBe('');
    });

    it('identifies the ordering hospital beside a routed order MRN', async () => {
      const routed = order({ performingHospitalId: 'lab-b', hospitalName: 'Ordering hospital A' });
      await setup([routed]);
      open(routed);
      expect(q('detail-patient-identity')?.textContent).toContain('(Ordering hospital A)');
    });
  });

  describe('drawer: specimen and timeline', () => {
    const specimen: LabSpecimen = {
      id: 's-1',
      labOrderId: 'o-1',
      accessionNumber: 'ACC-1',
      barcodeValue: null,
      specimenType: 'BLOOD',
      collectedAt: '2026-11-12T08:15:00',
      collectedByName: 'Awa Nurse',
      receivedAt: '2026-11-12T08:40:00',
      receivedByName: 'Issa Tech',
      currentLocation: null,
      status: 'RECEIVED',
      notes: null,
      createdAt: '2026-11-12T08:15:00',
      updatedAt: '2026-11-12T08:40:00',
    };

    it('lists the recorded steps with who, and the turnaround to validation', async () => {
      const o = order({ status: 'COMPLETED' });
      await setup([o], { results: [result({})], specimens: [specimen] });
      open(o);

      expect(component.detailTimeline().map((st) => st.key)).toEqual([
        'LAB.TIMELINE.ORDERED',
        'LAB.TIMELINE.COLLECTED',
        'LAB.TIMELINE.RECEIVED',
        'LAB.TIMELINE.RESULTED',
        'LAB.TIMELINE.VALIDATED',
      ]);
      const text = q('detail-timeline')?.textContent ?? '';
      expect(text).toContain('Awa Nurse');
      expect(text).toContain('Issa Tech');
      expect(q('detail-specimen-type')).not.toBeNull();
      // 08:00 ordered, 10:30 validated.
      expect(q('detail-turnaround')?.textContent).toContain('2 h 30 min');
    });

    it('never invents a step nobody recorded, nor a turnaround before validation', async () => {
      const o = order({ status: 'COLLECTED' });
      await setup([o], {
        specimens: [{ ...specimen, receivedAt: null, receivedByName: null, status: 'COLLECTED' }],
      });
      open(o);

      expect(component.detailTimeline().map((st) => st.key)).toEqual([
        'LAB.TIMELINE.ORDERED',
        'LAB.TIMELINE.COLLECTED',
      ]);
      expect(component.detailTurnaround()).toBeNull();
      expect(q('detail-turnaround')).toBeNull();
    });

    it('follows the live specimen, not a rejected one', async () => {
      const o = order({ status: 'COLLECTED' });
      const rejected: LabSpecimen = {
        ...specimen,
        id: 's-0',
        status: 'REJECTED',
        createdAt: '2026-11-12T09:00:00',
        collectedByName: 'Rejected Collector',
      };
      await setup([o], { specimens: [specimen, rejected] });
      open(o);
      expect(component.currentDetailSpecimen()?.id).toBe('s-1');
    });
  });
});
