import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { AfterVisitSummary } from '../../services/encounter.service';
import { formatMeasure } from '../../shared/i18n/app-locale';
import {
  VisitSummarySectionsComponent,
  hasContentSections,
} from './visit-summary-sections.component';

/** User decision 2026-11-12: each content section shows with data and hides without it. */
describe('VisitSummarySectionsComponent', () => {
  const full: Partial<AfterVisitSummary> = {
    hospitalName: 'CHU Yalgado',
    departmentName: 'Pédiatrie',
    hospitalPhone: '25 30 66 43',
    departmentPhone: '25 30 66 50',
    vitalSigns: {
      temperatureCelsius: 38.6,
      systolicBpMmHg: 110,
      diastolicBpMmHg: 70,
      flagged: true,
    },
    orderedTests: [
      {
        kind: 'LAB',
        name: 'NFS',
        status: 'COMPLETED',
        results: [{ value: '11.2', unit: 'g/dL', abnormalFlag: 'ABNORMAL_LOW' }],
      },
      { kind: 'LAB', name: 'Glycémie', status: 'IN_PROGRESS', results: [] },
      {
        kind: 'IMAGING',
        name: 'Radio thorax',
        status: 'RESULTS_AVAILABLE',
        impression: 'Pas de foyer',
      },
    ],
    allergies: [{ allergen: 'Pénicilline', severity: 'SEVERE' }],
    currentMedications: [{ name: 'Amoxicilline', dosage: '500 mg', frequency: '3 fois par jour' }],
    clinicalAssessment: 'Paludisme simple',
    clinicalPlan: 'ACT 3 jours',
    medicalHistory: [{ condition: 'Asthme', status: 'ACTIVE' }],
    surgicalHistory: [{ procedure: 'Appendicectomie', date: '2020-03-01' }],
    familyHistory: [{ relationship: 'Mère', condition: 'Drépanocytose' }],
    socialHistory: { tobaccoUse: false, occupation: 'Élève' },
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [VisitSummarySectionsComponent, TranslateModule.forRoot()],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      VISIT_SUMMARY: {
        VITALS_FLAGGED: 'Valeurs signalées',
        SEVERITY_SEVERE: 'Sévère',
        FLAG_LOW: 'Bas',
        NO: 'Non',
      },
    });
    translate.use('fr');
  });

  function render(value: Partial<AfterVisitSummary>): HTMLElement {
    const fixture = TestBed.createComponent(VisitSummarySectionsComponent);
    fixture.componentRef.setInput('summary', value);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const SECTIONS = [
    'avs-contact',
    'avs-vitals',
    'avs-tests',
    'avs-allergies',
    'avs-medications',
    'avs-assessment',
    'avs-plan',
    'avs-history',
  ];

  it('shows every section that has data', () => {
    const root = render(full);
    for (const id of SECTIONS) {
      expect(root.querySelector(`[data-testid="${id}"]`))
        .withContext(id)
        .not.toBeNull();
    }
    expect(root.querySelector('[data-testid="avs-vitals-flagged"]')?.textContent).toContain(
      'Valeurs signalées',
    );
    expect(root.querySelector('[data-testid="avs-vitals"]')?.textContent).toContain(
      formatMeasure(38.6, '°C'),
    );
    expect(root.querySelector('[data-testid="avs-allergies"]')?.textContent).toContain('Sévère');
    expect(root.querySelector('[data-testid="avs-tests"]')?.textContent).toContain('Bas');
    expect(root.querySelector('[data-testid="avs-tests"]')?.textContent).toContain('Pas de foyer');
    expect(root.querySelector('[data-testid="avs-history-family"]')?.textContent).toContain(
      'Drépanocytose',
    );
    expect(root.querySelector('[data-testid="avs-history-social"]')?.textContent).toContain('Non');
  });

  it('hides every section without data, and says so to the dialog', () => {
    const root = render({ hospitalName: 'CHU Yalgado', orderedTests: [], allergies: [] });
    for (const id of SECTIONS) {
      expect(root.querySelector(`[data-testid="${id}"]`))
        .withContext(id)
        .toBeNull();
    }
    expect(hasContentSections({ orderedTests: [], allergies: [] })).toBeFalse();
    expect(hasContentSections(full)).toBeTrue();
  });

  it('lists a test whose result is not released by its status only', () => {
    const root = render({
      orderedTests: [{ kind: 'LAB', name: 'Glycémie', status: 'IN_PROGRESS', results: [] }],
    });
    const test = root.querySelector('[data-testid="avs-test"]');
    expect(test?.textContent).toContain('Glycémie');
    expect(test?.querySelector('.vss-result')).toBeNull();
  });
});
