import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of } from 'rxjs';
import { MySummariesComponent } from './my-summaries.component';
import { AfterVisitSummary, PatientPortalService } from '../../services/patient-portal.service';

/**
 * The patient's after-visit summaries. Asserted through the rendered DOM in
 * French (the #661 shape): a section heading only counts once the page shows
 * it translated, and a section only once it renders for the summary opened.
 */
describe('MySummariesComponent', () => {
  let fixture: ComponentFixture<MySummariesComponent>;
  let portal: jasmine.SpyObj<PatientPortalService>;

  const host = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const texts = (selector: string): string[] =>
    Array.from(host().querySelectorAll(selector)).map((el) => el.textContent?.trim() ?? '');

  function summary(overrides: Partial<AfterVisitSummary> = {}): AfterVisitSummary {
    return {
      id: 's1',
      encounterDate: '2026-09-01',
      encounterType: '',
      providerName: 'Dr Awa Traoré',
      hospitalName: 'CHU Yalgado',
      diagnoses: ['diagnosis-a'],
      treatmentSummary: '',
      disposition: '',
      dischargeCondition: '',
      followUpInstructions: 'follow-up-text',
      activityRestrictions: '',
      dietInstructions: '',
      woundCareInstructions: '',
      warningSigns: '',
      patientEducation: '',
      additionalNotes: '',
      followUpDate: null,
      medications: [],
      status: 'FINAL',
      ...overrides,
    };
  }

  function create(): void {
    fixture = TestBed.createComponent(MySummariesComponent);
    fixture.detectChanges();
  }

  beforeEach(() => {
    portal = jasmine.createSpyObj<PatientPortalService>('PatientPortalService', [
      'getAfterVisitSummaries',
    ]);
    portal.getAfterVisitSummaries.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [MySummariesComponent, TranslateModule.forRoot()],
      providers: [{ provide: PatientPortalService, useValue: portal }],
    });
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        SUMMARIES: {
          TITLE: 'Résumés de visite',
          LOADING: 'Chargement…',
          EMPTY_TITLE: 'Aucun résumé',
          DIAGNOSES: 'Diagnostics',
          FOLLOW_UP_INSTRUCTIONS: 'Consignes de suivi',
          MEDICATIONS: 'Médicaments',
          PRINT: 'Imprimer',
        },
      },
    });
  });

  it('shows the translated loading state until the list arrives', () => {
    const pending = new Subject<AfterVisitSummary[]>();
    portal.getAfterVisitSummaries.and.returnValue(pending);
    create();
    expect(texts('.portal-loading span')).toEqual(['Chargement…']);

    pending.next([]);
    fixture.detectChanges();
    expect(host().querySelector('.portal-loading')).toBeNull();
  });

  it('shows the translated empty state when there is no summary', () => {
    create();
    expect(texts('h1')[0]).toContain('Résumés de visite');
    expect(texts('.portal-empty h3')).toEqual(['Aucun résumé']);
  });

  it('lists each summary collapsed, with its provider and hospital', () => {
    portal.getAfterVisitSummaries.and.returnValue(of([summary()]));
    create();
    expect(texts('.avs-provider')).toEqual(['Dr Awa Traoré']);
    expect(texts('.avs-dept')[0]).toContain('CHU Yalgado');
    expect(host().querySelector('.avs-body')).toBeNull();
  });

  it('opens a summary on click and shows only the sections it has', () => {
    portal.getAfterVisitSummaries.and.returnValue(of([summary()]));
    create();
    (host().querySelector('.avs-header') as HTMLElement).click();
    fixture.detectChanges();

    expect(texts('.avs-section h4')).toEqual(['Diagnostics', 'Consignes de suivi']);
    expect(texts('.avs-tag')).toEqual(['diagnosis-a']);
    expect(texts('.avs-print-btn')[0]).toContain('Imprimer');
  });

  it('renders the medication table when the summary carries medications', () => {
    portal.getAfterVisitSummaries.and.returnValue(
      of([summary({ medications: [{ name: 'med-a', dosage: 'dose-a', frequency: 'freq-a' }] })]),
    );
    create();
    fixture.componentInstance.toggle('s1');
    fixture.detectChanges();
    expect(texts('.avs-section h4')).toContain('Médicaments');
    expect(texts('.avs-med-table tbody td')).toEqual(['med-a', 'dose-a', 'freq-a']);
  });

  it('opens and closes from the keyboard', () => {
    portal.getAfterVisitSummaries.and.returnValue(of([summary()]));
    create();
    const header = host().querySelector('.avs-header') as HTMLElement;
    header.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(host().querySelector('.avs-body')).not.toBeNull();

    header.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    fixture.detectChanges();
    expect(host().querySelector('.avs-body')).toBeNull();
  });
});
