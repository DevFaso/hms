import { nowLocalDatetime } from '../shared/date-utils';
import { TestBed } from '@angular/core/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { LaborTabComponent } from './labor-tab';
import {
  LaborService,
  LaborEpisodeResponse,
  PartographEntryResponse,
} from '../services/labor.service';
import { ToastService } from '../core/toast.service';
import { PatientService, PatientResponse } from '../services/patient.service';

function episode(id: string, status: LaborEpisodeResponse['status']): LaborEpisodeResponse {
  return {
    id,
    patientId: 'p1',
    patientName: 'Awa Traore',
    hospitalId: 'h1',
    registrationId: null,
    maternalHistoryId: null,
    admittedByStaffName: null,
    laborOnsetAt: null,
    admittedAt: '2026-08-20T06:00:00',
    membraneStatus: null,
    membraneRuptureAt: null,
    gestationalAgeWeeks: 38,
    gravida: 2,
    para: 1,
    activePhaseStartAt: null,
    status,
    outcome: null,
    riskNotes: null,
    entryCount: 0,
    deliveryRecorded: false,
  };
}

function entry(
  id: string,
  alerts: PartographEntryResponse['alerts'] = [],
): PartographEntryResponse {
  return {
    id,
    episodeId: 'e1',
    patientId: 'p1',
    observationTime: '2026-08-20T08:00:00',
    documentedAt: '2026-08-20T08:01:00',
    lateEntry: false,
    recordedByStaffName: null,
    fetalHeartRateBpm: 140,
    liquorColour: 'CLEAR',
    mouldingDegree: null,
    cervicalDilationCm: 5,
    descentFifths: 3,
    contractionsPerTenMinutes: 3,
    contractionDurationSeconds: 40,
    oxytocinDropsPerMinute: null,
    drugsGiven: null,
    ivFluids: null,
    pulseBpm: 88,
    systolicBpMmHg: 120,
    diastolicBpMmHg: 80,
    temperatureCelsius: 37.0,
    urineOutputMl: null,
    urineProtein: null,
    urineAcetone: null,
    notes: null,
    alerts,
    hoursSinceActivePhaseStart: 1,
  };
}

