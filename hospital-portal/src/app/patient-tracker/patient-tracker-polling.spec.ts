import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
import { PatientTrackerComponent } from './patient-tracker.component';
import { PatientTrackerBoard, PatientTrackerService } from '../services/patient-tracker.service';
import { PatientTrackerWsService } from '../services/patient-tracker-ws.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';

describe('Patient tracker polling', () => {
  it('polls the selected hospital, skips in-flight reads and retains stale data until recovery', fakeAsync(() => {
    const first = new Subject<PatientTrackerBoard>();
    const connection = new Subject<boolean>();
    const service = jasmine.createSpyObj<PatientTrackerService>('PatientTrackerService', [
      'getTrackerBoard',
    ]);
    service.getTrackerBoard.and.returnValue(first);
    const ws = jasmine.createSpyObj<PatientTrackerWsService>('PatientTrackerWsService', [
      'connect',
      'disconnect',
      'getEvents',
      'getConnectionState',
    ]);
    ws.getEvents.and.returnValue(of());
    ws.getConnectionState.and.returnValue(connection);
    TestBed.configureTestingModule({
      imports: [PatientTrackerComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PatientTrackerService, useValue: service },
        { provide: PatientTrackerWsService, useValue: ws },
        {
          provide: AuthService,
          useValue: { getHospitalId: () => 'primary', hasAnyRole: () => false },
        },
        {
          provide: RoleContextService,
          useValue: { effectiveHospitalIdForRequest: () => 'selected' },
        },
      ],
    });
    const fixture = TestBed.createComponent(PatientTrackerComponent);
    const c = fixture.componentInstance;
    const board: PatientTrackerBoard = {
      arrived: [],
      triage: [],
      waitingForPhysician: [],
      inProgress: [],
      awaitingResults: [],
      readyForDischarge: [],
      totalPatients: 1,
      averageWaitMinutes: 20,
    };
    try {
      fixture.detectChanges();
      expect(ws.connect).toHaveBeenCalledWith('selected');
      tick(30_000);
      expect(service.getTrackerBoard.calls.count()).toBe(1);
      first.next(board);
      service.getTrackerBoard.and.returnValue(throwError(() => new Error('offline')));
      tick(30_000);
      fixture.detectChanges();
      expect(service.getTrackerBoard).toHaveBeenCalledWith('selected');
      expect(c.board()).toBe(board);
      expect(c.loadFailed()).toBeTrue();
      const alert = fixture.nativeElement.querySelector('[role="alert"]');
      const retry = new Subject<PatientTrackerBoard>();
      service.getTrackerBoard.and.returnValue(retry);
      tick(30_000);
      fixture.detectChanges();
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBe(alert);
      const pendingCalls = service.getTrackerBoard.calls.count();
      tick(30_000);
      expect(service.getTrackerBoard.calls.count()).toBe(pendingCalls);
      retry.next({ ...board, totalPatients: 2 });
      fixture.detectChanges();
      expect(c.loadFailed()).toBeFalse();
      expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
      connection.next(true);
      service.getTrackerBoard.and.returnValue(of(board));
      tick(119_999);
      expect(service.getTrackerBoard.calls.count()).toBe(pendingCalls);
      tick(1);
      expect(service.getTrackerBoard.calls.count()).toBe(pendingCalls + 1);
    } finally {
      fixture.destroy();
    }
  }));
});
