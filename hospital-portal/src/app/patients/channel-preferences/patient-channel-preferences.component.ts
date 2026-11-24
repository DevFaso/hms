import { PatientRecipientVerificationComponent } from '../../shared/patient-recipient-verification.component';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { actionRoles } from '../../core/action-permissions';
import { refusalReason } from '../../shared/refusal-reason';
import {
  DESK_NOTICE_CHANNELS,
  NOTICE_TYPES,
  NoticeType,
  changedChannelChoices,
  consentRecorderKey,
  ConsentRecorderKind,
} from '../../shared/notification-channel-choices';

/** One switch of the desk's matrix, as the API reads and writes it. */
export interface ChannelChoice {
  notificationType: NoticeType;
  channel: 'SMS' | 'EMAIL';
  enabled: boolean;
  recordedByKind?: ConsentRecorderKind | null;
  recordedByName?: string | null;
  recordedAt?: string | null;
}

/**
 * QA D2203: the patient's SMS and e-mail choices on the record's « Gestion administrative » tab, so
 * the desk can tell a patient (a phone-only one above all) that their texts can be stopped, and
 * record it. The same preferences every sender honours; each change is audited server-side. The
 * bell stays the patient's own choice in the portal.
 */
@Component({
  selector: 'app-patient-channel-preferences',
  standalone: true,
  imports: [
    PatientRecipientVerificationComponent,
    FormsModule,
    TranslateModule,
    DatePipe,
    ServerTimePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (canRecord) {
      <section
        class="info-card channel-prefs"
        data-testid="channel-prefs"
        aria-labelledby="channel-prefs-title"
      >
        <h3 id="channel-prefs-title">
          <span class="material-symbols-outlined" aria-hidden="true">sms</span>
          {{ 'PATIENT_CHANNELS.TITLE' | translate }}
        </h3>
        <p class="hint">{{ 'PATIENT_CHANNELS.INTRO' | translate }}</p>
        <app-patient-recipient-verification [patientId]="patientId()" />
        @if (loadFailed()) {
          <p class="error" role="alert">{{ 'PATIENT_CHANNELS.LOAD_FAILED' | translate }}</p>
        } @else if (choices().length > 0) {
          <div class="table-scroll">
            <table class="channel-table">
              <thead>
                <tr>
                  <th scope="col">{{ 'PATIENT_CHANNELS.KIND' | translate }}</th>
                  @for (ch of channels; track ch) {
                    <th scope="col">{{ 'NOTIFICATIONS.CHANNEL_' + ch | translate }}</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (type of types; track type) {
                  <tr>
                    <th scope="row">{{ 'NOTIFICATIONS.TYPE_' + type | translate }}</th>
                    @for (ch of channels; track ch) {
                      <td>
                        <input
                          type="checkbox"
                          [attr.data-testid]="'channel-' + type + '-' + ch"
                          [attr.aria-label]="
                            ('NOTIFICATIONS.TYPE_' + type | translate) +
                            ' — ' +
                            ('NOTIFICATIONS.CHANNEL_' + ch | translate)
                          "
                          [ngModel]="isOn(type, ch)"
                          (ngModelChange)="toggle(type, ch, $event)"
                          [disabled]="saving()"
                        />
                        @if (choice(type, ch); as storedChoice) {
                          @if (storedChoice.recordedAt) {
                            <small
                              class="choice-recorder"
                              [attr.data-testid]="'choice-recorder-' + type + '-' + ch"
                            >
                              {{ 'PATIENT_CHANNELS.RECORDED_BY' | translate }}
                              @if (recorderKey(storedChoice.recordedByKind); as key) {
                                {{ key | translate }}
                              } @else {
                                {{ storedChoice.recordedByName || ('COMMON.UNKNOWN' | translate) }}
                              }
                              <time [attr.datetime]="storedChoice.recordedAt | serverTime">{{
                                storedChoice.recordedAt | serverTime | date: 'short'
                              }}</time>
                            </small>
                          }
                        }
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="hint">{{ 'PATIENT_CHANNELS.SECURITY_NOTE' | translate }}</p>
          @if (unchanged()) {
            <p role="status" data-testid="channel-prefs-unchanged">
              {{ 'SUBMIT_GUARD.NO_CHANGES' | translate }}
            </p>
          }
          <div class="actions">
            <button
              type="button"
              class="btn-primary"
              data-testid="channel-prefs-save"
              [disabled]="saving()"
              (click)="save()"
            >
              {{ (saving() ? 'COMMON.SAVING' : 'PATIENT_CHANNELS.SAVE') | translate }}
            </button>
          </div>
        }
      </section>
    }
  `,
  styles: [
    `
      .channel-prefs .hint {
        font-size: 0.85rem;
        color: var(--text-muted, #666);
      }
      .channel-prefs .error {
        color: var(--danger, #c62828);
      }
      .channel-table {
        border-collapse: collapse;
        width: 100%;
      }
      .channel-table th,
      .channel-table td {
        padding: 0.4rem 0.5rem;
        text-align: left;
        border-bottom: 1px solid var(--border, #e5e7eb);
      }
      .channel-table td {
        text-align: center;
      }
      .choice-recorder {
        display: block;
        max-width: 12rem;
        overflow-wrap: anywhere;
        font-size: 0.75rem;
      }
      .choice-recorder time {
        display: block;
      }
      .channel-prefs .actions {
        display: flex;
        justify-content: flex-end;
        margin-top: 0.5rem;
      }
    `,
  ],
})
export class PatientChannelPreferencesComponent {
  private readonly http = inject(HttpClient);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);
  private readonly destroyRef = inject(DestroyRef);

  /** The patient whose choices are shown (none: nothing is read). */
  readonly patientId = input<string>('');

  readonly types = NOTICE_TYPES;
  readonly channels = DESK_NOTICE_CHANNELS;
  /** QA r73 D3700: « Autre établissement » / « Le patient » instead of a name the server withheld. */
  readonly recorderKey = consentRecorderKey;
  /** The roles PatientChannelPreferenceController admits (ActionPermissionsMirrorTest). */
  readonly canRecord = this.roleContext.hasAnyActiveRole(
    actionRoles('patientChannelPreferencesUpdate'),
  );

  private requestGeneration = 0;
  readonly choices = signal<ChannelChoice[]>([]);
  private readonly saved = signal<ChannelChoice[]>([]);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly unchanged = signal(false);
  readonly dirty = computed(() =>
    this.choices().some((choice) => {
      const before = this.saved().find(
        (s) => s.notificationType === choice.notificationType && s.channel === choice.channel,
      );
      return !before || before.enabled !== choice.enabled;
    }),
  );

  constructor() {
    effect(() => {
      const id = this.patientId();
      untracked(() => this.load(id));
    });
  }

  private url(id: string): string {
    return `/patients/${encodeURIComponent(id)}/channel-preferences`;
  }

  private isCurrent(generation: number, id: string): boolean {
    return generation === this.requestGeneration && id === this.patientId();
  }

  private load(id: string): void {
    const generation = ++this.requestGeneration;
    this.saved.set([]);
    this.choices.set([]);
    this.saving.set(false);
    this.loadFailed.set(false);
    if (!this.canRecord || !id) return;
    this.http
      .get<ChannelChoice[]>(this.url(id))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (!this.isCurrent(generation, id)) return;
          this.saved.set(rows ?? []);
          this.choices.set((rows ?? []).map((row) => ({ ...row })));
          this.loadFailed.set(false);
        },
        error: () => {
          if (this.isCurrent(generation, id)) this.loadFailed.set(true);
        },
      });
  }

  choice(type: string, channel: string): ChannelChoice | undefined {
    return this.choices().find((row) => row.notificationType === type && row.channel === channel);
  }

  isOn(type: string, channel: string): boolean {
    return (
      this.choices().find((c) => c.notificationType === type && c.channel === channel)?.enabled ??
      true
    );
  }

  toggle(type: NoticeType, channel: 'SMS' | 'EMAIL', enabled: boolean): void {
    if (this.saving() || this.loadFailed() || this.choices().length === 0) return;
    this.unchanged.set(false);
    const others = this.choices().filter(
      (c) => !(c.notificationType === type && c.channel === channel),
    );
    this.choices.set([...others, { notificationType: type, channel, enabled }]);
  }

  save(): void {
    if (this.saving()) return;
    if (!this.dirty()) {
      this.unchanged.set(true);
      return;
    }
    const generation = this.requestGeneration;
    const id = this.patientId();
    this.saving.set(true);
    this.http
      .put<ChannelChoice[]>(this.url(id), changedChannelChoices(this.choices(), this.saved()))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          if (!this.isCurrent(generation, id)) return;
          this.saving.set(false);
          this.saved.set(rows ?? []);
          this.choices.set((rows ?? []).map((row) => ({ ...row })));
          this.toast.success(this.translate.instant('PATIENT_CHANNELS.SAVED'));
        },
        error: (err: unknown) => {
          if (!this.isCurrent(generation, id)) return;
          this.saving.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PATIENT_CHANNELS.SAVE_FAILED'),
          );
        },
      });
  }
}
