import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnChanges,
  inject,
  input,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { ToastService } from '../../core/toast.service';
import { ImagingPacsService } from '../../services/imaging-pacs.service';
import { refusalReason } from '../../shared/refusal-reason';

type UltrasoundReporting = 'PHYSICIANS_ONLY' | 'NON_PHYSICIAN';

/**
 * Policy rule 3 (owner 2026-11-20, PROVISIONAL): a hospital's imaging reporting settings (V346).
 *
 * One setting, « Échographie : compte rendu par un non-radiologue »: whether a non-physician may
 * report an ultrasound at this hospital. It defaults to physicians only. It is a recorded
 * decision only: nothing opens reporting to anyone on it (sonographer reporting is not built),
 * and the page says so. A hospital admin sets their own hospital; the super-admin the one chosen
 * on the page that hosts this block.
 */
@Component({
  selector: 'app-imaging-reporting-settings',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="reporting-settings" data-testid="imaging-reporting-settings">
      <h2 class="reporting-settings__title">
        {{ 'IMAGING_REPORTING_SETTINGS.TITLE' | translate }}
      </h2>
      @if (loadFailed()) {
        <p class="reporting-settings__note" role="alert">
          {{ 'IMAGING_REPORTING_SETTINGS.LOAD_FAILED' | translate }}
        </p>
      } @else if (loading()) {
        <p class="reporting-settings__note" role="status">{{ 'COMMON.LOADING' | translate }}</p>
      } @else {
        <fieldset class="reporting-settings__choice">
          <legend>{{ 'IMAGING_REPORTING_SETTINGS.ULTRASOUND' | translate }}</legend>
          <div class="reporting-settings__option">
            <input
              type="radio"
              id="us-reporting-physicians"
              name="ultrasoundReporting"
              value="PHYSICIANS_ONLY"
              data-testid="us-reporting-physicians"
              [(ngModel)]="choice"
            />
            <label for="us-reporting-physicians">{{
              'IMAGING_REPORTING_SETTINGS.PHYSICIANS_ONLY' | translate
            }}</label>
          </div>
          <div class="reporting-settings__option">
            <input
              type="radio"
              id="us-reporting-non-physician"
              name="ultrasoundReporting"
              value="NON_PHYSICIAN"
              data-testid="us-reporting-non-physician"
              [(ngModel)]="choice"
            />
            <label for="us-reporting-non-physician">{{
              'IMAGING_REPORTING_SETTINGS.NON_PHYSICIAN' | translate
            }}</label>
          </div>
        </fieldset>
        <p class="reporting-settings__note" data-testid="us-reporting-provisional">
          {{ 'IMAGING_REPORTING_SETTINGS.SETTING_ONLY' | translate }}
        </p>
        <button
          type="button"
          class="btn-primary"
          data-testid="us-reporting-save"
          [disabled]="saving()"
          (click)="save()"
        >
          {{ 'COMMON.SAVE' | translate }}
        </button>
      }
    </section>
  `,
  styles: `
    .reporting-settings {
      margin-top: 24px;
      padding-top: 16px;
      border-top: 1px solid #e2e8f0;
      min-width: 0;
    }
    .reporting-settings__title {
      margin: 0 0 12px;
      font-size: 1.1rem;
    }
    .reporting-settings__choice {
      margin: 0 0 8px;
      padding: 0;
      border: 0;
      min-width: 0;
    }
    .reporting-settings__choice legend {
      padding: 0;
      margin-bottom: 6px;
      font-weight: 600;
      overflow-wrap: anywhere;
    }
    .reporting-settings__option {
      display: flex;
      align-items: flex-start;
      gap: 8px;
      margin-bottom: 4px;
    }
    .reporting-settings__option label {
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .reporting-settings__note {
      margin: 0 0 12px;
      color: #64748b;
      overflow-wrap: anywhere;
    }
  `,
})
export class ImagingReportingSettingsComponent implements OnChanges {
  readonly hospitalId = input.required<string>();

  private readonly pacs = inject(ImagingPacsService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  readonly loading = signal(false);
  readonly saving = signal(false);
  readonly loadFailed = signal(false);
  /** What the server holds. */
  readonly saved = signal<UltrasoundReporting>('PHYSICIANS_ONLY');
  choice: UltrasoundReporting = 'PHYSICIANS_ONLY';

  ngOnChanges(): void {
    const id = this.hospitalId();
    if (!id) return;
    this.loading.set(true);
    this.loadFailed.set(false);
    this.pacs
      .reportingSettings(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => {
          this.apply(settings.ultrasoundNonPhysicianReporting);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.loadFailed.set(true);
        },
      });
  }

  save(): void {
    const id = this.hospitalId();
    // An unchanged choice is sent too (the server writes and audits nothing then): the button is
    // never silently disabled.
    if (!id || this.saving()) return;
    this.saving.set(true);
    this.pacs
      .setReportingSettings(id, this.choice === 'NON_PHYSICIAN')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (settings) => {
          this.saving.set(false);
          this.apply(settings.ultrasoundNonPhysicianReporting);
          this.toast.success(this.translate.instant('IMAGING_REPORTING_SETTINGS.SAVED'));
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('IMAGING_REPORTING_SETTINGS.SAVE_FAILED'),
          );
        },
      });
  }

  private apply(nonPhysician: boolean): void {
    const value: UltrasoundReporting = nonPhysician ? 'NON_PHYSICIAN' : 'PHYSICIANS_ONLY';
    this.saved.set(value);
    this.choice = value;
  }
}
