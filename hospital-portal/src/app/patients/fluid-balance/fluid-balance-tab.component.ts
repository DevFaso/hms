import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../../core/toast.service';
import {
  FluidBalanceService,
  INTAKE_ROUTES,
  IntakeOutputRoute,
  IntakeOutputSummary,
  OUTPUT_ROUTES,
} from '../../services/fluid-balance.service';

type WindowPreset = '24h' | '48h' | '7d';

const WINDOW_HOURS: Record<WindowPreset, number> = { '24h': 24, '48h': 48, '7d': 168 };

/**
 * Fluid intake/output per patient (P3 #18, flowsheets/I&O) — the first
 * consumer of /patients/{id}/intake-output. Entry is modal-per-timepoint
 * (the labor/postpartum house pattern), not an inline-editable grid.
 * Totals and balance come from the server; this component never sums.
 */
@Component({
  selector: 'app-fluid-balance-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './fluid-balance-tab.component.html',
  styleUrl: './fluid-balance-tab.component.scss',
})
export class FluidBalanceTabComponent implements OnInit {
  @Input({ required: true }) patientId!: string;

  private readonly fluidService = inject(FluidBalanceService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly intakeRoutes = INTAKE_ROUTES;
  readonly outputRoutes = OUTPUT_ROUTES;

  readonly loading = signal(true);
  readonly error = signal('');
  readonly summary = signal<IntakeOutputSummary | null>(null);
  readonly windowPreset = signal<WindowPreset>('24h');

  /* Record modal */
  readonly showModal = signal(false);
  readonly saving = signal(false);
  readonly formRoute = signal<IntakeOutputRoute | ''>('');
  readonly formVolume = signal<number | null>(null);
  readonly formTime = signal('');
  readonly formNotes = signal('');

  readonly balancePositive = computed(() => (this.summary()?.balanceMl ?? 0) >= 0);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set('');
    const hours = WINDOW_HOURS[this.windowPreset()];
    const from = this.toLocalDateTimeString(new Date(Date.now() - hours * 3600 * 1000));
    this.fluidService.getSummary(this.patientId, { from }).subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.loading.set(false);
      },
      error: (err) => {
        this.error.set(err?.error?.message ?? this.translate.instant('FLUID_BALANCE.LOAD_FAILED'));
        this.loading.set(false);
      },
    });
  }

  setWindow(preset: WindowPreset): void {
    this.windowPreset.set(preset);
    this.load();
  }

  openModal(): void {
    this.formRoute.set('');
    this.formVolume.set(null);
    this.formTime.set('');
    this.formNotes.set('');
    this.showModal.set(true);
  }

  closeModal(): void {
    if (this.saving()) return;
    this.showModal.set(false);
  }

  submit(): void {
    const route = this.formRoute();
    const volume = this.formVolume();
    if (!route) {
      this.toast.error(this.translate.instant('FLUID_BALANCE.ROUTE_REQUIRED'));
      return;
    }
    if (!volume || volume <= 0) {
      this.toast.error(this.translate.instant('FLUID_BALANCE.VOLUME_REQUIRED'));
      return;
    }
    if (this.saving()) return;
    // In-flight guard set BEFORE the call is dispatched (the #443 lesson).
    this.saving.set(true);
    this.fluidService
      .record(this.patientId, {
        route,
        volumeMl: volume,
        observationTime: this.formTime() || undefined,
        notes: this.formNotes() || undefined,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.showModal.set(false);
          this.toast.success(this.translate.instant('FLUID_BALANCE.SAVED'));
          this.load();
        },
        error: (err) => {
          this.saving.set(false);
          // Surface the backend refusal verbatim (house rule).
          this.toast.error(
            err?.error?.message ?? this.translate.instant('FLUID_BALANCE.SAVE_FAILED'),
          );
        },
      });
  }

  routeLabel(route: IntakeOutputRoute): string {
    return this.translate.instant(`FLUID_BALANCE.ROUTE_${route}`);
  }

  /** yyyy-MM-ddTHH:mm in LOCAL time — the backend field is a LocalDateTime,
   *  so an ISO/UTC "Z" string would fail to parse (or worse, shift the day). */
  private toLocalDateTimeString(date: Date): string {
    const pad = (n: number) => String(n).padStart(2, '0');
    return (
      `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
      `T${pad(date.getHours())}:${pad(date.getMinutes())}:00`
    );
  }
}
