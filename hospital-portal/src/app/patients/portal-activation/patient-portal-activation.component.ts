import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { PatientService, PortalActivationCode } from '../../services/patient.service';
import { deliveryWarningKeys, hasActivationSent } from '../../shared/delivery-warnings';
import { refusalReason } from '../../shared/refusal-reason';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { DateTimePipe } from '../../shared/pipes/date-time.pipe';
import { ToastService } from '../../core/toast.service';

/**
 * QA r70 D3206: the desk that registered a patient finishes the patient's portal account when the activation SMS
 * went nowhere. Shows the pending code (to hand over in person; the server audits the reveal and keeps it to the
 * receptionist or hospital administrator of the acting hospital) or re-sends the activation. The code lives in
 * this component only while it is open.
 */
@Component({
  selector: 'app-patient-portal-activation',
  standalone: true,
  imports: [TranslateModule, ServerTimePipe, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="info-card portal-activation" data-testid="portal-activation">
      <h3>
        <span class="material-symbols-outlined" aria-hidden="true">key</span>
        {{ 'PORTAL_ACTIVATION.TITLE' | translate }}
      </h3>
      @if (code(); as shown) {
        <p class="portal-activation-code" data-testid="portal-activation-code">
          <code>{{ shown.confirmationCode }}</code>
        </p>
        <p class="medical-text">{{ 'PORTAL_ACTIVATION.HOW_TO' | translate }}</p>
        @if (shown.expiresAt) {
          <p class="medical-text">
            {{
              'PLATFORM_SETUP.ACTIVATION.EXPIRES'
                | translate: { date: (shown.expiresAt | serverTime | dateTime) }
            }}
          </p>
        }
      } @else {
        <p class="medical-text">{{ 'PORTAL_ACTIVATION.HELP' | translate }}</p>
      }
      @if (error(); as reason) {
        <p class="portal-activation-error" role="alert" data-testid="portal-activation-error">
          {{ reason }}
        </p>
      }
      <div class="portal-activation-actions">
        @if (!code()) {
          <button
            type="button"
            class="btn-outline"
            data-testid="portal-activation-show"
            [disabled]="busy()"
            (click)="show()"
          >
            {{ 'PORTAL_ACTIVATION.SHOW' | translate }}
          </button>
        }
        <button
          type="button"
          class="btn-outline"
          data-testid="portal-activation-resend"
          [disabled]="busy()"
          (click)="resend()"
        >
          {{ 'PORTAL_ACTIVATION.RESEND' | translate }}
        </button>
      </div>
    </section>
  `,
  styles: [
    `
      .portal-activation-code code {
        font-size: 1.5rem;
        font-weight: 700;
        letter-spacing: 0.2em;
      }
      .portal-activation-error {
        color: var(--danger-text);
      }
      .portal-activation-actions {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
    `,
  ],
})
export class PatientPortalActivationComponent {
  readonly patientId = input.required<string>();

  protected readonly code = signal<PortalActivationCode | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly patients = inject(PatientService);
  private readonly translate = inject(TranslateService);
  private readonly toast = inject(ToastService);

  show(): void {
    if (this.busy()) return;
    const id = this.patientId();
    this.busy.set(true);
    this.error.set(null);
    this.patients.revealPortalActivationCode(id).subscribe({
      next: (shown) => {
        this.busy.set(false);
        // The page may have moved to another patient meanwhile: that patient never shows this code.
        if (this.patientId() === id) this.code.set(shown);
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(
          refusalReason(err) ?? this.translate.instant('PLATFORM_SETUP.ACTIVATION.SHOW_FAILED'),
        );
      },
    });
  }

  resend(): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.patients.resendPortalActivation(this.patientId()).subscribe({
      next: (report) => {
        this.busy.set(false);
        // A re-send may replace an expired code: the one on screen is no longer the one to hand over.
        this.code.set(null);
        const sent = hasActivationSent(report, 'SMS') || hasActivationSent(report, 'EMAIL');
        this.toast.success(
          this.translate.instant(
            sent ? 'PORTAL_ACTIVATION.RESENT' : 'PORTAL_ACTIVATION.RESENT_NOWHERE',
          ),
        );
        for (const key of deliveryWarningKeys(report)) {
          this.toast.warning(this.translate.instant(key));
        }
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(
          refusalReason(err) ?? this.translate.instant('PORTAL_ACTIVATION.RESEND_FAILED'),
        );
      },
    });
  }
}
