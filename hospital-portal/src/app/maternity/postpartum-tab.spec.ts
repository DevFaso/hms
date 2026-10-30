import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

import { PostpartumTabComponent } from './postpartum-tab';
import {
  NewbornAssessmentResponse,
  PostpartumObservationResponse,
  PostpartumSchedule,
  PostpartumService,
} from '../services/postpartum.service';
import { LaborService } from '../services/labor.service';
import {
  ProInstrumentView,
  ProResponse,
  ProScreeningService,
} from '../services/pro-screening.service';
import { AuthService } from '../auth/auth.service';
import { ToastService } from '../core/toast.service';
import { PatientService, PatientResponse } from '../services/patient.service';

/**
 * Covers the mental-health screening card (Tier 2 item 47). The instrument
 * fixture is a made-up two-item form — never EPDS wording, which is
 * licensed text that reaches the app as data from a validated source.
 */
const instrument: ProInstrumentView = {
  code: 'EPDS',
  name: 'Test instrument',
  language: 'en',
  availableLanguages: ['en', 'fr'],
  maxScore: 6,
  criticalItemNo: 2,
  items: [
    {
      itemNo: 1,
      prompt: 'First question',
      options: [
        { optionNo: 0, label: 'Never' },
        { optionNo: 3, label: 'Often' },
      ],
    },
    {
      itemNo: 2,
      prompt: 'Second question',
      options: [
        { optionNo: 0, label: 'No' },
        { optionNo: 3, label: 'Yes' },
      ],
    },
  ],
};

function response(id: string, over: Partial<ProResponse> = {}): ProResponse {
  return {
    id,
    instrumentCode: 'EPDS',
    instrumentName: 'Test instrument',
    patientId: 'p1',
    source: 'STAFF_ADMINISTERED',
    language: 'en',
    administeredAt: '2026-09-01T10:00:00',
    answers: { 1: 0, 2: 0 },
    totalScore: 0,
    maxScore: 6,
    answeredItems: 2,
    totalItems: 2,
    complete: true,
    screenPositive: false,
    criticalItemPositive: false,
    escalationLevel: 0,
    ...over,
  };
}

function schedule(over: Partial<PostpartumSchedule['screening']> = {}): PostpartumSchedule {
  return {
    screening: {
      instrumentCode: 'EPDS',
      instrumentAvailable: true,
      due: true,
      escalationOpen: false,
      ...over,
    },
  } as PostpartumSchedule;
}