describe('LaborTabComponent', () => {
  let component: LaborTabComponent;
  let laborSpy: jasmine.SpyObj<LaborService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let patientSpy: jasmine.SpyObj<PatientService>;

  beforeEach(async () => {
    laborSpy = jasmine.createSpyObj('LaborService', [
      'startEpisode',
      'episodes',
      'addEntry',
      'entries',
      'recordDelivery',
      'delivery',
    ]);
    laborSpy.episodes.and.returnValue(of([]));
    laborSpy.entries.and.returnValue(of([]));
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info']);
    patientSpy = jasmine.createSpyObj('PatientService', ['search', 'lookup']);
    patientSpy.search.and.returnValue(of([]));
    patientSpy.lookup.and.returnValue(of([]));

    await TestBed.configureTestingModule({
      imports: [LaborTabComponent, TranslateModule.forRoot()],
      providers: [
        { provide: LaborService, useValue: laborSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: PatientService, useValue: patientSpy },
      ],
    }).compileComponents();

    component = TestBed.createComponent(LaborTabComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('D65: with no patient picked, shows the labour hint, not the referrals one', () => {
    const fixture = TestBed.createComponent(LaborTabComponent);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('LABOR.PICK_PATIENT_HINT');
    expect(text).not.toContain('MATERNITY.PICK_PATIENT_HINT');
  });

  it('picking a patient loads episodes and entries of the active episode', () => {
    laborSpy.episodes.and.returnValue(of([episode('e1', 'ACTIVE')]));
    laborSpy.entries.and.returnValue(of([entry('pe1')]));

    component.onPatientChange({ id: 'p1' } as PatientResponse);

    expect(laborSpy.episodes).toHaveBeenCalledWith('p1');
    expect(laborSpy.entries).toHaveBeenCalledWith('p1', 'e1');
    expect(component.activeEpisode()?.id).toBe('e1');
    expect(component.entries().length).toBe(1);
  });

  it('startEpisode posts and reloads on success', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    laborSpy.startEpisode.and.returnValue(of(episode('e2', 'ACTIVE')));
    laborSpy.episodes.and.returnValue(of([episode('e2', 'ACTIVE')]));

    component.openStartForm();
    component.startForm.laborOnsetAt = '2026-10-19T06:00';
    component.submitStart();

    expect(laborSpy.startEpisode).toHaveBeenCalledWith('p1', jasmine.any(Object));
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.startFormOpen()).toBeFalse();
  });

  it('D295: an episode is not started without an onset time', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);

    component.openStartForm();
    component.submitStart();

    expect(laborSpy.startEpisode).not.toHaveBeenCalled();
    expect(component.startFormOpen()).toBeTrue();
  });

  it('D214: a refused start shows the server reason and sends the override', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    laborSpy.startEpisode.and.returnValue(
      throwError(() => ({ status: 400, error: { message: 'Naissance récente : motif requis' } })),
    );

    component.openStartForm();
    component.startForm.laborOnsetAt = '2026-10-19T06:00';
    component.startForm.recentDeliveryOverrideReason = 'Second jumeau';
    component.submitStart();

    expect(laborSpy.startEpisode).toHaveBeenCalledWith(
      'p1',
      jasmine.objectContaining({ recentDeliveryOverrideReason: 'Second jumeau' }),
    );
    expect(toastSpy.error).toHaveBeenCalledWith('Naissance récente : motif requis');
    expect(component.startFormOpen()).toBeTrue();
  });

  it('submitEntry rejects an all-empty form', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.episodes.set([episode('e1', 'ACTIVE')]);
    component.entryForm = {};

    component.submitEntry();

    expect(toastSpy.error).toHaveBeenCalled();
    expect(laborSpy.addEntry).not.toHaveBeenCalled();
  });

  it('submitEntry surfaces URGENT alerts as error toasts', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.episodes.set([episode('e1', 'ACTIVE')]);
    component.entryForm = { fetalHeartRateBpm: 90 };
    laborSpy.addEntry.and.returnValue(
      of(
        entry('pe2', [
          {
            type: 'FETAL_HEART_RATE',
            severity: 'URGENT',
            code: 'labor-fetal-bradycardia',
            message: 'FHR 90 below 110',
            triggeredBy: 'fetalHeartRateBpm',
            createdAt: '2026-08-20T08:00:00',
          },
        ]),
      ),
    );
    laborSpy.episodes.and.returnValue(of([episode('e1', 'ACTIVE')]));

    component.submitEntry();

    expect(toastSpy.success).toHaveBeenCalled(); // entry saved
    expect(toastSpy.error).toHaveBeenCalledWith('FHR 90 below 110');
    expect(component.entryFormOpen()).toBeFalse();
  });

  it('submitDelivery requires a delivery mode', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.episodes.set([episode('e1', 'ACTIVE')]);
    component.deliveryForm = {};

    component.submitDelivery();

    expect(toastSpy.error).toHaveBeenCalled();
    expect(laborSpy.recordDelivery).not.toHaveBeenCalled();
  });

  it('QA D745/D746: a delivery dated in the future shows the field reason', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.episodes.set([episode('e1', 'ACTIVE')]);
    laborSpy.recordDelivery.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          message: 'La demande contient des erreurs de validation',
          fieldErrors: { birthDateTime: 'Cette heure ne peut pas être dans le futur.' },
        },
      })),
    );
    component.openDeliveryForm();
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';
    component.submitDelivery();

    expect(toastSpy.error).toHaveBeenCalledWith('Cette heure ne peut pas être dans le futur.');
  });

  it('submitDelivery posts and closes the form', () => {
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.episodes.set([episode('e1', 'ACTIVE')]);
    laborSpy.recordDelivery.and.returnValue(
      of({
        id: 'd1',
        episodeId: 'e1',
        patientId: 'p1',
        deliveredByStaffName: null,
        birthDateTime: '2026-08-20T09:00:00',
        deliveryMode: 'SPONTANEOUS_VAGINAL',
        liveBirth: true,
        numberOfInfants: 1,
        infantSex: 'FEMALE',
        birthWeightGrams: 3200,
        gestationalAgeWeeksAtBirth: 38,
        apgarOneMinute: 8,
        apgarFiveMinute: 9,
        placentaDeliveredAt: null,
        placentaComplete: true,
        uterotonicGiven: true,
        estimatedBloodLossMl: 250,
        perinealTear: 'NONE',
        notes: null,
        alerts: [],
      }),
    );
    laborSpy.episodes.and.returnValue(of([episode('e1', 'DELIVERED')]));

    component.openDeliveryForm();
    // Mirror real usage: the mode is chosen after the form opens (opening
    // resets the form to its live-birth defaults).
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';
    // #941 review: "now" on the workstation's wall clock goes out as UTC.
    component.deliveryForm.birthDateTime = nowLocalDatetime(new Date(Date.UTC(2026, 9, 24, 9, 0)));
    component.submitDelivery();

    expect(laborSpy.recordDelivery).toHaveBeenCalledWith('p1', 'e1', jasmine.any(Object));
    expect(laborSpy.recordDelivery.calls.mostRecent().args[2].birthDateTime).toBe(
      '2026-10-24T09:00:00',
    );
    expect(component.deliveryFormOpen()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('load failure surfaces the load-error toast', () => {
    laborSpy.episodes.and.returnValue(throwError(() => new Error('boom')));

    component.onPatientChange({ id: 'p1' } as PatientResponse);

    expect(toastSpy.error).toHaveBeenCalled();
  });
  /* ── Round 13 browser walk (QA D806, D808, D809, D811) ── */

  function useFrench(): void {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('fr', {
      FIELD_LIMITS: {
        RANGE: '{{field}} : la valeur doit être comprise entre {{min}} et {{max}}.',
        MIN: '{{field}} : la valeur doit être supérieure ou égale à {{min}}.',
        FIELD_REASON: '{{field}} : {{reason}}',
      },
      LABOR: {
        DILATION: 'Dilatation (cm)',
        APGAR_ONE: 'APGAR à 1 min',
        BIRTH_BEFORE_LAST_ENTRY: 'Heure de naissance : avant le partogramme.',
        PLACENTA_BEFORE_BIRTH: 'Délivrance du placenta : avant la naissance.',
        ACTIVE_EPISODE: 'Travail en cours',
        STATUS_ACTIVE: 'En travail',
      },
      MATERNITY: { PARA: 'Parité (P)', PARA_ABOVE_GRAVIDA: 'P ne peut pas dépasser G.' },
    });
    translate.use('fr');
  }

  function activeEpisodeWith(entries: PartographEntryResponse[]): void {
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.episodes.set([episode('e1', 'ACTIVE')]);
    component.entries.set(entries);
  }

  it('QA D808: a dilation of 15 is refused before sending, naming the field', () => {
    useFrench();
    activeEpisodeWith([]);
    component.entryForm = { cervicalDilationCm: 15 };

    component.submitEntry();

    expect(laborSpy.addEntry).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith(
      'Dilatation (cm) : la valeur doit être comprise entre 0 et 10.',
    );
  });

  it('QA D808: an APGAR of 15 is refused before sending, naming the field', () => {
    useFrench();
    activeEpisodeWith([]);
    component.openDeliveryForm();
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';
    component.deliveryForm.apgarOneMinute = 15;

    component.submitDelivery();

    expect(laborSpy.recordDelivery).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith(
      'APGAR à 1 min : la valeur doit être comprise entre 0 et 10.',
    );
  });

  it('QA D808: a bare server constraint reason is shown after the field label', () => {
    useFrench();
    activeEpisodeWith([]);
    laborSpy.addEntry.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          error: 'Validation Failed',
          message: 'La demande contient des erreurs de validation',
          fieldErrors: { cervicalDilationCm: 'doit être inférieur ou égal à 10' },
        },
      })),
    );
    component.entryForm = { cervicalDilationCm: 9 };

    component.submitEntry();

    expect(toastSpy.error).toHaveBeenCalledWith(
      'Dilatation (cm) : doit être inférieur ou égal à 10',
    );
  });

  it('QA D806: a birth earlier than a charted partograph entry is refused before sending', () => {
    useFrench();
    const charted = { ...entry('pe1'), observationTime: '2026-10-25T18:42:00' };
    activeEpisodeWith([charted]);
    component.openDeliveryForm();
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';
    // 18:23 UTC on the workstation's wall clock, before the 18:42 UTC entry.
    component.deliveryForm.birthDateTime = nowLocalDatetime(
      new Date(Date.UTC(2026, 9, 25, 18, 23)),
    );

    component.submitDelivery();

    expect(laborSpy.recordDelivery).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('Heure de naissance : avant le partogramme.');
  });

  it('QA D806: a placenta delivered before the birth is refused before sending', () => {
    useFrench();
    activeEpisodeWith([{ ...entry('pe1'), observationTime: '2026-10-25T18:00:00' }]);
    component.openDeliveryForm();
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';
    component.deliveryForm.birthDateTime = nowLocalDatetime(
      new Date(Date.UTC(2026, 9, 25, 18, 23)),
    );
    component.deliveryForm.placentaDeliveredAt = nowLocalDatetime(
      new Date(Date.UTC(2026, 9, 25, 15, 43)),
    );

    component.submitDelivery();

    expect(laborSpy.recordDelivery).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('Délivrance du placenta : avant la naissance.');
  });

  it('QA D806: times in order (birth after the last entry, placenta after the birth) are sent', () => {
    activeEpisodeWith([{ ...entry('pe1'), observationTime: '2026-10-25T18:00:00' }]);
    laborSpy.recordDelivery.and.returnValue(throwError(() => ({ status: 500 })));
    component.openDeliveryForm();
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';
    component.deliveryForm.birthDateTime = nowLocalDatetime(
      new Date(Date.UTC(2026, 9, 25, 18, 23)),
    );
    component.deliveryForm.placentaDeliveredAt = nowLocalDatetime(
      new Date(Date.UTC(2026, 9, 25, 18, 35)),
    );

    component.submitDelivery();

    expect(laborSpy.recordDelivery).toHaveBeenCalled();
  });

  it('QA D806: the server refusal on the birth field is shown as sent (it names the field)', () => {
    activeEpisodeWith([]);
    laborSpy.recordDelivery.and.returnValue(
      throwError(() => ({
        status: 400,
        error: {
          error: 'Bad Request',
          message: "L'heure de naissance ne peut pas précéder une observation du partogramme.",
          fieldErrors: {
            birthDateTime:
              "L'heure de naissance ne peut pas précéder une observation du partogramme.",
          },
        },
      })),
    );
    component.openDeliveryForm();
    component.deliveryForm.deliveryMode = 'SPONTANEOUS_VAGINAL';

    component.submitDelivery();

    expect(toastSpy.error).toHaveBeenCalledWith(
      "L'heure de naissance ne peut pas précéder une observation du partogramme.",
    );
  });

  it('QA D809: an admission typed G1 P3 is refused before sending', () => {
    useFrench();
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.openStartForm();
    component.startForm.laborOnsetAt = '2026-10-19T06:00';
    component.startForm.gravida = 1;
    component.startForm.para = 3;

    component.submitStart();

    expect(laborSpy.startEpisode).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith('P ne peut pas dépasser G.');
  });

  it('QA D809: a negative parity is refused before sending, naming the field', () => {
    useFrench();
    component.patient.set({ id: 'p1' } as PatientResponse);
    component.openStartForm();
    component.startForm.laborOnsetAt = '2026-10-19T06:00';
    component.startForm.para = -1;

    component.submitStart();

    expect(laborSpy.startEpisode).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalledWith(
      'Parité (P) : la valeur doit être supérieure ou égale à 0.',
    );
  });

  it('QA D811: the active labour shows its status once, and a missing temperature as a bare dash', () => {
    useFrench();
    laborSpy.episodes.and.returnValue(of([episode('e1', 'ACTIVE')]));
    laborSpy.entries.and.returnValue(of([{ ...entry('pe1'), temperatureCelsius: null }]));
    const fixture = TestBed.createComponent(LaborTabComponent);
    fixture.componentInstance.onPatientChange({ id: 'p1' } as PatientResponse);
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Travail en cours');
    expect(text).not.toContain('En travail');
    expect(text).not.toContain('—°C');
    expect(text).not.toContain('— °C');
  });
});
