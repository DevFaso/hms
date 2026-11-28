import { computed, signal } from '@angular/core';
import { of } from 'rxjs';
import {
  UrgentAlertIncident,
  UrgentAlertPatientMark,
  UrgentAlertsMine,
  UrgentAlertsService,
} from '../services/urgent-alerts.service';

/** What a spec seeds the stub with; everything defaults to « nothing waits ». */
export interface UrgentAlertsStubState {
  mine?: Partial<UrgentAlertsMine>;
  marks?: UrgentAlertPatientMark[];
  incidents?: UrgentAlertIncident[];
}

export type UrgentAlertsStub = UrgentAlertsService & {
  /** Replaces the reader's alerts, as a refresh would. */
  setMine(mine: Partial<UrgentAlertsMine>): void;
  refreshMine: jasmine.Spy;
  refreshMarks: jasmine.Spy;
  resetMine: jasmine.Spy;
  resetMarks: jasmine.Spy;
  incidents: jasmine.Spy;
};

const NONE: UrgentAlertsMine = {
  toAcknowledge: 0,
  documentedWaiting: 0,
  oldestRaisedAt: null,
  items: [],
};

/**
 * One `UrgentAlertsService` stand-in for page specs, backed by real signals: a spec of a screen that only hosts
 * the banner or the patient mark issues no request of its own for them.
 */
export function urgentAlertsStub(initial: UrgentAlertsStubState = {}): UrgentAlertsStub {
  const mine = signal<UrgentAlertsMine>({ ...NONE, ...initial.mine });
  const marks = signal<ReadonlyMap<string, UrgentAlertPatientMark>>(
    new Map((initial.marks ?? []).map((mark) => [mark.patientId, mark])),
  );
  return {
    mine: mine.asReadonly(),
    marks: marks.asReadonly(),
    waiting: computed(() => mine().toAcknowledge + mine().documentedWaiting),
    refreshMine: jasmine.createSpy('refreshMine'),
    refreshMarks: jasmine.createSpy('refreshMarks'),
    resetMine: jasmine.createSpy('resetMine'),
    resetMarks: jasmine.createSpy('resetMarks'),
    incidents: jasmine.createSpy('incidents').and.returnValue(of(initial.incidents ?? [])),
    setMine: (next: Partial<UrgentAlertsMine>) => mine.set({ ...NONE, ...next }),
  } as unknown as UrgentAlertsStub;
}
