import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { CriticalEscalationSettings, LabService } from '../../services/lab.service';
import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';

/**
 * QA r70 (scorecard C5), provisional rule CRITICAL_RESULT_ESCALATION: a hospital's own steps of the
 * critical-result ladder (minutes after the first notice) and the doctor it names médecin-chef. Closed until
 * the administrator opens it, so the worklist above loads nothing more than before. It holds no result.
 */
@Component({
  selector: 'app-critical-escalation-settings',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="escalation-settings" data-testid="escalation-settings">
      <button
        type="button"
        class="btn-secondary"
        data-testid="escalation-settings-toggle"
        [attr.aria-expanded]="open()"
        (click)="toggle()"
      >
        {{ 'LAB_CRITICAL_PENDING.ESCALATION_TITLE' | translate }}
      </button>
      @if (open()) {
        <p class="escalation-settings__hint">
          {{ 'LAB_CRITICAL_PENDING.ESCALATION_HINT' | translate }}
        </p>
        @if (failed()) {
          <p
            class="escalation-settings__hint"
            role="alert"
            data-testid="escalation-settings-failed"
          >
            {{ 'LAB_CRITICAL_PENDING.ESCALATION_LOAD_ERROR' | translate }}
          </p>
        } @else if (settings(); as current) {
          <div class="escalation-settings__grid">
            <label for="esc-cover"
              >{{ 'LAB_CRITICAL_PENDING.ESCALATION_COVER' | translate }} *
              <input
                id="esc-cover"
                type="number"
                required
                min="5"
                max="1440"
                data-server-field="coverAfterMinutes"
                [(ngModel)]="cover"
            /></label>
            <label for="esc-lead"
              >{{ 'LAB_CRITICAL_PENDING.ESCALATION_LEAD' | translate }} *
              <input
                id="esc-lead"
                type="number"
                required
                min="5"
                max="1440"
                data-server-field="leadAfterMinutes"
                [(ngModel)]="lead"
            /></label>
            <label for="esc-unresolved"
              >{{ 'LAB_CRITICAL_PENDING.ESCALATION_UNRESOLVED' | translate }} *
              <input
                id="esc-unresolved"
                type="number"
                required
                min="5"
                max="1440"
                data-server-field="unresolvedAfterMinutes"
                [(ngModel)]="unresolved"
            /></label>
            <label for="esc-chief"
              >{{ 'LAB_CRITICAL_PENDING.ESCALATION_CHIEF' | translate }}
              <select
                id="esc-chief"
                data-server-field="medicalLeadUserId"
                [(ngModel)]="medicalLead"
              >
                <option value="">
                  {{ 'LAB_CRITICAL_PENDING.ESCALATION_CHIEF_NONE' | translate }}
                </option>
                @for (doctor of current.doctors; track doctor.userId) {
                  <option [value]="doctor.userId">{{ doctor.name }}</option>
                }
              </select></label
            >
          </div>
          @if (!valid) {
            <p
              class="escalation-settings__hint"
              role="alert"
              data-testid="escalation-settings-invalid"
            >
              {{ 'LAB_CRITICAL_PENDING.ESCALATION_INVALID' | translate }}
            </p>
          }
          <button
            type="button"
            class="btn-primary"
            data-testid="escalation-settings-save"
            [disabled]="saving() || !valid"
            (click)="save()"
          >
            {{ 'COMMON.SAVE' | translate }}
          </button>
        } @else {
          <p class="escalation-settings__hint">{{ 'COMMON.LOADING' | translate }}</p>
        }
      }
    </section>
  `,
  styles: [
    `
      .escalation-settings {
        margin: 12px 0 16px;
        min-width: 0;
      }
      .escalation-settings__hint {
        margin: 8px 0;
        font-size: 0.9rem;
        color: #40566b;
        overflow-wrap: anywhere;
      }
      .escalation-settings__grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
        gap: 12px;
        margin: 8px 0 12px;
      }
      .escalation-settings__grid label {
        display: flex;
        flex-direction: column;
        gap: 4px;
        font-size: 0.88rem;
        min-width: 0;
      }
      .escalation-settings__grid input,
      .escalation-settings__grid select {
        max-width: 100%;
        box-sizing: border-box;
        padding: 6px 8px;
      }
    `,
  ],
})
export class CriticalEscalationSettingsComponent {
  private readonly labService = inject(LabService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly hospitalId = input.required<string>();

  readonly open = signal(false);
  readonly failed = signal(false);
  readonly saving = signal(false);
  readonly settings = signal<CriticalEscalationSettings | null>(null);

  cover = 15;
  lead = 30;
  unresolved = 60;
  medicalLead = '';

  /** As the server checks them: increasing, from 5 minutes to a day. */
  get valid(): boolean {
    const [a, b, c] = [Number(this.cover), Number(this.lead), Number(this.unresolved)];
    return (
      Number.isInteger(a) &&
      Number.isInteger(b) &&
      Number.isInteger(c) &&
      a >= 5 &&
      a < b &&
      b < c &&
      c <= 1440
    );
  }

  toggle(): void {
    this.open.update((value) => !value);
    if (this.open() && !this.settings()) {
      this.load();
    }
  }

  private load(): void {
    this.failed.set(false);
    this.labService.criticalEscalationSettings(this.hospitalId()).subscribe({
      next: (current) => this.show(current),
      error: () => this.failed.set(true),
    });
  }

  private show(current: CriticalEscalationSettings): void {
    this.cover = current.coverAfterMinutes;
    this.lead = current.leadAfterMinutes;
    this.unresolved = current.unresolvedAfterMinutes;
    this.medicalLead = current.medicalLeadUserId ?? '';
    this.settings.set(current);
  }

  save(): void {
    if (!this.valid || this.saving()) return;
    this.saving.set(true);
    this.labService
      .saveCriticalEscalationSettings(this.hospitalId(), {
        coverAfterMinutes: Number(this.cover),
        leadAfterMinutes: Number(this.lead),
        unresolvedAfterMinutes: Number(this.unresolved),
        medicalLeadUserId: this.medicalLead || null,
      })
      .subscribe({
        next: (current) => {
          this.show(current);
          this.saving.set(false);
          this.toast.success(this.translate.instant('LAB_CRITICAL_PENDING.ESCALATION_SAVED'));
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.toast.error(
            refusalReason(err) ??
              this.translate.instant('LAB_CRITICAL_PENDING.ESCALATION_SAVE_ERROR'),
          );
        },
      });
  }
}
