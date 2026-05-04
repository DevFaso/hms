import { CommonModule, DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { catchError, forkJoin, of } from 'rxjs';

import {
  HospitalLifecycleResponse,
  HospitalLifecycleState,
} from '../services/hospital-lifecycle.model';
import { HospitalLifecycleService } from '../services/hospital-lifecycle.service';
import { HospitalResponse, HospitalService } from '../services/hospital.service';

type ActionKind = 'suspend' | 'restore' | 'archive' | 'schedule-purge' | 'cancel-purge';

interface ActionDialog {
  kind: ActionKind;
  reason: string;
  scheduledFor: string;
  busy: boolean;
  errorKey: string | null;
}

@Component({
  selector: 'app-hospital-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, DatePipe],
  templateUrl: './hospital-detail.html',
  styleUrl: './hospital-detail.scss',
})
export class HospitalDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly hospitalService = inject(HospitalService);
  private readonly lifecycleService = inject(HospitalLifecycleService);

  readonly hospitalId = signal<string>('');
  readonly hospital = signal<HospitalResponse | null>(null);
  readonly lifecycle = signal<HospitalLifecycleResponse | null>(null);
  readonly loading = signal(true);
  readonly errored = signal(false);
  readonly dialog = signal<ActionDialog | null>(null);

  readonly state = computed<HospitalLifecycleState | null>(() => this.lifecycle()?.state ?? null);

  readonly canSuspend = computed(() => this.state() === 'ACTIVE');
  readonly canRestore = computed(() => this.state() === 'SUSPENDED' || this.state() === 'ARCHIVED');
  readonly canArchive = computed(() => this.state() === 'ACTIVE' || this.state() === 'SUSPENDED');
  readonly canSchedulePurge = computed(() => this.state() === 'ARCHIVED');
  readonly canCancelPurge = computed(() => this.state() === 'PURGE_SCHEDULED');

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.errored.set(true);
      this.loading.set(false);
      return;
    }
    this.hospitalId.set(id);
    this.refresh();
  }

  refresh(): void {
    this.loading.set(true);
    this.errored.set(false);

    // Copilot review fix — coordinate both fetches with forkJoin so
    // loading flips off only after BOTH calls complete; the prior
    // staggered subscribe could clear loading before the hospital
    // request had returned.
    forkJoin({
      hospital: this.hospitalService.getById(this.hospitalId()).pipe(catchError(() => of(null))),
      lifecycle: this.lifecycleService.get(this.hospitalId()).pipe(catchError(() => of(null))),
    }).subscribe(({ hospital, lifecycle }) => {
      if (hospital === null || lifecycle === null) {
        this.errored.set(true);
      }
      if (hospital !== null) {
        this.hospital.set(hospital);
      }
      if (lifecycle !== null) {
        this.lifecycle.set(lifecycle);
      }
      this.loading.set(false);
    });
  }

  // ── Action dialog ────────────────────────────────────────────────────

  openDialog(kind: ActionKind): void {
    this.dialog.set({
      kind,
      reason: '',
      scheduledFor: this.defaultPurgeDate(),
      busy: false,
      errorKey: null,
    });
  }

  closeDialog(): void {
    this.dialog.set(null);
  }

  patchDialog<K extends keyof ActionDialog>(key: K, value: ActionDialog[K]): void {
    this.dialog.update((current) => (current ? { ...current, [key]: value } : current));
  }

  submitDialog(): void {
    const state = this.dialog();
    if (!state) return;
    this.dialog.set({ ...state, busy: true, errorKey: null });

    this.dispatchLifecycleAction(state)
      .pipe(
        catchError(() => {
          this.dialog.update((current) =>
            current
              ? { ...current, busy: false, errorKey: 'HOSPITAL_LIFECYCLE.ERROR.ACTION_FAILED' }
              : current,
          );
          return of(null);
        }),
      )
      .subscribe((updated) => {
        if (!updated) return;
        this.lifecycle.set(updated);
        this.dialog.set(null);
      });
  }

  /**
   * Dispatch the lifecycle service call matching {@code state.kind}.
   * Extracted from {@link submitDialog} so the ternary chain doesn't
   * trip Sonar's nested-ternary rule (S3358).
   */
  private dispatchLifecycleAction(state: ActionDialog) {
    const id = this.hospitalId();
    const reason = state.reason.trim();
    switch (state.kind) {
      case 'suspend':
        return this.lifecycleService.suspend(id, { reason });
      case 'restore':
        return this.lifecycleService.restore(id);
      case 'archive':
        return this.lifecycleService.archive(id, { reason });
      case 'schedule-purge':
        return this.lifecycleService.schedulePurge(id, {
          reason,
          scheduledFor: state.scheduledFor,
        });
      case 'cancel-purge':
        return this.lifecycleService.cancelPurge(id);
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────

  needsReason(kind: ActionKind): boolean {
    return kind === 'suspend' || kind === 'archive' || kind === 'schedule-purge';
  }

  needsScheduledFor(kind: ActionKind): boolean {
    return kind === 'schedule-purge';
  }

  isReasonValid(state: ActionDialog): boolean {
    if (!this.needsReason(state.kind)) return true;
    return state.reason.trim().length >= 5;
  }

  /**
   * MVP-2 default — schedule purge 30 days out unless the operator
   * picks a different date.
   */
  private defaultPurgeDate(): string {
    const d = new Date();
    d.setDate(d.getDate() + 30);
    return d.toISOString().slice(0, 10);
  }

  stateColor(state: HospitalLifecycleState | null): string {
    switch (state) {
      case 'ACTIVE':
        return '#10b981';
      case 'SUSPENDED':
        return '#f59e0b';
      case 'ARCHIVED':
        return '#64748b';
      case 'PURGE_SCHEDULED':
        return '#dc2626';
      case 'PURGED':
        return '#1e293b';
      default:
        return '#94a3b8';
    }
  }
}
