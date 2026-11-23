import { Component, ChangeDetectionStrategy, inject, input, signal, effect } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { refusalReason } from './refusal-reason';
import { SubmitGuardDirective } from './forms/submit-guard.directive';

interface VerificationStatus {
  channel: 'SMS' | 'EMAIL';
  verified: boolean;
  expiresAt?: string | null;
}

/** The server chooses the existing recipient. Never accept or display an address or a code from it. */
@Component({
  selector: 'app-patient-recipient-verification',
  standalone: true,
  imports: [FormsModule, TranslateModule, SubmitGuardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (allowed()) {
      <section class="verification" aria-labelledby="recipient-verification-title">
        <h2 id="recipient-verification-title">{{ 'RECIPIENT_VERIFICATION.TITLE' | translate }}</h2>
        <p>{{ 'RECIPIENT_VERIFICATION.INTRO' | translate }}</p>
        @if (error()) {
          <p role="alert">{{ error() }}</p>
        }
        @if (loading()) {
          <p role="status">{{ 'COMMON.LOADING' | translate }}</p>
        } @else if (statuses().length === 0) {
          <button type="button" (click)="load()">{{ 'COMMON.RETRY' | translate }}</button>
        }
        @for (row of statuses(); track row.channel) {
          <div class="verification-channel" [attr.data-testid]="'verify-' + row.channel">
            <h3>{{ 'NOTIFICATIONS.CHANNEL_' + row.channel | translate }}</h3>
            @if (row.verified) {
              <p role="status">{{ 'RECIPIENT_VERIFICATION.VERIFIED' | translate }}</p>
            } @else {
              <p>{{ 'RECIPIENT_VERIFICATION.NOT_VERIFIED' | translate }}</p>
              <button type="button" [disabled]="busy()" (click)="request(row.channel)">
                {{ 'RECIPIENT_VERIFICATION.SEND' | translate }}
              </button>
              @if (pending() === row.channel) {
                <form (ngSubmit)="confirm(row.channel)">
                  <label [for]="'recipient-code-' + row.channel"
                    >{{ 'RECIPIENT_VERIFICATION.CODE' | translate }} *</label
                  >
                  <input
                    [id]="'recipient-code-' + row.channel"
                    name="code"
                    [(ngModel)]="code"
                    inputmode="numeric"
                    autocomplete="one-time-code"
                    pattern="[0-9]{6}"
                    maxlength="6"
                    required
                  />
                  <button
                    type="submit"
                    [disabled]="busy()"
                    [appSubmitGuard]="
                      !validCode() && ('RECIPIENT_VERIFICATION.CODE_REQUIRED' | translate)
                    "
                    [submitGuardFields]="['recipient-code-' + row.channel]"
                  >
                    {{ 'RECIPIENT_VERIFICATION.CONFIRM' | translate }}
                  </button>
                </form>
              }
            }
          </div>
        }
      </section>
    }
  `,
  styles: [
    `
      .verification {
        margin-block: 1.5rem;
        padding: 1rem;
        border: 1px solid var(--color-border, #cbd5e1);
        border-radius: 0.75rem;
      }
      .verification-channel {
        padding-block: 0.75rem;
      }
      form {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        margin-block: 0.75rem;
      }
      input {
        width: 9rem;
        max-width: 100%;
      }
      button {
        min-height: 44px;
        padding: 0.5rem 0.75rem;
        border: 0;
        border-radius: 6px;
        background: var(--color-primary, #0b7061);
        color: var(--color-on-primary, #fff);
        cursor: pointer;
      }
      h2,
      h3 {
        font-size: 1rem;
      }
    `,
  ],
})
export class PatientRecipientVerificationComponent {
  readonly patientId = input<string>();
  private readonly http = inject(HttpClient);
  private readonly roles = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  readonly statuses = signal<VerificationStatus[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly pending = signal<'SMS' | 'EMAIL' | null>(null);
  code = '';

  constructor() {
    effect(() => {
      if (this.allowed()) this.load();
    });
  }
  allowed(): boolean {
    return this.patientId()
      ? this.roles.hasAnyActiveRole(['ROLE_RECEPTIONIST', 'ROLE_HOSPITAL_ADMIN'])
      : this.roles.hasAnyActiveRole(['ROLE_PATIENT']);
  }
  private endpoint(): string {
    return this.patientId()
      ? `/patients/${this.patientId()}/recipient-verification`
      : '/me/patient/recipient-verification';
  }
  load(): void {
    this.loading.set(true);
    this.error.set('');
    this.http.get<VerificationStatus[]>(this.endpoint()).subscribe({
      next: (rows) => {
        this.statuses.set(rows);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.fail(err);
      },
    });
  }
  request(channel: 'SMS' | 'EMAIL'): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    this.code = '';
    this.http.post<VerificationStatus>(`${this.endpoint()}/${channel}/request`, {}).subscribe({
      next: () => {
        this.pending.set(channel);
        this.busy.set(false);
      },
      error: (err) => {
        this.busy.set(false);
        this.fail(err);
      },
    });
  }
  validCode(): boolean {
    return /^[0-9]{6}$/.test(this.code);
  }
  confirm(channel: 'SMS' | 'EMAIL'): void {
    if (this.busy() || !this.validCode()) return;
    this.busy.set(true);
    this.error.set('');
    this.http
      .post<VerificationStatus>(`${this.endpoint()}/${channel}/confirm`, { code: this.code })
      .subscribe({
        next: (confirmed) => {
          this.statuses.update((rows) =>
            rows.map((row) => (row.channel === channel ? confirmed : row)),
          );
          this.pending.set(null);
          this.code = '';
          this.busy.set(false);
        },
        error: (err) => {
          this.busy.set(false);
          this.fail(err);
        },
      });
  }
  private fail(err: unknown): void {
    this.error.set(refusalReason(err) ?? this.translate.instant('RECIPIENT_VERIFICATION.ERROR'));
  }
}
