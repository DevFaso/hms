import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';

import { PostpartumTabComponent } from './postpartum-tab';
import { PostpartumSchedule, PostpartumService } from '../services/postpartum.service';
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
      'instrument',
      'record',
      'history',
      'acknowledge',
    ]);
    screeningSpy.history.and.returnValue(of([]));
    screeningSpy.instrument.and.returnValue(of(instrument));

    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warning']);
    const patientSpy = jasmine.createSpyObj('PatientService', ['search', 'lookup']);
    patientSpy.search.and.returnValue(of([]));
    patientSpy.lookup.and.returnValue(of([]));
    const authSpy = jasmine.createSpyObj('AuthService', ['getUserProfile']);
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
    // An older schedule without the hook must not lock the button forever.
    component.schedule.set({} as PostpartumSchedule);
    expect(component.screeningAvailable()).toBeTrue();
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
});
