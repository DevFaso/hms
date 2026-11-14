import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { LabCorrectionBadgeComponent } from '../../lab/shared/lab-correction-badge.component';
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
        FLAG_HIGH: 'Élevé',
        REFERENCE: 'référence',
        LAB_AWAITING_RELEASE: 'En attente de validation',
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

  it('marks corrected results with the shared patient-facing badge', () => {
    const fixture = TestBed.createComponent(VisitSummarySectionsComponent);
    fixture.componentRef.setInput('summary', {
      orderedTests: [
        {
          kind: 'LAB',
          name: 'NFS',
          status: 'COMPLETED',
          results: [{ value: '11.2', unit: 'g/dL', corrected: true }],
        },
      ],
    });
    fixture.detectChanges();

    const badge = fixture.debugElement.query(By.directive(LabCorrectionBadgeComponent))
      .componentInstance as LabCorrectionBadgeComponent;
    expect(badge.corrected()).toBeTrue();
    expect(badge.audience()).toBe('patient');
    expect(
      fixture.nativeElement.querySelector('app-lab-correction-badge').textContent.trim(),
    ).not.toBe('');
  });

  it('lists a test whose result is not released by its status only', () => {
    const root = render({
      orderedTests: [{ kind: 'LAB', name: 'Glycémie', status: 'IN_PROGRESS', results: [] }],
    });
    const test = root.querySelector('[data-testid="avs-test"]');
    expect(test?.textContent).toContain('Glycémie');
    expect(test?.querySelector('.vss-result')).toBeNull();
  });

  it('QA D1743: a resulted test with nothing released reads awaiting validation, not resulted', () => {
    const root = render({
      orderedTests: [{ kind: 'LAB', name: 'Glycémie', status: 'AWAITING_RELEASE', results: [] }],
    });
    const status = root.querySelector('[data-testid="avs-test-status"]');
    expect(status?.textContent?.trim()).toBe('En attente de validation');
  });

  it('QA D1740: a released value shows its grade and the range it was graded against', () => {
    const root = render({
      orderedTests: [
        {
          kind: 'LAB',
          name: 'Glycémie à jeun',
          status: 'RESULTED',
          results: [
            {
              value: '1.32',
              unit: 'g/L',
              referenceRange: '0.7 - 0.99 g/L',
              abnormalFlag: 'ABNORMAL_HIGH',
            },
          ],
        },
      ],
    });
    const result = root.querySelector('.vss-result')?.textContent ?? '';
    expect(result).toContain('référence');
    // D1106: the server sends a machine range ("0.7 - 0.99 g/L"); French reads it with a decimal comma.
    expect(result).toContain('0,7 – 0,99 g/L');
    expect(result).not.toContain('0.7');
    expect(root.querySelector('.vss-flag')?.textContent?.trim()).toBe('Élevé');
  });

  /* ── Polish 2026-11-13 (user review of an outpatient hypertension visit) ── */

  function renderAs(value: Partial<AfterVisitSummary>, audience: 'staff' | 'patient' = 'staff') {
    const fixture = TestBed.createComponent(VisitSummarySectionsComponent);
    fixture.componentRef.setInput('summary', value);
    fixture.componentRef.setInput('audience', audience);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  function useFrench(): void {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(
      'fr',
      {
        COMMON: { COLON: ' :' },
        ENCOUNTERS: { AVS_DIAGNOSES: 'Diagnostics de sortie' },
        VISIT_SUMMARY: {
          FLAG_HIGH: 'Élevé',
          FLAG_LOW: 'Bas',
          FLAG_ABNORMAL: 'Anormal',
          DIAGNOSES: 'Diagnostic(s)',
          MRN: 'N° de dossier',
          BORN_ON: 'Né(e) le',
          AGE_YEARS: '{{n}} ans',
          SIGNED_BY: 'Signé par {{name}} le {{date}}',
          AS_ENTERED: 'Tel que saisi par le soignant',
          REACTION_UNSPECIFIED: 'réaction non précisée',
          SINCE: 'depuis le {{date}}',
          PRESCRIBED_BY: 'prescrit par {{name}}',
          DUPLICATE_MEDICINE_PATIENT:
            'Plusieurs prescriptions du même médicament — vérifiez avec votre médecin avant de les prendre ensemble',
          DUPLICATE_MEDICINE_STAFF: 'Prescriptions actives en double pour ce médicament — à revoir',
        },
      },
      true,
    );
    translate.use('fr');
  }

  const visit: Partial<AfterVisitSummary> = {
    patientName: 'Awa Ouédraogo',
    patientDateOfBirth: '1970-03-15',
    patientMrn: 'MRN-0042',
    visitDate: '2026-11-12T09:00:00',
    providerName: 'Dr Kaboré',
    departmentName: 'Médecine interne',
    hospitalName: 'CHU Yalgado',
    hospitalPhone: '25 30 66 43',
    chiefComplaint: 'Céphalées',
    vitalSigns: {
      systolicBpMmHg: 142,
      diastolicBpMmHg: 88,
      heartRateBpm: 72,
      flagged: false,
    },
    clinicalAssessment: 'HTA non contrôlée',
    dischargeDiagnoses: ['Hypertension artérielle'],
    clinicalPlan: 'Amlodipine 5 mg',
    followUpInstructions: 'Réduire le sel',
    currentMedications: [{ name: 'Amlodipine', dosage: '5 mg', frequency: '1 fois par jour' }],
    orderedTests: [{ kind: 'LAB', name: 'Créatinine', status: 'IN_PROGRESS', results: [] }],
    allergies: [{ allergen: 'Pénicilline' }],
    medicalHistory: [{ condition: 'Diabète', status: 'ACTIVE' }],
    followUpAppointmentDate: '2026-12-10',
  };

  it('lays the sections out in the reading order the user set', () => {
    const root = renderAs(visit);
    const order = Array.from(root.querySelectorAll('.vss-doc > section')).map((el) =>
      el.getAttribute('data-testid'),
    );
    expect(order).toEqual([
      'avs-identity',
      'avs-complaint',
      'avs-vitals',
      'avs-assessment',
      'avs-diagnoses',
      'avs-advice',
      'avs-medications',
      'avs-tests',
      'avs-allergies',
      'avs-history',
      'avs-appointment',
      'avs-contact',
    ]);
  });

  it('shows one compact identity band: the name once, the date of birth with the age, the MRN', () => {
    useFrench();
    const root = renderAs(visit);
    const band = root.querySelector('[data-testid="avs-identity"]')!;
    expect(root.textContent!.split('Awa Ouédraogo').length - 1).toBe(1);
    expect(band.querySelector('[data-testid="avs-dob"]')?.textContent).toContain('1970');
    // 15 March 1970 to 12 November 2026: 56 completed years.
    expect(band.querySelector('[data-testid="avs-dob"]')?.textContent).toContain('(56 ans)');
    expect(band.querySelector('[data-testid="avs-mrn"]')?.textContent).toContain(
      'N° de dossier : MRN-0042',
    );
  });

  it('signs the document when the note is signed, and not while one waits for its signature', () => {
    useFrench();
    const signed = renderAs({
      ...visit,
      signedByName: 'Dr Kaboré',
      signedAt: '2026-11-12T10:15:00',
    });
    const line = signed.querySelector('[data-testid="avs-signature"]')?.textContent ?? '';
    expect(line).toContain('Signé par Dr Kaboré le');
    expect(line).toContain('2026');

    const pending = renderAs({
      ...visit,
      signedByName: 'Dr Kaboré',
      signedAt: '2026-11-12T10:15:00',
      pendingSignature: true,
    });
    expect(pending.querySelector('[data-testid="avs-signature"]')).toBeNull();
    expect(renderAs(visit).querySelector('[data-testid="avs-signature"]')).toBeNull();
  });

  it('names the diagnoses "at discharge" only for a stay', () => {
    useFrench();
    const heading = (root: HTMLElement) =>
      root.querySelector('[data-testid="avs-diagnoses"] h3')?.textContent?.trim();
    expect(heading(renderAs(visit))).toBe('diagnosis Diagnostic(s)');
    expect(heading(renderAs({ ...visit, inpatientStay: true }))).toBe(
      'diagnosis Diagnostics de sortie',
    );
  });

  it('flags a vital value only where the existing rules judged it', () => {
    useFrench();
    // 142/88: no rule of VitalSignJudgement fires on it, so no flag is invented here.
    const unjudged = renderAs(visit);
    expect(unjudged.querySelectorAll('[data-testid="avs-vital-flag"]').length).toBe(0);

    const judged = renderAs({
      ...visit,
      vitalSigns: {
        systolicBpMmHg: 210,
        diastolicBpMmHg: 88,
        heartRateBpm: 72,
        spo2Percent: 86,
        flagged: true,
        valueFlags: { systolicBpMmHg: 'HIGH', spo2Percent: 'LOW' },
      },
    });
    const flagOf = (id: string) =>
      judged.querySelector(`[data-testid="${id}"] [data-testid="avs-vital-flag"]`);
    expect(flagOf('avs-vital-bp')?.textContent).toBe('Élevé');
    expect(flagOf('avs-vital-spo2')?.textContent).toBe('Bas');
    expect(flagOf('avs-vital-hr')).toBeNull();
  });

  it('writes a rate as "72/min", and shows height and BMI only when recorded', () => {
    const root = renderAs(visit);
    expect(root.querySelector('[data-testid="avs-vital-hr"] dd')?.textContent).toContain('72/min');
    expect(root.querySelector('[data-testid="avs-vital-height"]')).toBeNull();
    expect(root.querySelector('[data-testid="avs-vital-bmi"]')).toBeNull();

    const measured = renderAs({
      ...visit,
      vitalSigns: { heightCm: 165, weightKg: 70, bmi: 25.7, flagged: false },
    });
    expect(measured.querySelector('[data-testid="avs-vital-height"]')).not.toBeNull();
    expect(measured.querySelector('[data-testid="avs-vital-bmi"] dd')?.textContent).toContain(
      formatMeasure(25.7, 'kg/m²'),
    );
  });

  it('says "réaction non précisée" for an allergy recorded without one', () => {
    useFrench();
    const root = renderAs(visit);
    expect(root.querySelector('[data-testid="avs-allergy"]')?.textContent).toContain(
      'réaction non précisée',
    );
    const known = renderAs({
      ...visit,
      allergies: [{ allergen: 'Pénicilline', reaction: 'Urticaire' }],
    });
    expect(known.querySelector('[data-testid="avs-allergy-unspecified"]')).toBeNull();
  });

  it('keeps one "as entered" caption for the assessment and the plan', () => {
    const root = renderAs(visit);
    expect(root.querySelectorAll('[data-testid="avs-as-entered"]').length).toBe(1);
    const planOnly = renderAs({ ...visit, clinicalAssessment: null });
    expect(
      planOnly.querySelectorAll('[data-testid="avs-advice"] [data-testid="avs-as-entered"]').length,
    ).toBe(1);
  });

  it('shows each medicine as drug, dose, frequency, start and prescriber, never the dose twice', () => {
    useFrench();
    const root = renderAs({
      ...visit,
      currentMedications: [
        {
          name: 'Paracétamol 500 mg',
          dosage: '500 mg',
          frequency: '3 fois par jour',
          startDate: '2026-11-12',
          prescriberName: 'Dr Kaboré',
          drugKey: 'drug-0',
        },
      ],
    });
    const line = root.querySelector('[data-testid="avs-med-line"]')!;
    expect(line.textContent).not.toContain('500 mg');
    expect(line.textContent).toContain('3 fois par jour');
    expect(line.textContent).toContain('depuis le');
    expect(line.textContent).toContain('2026');
    expect(line.textContent).toContain('prescrit par Dr Kaboré');
    expect(root.querySelector('[data-testid="avs-med-group"] .vss-item-title')?.textContent).toBe(
      'Paracétamol 500 mg',
    );
  });

  // User decision 2026-11-13 (option a): one drug shown once, each active order under it, with a warning.
  it('groups three active paracetamol orders under one drug with the warning', () => {
    useFrench();
    const paracetamol = (frequency: string) => ({
      name: 'Paracétamol',
      dosage: '1 g',
      frequency,
      drugKey: 'drug-0',
    });
    const meds = [
      paracetamol('toutes les 6 h'),
      paracetamol('3 fois par jour'),
      paracetamol('si douleur'),
    ];
    const patient = renderAs({ ...visit, currentMedications: meds }, 'patient');
    expect(patient.querySelectorAll('[data-testid="avs-med-group"]').length).toBe(1);
    expect(patient.querySelectorAll('[data-testid="avs-med-line"]').length).toBe(3);
    expect(patient.querySelector('[data-testid="avs-med-duplicate"]')?.textContent).toContain(
      'Plusieurs prescriptions du même médicament — vérifiez avec votre médecin avant de les prendre ensemble',
    );

    const staff = renderAs({ ...visit, currentMedications: meds }, 'staff');
    expect(staff.querySelector('[data-testid="avs-med-duplicate"]')?.textContent).toContain(
      'Prescriptions actives en double pour ce médicament — à revoir',
    );
  });

  it('leaves two different drugs ungrouped, with no warning', () => {
    const root = renderAs(
      {
        ...visit,
        currentMedications: [
          { name: 'Amlodipine', dosage: '5 mg', drugKey: 'drug-0' },
          { name: 'Metformine', dosage: '500 mg', drugKey: 'drug-1' },
        ],
      },
      'patient',
    );
    expect(root.querySelectorAll('[data-testid="avs-med-group"]').length).toBe(2);
    expect(root.querySelector('[data-testid="avs-med-duplicate"]')).toBeNull();
  });

  it('shows the follow-up appointment with its time, service and clinician when known', () => {
    const root = renderAs({
      ...visit,
      followUpAppointmentTime: '09:30',
      followUpDepartmentName: 'Cardiologie',
      followUpClinicianName: 'Dr Sawadogo',
    });
    const appt = root.querySelector('[data-testid="avs-appointment"]')!;
    expect(appt.querySelector('[data-testid="avs-appointment-time"]')?.textContent).toBe('09:30');
    expect(appt.textContent).toContain('Cardiologie');
    expect(appt.textContent).toContain('Dr Sawadogo');
    expect(renderAs(visit).querySelector('[data-testid="avs-appointment-time"]')).toBeNull();
  });

  /* ── Rex P2s on #1145 ── */

  it('hides a dose only when the name carries that whole strength', () => {
    const shown = (name: string, dosage: string) => {
      const root = renderAs({ ...visit, currentMedications: [{ name, dosage, drugKey: 'd' }] });
      return root.querySelector('[data-testid="avs-med-line"]')?.textContent ?? '';
    };
    expect(shown('Paracétamol 250 mg', '50 mg')).toContain('50 mg');
    expect(shown('Haldol 0,5 mg', '5 mg')).toContain('5 mg');
    expect(shown('Amoxicilline 125 mg/5 ml', '125 mg')).toContain('125 mg');
    expect(shown('Paracétamol 500mg', '500 mg')).not.toContain('500');
    expect(shown('Haldol 0,5 mg', '0.5 mg')).not.toContain('0.5');
  });

  it('counts the age on the calendar: a second birthday is 2 years, not 23 months', () => {
    useFrench();
    TestBed.inject(TranslateService).setTranslation(
      'fr',
      { VISIT_SUMMARY: { AGE_MONTHS: '{{n}} mois', AGE_DAYS: '{{n}} jours' } },
      true,
    );
    const age = (born: string, at: string) =>
      renderAs({ patientName: 'X', patientDateOfBirth: born, visitDate: at })
        .querySelector('[data-testid="avs-dob"]')
        ?.textContent?.replace(/\s+/g, ' ');
    expect(age('2024-03-01', '2026-03-01T09:00:00')).toContain('(2 ans)');
    expect(age('2024-03-01', '2026-02-28T09:00:00')).toContain('(23 mois)');
    expect(age('2026-01-15', '2026-02-15T09:00:00')).toContain('(1 mois)');
    expect(age('2026-01-31', '2026-02-28T09:00:00')).toContain('(28 jours)');
    expect(age('1970-11-12', '2026-11-12T09:00:00')).toContain('(56 ans)');
  });
});
