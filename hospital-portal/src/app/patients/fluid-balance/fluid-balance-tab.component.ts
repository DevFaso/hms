import {
  Component,
  ElementRef,
  Input,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
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
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { toServerDateTime } from '../../shared/date-utils';
import { ModalDialogDirective } from '../../shared/a11y/modal-dialog.directive';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { refuseFields } from '../../shared/forms/invalid-fields';

type WindowPreset = '24h' | '48h' | '7d';

const WINDOW_HOURS: Record<WindowPreset, number> = { '24h': 24, '48h': 48, '7d': 168 };

/** QA D1279: the two required fields of the entry dialog, in reading order. */
const IO_ROUTE_ID = 'io-route';
const IO_VOLUME_ID = 'io-volume';
const IO_FIELDS = [IO_ROUTE_ID, IO_VOLUME_ID];

/**
 * Fluid intake/output per patient (P3 #18, flowsheets/I&O) — the first
 * consumer of /patients/{id}/intake-output. Entry is modal-per-timepoint
 * (the labor/postpartum house pattern), not an inline-editable grid.
 * Totals and balance come from the server; this component never sums.
 */
@Component({
  selector: 'app-fluid-balance-tab',
  standalone: true,
  imports: [
    ModalDialogDirective,
    SubmitGuardDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
  ],
  templateUrl: './fluid-balance-tab.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './fluid-balance-tab.component.scss',
})
export class FluidBalanceTabComponent implements OnInit {
  @Input({ required: true }) patientId!: string;

  private readonly fluidService = inject(FluidBalanceService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

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
    // #948 review: observation times are stored on the server's UTC clock
    // (entered through toServerDateTime, read back through serverTime), so
    // the window start is sent on that clock too. Sent as the local wall
    // clock, a 24 h window covered 28 h in New York and 22 h east of UTC.
    const from = new Date(Date.now() - hours * 3600 * 1000).toISOString().slice(0, 19);
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

  /**
   * QA D1279: a volume typed but not positive (0, -5) is not blank, so the
   * guard's required check lets it through; this is its reason.
   */
  volumeRefusal(): string | null {
    const volume = this.formVolume();
    if (volume === null || (volume as unknown) === '' || volume > 0) return null;
    return this.translate.instant('FLUID_BALANCE.VOLUME_REQUIRED');
  }

  submit(): void {
    const route = this.formRoute();
    const volume = this.formVolume();
    // QA D1279 (D1256 class): every blank required field is named and marked
    // at once, never one per press (the guard on "Enregistrer" says the same
    // before this runs; this holds for any other caller).
    const blank = [
      ...(route ? [] : [IO_ROUTE_ID]),
      ...(volume === null || (volume as unknown) === '' ? [IO_VOLUME_ID] : []),
    ];
    if (!route || blank.length > 0) {
      refuseFields(
        this.host.nativeElement,
        blank,
        IO_FIELDS,
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      const labels = blank.map((id) =>
        this.translate.instant(
          id === IO_ROUTE_ID ? 'FLUID_BALANCE.FIELD_ROUTE' : 'FLUID_BALANCE.FIELD_VOLUME',
        ),
      );
      this.toast.error(
        this.translate.instant('COMMON.MISSING_REQUIRED_FIELDS', { fields: labels.join(', ') }),
      );
      return;
    }
    if (!volume || volume <= 0) {
      const message = this.translate.instant('FLUID_BALANCE.VOLUME_REQUIRED');
      refuseFields(this.host.nativeElement, [IO_VOLUME_ID], IO_FIELDS, message);
      this.toast.error(message);
      return;
    }
    if (this.saving()) return;
    // In-flight guard set BEFORE the call is dispatched (the #443 lesson).
    this.saving.set(true);
    this.fluidService
      .record(this.patientId, {
        route,
        volumeMl: volume,
        // QA D777/D784: the picker gives local wall time; the server keeps UTC.
        observationTime: this.formTime() ? toServerDateTime(this.formTime()) : undefined,
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
}
