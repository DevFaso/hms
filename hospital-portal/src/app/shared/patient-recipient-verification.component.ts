import {
  Component,
  ChangeDetectionStrategy,
  ElementRef,
  inject,
  input,
  signal,
  effect,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoleContextService } from '../core/role-context.service';
import { actionRoles } from '../core/action-permissions';
import { refusalReason, refusedFieldReasons } from './refusal-reason';
import { refuseFields } from './forms/invalid-fields';
import { SubmitGuardDirective } from './forms/submit-guard.directive';
import { ServerTimePipe } from './pipes/server-time.pipe';

interface VerificationStatus {
  channel: 'SMS' | 'EMAIL';
  verified: boolean;
  expiresAt?: string | null;
  maskedRecipient?: string | null;
}

/** The server chooses the contact; show only its mask, never accept a recipient or code from a response. */
@Component({
  selector: 'app-patient-recipient-verification',
  standalone: true,
  imports: [FormsModule, TranslateModule, SubmitGuardDirective, DatePipe, ServerTimePipe],
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
            @if (row.maskedRecipient) {
              <p data-testid="verification-contact">
                {{ 'RECIPIENT_VERIFICATION.CONTACT' | translate: { contact: row.maskedRecipient } }}
              </p>
            }
            @if (row.verified) {
              <p role="status">{{ 'RECIPIENT_VERIFICATION.VERIFIED' | translate }}</p>
            } @else {
              <p>{{ 'RECIPIENT_VERIFICATION.NOT_VERIFIED' | translate }}</p>
              <button type="button" [disabled]="busy()" (click)="request(row.channel)">
                {{ 'RECIPIENT_VERIFICATION.SEND' | translate }}
              </button>
              @if (pending() === row.channel) {
                <p role="status" data-testid="verification-code-sent">
                  @if (sentTo()?.maskedRecipient) {
                    {{
                      'RECIPIENT_VERIFICATION.CODE_SENT'
                        | translate: { contact: sentTo()?.maskedRecipient }
                    }}
                  } @else {
                    {{ 'RECIPIENT_VERIFICATION.CODE_SENT_GENERIC' | translate }}
                  }
                  @if (sentTo()?.expiresAt) {
                    {{
                      'RECIPIENT_VERIFICATION.EXPIRES'
                        | translate
                          : {
                              time:
                                (sentTo()?.expiresAt
                                | serverTime
                                | date: 'shortTime' : undefined : uiLocale()),
                            }
                    }}
                  }
                </p>
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
                    data-testid="recipient-code-confirm"
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
      :host {
        display: block;
        min-width: 0;
        max-width: 100%;
      }
      .verification {
        box-sizing: border-box;
        min-width: 0;
        max-width: 100%;
        margin-block: 1.5rem;
        padding: 1rem;
        border: 1px solid var(--color-border, #cbd5e1);
        border-radius: 0.75rem;
      }
      .verification-channel {
        padding-block: 0.75rem;
      }
      form {
        box-sizing: border-box;
        min-width: 0;
        width: 100%;
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 0.5rem;
        margin-block: 0.75rem;
      }
      input {
        box-sizing: border-box;
        min-width: 0;
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
  private readonly host = inject(ElementRef<HTMLElement>);
  readonly patientId = input<string>();
  private readonly http = inject(HttpClient);
  private readonly roles = inject(RoleContextService);
  private readonly translate = inject(TranslateService);
  readonly statuses = signal<VerificationStatus[]>([]);
  readonly loading = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly pending = signal<'SMS' | 'EMAIL' | null>(null);
  readonly sentTo = signal<VerificationStatus | null>(null);
  private contextVersion = 0;
  code = '';

  constructor() {
    effect(() => {
      if (this.allowed()) this.load();
      else this.clearContext();
    });
  }
  allowed(): boolean {
    return this.patientId()
      ? this.roles.hasAnyActiveRole(actionRoles('patientRecipientVerify'))
      : this.roles.hasAnyActiveRole(actionRoles('patientSelfRecipientVerify'));
  }
  private endpoint(): string {
    return this.patientId()
      ? `/patients/${this.patientId()}/recipient-verification`
      : '/me/patient/recipient-verification';
  }
  load(): void {
    this.clearContext();
    if (!this.allowed()) return;
    const version = this.contextVersion;
    const patientId = this.patientId();
    this.loading.set(true);
    this.error.set('');
    this.http.get<VerificationStatus[]>(this.endpoint()).subscribe({
      next: (rows) => {
        if (!this.current(version, patientId)) return;
        this.statuses.set(rows);
        this.loading.set(false);
      },
      error: (err) => {
        if (!this.current(version, patientId)) return;
        this.loading.set(false);
        this.fail(err);
      },
    });
  }
  request(channel: 'SMS' | 'EMAIL'): void {
    if (this.busy() || !this.allowed()) return;
    const version = this.contextVersion;
    const patientId = this.patientId();
    this.busy.set(true);
    this.error.set('');
    this.code = '';
    this.http.post<VerificationStatus>(`${this.endpoint()}/${channel}/request`, {}).subscribe({
      next: (status) => {
        if (!this.current(version, patientId)) return;
        this.statuses.update((rows) => rows.map((row) => (row.channel === channel ? status : row)));
        this.sentTo.set(status);
        this.pending.set(channel);
        this.busy.set(false);
      },
      error: (err) => {
        if (!this.current(version, patientId)) return;
        this.busy.set(false);
        this.fail(err);
      },
    });
  }
  validCode(): boolean {
    return /^[0-9]{6}$/.test(this.code);
  }
  confirm(channel: 'SMS' | 'EMAIL'): void {
    if (this.busy() || !this.validCode() || !this.allowed()) return;
    const version = this.contextVersion;
    const patientId = this.patientId();
    this.busy.set(true);
    this.error.set('');
    this.http
      .post<VerificationStatus>(`${this.endpoint()}/${channel}/confirm`, { code: this.code })
      .subscribe({
        next: (confirmed) => {
          if (!this.current(version, patientId)) return;
          this.statuses.update((rows) =>
            rows.map((row) => (row.channel === channel ? confirmed : row)),
          );
          this.pending.set(null);
          this.sentTo.set(null);
          this.code = '';
          this.busy.set(false);
        },
        error: (err) => {
          if (!this.current(version, patientId)) return;
          this.busy.set(false);
          const id = 'recipient-code-' + channel;
          refuseFields(
            this.host.nativeElement,
            refusedFieldReasons(err, { code: id }).map(([fieldId, message]) => ({
              id: fieldId,
              message,
            })),
            [id],
          );
          this.fail(err);
        },
      });
  }
  private fail(err: unknown): void {
    this.error.set(refusalReason(err) ?? this.translate.instant('RECIPIENT_VERIFICATION.ERROR'));
  }
  uiLocale(): string {
    const language = this.translate.getCurrentLang()?.toLowerCase().split('-')[0];
    return language === 'en' || language === 'es' ? language : 'fr';
  }
  private current(version: number, patientId: string | undefined): boolean {
    return version === this.contextVersion && patientId === this.patientId() && this.allowed();
  }
  private clearContext(): void {
    this.contextVersion++;
    this.statuses.set([]);
    this.pending.set(null);
    this.sentTo.set(null);
    this.code = '';
    this.busy.set(false);
    this.loading.set(false);
    this.error.set('');
  }
}
