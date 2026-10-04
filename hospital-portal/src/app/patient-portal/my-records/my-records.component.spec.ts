import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { MyRecordsComponent } from './my-records.component';
import {
  HealthSummaryDTO,
  ImmunizationSummary,
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

  it('asks for up to 50 lab results', () => {
    create();
    expect(portal.getMyLabResults).toHaveBeenCalledWith(50);
  });

  it('shows the first four medications and counts the rest', () => {
    const meds = ['a', 'b', 'c', 'd', 'e', 'f'].map(
      (n) => ({ id: n, medicationName: `med-${n}`, dosage: '' }) as MedicationSummary,
    );
    portal.getMyMedications.and.returnValue(of(meds));
    create();
    const firstCard = host().querySelector('.dashboard-card')!;
    const names = Array.from(firstCard.querySelectorAll('.card-item-name')).map((el) =>
      el.textContent?.trim(),
    );
    expect(names).toEqual(['med-a', 'med-b', 'med-c', 'med-d']);
    expect(firstCard.querySelector('.card-more')?.textContent?.trim()).toBe('+ 2 de plus');
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