describe('PostpartumTabComponent — screening', () => {
  let component: PostpartumTabComponent;
  let postpartumSpy: jasmine.SpyObj<PostpartumService>;
  let screeningSpy: jasmine.SpyObj<ProScreeningService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    postpartumSpy = jasmine.createSpyObj('PostpartumService', [
      'schedule',
      'recentObservations',
      'recentNewbornAssessments',
      'createObservation',
      'createNewbornAssessment',
    ]);
    postpartumSpy.schedule.and.returnValue(of(schedule()));
    postpartumSpy.recentObservations.and.returnValue(of([]));
    postpartumSpy.recentNewbornAssessments.and.returnValue(of([]));

    const laborSpy = jasmine.createSpyObj('LaborService', ['episodes', 'delivery']);
    laborSpy.episodes.and.returnValue(of([]));

    screeningSpy = jasmine.createSpyObj('ProScreeningService', [
      'instruments',
      'instrument',
      'record',
      'history',
      'acknowledge',
    ]);
    screeningSpy.history.and.returnValue(of([]));
    screeningSpy.instrument.and.returnValue(of(instrument));
    screeningSpy.instruments.and.returnValue(
      of([{ code: 'EPDS', name: 'EPDS', languages: ['en'] }]),
    );

    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warning']);
    const patientSpy = jasmine.createSpyObj('PatientService', ['search', 'lookup']);
    patientSpy.search.and.returnValue(of([]));
    patientSpy.lookup.and.returnValue(of([]));
    const authSpy = jasmine.createSpyObj('AuthService', ['getUserProfile', 'getHospitalId']);
    authSpy.getHospitalId.and.returnValue('h1');
    authSpy.getUserProfile.and.returnValue({ staffId: 's1' });

    await TestBed.configureTestingModule({
      imports: [PostpartumTabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: PostpartumService, useValue: postpartumSpy },
        { provide: LaborService, useValue: laborSpy },
        { provide: ProScreeningService, useValue: screeningSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: PatientService, useValue: patientSpy },
        { provide: AuthService, useValue: authSpy },
      ],
    }).compileComponents();

    component = TestBed.createComponent(PostpartumTabComponent).componentInstance;
  });

  it('D121: a schedule without a phase never renders POSTPARTUM.PHASE_null', () => {
    expect(component.phaseLabelKey({ phase: null } as unknown as PostpartumSchedule)).toBe(
      'POSTPARTUM.PHASE_UNKNOWN',
    );
    expect(
      component.phaseLabelKey({ phase: 'SHIFT_BASELINE' } as unknown as PostpartumSchedule),
    ).toBe('POSTPARTUM.PHASE_SHIFT_BASELINE');
  });

  it('D65: with no patient picked, shows the postpartum hint, not the referrals one', () => {
    const fixture = TestBed.createComponent(PostpartumTabComponent);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('POSTPARTUM.PICK_PATIENT_HINT');
    expect(text).not.toContain('MATERNITY.PICK_PATIENT_HINT');
  });

  it('D86: the first postpartum observation carries the acting hospital', () => {
    postpartumSpy.createObservation.and.returnValue(
      of({ alerts: [] } as unknown as PostpartumObservationResponse),
    );
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openObservation();
    component.submitObservation();
    const body = postpartumSpy.createObservation.calls.mostRecent().args[1];
    expect(body.hospitalId).toBe('h1');
  });

  it('picking a patient loads the screening history for the plan instrument', () => {
    screeningSpy.history.and.returnValue(of([response('r1')]));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    expect(screeningSpy.history).toHaveBeenCalledWith('p1', 'EPDS', 20);
    expect(component.screenings().length).toBe(1);
    expect(component.screeningsError()).toBeFalse();
  });

  it('a failed history load is an error, not an empty list', () => {
    screeningSpy.history.and.returnValue(throwError(() => new Error('boom')));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    expect(component.screeningsError()).toBeTrue();
    expect(component.screeningsLoading()).toBeFalse();
  });

  it('disables administering until the validated text is loaded', () => {
    postpartumSpy.schedule.and.returnValue(of(schedule({ instrumentAvailable: false })));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    expect(component.screeningAvailable()).toBeFalse();
    // An older schedule without the hook must not lock the button forever:
    // the instrument list (EPDS loaded here) decides.
    component.schedule.set({} as PostpartumSchedule);
    expect(component.screeningAvailable()).toBeTrue();
  });

  it('QA D539: the disabled screening button looks disabled', () => {
    postpartumSpy.schedule.and.returnValue(of(schedule({ instrumentAvailable: false })));
    const fixture = TestBed.createComponent(PostpartumTabComponent);
    fixture.componentInstance.onPatientPicked({ id: 'p1' } as PatientResponse);
    fixture.detectChanges();
    const button = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
        '.screening-card button.btn-primary',
      ),
    )[0];
    expect(button).toBeDefined();
    expect(button.disabled).toBeTrue();
    expect(getComputedStyle(button).opacity).toBe('0.5');
    expect(getComputedStyle(button).cursor).toBe('not-allowed');
  });

  it('QA D323: with no plan and no EPDS text on the server, the button is off', () => {
    screeningSpy.instruments.and.returnValue(of([]));
    const fresh = TestBed.createComponent(PostpartumTabComponent).componentInstance;
    expect(fresh.schedule()).toBeNull();
    expect(fresh.screeningAvailable()).toBeFalse();
    fresh.schedule.set({} as PostpartumSchedule);
    expect(fresh.screeningAvailable()).toBeFalse();
  });

  it('QA D323: an instrument list that fails to load does not offer the screening', () => {
    screeningSpy.instruments.and.returnValue(throwError(() => new Error('down')));
    const fresh = TestBed.createComponent(PostpartumTabComponent).componentInstance;
    expect(fresh.screeningAvailable()).toBeFalse();
  });

  it('opening the modal loads the instrument and keeps the language the server chose', () => {
    screeningSpy.instrument.and.returnValue(of({ ...instrument, language: 'en' }));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    expect(screeningSpy.instrument).toHaveBeenCalledWith('EPDS', undefined);
    expect(component.showScreeningModal()).toBeTrue();
    expect(component.screeningInstrument()?.code).toBe('EPDS');
    expect(component.screeningLanguage).toBe('en');
  });

  it('answers survive a language switch', () => {
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    component.screeningAnswers.set({ 1: 3 });
    screeningSpy.instrument.and.returnValue(of({ ...instrument, language: 'fr' }));
    component.changeScreeningLanguage('fr');
    expect(screeningSpy.instrument).toHaveBeenCalledWith('EPDS', 'fr');
    expect(component.screeningAnswers()).toEqual({ 1: 3 });
    expect(component.screeningLanguage).toBe('fr');
  });

  it('a failed instrument load is an error state, not an empty modal', () => {
    screeningSpy.instrument.and.returnValue(throwError(() => new Error('boom')));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    expect(component.screeningInstrumentError()).toBeTrue();
    expect(component.screeningInstrumentLoading()).toBeFalse();
    expect(component.screeningInstrument()).toBeNull();
    // The pipeline survives the failure: the next request still goes out.
    screeningSpy.instrument.and.returnValue(of(instrument));
    component.changeScreeningLanguage('fr');
    expect(component.screeningInstrumentError()).toBeFalse();
    expect(component.screeningInstrument()?.code).toBe('EPDS');
  });

  it('a slow earlier language never lands on top of the one asked for last', () => {
    const slowEnglish = new Subject<ProInstrumentView>();
    screeningSpy.instrument.and.returnValue(slowEnglish);
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    screeningSpy.instrument.and.returnValue(of({ ...instrument, language: 'fr' }));
    component.changeScreeningLanguage('fr');
    expect(component.screeningLanguage).toBe('fr');

    slowEnglish.next({ ...instrument, language: 'en' });
    slowEnglish.complete();
    expect(component.screeningLanguage).toBe('fr');
    expect(component.screeningInstrument()?.language).toBe('fr');
  });

  it('a failed schedule for the previous patient does not blank the next one', () => {
    const slowFailure = new Subject<PostpartumSchedule>();
    postpartumSpy.schedule.and.returnValue(slowFailure);
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    postpartumSpy.schedule.and.returnValue(of(schedule()));
    component.onPatientPicked({ id: 'p2' } as PatientResponse);
    expect(component.schedule()).not.toBeNull();

    slowFailure.error(new Error('boom'));
    expect(component.schedule()).not.toBeNull();
  });

  it('refuses an incomplete screening and names the missing items', () => {
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    component.screeningAnswers.set({ 1: 0 });
    component.submitScreening();
    expect(screeningSpy.record).not.toHaveBeenCalled();
    expect(toastSpy.warning).toHaveBeenCalled();
    expect(component.showScreeningModal()).toBeTrue();
  });

  it('records a complete screening, prepends it and refreshes the cadence hook', () => {
    screeningSpy.record.and.returnValue(of(response('r2')));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    postpartumSpy.schedule.calls.reset();
    component.openScreening();
    component.screeningAnswers.set({ 1: 0, 2: 0 });
    component.screeningNotes = '  read aloud  ';
    component.submitScreening();

    expect(screeningSpy.record).toHaveBeenCalledWith(
      'p1',
      jasmine.objectContaining({
        instrumentCode: 'EPDS',
        language: 'en',
        answers: { 1: 0, 2: 0 },
        notes: 'read aloud',
      }),
    );
    expect(component.screenings().map((r) => r.id)).toEqual(['r2']);
    expect(component.showScreeningModal()).toBeFalse();
    expect(postpartumSpy.schedule).toHaveBeenCalledWith('p1');
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('sends the screening time as the UTC the history reads back (#948 review)', () => {
    screeningSpy.record.and.returnValue(of(response('r4')));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    component.screeningAnswers.set({ 1: 0, 2: 0 });
    component.screeningAdministeredAt = '2026-09-01T10:00';
    component.submitScreening();

    const [, sent] = screeningSpy.record.calls.mostRecent().args;
    expect(sent.administeredAt).toBe(new Date(2026, 8, 1, 10, 0).toISOString().slice(0, 19));
  });

  it('grades the saved toast by severity: safety item beats screen positive', () => {
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openScreening();
    component.screeningAnswers.set({ 1: 3, 2: 3 });

    screeningSpy.record.and.returnValue(of(response('r3', { screenPositive: true })));
    component.submitScreening();
    expect(toastSpy.warning).toHaveBeenCalledTimes(1);
    expect(toastSpy.error).not.toHaveBeenCalled();

    component.openScreening();
    component.screeningAnswers.set({ 1: 3, 2: 3 });
    screeningSpy.record.and.returnValue(
      of(response('r4', { screenPositive: true, criticalItemPositive: true, escalationLevel: 1 })),
    );
    component.submitScreening();
    expect(toastSpy.error).toHaveBeenCalledTimes(1);
    expect(toastSpy.warning).toHaveBeenCalledTimes(1);
  });

  it('only an unacknowledged safety-item answer needs acknowledgement', () => {
    expect(component.needsAcknowledgement(response('a'))).toBeFalse();
    expect(
      component.needsAcknowledgement(response('b', { criticalItemPositive: true })),
    ).toBeTrue();
    expect(
      component.needsAcknowledgement(
        response('c', { criticalItemPositive: true, acknowledgedAt: '2026-09-01T11:00:00' }),
      ),
    ).toBeFalse();
  });

  it('acknowledging replaces the row and refreshes the schedule', () => {
    const open = response('r5', { criticalItemPositive: true, escalationLevel: 2 });
    screeningSpy.history.and.returnValue(of([open]));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    postpartumSpy.schedule.calls.reset();
    screeningSpy.acknowledge.and.returnValue(
      of({ ...open, acknowledgedAt: '2026-09-01T11:00:00', acknowledgedByDisplay: 'Midwife A' }),
    );

    component.openAcknowledge(open);
    component.ackNote = 'Called the mother';
    component.submitAcknowledge();

    expect(screeningSpy.acknowledge).toHaveBeenCalledWith('p1', 'r5', 'Called the mother');
    expect(component.screenings()[0].acknowledgedAt).toBe('2026-09-01T11:00:00');
    expect(component.showAckModal()).toBeFalse();
    expect(postpartumSpy.schedule).toHaveBeenCalledWith('p1');
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('a refused acknowledgement re-fetches the list, since the row is stale', () => {
    const open = response('r6', { criticalItemPositive: true });
    screeningSpy.history.and.returnValue(of([open]));
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    screeningSpy.history.calls.reset();
    screeningSpy.acknowledge.and.returnValue(throwError(() => new Error('already acknowledged')));

    component.openAcknowledge(open);
    component.submitAcknowledge();

    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.ackSaving()).toBeFalse();
    expect(screeningSpy.history).toHaveBeenCalledWith('p1', 'EPDS', 20);
  });

  it('drops screening results that arrive after the patient changed', () => {
    const inFlight = new Subject<ProResponse[]>();
    screeningSpy.history.and.returnValue(inFlight.asObservable());
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    screeningSpy.history.and.returnValue(of([]));
    component.onPatientPicked({ id: 'p2' } as PatientResponse);
    inFlight.next([response('stale')]);
    expect(component.screenings()).toEqual([]);
  });

  it('resolves maternal and newborn alerts through their own vocabularies', () => {
    // Two `alert.type` renders, two enums, two tabs. The maternal observations
    // carry PostpartumAlertType (HEMORRHAGE, INFECTION, …); the newborn
    // assessments carry NewbornAlertType (APGAR, THERMOREGULATION, …), which
    // shares not one constant with it. Piping both through the maternal domain
    // — as the first cut of this tranche did — left every newborn alert in
    // Title-Cased English while the enum gate called the domain fully covered.
    const translate = TestBed.inject(TranslateService);
    translate.setFallbackLang('fr');
    translate.use('fr');
    translate.setTranslation('fr', {
      PORTAL: {
        ENUM: {
          POSTPARTUM_ALERT_TYPE: { HEMORRHAGE: 'maternel-hemorragie' },
          NEWBORN_ALERT_TYPE: { APGAR: 'nouveau-ne-apgar' },
        },
      },
    });
    const fixture = TestBed.createComponent(PostpartumTabComponent);
    const component = fixture.componentInstance;
    component.patient.set({ id: 'p-1', firstName: 'Awa' } as PatientResponse);
    component.observations.set([
      {
        id: 'obs-1',
        alerts: [{ type: 'HEMORRHAGE', severity: 'URGENT', message: 'saignement' }],
      } as unknown as PostpartumObservationResponse,
    ]);
    component.assessments.set([
      {
        id: 'asm-1',
        alerts: [{ type: 'APGAR', severity: 'URGENT', message: 'apgar bas' }],
      } as unknown as NewbornAssessmentResponse,
    ]);

    const textNow = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

    component.section.set('mother');
    fixture.detectChanges();
    expect(textNow()).toContain('maternel-hemorragie');

    component.section.set('newborn');
    fixture.detectChanges();
    expect(textNow()).toContain('nouveau-ne-apgar');
    // Title-Cased English is what an unkeyed value falls through to, and is
    // what the maternal domain would have produced for APGAR.
    expect(textNow()).not.toContain('Apgar ');
  });
  /* ── Round 13 browser walk (QA D807, D808) ── */

  it('QA D807: the delivery picker shows the translated mode, never the raw code', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', { LABOR: { MODE_SPONTANEOUS_VAGINAL: 'Voie basse spontanée' } });
    translate.use('fr');

    const label = component.deliveryLabel({
      id: 'd1',
      birthDateTime: '2026-10-25T18:23:00',
      deliveryMode: 'SPONTANEOUS_VAGINAL',
    } as never);

    expect(label).toContain('Voie basse spontanée');
    expect(label).not.toContain('SPONTANEOUS_VAGINAL');
  });

  it('QA D808: a newborn APGAR of 15 is refused before sending, naming the field', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      FIELD_LIMITS: { RANGE: '{{field}} : entre {{min}} et {{max}}.' },
      POSTPARTUM: { APGAR_ONE: 'APGAR à 1 min' },
    });
    translate.use('fr');
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openAssessment();
    component.assessmentForm.apgarOneMinute = 15;

    component.submitAssessment();

    expect(postpartumSpy.createNewbornAssessment).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('APGAR à 1 min : entre 0 et 10.');
  });

  it('QA D808 (sibling): a pain score of 15 is refused before sending, naming the field', () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      FIELD_LIMITS: { RANGE: '{{field}} : entre {{min}} et {{max}}.' },
      POSTPARTUM: { PAIN: 'Douleur' },
    });
    translate.use('fr');
    component.onPatientPicked({ id: 'p1' } as PatientResponse);
    component.openObservation();
    component.observationForm.painScore = 15;

    component.submitObservation();

    expect(postpartumSpy.createObservation).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('Douleur : entre 0 et 10.');
  });
  it('QA D1068: the observation and newborn tables read back what was typed', () => {
    postpartumSpy.recentObservations.and.returnValue(
      of([
        {
          id: 'o1',
          patientId: 'p1',
          observationTime: '2026-10-30T18:00:00',
          uterotonicGiven: true,
          psychosocialNotes: 'Mère anxieuse, soutien familial présent',
        } as PostpartumObservationResponse,
      ]),
    );
    postpartumSpy.recentNewbornAssessments.and.returnValue(
      of([
        {
          id: 'a1',
          patientId: 'p1',
          assessmentTime: '2026-10-30T18:10:00',
          examGeneralAppearance: 'Rose, tonique',
          examNotes: 'Fontanelle normale',
          followUpNotes: 'Revoir à J3',
        } as NewbornAssessmentResponse,
      ]),
    );
    const fixture = TestBed.createComponent(PostpartumTabComponent);
    fixture.componentInstance.onPatientPicked({ id: 'p1' } as PatientResponse);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const obs = el.querySelector('[data-testid="postpartum-observation-notes"]')?.textContent ?? '';
    expect(obs).toContain('Mère anxieuse, soutien familial présent');
    expect(obs).toContain('POSTPARTUM.UTEROTONIC');

    fixture.componentInstance.setSection('newborn');
    fixture.detectChanges();
    const nb = el.querySelector('[data-testid="newborn-assessment-notes"]')?.textContent ?? '';
    expect(nb).toContain('Rose, tonique');
    expect(nb).toContain('Fontanelle normale');
    expect(nb).toContain('Revoir à J3');
  });
});
