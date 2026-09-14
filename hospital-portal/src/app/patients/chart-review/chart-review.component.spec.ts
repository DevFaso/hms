import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';

import { ChartReviewComponent } from './chart-review.component';
import { ChartReview, ChartReviewService } from '../../services/chart-review.service';

describe('ChartReviewComponent', () => {
  let fixture: ComponentFixture<ChartReviewComponent>;
  let chartSpy: jasmine.SpyObj<ChartReviewService>;

  beforeEach(async () => {
    chartSpy = jasmine.createSpyObj<ChartReviewService>('ChartReviewService', ['getChartReview']);

    await TestBed.configureTestingModule({
      imports: [ChartReviewComponent, TranslateModule.forRoot()],
      providers: [{ provide: ChartReviewService, useValue: chartSpy }],
    }).compileComponents();

    fixture = TestBed.createComponent(ChartReviewComponent);
  });

  function setPatient(id: string | null | undefined, hospitalId?: string | null): void {
    fixture.componentRef.setInput('patientId', id);
    if (hospitalId !== undefined) {
      fixture.componentRef.setInput('hospitalId', hospitalId);
    }
    fixture.detectChanges();
  }

  function rootEl(): HTMLElement | null {
    return fixture.nativeElement.querySelector('[data-testid="chart-review"]');
  }

  it('hides the viewer entirely when patientId is missing', () => {
    setPatient(null);
    expect(rootEl()).toBeNull();
    expect(chartSpy.getChartReview).not.toHaveBeenCalled();
  });

  it('renders the timeline panel by default when data arrives', () => {
    chartSpy.getChartReview.and.returnValue(of(populatedChart()));

    setPatient('p-1');

    expect(chartSpy.getChartReview).toHaveBeenCalledOnceWith('p-1', undefined, undefined);
    expect(rootEl()?.dataset['state']).toBe('ready');
    expect(
      fixture.nativeElement.querySelector('[data-testid="chart-review-panel-timeline"]'),
    ).not.toBeNull();
  });

  it('forwards the hospital scope when supplied', () => {
    chartSpy.getChartReview.and.returnValue(of(emptyChart()));
    setPatient('p-2', 'h-77');
    expect(chartSpy.getChartReview).toHaveBeenCalledOnceWith('p-2', 'h-77', undefined);
  });

  it('switches panels when a different tab is clicked', () => {
    chartSpy.getChartReview.and.returnValue(of(populatedChart()));
    setPatient('p-3');

    const resultsTab = fixture.nativeElement.querySelector(
      '[data-testid="chart-review-tab-results"]',
    ) as HTMLButtonElement;
    expect(resultsTab).not.toBeNull();
    resultsTab.click();
    fixture.detectChanges();

    expect(
      fixture.nativeElement.querySelector('[data-testid="chart-review-panel-results"]'),
    ).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="chart-review-panel-timeline"]'),
    ).toBeNull();
  });

  it('shows the empty state when the aggregate is empty across every section', () => {
    chartSpy.getChartReview.and.returnValue(of(emptyChart()));
    setPatient('p-empty');
    expect(rootEl()?.dataset['state']).toBe('empty');
    expect(
      fixture.nativeElement.querySelector('[data-testid="chart-review-empty"]'),
    ).not.toBeNull();
  });

  it('shows the error state without throwing when the service fails', () => {
    chartSpy.getChartReview.and.returnValue(
      throwError(() => new Error('network')) as Observable<ChartReview>,
    );
    setPatient('p-err');
    expect(rootEl()?.dataset['state']).toBe('error');
    expect(
      fixture.nativeElement.querySelector('[data-testid="chart-review-error"]'),
    ).not.toBeNull();
  });

  it('cancels the previous request when patientId changes mid-flight (clinical-safety)', () => {
    const firstStream = new Subject<ChartReview>();
    chartSpy.getChartReview.and.returnValue(firstStream.asObservable());
    setPatient('p-slow');
    expect(firstStream.observed).toBeTrue();

    const secondChart = populatedChart('p-fast');
    chartSpy.getChartReview.and.returnValue(of(secondChart));
    setPatient('p-fast');

    firstStream.next(populatedChart('p-slow'));
    firstStream.complete();
    fixture.detectChanges();

    expect(firstStream.observed).toBeFalse();
    expect(rootEl()?.dataset['state']).toBe('ready');
    // Timeline rendered against the second (current) patient's payload
    const timelineTitles = fixture.nativeElement.querySelectorAll('.chart-review__timeline-title');
    expect(timelineTitles.length).toBeGreaterThan(0);
  });

  it('resolves each tab status through its own domain, not one shared pool', () => {
    // ChartReviewServiceImpl fills these four from four different entities —
    // Encounter, Prescription, ImagingOrder, ProcedureOrder — so they are four
    // vocabularies that happen to share a field name. Piping them all through
    // one domain would Title-Case whichever values that domain does not hold,
    // and the enum-coverage gate would still read green because the domain it
    // was given IS fully keyed. Each sentinel below is reachable only from its
    // own PORTAL.ENUM group.
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          ENCOUNTER_STATUS: { IN_PROGRESS: 'consultation-en-cours' },
          PRESCRIPTION_STATUS: { SIGNED: 'ordonnance-signee' },
          IMAGING_ORDER_STATUS: { ORDERED: 'imagerie-demandee' },
          PROCEDURE_ORDER_STATUS: { SCHEDULED: 'intervention-planifiee' },
        },
      },
    });
    chartSpy.getChartReview.and.returnValue(of(populatedChart()));
    setPatient('p-9');

    const cases: [string, string, string][] = [
      ['encounters', 'consultation-en-cours', 'IN_PROGRESS'],
      ['medications', 'ordonnance-signee', 'SIGNED'],
      ['imaging', 'imagerie-demandee', 'ORDERED'],
      ['procedures', 'intervention-planifiee', 'SCHEDULED'],
    ];
    for (const [tab, expected, wire] of cases) {
      (
        fixture.nativeElement.querySelector(
          `[data-testid="chart-review-tab-${tab}"]`,
        ) as HTMLButtonElement
      ).click();
      fixture.detectChanges();
      const pills = Array.from(
        fixture.nativeElement.querySelectorAll(
          `[data-testid="chart-review-panel-${tab}"] .chart-review__pill`,
        ) as NodeListOf<HTMLElement>,
      ).map((el) => (el.textContent ?? '').trim());
      expect(pills).withContext(`${tab} pill`).toContain(expected);
      expect(pills).withContext(`${tab} still raw`).not.toContain(wire);
    }
  });
});

