import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { MyRecordsComponent } from './my-records.component';
import {
  HealthSummaryDTO,
  ImmunizationSummary,
  LabResultSummary,
  MedicationSummary,
  PatientPortalService,
} from '../../services/patient-portal.service';

/**
 * The patient's health-summary dashboard. Asserted through the rendered DOM in
 * French (the #661 shape): each card's empty line is a translation key, and
 * only the DOM shows whether it resolves.
 */
describe('MyRecordsComponent', () => {
  let fixture: ComponentFixture<MyRecordsComponent>;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function summary(overrides: Partial<HealthSummaryDTO> = {}): HealthSummaryDTO {
    return {
      profile: { dateOfBirth: '1990-01-01' },
      recentLabResults: [],
      currentMedications: [],
      latestVitals: [],
      immunizations: [],
      allergies: [],
      activeDiagnoses: [],
      ...overrides,
    } as HealthSummaryDTO;
  }

  function create(): void {
    fixture = TestBed.createComponent(MyRecordsComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getHealthSummary',
      'getMyEncounters',
      'getMyLabResults',
      'getMyMedications',
      'getMyImmunizations',
    ]);
    portal.getHealthSummary.and.returnValue(of(summary()));
    portal.getMyEncounters.and.returnValue(of([]));
    portal.getMyLabResults.and.returnValue(of([]));
    portal.getMyMedications.and.returnValue(of([]));
    portal.getMyImmunizations.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [MyRecordsComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: portal }, provideRouter([])],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      COMMON: { COLON: '\u00a0:' },
      PORTAL: {
        RECORDS: {
          TITLE: 'Mon dossier',
          MEDICATIONS_TAB: 'Médicaments',
          NO_MEDS: 'Aucun médicament',
          NO_LABS: 'Aucun résultat',
          NO_CONDITIONS: 'Aucun problème de santé',
          NO_IMMUNIZATIONS: 'Aucune vaccination',
          NO_ALLERGIES: 'Aucune allergie connue',
          MORE: 'de plus',
        },
      },
    });
  });

  it('shows every card’s translated empty line for a new patient', () => {
    create();
    expect(texts('h1')[0]).toContain('Mon dossier');
    expect(texts('.card-empty')).toEqual([
      'Aucun médicament',
      'Aucun résultat',
      'Aucun problème de santé',
      'Aucune vaccination',
      'Aucune allergie connue',
    ]);
  });

  it('reads only the lab results the card previews (QA D1362: the count is the server total)', () => {
    create();
    expect(portal.getMyLabResults).toHaveBeenCalledWith(4);
  });

  it('shows the first four medications and counts the rest from the server total', () => {
    const meds = ['a', 'b', 'c', 'd', 'e', 'f'].map(
      (n) => ({ id: n, medicationName: `med-${n}`, dosage: '' }) as MedicationSummary,
    );
    portal.getMyMedications.and.returnValue(of(meds));
    // QA D1362: A2 has 444 medications; the list endpoint sent 6 of them.
    portal.getHealthSummary.and.returnValue(of(summary({ medicationTotal: 444 })));
    create();
    const firstCard = host().querySelector('.dashboard-card')!;
    const names = Array.from(firstCard.querySelectorAll('.card-item-name')).map((el) =>
      el.textContent?.trim(),
    );
    expect(names).toEqual(['med-a', 'med-b', 'med-c', 'med-d']);
    expect(firstCard.querySelector('.card-more')?.textContent?.trim()).toBe('+ 440 de plus');
  });

  it('QA D1362: the lab card counts every result the server has, not the rows it loaded', () => {
    const labs = ['a', 'b', 'c', 'd'].map(
      (n) => ({ id: n, testName: `lab-${n}`, collectedDate: '2026-11-01' }) as LabResultSummary,
    );
    portal.getMyLabResults.and.returnValue(of(labs));
    portal.getHealthSummary.and.returnValue(of(summary({ labResultTotal: 125 })));
    create();
    const labCard = host().querySelectorAll('.dashboard-card')[1];
    expect(labCard.querySelectorAll('.card-item-name').length).toBe(4);
    expect(labCard.querySelector('.card-more')?.textContent?.trim()).toBe('+ 121 de plus');
  });

  it('QA D1362: with no server total a card shows no count rather than one taken from its rows', () => {
    const meds = ['a', 'b', 'c', 'd', 'e', 'f'].map(
      (n) => ({ id: n, medicationName: `med-${n}`, dosage: '' }) as MedicationSummary,
    );
    portal.getMyMedications.and.returnValue(of(meds));
    portal.getHealthSummary.and.returnValue(of(summary({ medicationTotal: null })));
    create();
    const firstCard = host().querySelector('.dashboard-card')!;
    expect(firstCard.querySelector('.card-more')).toBeNull();
    expect(firstCard.querySelector('.card-link')).not.toBeNull();
  });

  it('lists allergies and active conditions from the health summary', () => {
    portal.getHealthSummary.and.returnValue(
      of(summary({ allergies: ['allergy-a'], activeDiagnoses: ['condition-a'] })),
    );
    create();
    expect(texts('.rec-chip')).toEqual(['allergy-a']);
    expect(texts('.card-item-name')).toContain('condition-a');
    expect(texts('.card-empty')).not.toContain('Aucune allergie connue');
  });

  it('lists immunizations by vaccine name', () => {
    portal.getMyImmunizations.and.returnValue(
      of([
        {
          id: 'i1',
          vaccineName: 'vaccine-a',
          dateAdministered: '2026-01-02',
        } as ImmunizationSummary,
      ]),
    );
    create();
    expect(texts('.card-item-name')).toContain('vaccine-a');
  });

  it('still renders the cards when one of the reads fails', () => {
    portal.getMyMedications.and.returnValue(throwError(() => new Error('500')));
    create();
    expect(host().querySelector('.portal-loading')).toBeNull();
    expect(host().querySelectorAll('.dashboard-card').length).toBeGreaterThan(0);
  });

  it('QA D841: shows the record number each hospital gave the patient', () => {
    portal.getHealthSummary.and.returnValue(
      of(
        summary({
          profile: {
            dateOfBirth: '1990-01-01',
            hospitalRecords: [
              { hospitalId: 'h-a', hospitalName: 'CHU Wend-Panga', mrn: 'CHW7848' },
              { hospitalId: 'h-b', hospitalName: 'Clinique Nabonswendé', mrn: 'CNK4035' },
            ],
          } as HealthSummaryDTO['profile'],
        }),
      ),
    );
    create();
    expect(texts('[data-testid="my-record-numbers"] li')).toEqual([
      // QA D1079: the colon is COMMON.COLON (a no-break space before it in French).
      'CHU Wend-Panga\u00a0: CHW7848',
      'Clinique Nabonswendé\u00a0: CNK4035',
    ]);
  });

  it('QA D841: shows no record-number block when no registration carries one', () => {
    create();
    expect(host().querySelector('[data-testid="my-record-numbers"]')).toBeNull();
  });

  it('links each card to its page', () => {
    create();
    const links = Array.from(host().querySelectorAll('a.card-link')).map((a) =>
      a.getAttribute('href'),
    );
    expect(links).toEqual([
      '/my-medications',
      '/my-lab-results',
      '/my-medical-history',
      '/my-sharing',
    ]);
  });
});
