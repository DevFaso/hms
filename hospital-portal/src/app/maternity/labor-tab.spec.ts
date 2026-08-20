import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
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
    component.submitStart();

    expect(laborSpy.startEpisode).toHaveBeenCalledWith('p1', jasmine.any(Object));
    expect(toastSpy.success).toHaveBeenCalled();
    expect(component.startFormOpen()).toBeFalse();
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
    component.submitDelivery();

    expect(laborSpy.recordDelivery).toHaveBeenCalledWith('p1', 'e1', jasmine.any(Object));
    expect(component.deliveryFormOpen()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('load failure surfaces the load-error toast', () => {
    laborSpy.episodes.and.returnValue(throwError(() => new Error('boom')));

    component.onPatientChange({ id: 'p1' } as PatientResponse);

    expect(toastSpy.error).toHaveBeenCalled();
  });
});