function populatedChart(patientId = 'p-1'): ChartReview {
  return {
    patientId,
    limit: 20,
    encounters: [
      {
        id: 'e-1',
        encounterType: 'INPATIENT',
        status: 'IN_PROGRESS',
        encounterDate: '2026-04-29T10:30:00',
        staffFullName: 'Dr Compaoré',
        departmentName: 'Internal Medicine',
        chiefComplaint: 'Persistent fever',
      },
    ],
    notes: [
      {
        id: 'n-1',
        encounterId: 'e-1',
        template: 'SOAP',
        authorName: 'Dr Compaoré',
        documentedAt: '2026-04-29T11:00:00',
        signedAt: '2026-04-29T11:05:00',
        signed: true,
        lateEntry: false,
        preview: 'Patient stable. Continue antimalarial protocol.',
      },
    ],
    results: [
      {
        id: 'r-1',
        testName: 'Hemoglobin',
        testCode: '718-7',
        resultValue: '9.4',
        resultUnit: 'g/dL',
        abnormalFlag: 'ABNORMAL',
        resultDate: '2026-04-29T08:00:00',
        acknowledged: false,
        released: true,
      },
    ],
    medications: [
      {
        id: 'm-1',
        medicationName: 'Amoxicillin',
        dosage: '500 mg',
        route: 'PO',
        frequency: 'TID',
        duration: '7 days',
        status: 'SIGNED',
        createdAt: '2026-04-28T09:00:00',
        prescriberName: 'Dr Traoré',
        controlledSubstance: false,
        inpatientOrder: false,
      },
    ],
    imaging: [
      {
        id: 'i-1',
        modality: 'XRAY',
        studyType: 'Chest XR',
        bodyRegion: 'Chest',
        priority: 'ROUTINE',
        status: 'ORDERED',
        orderedAt: '2026-04-27T08:00:00',
        clinicalQuestion: 'Rule out pneumonia',
        reportImpression: 'No acute cardiopulmonary findings.',
        reportStatus: 'FINAL',
      },
    ],
    procedures: [
      {
        id: 'pr-1',
        procedureName: 'Lumbar puncture',
        urgency: 'URGENT',
        status: 'SCHEDULED',
        orderedAt: '2026-04-26T10:00:00',
        orderingProviderName: 'Dr Ouedraogo',
        indication: 'Suspected meningitis',
        consentObtained: true,
      },
    ],
    timeline: [
      {
        id: 'n-1',
        section: 'NOTE',
        occurredAt: '2026-04-29T11:00:00',
        title: 'SOAP — Dr Compaoré',
        summary: 'Patient stable.',
        status: 'SIGNED',
      },
      {
        id: 'e-1',
        section: 'ENCOUNTER',
        occurredAt: '2026-04-29T10:30:00',
        title: 'INPATIENT — Internal Medicine',
        summary: 'Persistent fever',
        status: 'IN_PROGRESS',
      },
    ],
  };
}

function emptyChart(): ChartReview {
  return {
    patientId: 'p-blank',
    limit: 20,
    encounters: [],
    notes: [],
    results: [],
    medications: [],
    imaging: [],
    procedures: [],
    timeline: [],
  };
}
