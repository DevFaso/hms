import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { ToastService } from '../../core/toast.service';
import { refusalReason } from '../../shared/refusal-reason';

/** The limits the server enforces (PlatformRequestService). */
export const PLATFORM_REQUEST_SUBJECT_MAX = 120;
export const PLATFORM_REQUEST_MESSAGE_MAX = 2000;

/**
 * QA D4308: a hospital administrator writes to the platform administrators.
 * The request lands in their inbox; nothing is sent by SMS or e-mail.
 */
@Component({
  selector: 'app-platform-request',
  standalone: true,
  imports: [FormsModule, TranslateModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="platform-request" data-testid="platform-request">
      @if (!open()) {
        <button
          type="button"
          class="btn-secondary"
          data-testid="platform-request-open"
          (click)="open.set(true)"
        >
          {{ 'inBasket.platformRequest.open' | translate }}
        </button>
      } @else {
        <form class="form-card" (ngSubmit)="send()" data-testid="platform-request-form">
          <h2>{{ 'inBasket.platformRequest.title' | translate }}</h2>
          <p class="hint">{{ 'inBasket.platformRequest.hint' | translate }}</p>
          <div class="field">
            <label for="platform-request-subject"
              >{{ 'inBasket.platformRequest.subject' | translate }} *</label
            >
            <input
              id="platform-request-subject"
              name="subject"
              type="text"
              required
              [attr.maxlength]="subjectMax"
              [(ngModel)]="subject"
              data-testid="platform-request-subject"
            />
          </div>
          <div class="field">
            <label for="platform-request-message"
              >{{ 'inBasket.platformRequest.message' | translate }} *</label
            >
            <textarea
              id="platform-request-message"
              name="message"
              rows="5"
              required
              [attr.maxlength]="messageMax"
              [(ngModel)]="message"
              data-testid="platform-request-message"
            ></textarea>
            <small class="counter">{{ message.length }} / {{ messageMax }}</small>
          </div>
          @if (error(); as reason) {
            <p class="error" role="alert" data-testid="platform-request-error">{{ reason }}</p>
          }
          <div class="actions">
            <button type="button" class="btn-secondary" (click)="cancel()" [disabled]="sending()">
              {{ 'COMMON.CANCEL' | translate }}
            </button>
            <button
              type="submit"
              class="btn-primary"
              [disabled]="sending() || !subject.trim() || !message.trim()"
              data-testid="platform-request-send"
            >
              {{
                (sending() ? 'inBasket.platformRequest.sending' : 'inBasket.platformRequest.send')
                  | translate
              }}
            </button>
          </div>
        </form>
      }
    </section>
  `,
  styles: `
    .platform-request {
      margin-bottom: 20px;
    }
    .form-card {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px;
      border: 1px solid var(--border-color, #e2e8f0);
      border-radius: 8px;
      background: var(--surface, #fff);
    }
    h2 {
      margin: 0;
      font-size: 1.05rem;
    }
    .hint {
      margin: 0;
      color: var(--text-secondary);
      font-size: 0.875rem;
    }
    .field {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .field input,
    .field textarea {
      width: 100%;
      padding: 8px 10px;
      border: 1px solid var(--border-color, #cbd5e1);
      border-radius: 6px;
      font: inherit;
    }
    .counter {
      align-self: flex-end;
      color: var(--text-secondary);
    }
    .error {
      margin: 0;
      color: var(--danger-text, #b91c1c);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      justify-content: flex-end;
    }
  `,
})
export class PlatformRequestComponent {
  private readonly http = inject(HttpClient);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  readonly subjectMax = PLATFORM_REQUEST_SUBJECT_MAX;
  readonly messageMax = PLATFORM_REQUEST_MESSAGE_MAX;

  readonly open = signal(false);
  readonly sending = signal(false);
  readonly error = signal<string | null>(null);

  subject = '';
  message = '';

  cancel(): void {
    this.open.set(false);
    this.error.set(null);
  }

  send(): void {
    // One request per press: a second submit while one is in flight is ignored.
    if (this.sending() || !this.subject.trim() || !this.message.trim()) return;
    this.sending.set(true);
    this.error.set(null);
    this.http
      .post<{ id: string }>('/platform-requests', {
        subject: this.subject.trim(),
        message: this.message.trim(),
      })
      .subscribe({
        next: () => {
          this.sending.set(false);
          this.subject = '';
          this.message = '';
          this.open.set(false);
          this.toast.success(this.translate.instant('inBasket.platformRequest.sent'));
        },
        error: (err: unknown) => {
          this.sending.set(false);
          this.error.set(
            refusalReason(err) ?? this.translate.instant('inBasket.platformRequest.failed'),
          );
        },
      });
  }
}
