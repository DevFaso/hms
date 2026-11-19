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
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { actionRoles } from '../../core/action-permissions';
import { refusalReason } from '../../shared/refusal-reason';
import {
  DESK_NOTICE_CHANNELS,
  NOTICE_TYPES,
  NoticeType,
} from '../../shared/notification-channel-choices';

/** One switch of the desk's matrix, as the API reads and writes it. */
export interface ChannelChoice {
  notificationType: NoticeType;
  channel: 'SMS' | 'EMAIL';
  enabled: boolean;
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
  imports: [FormsModule, TranslateModule],
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
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
          <p class="hint">{{ 'PATIENT_CHANNELS.SECURITY_NOTE' | translate }}</p>
          <div class="actions">
            <button
              type="button"
              class="btn-primary"
              data-testid="channel-prefs-save"
              [disabled]="saving() || !dirty()"
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

  readonly patientId = input.required<string>();

  readonly types = NOTICE_TYPES;
  readonly channels = DESK_NOTICE_CHANNELS;
  /** The roles PatientChannelPreferenceController admits (ActionPermissionsMirrorTest). */
  readonly canRecord = this.roleContext.hasAnyActiveRole(
    actionRoles('patientChannelPreferencesUpdate'),
  );

  readonly choices = signal<ChannelChoice[]>([]);
  private readonly saved = signal<ChannelChoice[]>([]);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
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

  private load(id: string): void {
    if (!this.canRecord || !id) return;
    this.http
      .get<ChannelChoice[]>(this.url(id))
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.saved.set(rows ?? []);
          this.choices.set((rows ?? []).map((row) => ({ ...row })));
          this.loadFailed.set(false);
        },
        error: () => this.loadFailed.set(true),
      });
  }

  isOn(type: string, channel: string): boolean {
    return (
      this.choices().find((c) => c.notificationType === type && c.channel === channel)?.enabled ??
      true
    );
  }

  toggle(type: NoticeType, channel: 'SMS' | 'EMAIL', enabled: boolean): void {
    const others = this.choices().filter(
      (c) => !(c.notificationType === type && c.channel === channel),
    );
    this.choices.set([...others, { notificationType: type, channel, enabled }]);
  }

  save(): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.http
      .put<ChannelChoice[]>(this.url(this.patientId()), this.choices())
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (rows) => {
          this.saving.set(false);
          this.saved.set(rows ?? []);
          this.choices.set((rows ?? []).map((row) => ({ ...row })));
          this.toast.success(this.translate.instant('PATIENT_CHANNELS.SAVED'));
        },
        error: (err: unknown) => {
          this.saving.set(false);
          this.toast.error(
            refusalReason(err) ?? this.translate.instant('PATIENT_CHANNELS.SAVE_FAILED'),
          );
        },
      });
  }
}
