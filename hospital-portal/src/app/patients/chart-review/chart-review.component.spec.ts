import { formatDate } from '@angular/common';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Observable, Subject, of, throwError } from 'rxjs';

import { ChartReviewComponent } from './chart-review.component';
import { ChartReview, ChartReviewService } from '../../services/chart-review.service';
import en from '../../../assets/i18n/en.json';
import es from '../../../assets/i18n/es.json';
import fr from '../../../assets/i18n/fr.json';

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

  it('D61: imaging modality and procedure urgency are labels, not wire tokens', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          IMAGING_MODALITY: { XRAY: 'Radiographie' },
          PROCEDURE_URGENCY: { URGENT: 'Urgent-fr' },
        },
      },
    });
    chartSpy.getChartReview.and.returnValue(of(populatedChart()));
    setPatient('p-9');

    const panelText = (tab: string): string => {
      (
        fixture.nativeElement.querySelector(
          `[data-testid="chart-review-tab-${tab}"]`,
        ) as HTMLButtonElement
      ).click();
      fixture.detectChanges();
      return (
        (
          fixture.nativeElement.querySelector(
            `[data-testid="chart-review-panel-${tab}"]`,
          ) as HTMLElement
        ).textContent ?? ''
      );
    };
    const imaging = panelText('imaging');
    expect(imaging).toContain('Radiographie');
    expect(imaging).not.toContain('XRAY');
    const procedures = panelText('procedures');
    expect(procedures).toContain('Urgent-fr');
    expect(procedures).not.toContain('URGENT');
  });

  it('translates the timeline pill for every section, not just the tabs', () => {
    // One badge, six emitters: the timeline pill carries Encounter status, a
    // note's SIGNED/DRAFT, AbnormalFlag, Prescription status, an imaging
    // report-or-order status and ProcedureOrder status. It resolves through the
    // shared `status` pool, which was missing 27 of those values — so the same
    // record read French in the tab and English in the timeline above it.
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          STATUS: {
            READY_FOR_DISCHARGE: 'pret-pour-la-sortie',
            RESULTS_AVAILABLE: 'resultats-disponibles',
            POSTPONED: 'reporte',
          },
        },
      },
    });
    const chart = populatedChart();
    chart.timeline = [
      {
        id: 'e-9',
        section: 'ENCOUNTER',
        occurredAt: '2026-04-29T10:00:00',
        title: 'A',
        status: 'READY_FOR_DISCHARGE',
      },
      {
        id: 'i-9',
        section: 'IMAGING',
        occurredAt: '2026-04-28T10:00:00',
        title: 'B',
        status: 'RESULTS_AVAILABLE',
      },
      {
        id: 'p-9',
        section: 'PROCEDURE',
        occurredAt: '2026-04-27T10:00:00',
        title: 'C',
        status: 'POSTPONED',
      },
    ];
    chartSpy.getChartReview.and.returnValue(of(chart));

    setPatient('p-10');

    const pills = Array.from(
      fixture.nativeElement.querySelectorAll(
        '[data-testid="chart-review-panel-timeline"] .chart-review__pill',
      ) as NodeListOf<HTMLElement>,
    ).map((el) => (el.textContent ?? '').trim());
    // The positive assertion is the whole test: an unkeyed value falls to the
    // English LABELS net ("Ready for Discharge") before the Title-Case
    // prettifier, and either way it is not the French key asserted here.
    for (const expected of ['pret-pour-la-sortie', 'resultats-disponibles', 'reporte']) {
      expect(pills).withContext(`timeline pill ${expected}`).toContain(expected);
    }
  });

  /** The timeline pills, section then status, as rendered. */
  function timelinePills(): string[] {
    return Array.from(
      fixture.nativeElement.querySelectorAll(
        '[data-testid="chart-review-panel-timeline"] .chart-review__timeline-head',
      ) as NodeListOf<HTMLElement>,
    ).map((el) => (el.textContent ?? '').replace(/\s+/g, ' ').trim());
  }

  function d897Chart(): ChartReview {
    const chart = populatedChart();
    chart.timeline = [
      {
        id: 'n-1',
        section: 'NOTE',
        occurredAt: '2026-10-26T10:00:00',
        title: 'SOAP',
        status: 'SIGNED',
      },
      {
        id: 'e-1',
        section: 'ENCOUNTER',
        occurredAt: '2026-10-26T09:00:00',
        title: 'A',
        status: 'COMPLETED',
      },
      {
        id: 'i-1',
        section: 'IMAGING',
        occurredAt: '2026-10-26T08:00:00',
        title: 'B',
        status: 'FINAL',
      },
      {
        id: 'p-1',
        section: 'PROCEDURE',
        occurredAt: '2026-10-26T07:00:00',
        title: 'C',
        status: 'COMPLETED',
      },
      {
        id: 'm-1',
        section: 'MEDICATION',
        occurredAt: '2026-10-26T06:00:00',
        title: 'D',
        status: 'SIGNED',
      },
    ];
    return chart;
  }

  it('QA D897: French timeline statuses agree with their section ("Note Signée", "Consultation Terminée")', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', fr);
    translate.use('fr');
    chartSpy.getChartReview.and.returnValue(of(d897Chart()));

    setPatient('p-897');

    expect(timelinePills()).toEqual([
      'Note Signée',
      'Consultation Terminée',
      'Imagerie Finale',
      'Procédure Terminée',
      // A masculine noun keeps the shared label.
      'Médicament Signé',
    ]);
  });

  it('QA D897: Spanish agrees too, and its procedimiento is masculine', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('es', es);
    translate.use('es');
    chartSpy.getChartReview.and.returnValue(of(d897Chart()));

    setPatient('p-897');

    expect(timelinePills()).toEqual([
      'Nota Firmada',
      'Consulta Completada',
      'Imagen Definitiva',
      'Procedimiento Completado',
      'Medicamento Firmado',
    ]);
  });

  it('QA D897: English is unchanged, and a status with no agreed label keeps the shared one', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', en);
    translate.use('en');
    const chart = d897Chart();
    chart.timeline = [
      ...(chart.timeline ?? []),
      {
        id: 'r-1',
        section: 'RESULT',
        occurredAt: '2026-10-26T05:00:00',
        title: 'E',
        status: 'HIGH',
      },
    ];
    chartSpy.getChartReview.and.returnValue(of(chart));

    setPatient('p-897');

    const pills = timelinePills();
    expect(pills.slice(0, 5)).toEqual([
      'Note Signed',
      'Encounter Completed',
      'Imaging Final',
      'Procedure Completed',
      'Medication Signed',
    ]);
    expect(pills[5]).not.toContain('TIMELINE_STATUS');
  });

  it('QA D897: every agreed label exists in all three languages for the same statuses', () => {
    const trees = [en, fr, es].map(
      (d) =>
        (
          d as unknown as {
            CHART_REVIEW: { TIMELINE_STATUS: Record<string, Record<string, string>> };
          }
        ).CHART_REVIEW.TIMELINE_STATUS,
    );
    const shape = (t: Record<string, Record<string, string>>): string[] =>
      Object.entries(t).flatMap(([section, labels]) =>
        Object.keys(labels).map((s) => `${section}.${s}`),
      );
    expect(shape(trees[1]).sort()).toEqual(shape(trees[0]).sort());
    expect(shape(trees[2]).sort()).toEqual(shape(trees[0]).sort());
  });

  for (const [lang, label] of [
    ['en', 'Inpatient-en'],
    ['es', 'Hospitalizado-es'],
    ['fr', 'Hospitalisé-fr'],
  ] as const) {
    it(`QA D829: labels the timeline encounter type in ${lang}, never the raw code`, () => {
      const translate = TestBed.inject(TranslateService);
      translate.setTranslation(lang, {
        PORTAL: { ENUM: { ENCOUNTER_TYPE: { INPATIENT: label } } },
      });
      translate.use(lang);
      chartSpy.getChartReview.and.returnValue(of(populatedChart()));

      setPatient('p-829');

      const title = (
        fixture.nativeElement.querySelector('[data-testid="timeline-encounter-title"]')
          ?.textContent ?? ''
      ).replace(/\s+/g, ' ');
      expect(title).toContain(`${label} — Internal Medicine`);
      expect(title).not.toContain('INPATIENT');
    });
  }

  it('QA D777: shows the zone-less UTC server time in the reader zone, like every other screen', () => {
    const chart = populatedChart();
    chart.timeline = [
      { id: 'e-7', section: 'ENCOUNTER', occurredAt: '2026-10-24T15:41:00', title: 'Vitals' },
    ];
    chartSpy.getChartReview.and.returnValue(of(chart));

    setPatient('p-777');

    const text = (
      fixture.nativeElement.querySelector('[data-testid="chart-review-panel-timeline"]')
        ?.textContent ?? ''
    ).replace(/\s+/g, ' ');
    expect(text).toContain(
      formatDate('2026-10-24T15:41:00Z', 'shortTime', 'en-US').replace(/\s+/g, ' '),
    );
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
