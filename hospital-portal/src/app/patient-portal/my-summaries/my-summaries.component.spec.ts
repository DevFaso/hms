import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';
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
      'getVisitSummaryDetail',
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
          PENDING_SIGNATURE: 'Non finalisé — en attente de signature du praticien',
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

  it('2026-11-12: opening a summary reads its visit once and shows the content sections', () => {
    portal.getAfterVisitSummaries.and.returnValue(
      of([summary({ encounterId: 'e1', followUpInstructions: 'Boire beaucoup\n\nACT 3 jours' })]),
    );
    portal.getVisitSummaryDetail.and.returnValue(
      of({
        encounterId: 'e1',
        visitDate: '2026-09-01',
        providerName: '',
        departmentName: '',
        hospitalName: 'CHU Yalgado',
        dischargeDiagnoses: [],
        encounterStatus: 'COMPLETED',
        checkoutTimestamp: '2026-09-01T10:00:00',
        allergies: [{ allergen: 'Pénicilline', severity: 'SEVERE' }],
        clinicalPlan: 'ACT 3 jours',
      }),
    );
    create();
    fixture.componentInstance.toggle('s1');
    fixture.detectChanges();

    expect(portal.getVisitSummaryDetail).toHaveBeenCalledOnceWith('e1');
    expect(host().querySelector('[data-testid="avs-allergies"]')?.textContent).toContain(
      'Pénicilline',
    );
    expect(host().querySelector('[data-testid="avs-plan"]')?.textContent).toContain('ACT 3 jours');
    // The plan has its own section: the follow-up no longer repeats it.
    const followUp = Array.from(host().querySelectorAll('.avs-section p')).map(
      (p) => p.textContent,
    );
    expect(followUp).toContain('Boire beaucoup');
    expect(followUp).not.toContain('Boire beaucoup\n\nACT 3 jours');

    fixture.componentInstance.toggle('s1');
    fixture.componentInstance.toggle('s1');
    expect(portal.getVisitSummaryDetail).toHaveBeenCalledTimes(1);
  });

  it('retries a failed detail request when the patient reopens the summary', () => {
    portal.getAfterVisitSummaries.and.returnValue(of([summary({ encounterId: 'e1' })]));
    portal.getVisitSummaryDetail.and.returnValues(
      throwError(() => new Error('Temporary failure')),
      of({
        encounterId: 'e1',
        visitDate: '2026-09-01',
        providerName: '',
        departmentName: '',
        hospitalName: 'CHU Yalgado',
        dischargeDiagnoses: [],
        encounterStatus: 'COMPLETED',
        checkoutTimestamp: '2026-09-01T10:00:00',
        clinicalPlan: 'Recovered plan',
      }),
    );
    create();
    fixture.componentInstance.toggle('s1');
    fixture.componentInstance.toggle('s1');
    fixture.componentInstance.toggle('s1');
    fixture.detectChanges();

    expect(portal.getVisitSummaryDetail).toHaveBeenCalledTimes(2);
    expect(host().querySelector('[data-testid="avs-plan"]')?.textContent).toContain(
      'Recovered plan',
    );
  });

  it('removes a complete multi-paragraph plan suffix and preserves earlier matching paragraphs', () => {
    portal.getAfterVisitSummaries.and.returnValue(of([summary()]));
    create();
    fixture.componentInstance.details.set({
      s1: {
        encounterId: 'e1',
        visitDate: '',
        providerName: '',
        departmentName: '',
        hospitalName: '',
        dischargeDiagnoses: [],
        encounterStatus: '',
        checkoutTimestamp: '',
        clinicalPlan: 'Plan one\n\nPlan two',
      },
    });
    expect(
      fixture.componentInstance.followUpText(
        summary({
          followUpInstructions: 'Instructions\n\nPlan one\n\nPlan two',
        }),
      ),
    ).toBe('Instructions');
    expect(
      fixture.componentInstance.followUpText(
        summary({
          followUpInstructions: 'Plan one\n\nPlan two',
        }),
      ),
    ).toBe('');
    expect(
      fixture.componentInstance.followUpText(
        summary({
          followUpInstructions: 'Plan one\n\nPlan two\n\nOther instructions',
        }),
      ),
    ).toBe('Plan one\n\nPlan two\n\nOther instructions');
  });

  it('a summary with no visit keeps its card and reads nothing more', () => {
    portal.getAfterVisitSummaries.and.returnValue(of([summary()]));
    create();
    fixture.componentInstance.toggle('s1');
    fixture.detectChanges();
    expect(portal.getVisitSummaryDetail).not.toHaveBeenCalled();
    expect(host().querySelector('[data-testid="avs-detail-sections"]')).toBeNull();
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

  // Provisional platform rule: the patient's summary reads "not finalised" while a note is unsigned.
  it('shows the not-finalised banner while a note of the visit is unsigned, and not once signed', () => {
    portal.getAfterVisitSummaries.and.returnValue(
      of([summary({ id: 'pending', pendingSignature: true }), summary({ id: 'signed' })]),
    );
    create();

    expect(texts('[data-testid="avs-pending-signature"]')).toEqual([
      'pending_actions Non finalisé — en attente de signature du praticien',
    ]);
  });
});
