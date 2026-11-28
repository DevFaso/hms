import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { InBasketItem } from './in-basket.service';

/** The reader's urgent alerts of the escalation ladder at the acting hospital (GET /urgent-alerts/mine). */
export interface UrgentAlertsMine {
  /** The alerts the reader holds an item for and may acknowledge. */
  toAcknowledge: number;
  /** The alerts the reader documented that nobody else has acknowledged yet. */
  documentedWaiting: number;
  /** When the oldest of them was raised (a zone-less server time); null when there is none. */
  oldestRaisedAt: string | null;
  items: InBasketItem[];
}

/** A patient with urgent alerts nobody has acknowledged: how many, and since when the oldest waits. */
export interface UrgentAlertPatientMark {
  patientId: string;
  open: number;
  oldestRaisedAt: string | null;
}

/** One line of the register of unresolved urgent alerts (GET /urgent-alerts/incidents). */
export interface UrgentAlertIncident {
  id: string;
  /** The incidents of one patient opened on one day share it. It says nothing of the patient. */
  caseKey: string;
  source: string;
  sourceLabel: string;
  /** Null for an administrator: the register is read without the chart. */
  patientId: string | null;
  patientName: string | null;
  raisedAt: string | null;
  openedAt: string;
  minutesUnacknowledged: number;
  recipientsTold: number;
  /** 1 the covering clinician, 2 the médecin-chef, 3 the incident, then one per reminder. */
  stepsTaken: number | null;
  lastStepAt: string | null;
  acknowledgedByName: string | null;
  closedAt: string | null;
  resolution: string | null;
}

const NONE: UrgentAlertsMine = {
  toAcknowledge: 0,
  documentedWaiting: 0,
  oldestRaisedAt: null,
  items: [],
};

/** An answer of another shape (an empty page, nothing) reads as « none », never as a broken screen. */
function readMine(mine: Partial<UrgentAlertsMine> | null | undefined): UrgentAlertsMine {
  if (!mine) return NONE;
  return {
    toAcknowledge: typeof mine.toAcknowledge === 'number' ? mine.toAcknowledge : 0,
    documentedWaiting: typeof mine.documentedWaiting === 'number' ? mine.documentedWaiting : 0,
    oldestRaisedAt: mine.oldestRaisedAt ?? null,
    items: Array.isArray(mine.items) ? mine.items : [],
  };
}

/**
 * QA D4100, D4101 (r81): the urgent alerts the escalation ladder is waiting on. One copy of the reader's alerts
 * and of the patients' marks is held here, so the dashboard, the in-basket, the worklist and the bed board show
 * the same count and are refreshed together.
 */
@Injectable({ providedIn: 'root' })
export class UrgentAlertsService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/urgent-alerts';

  /** Only the latest read may write: a response issued under the previous hospital is dropped. */
  private mineRequest = 0;
  private marksRequest = 0;

  private readonly mineState = signal<UrgentAlertsMine>(NONE);
  private readonly marksState = signal<ReadonlyMap<string, UrgentAlertPatientMark>>(new Map());

  readonly mine = this.mineState.asReadonly();
  /** The alerts waiting on the reader: those to acknowledge and those she documented. */
  readonly waiting = computed(
    () => this.mineState().toAcknowledge + this.mineState().documentedWaiting,
  );
  readonly marks = this.marksState.asReadonly();

  /** Reads the reader's alerts again. A failed read keeps the last count: silence must not read as « none ». */
  refreshMine(): void {
    const request = ++this.mineRequest;
    this.http.get<UrgentAlertsMine>(`${this.baseUrl}/mine`).subscribe({
      next: (mine) => {
        if (request === this.mineRequest) this.mineState.set(readMine(mine));
      },
      error: () => undefined,
    });
  }

  /** Reads the patients' marks again. */
  refreshMarks(): void {
    const request = ++this.marksRequest;
    this.http.get<UrgentAlertPatientMark[]>(`${this.baseUrl}/patients`).subscribe({
      next: (marks) => {
        if (request !== this.marksRequest) return;
        this.marksState.set(
          new Map((Array.isArray(marks) ? marks : []).map((mark) => [mark.patientId, mark])),
        );
      },
      error: () => undefined,
    });
  }

  /** Drops the reader's alerts read under the previous hospital, before the next read is issued. */
  resetMine(): void {
    this.mineRequest++;
    this.mineState.set(NONE);
  }

  /** Drops the patients' marks read under the previous hospital, before the next read is issued. */
  resetMarks(): void {
    this.marksRequest++;
    this.marksState.set(new Map());
  }

  incidents(): Observable<UrgentAlertIncident[]> {
    return this.http
      .get<UrgentAlertIncident[]>(`${this.baseUrl}/incidents`)
      .pipe(map((lines) => (Array.isArray(lines) ? lines : [])));
  }
}
