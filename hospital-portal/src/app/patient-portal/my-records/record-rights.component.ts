import {
  Component,
  ChangeDetectionStrategy,
  ElementRef,
  OnInit,
  inject,
  input,
  signal,
} from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { RoiRequest, RoiRequestKind } from '../../services/roi.service';
import { PatientHospitalRecord } from '../../services/patient-portal.service';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { SubmitGuardDirective } from '../../shared/forms/submit-guard.directive';
import { missingRequiredMessage } from '../../shared/required-fields';
import { refusalReason, refusedFieldReasons } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import { RoleContextService } from '../../core/role-context.service';
import { actionRoles } from '../../core/action-permissions';

/** Own-account requests only. The server resolves identity; a request never edits medical data. */
@Component({
  selector: 'app-record-rights',
  standalone: true,
  imports: [FormsModule, TranslateModule, DatePipe, ServerTimePipe, SubmitGuardDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (canRequest()) {
      <section
        class="rights-card"
        aria-labelledby="record-rights-title"
        data-testid="record-rights"
      >
        <h2 id="record-rights-title">{{ 'RECORD_RIGHTS.TITLE' | translate }}</h2>
        <p>{{ 'RECORD_RIGHTS.INTRO' | translate }}</p>
        @if (error()) {
          <p role="alert">{{ error() }}</p>
        }
        @if (sent()) {
          <p role="status">{{ 'RECORD_RIGHTS.SENT' | translate }}</p>
        }
        <form (ngSubmit)="submit()" novalidate>
          <label for="rights-hospital">{{ 'RECORD_RIGHTS.HOSPITAL' | translate }} *</label>
          <select id="rights-hospital" name="hospitalId" [(ngModel)]="hospitalId" required>
            <option value="">{{ 'COMMON.SELECT' | translate }}</option>
            @for (hospital of hospitals(); track hospital.hospitalId) {
              <option [value]="hospital.hospitalId">{{ hospital.hospitalName }}</option>
            }
          </select>
          <label for="rights-kind">{{ 'RECORD_RIGHTS.KIND' | translate }}</label>
          <select id="rights-kind" name="requestKind" [(ngModel)]="kind">
            <option value="COPY">{{ 'RECORD_RIGHTS.COPY' | translate }}</option>
            <option value="CORRECTION">{{ 'RECORD_RIGHTS.CORRECTION' | translate }}</option>
          </select>
          <label for="rights-purpose">{{ 'RECORD_RIGHTS.PURPOSE' | translate }} *</label>
          <textarea
            id="rights-purpose"
            name="purpose"
            [(ngModel)]="purpose"
            maxlength="500"
            required
            rows="2"
          ></textarea>
          @if (kind === 'CORRECTION') {
            <label for="rights-description">{{ 'RECORD_RIGHTS.DESCRIPTION' | translate }} *</label>
            <textarea
              id="rights-description"
              name="description"
              [(ngModel)]="description"
              maxlength="500"
              required
              rows="3"
            ></textarea>
          } @else {
            <p>{{ 'RECORD_RIGHTS.FULL_RECORD' | translate }}</p>
          }
          <button
            type="submit"
            data-testid="record-rights-submit"
            [disabled]="saving()"
            [appSubmitGuard]="refusal()"
            [submitGuardFields]="['rights-hospital', 'rights-purpose', 'rights-description']"
          >
            {{ (saving() ? 'COMMON.SAVING' : 'RECORD_RIGHTS.SUBMIT') | translate }}
          </button>
        </form>
        <h3>{{ 'RECORD_RIGHTS.HISTORY' | translate }}</h3>
        @if (loading()) {
          <p role="status">{{ 'COMMON.LOADING' | translate }}</p>
        } @else if (loadFailed()) {
          <button type="button" (click)="load()">{{ 'COMMON.RETRY' | translate }}</button>
        } @else if (requests().length === 0) {
          <p>{{ 'RECORD_RIGHTS.EMPTY' | translate }}</p>
        }
        @for (request of requests(); track request.id) {
          <article>
            <h4>
              {{
                (request.requestKind === 'CORRECTION'
                  ? 'RECORD_RIGHTS.CORRECTION'
                  : 'RECORD_RIGHTS.COPY'
                ) | translate
              }}
              · {{ request.hospitalName }}
            </h4>
            <p>
              {{ statusKey(request.status) | translate }} ·
              {{ request.requestedOn | serverTime | date: 'mediumDate' }}
            </p>
            @if (request.dueOn) {
              <p>
                {{ 'RECORD_RIGHTS.TARGET' | translate }}
                {{ request.dueOn | serverTime | date: 'mediumDate' }}
              </p>
            }
            <p>{{ request.purpose }}</p>
            @if (request.requestKind === 'CORRECTION') {
              <p>{{ request.scopeDescription }}</p>
            }
            @if (request.decisionNote) {
              <p>{{ 'RECORD_RIGHTS.DECISION' | translate }} {{ request.decisionNote }}</p>
            }
            @if (request.decidedAt) {
              <p>
                {{ request.decidedAt | serverTime | date: 'short' }} ·
                {{ request.decidedByName || ('COMMON.UNKNOWN' | translate) }}
              </p>
            }
          </article>
        }
      </section>
    }
  `,
  styles: [
    `
      .rights-card {
        margin-block: 1.5rem;
        padding: 1.25rem;
        background: var(--color-surface, white);
        border: 1px solid var(--color-border, #cbd5e1);
        border-radius: 0.75rem;
      }
      form {
        display: grid;
        gap: 0.6rem;
        max-width: 40rem;
      }
      select,
      textarea,
      button {
        min-height: 44px;
        max-width: 100%;
        padding: 0.6rem;
        font: inherit;
      }
      article {
        padding-block: 1rem;
        border-top: 1px solid var(--color-border, #cbd5e1);
        overflow-wrap: anywhere;
      }
      h4 {
        margin-block: 0.5rem;
      }
    `,
  ],
})
export class RecordRightsComponent implements OnInit {
  private readonly host = inject(ElementRef<HTMLElement>);
  readonly hospitals = input<PatientHospitalRecord[]>([]);
  private readonly http = inject(HttpClient);
  private readonly translate = inject(TranslateService);
  private readonly roles = inject(RoleContextService);
  canRequest(): boolean {
    return this.roles.hasAnyActiveRole(actionRoles('patientRecordRequest'));
  }
  readonly requests = signal<RoiRequest[]>([]);
  readonly saving = signal(false);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly error = signal('');
  readonly sent = signal(false);
  hospitalId = '';
  kind: RoiRequestKind = 'COPY';
  purpose = '';
  description = '';
  ngOnInit(): void {
    if (this.canRequest()) this.load();
  }
  load(): void {
    if (!this.canRequest()) return;
    this.loading.set(true);
    this.loadFailed.set(false);
    this.error.set('');
    this.http.get<RoiRequest[]>('/me/patient/roi-requests').subscribe({
      next: (rows) => {
        this.requests.set(rows);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.loadFailed.set(true);
        this.error.set(refusalReason(err) ?? this.translate.instant('RECORD_RIGHTS.FAILED'));
      },
    });
  }
  refusal(): string | null {
    return missingRequiredMessage(this.translate, [
      { labelKey: 'RECORD_RIGHTS.HOSPITAL', value: this.hospitalId },
      { labelKey: 'RECORD_RIGHTS.PURPOSE', value: this.purpose.trim() },
      {
        labelKey: 'RECORD_RIGHTS.DESCRIPTION',
        value: this.kind === 'CORRECTION' ? this.description.trim() : 'FULL_RECORD',
      },
    ]);
  }
  submit(): void {
    if (!this.canRequest() || this.saving() || this.refusal()) return;
    this.saving.set(true);
    this.error.set('');
    this.sent.set(false);
    this.http
      .post<RoiRequest>('/me/patient/roi-requests', {
        hospitalId: this.hospitalId,
        requestKind: this.kind,
        purpose: this.purpose.trim(),
        scopeDescription: this.kind === 'COPY' ? 'FULL_RECORD' : this.description.trim(),
      })
      .subscribe({
        next: (row) => {
          this.requests.update((rows) => [row, ...rows]);
          this.saving.set(false);
          this.sent.set(true);
          this.purpose = '';
          this.description = '';
        },
        error: (err) => {
          this.saving.set(false);
          const fields = {
            hospitalId: 'rights-hospital',
            purpose: 'rights-purpose',
            scopeDescription: 'rights-description',
          };
          refuseFields(
            this.host.nativeElement,
            refusedFieldReasons(err, fields).map(([id, message]) => ({ id, message })),
            Object.values(fields),
          );
          this.error.set(refusalReason(err) ?? this.translate.instant('RECORD_RIGHTS.FAILED'));
        },
      });
  }
  statusKey(status: string): string {
    const keys: Record<string, string> = {
      PENDING: 'ROI.STATUS_PENDING',
      FULFILLED: 'ROI.STATUS_FULFILLED',
      DENIED: 'ROI.STATUS_DENIED',
      CANCELLED: 'ROI.STATUS_CANCELLED',
    };
    return keys[status] ?? 'COMMON.UNKNOWN';
  }
}
