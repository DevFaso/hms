import { Component, OnInit, OnDestroy, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, interval, of } from 'rxjs';
import { exhaustMap, catchError } from 'rxjs/operators';
import {
  PatientTrackerService,
  PatientTrackerBoard,
  PatientTrackerItem,
} from '../services/patient-tracker.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule } from '@ngx-translate/core';
import { CheckoutDialogComponent } from '../checkout/checkout-dialog/checkout-dialog.component';
import {
  EncounterService,
  EncounterResponse,
  AfterVisitSummary,
} from '../services/encounter.service';

export interface TrackerColumn {
  key: keyof Pick<
    PatientTrackerBoard,
    | 'arrived'
    | 'triage'
    | 'waitingForPhysician'
    | 'inProgress'
    | 'awaitingResults'
    | 'readyForDischarge'
  >;
  label: string;
  colorClass: string;
}

@Component({
  selector: 'app-patient-tracker',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, CheckoutDialogComponent],
  templateUrl: './patient-tracker.component.html',
  styleUrl: './patient-tracker.component.scss',
})
export class PatientTrackerComponent implements OnInit, OnDestroy {
  private readonly trackerService = inject(PatientTrackerService);
  private readonly auth = inject(AuthService);
  private readonly encounterService = inject(EncounterService);

  private refreshSub?: Subscription;
  private static readonly REFRESH_MS = 30_000;

  board = signal<PatientTrackerBoard | null>(null);
  loading = signal(false);
  lastRefreshed = signal<Date | null>(null);

  readonly columns: TrackerColumn[] = [
    { key: 'arrived', label: 'TRACKER.COL_CHECKED_IN', colorClass: 'col-arrived' },
    { key: 'triage', label: 'TRACKER.COL_TRIAGE', colorClass: 'col-triage' },
    { key: 'waitingForPhysician', label: 'TRACKER.COL_WAITING', colorClass: 'col-waiting' },
    { key: 'inProgress', label: 'TRACKER.COL_IN_PROGRESS', colorClass: 'col-in-progress' },
    { key: 'awaitingResults', label: 'TRACKER.COL_AWAITING_RESULTS', colorClass: 'col-awaiting' },
    { key: 'readyForDischarge', label: 'TRACKER.COL_READY_DISCHARGE', colorClass: 'col-discharge' },
  ];

  totalPatients = computed(() => this.board()?.totalPatients ?? 0);
  averageWait = computed(() => this.board()?.averageWaitMinutes ?? 0);

  getColumnItems(key: string): PatientTrackerItem[] {
    const b = this.board();
    if (!b) return [];
    return (b as unknown as Record<string, PatientTrackerItem[]>)[key] ?? [];
  }

  ngOnInit(): void {
    this.loadBoard();
    this.refreshSub = interval(PatientTrackerComponent.REFRESH_MS)
      .pipe(
        exhaustMap(() => {
          const hospitalId = this.auth.getHospitalId();
          if (!hospitalId) return of(null);
          return this.trackerService.getTrackerBoard(hospitalId).pipe(catchError(() => of(null)));
        }),
      )
      .subscribe((data) => {
        if (data) {
          this.board.set(data);
          this.lastRefreshed.set(new Date());
        }
      });
  }

  ngOnDestroy(): void {
    this.refreshSub?.unsubscribe();
  }

  loadBoard(): void {
    const hospitalId = this.auth.getHospitalId();
    if (!hospitalId) return;

    this.loading.set(true);
    this.trackerService.getTrackerBoard(hospitalId).subscribe({
      next: (data) => {
        this.board.set(data);
        this.lastRefreshed.set(new Date());
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
      },
    });
  }

  refresh(): void {
    this.loadBoard();
  }

  getWaitClass(minutes: number): string {
    if (minutes >= 30) return 'wait-critical';
    if (minutes >= 15) return 'wait-warning';
    return 'wait-ok';
  }

  getAcuityClass(acuity: string): string {
    if (!acuity) return 'acuity-routine';
    const upper = acuity.toUpperCase();
    if (upper.startsWith('ESI-1') || upper === 'EMERGENT') return 'acuity-emergent';
    if (upper.startsWith('ESI-2') || upper === 'URGENT') return 'acuity-urgent';
    if (upper.startsWith('ESI-3')) return 'acuity-moderate';
    return 'acuity-routine';
  }

  /* ── Check-Out dialog (MVP 6) ─────────── */
  checkoutEncounter = signal<EncounterResponse | null>(null);
  showCheckoutDialog = signal(false);

  openCheckout(item: PatientTrackerItem): void {
    this.encounterService.getById(item.encounterId).subscribe({
      next: (encounter) => {
        this.checkoutEncounter.set(encounter);
        this.showCheckoutDialog.set(true);
      },
    });
  }

  onCheckoutDismissed(): void {
    this.showCheckoutDialog.set(false);
    this.checkoutEncounter.set(null);
  }

  onCheckoutCompleted(_avs: AfterVisitSummary): void {
    this.showCheckoutDialog.set(false);
    this.checkoutEncounter.set(null);
    this.loadBoard();
  }

  /* ── Complete Triage — advance TRIAGE → WAITING ─── */
  triageInProgress = signal(false);

  completeTriage(item: PatientTrackerItem): void {
    this.triageInProgress.set(true);
    this.encounterService.completeTriage(item.encounterId).subscribe({
      next: () => {
        this.triageInProgress.set(false);
        this.loadBoard();
      },
      error: () => {
        this.triageInProgress.set(false);
      },
    });
  }
}
