import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header/page-header.component';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  NotificationService,
  NotificationPreferenceUpdate,
  NotificationPreference,
} from '../services/notification.service';
import { ToastService } from '../core/toast.service';

import { SubmitGuardDirective } from '../shared/forms/submit-guard.directive';
import { refusalReason } from '../shared/refusal-reason';
import {
  NOTICE_CHANNELS,
  NOTICE_TYPES,
  changedChannelChoices,
} from '../shared/notification-channel-choices';
// QA D2204: only what a sender reads (shared with the desk's view of a patient).
const NOTIFICATION_TYPES = NOTICE_TYPES;
const CHANNELS = NOTICE_CHANNELS;

interface PrefRow {
  notificationType: string;
  /** i18n key for the row label — resolved in the template so it follows a language switch. */
  labelKey: string;
  channels: Record<string, boolean>;
}

@Component({
  selector: 'app-notification-settings',
  standalone: true,
  imports: [SubmitGuardDirective, FormsModule, TranslateModule, PageHeaderComponent],
  template: `
    <div class="settings-page">
      <app-page-header
        [title]="'NOTIFICATIONS.SETTINGS_TITLE' | translate"
        icon="tune"
        backLink="/notifications"
        [backLabel]="'NOTIFICATIONS.SETTINGS_BACK' | translate"
      />

      @if (loading()) {
        <div class="settings-loading">
          <div class="spinner"></div>
          <span>{{ 'NOTIFICATIONS.LOADING_PREFERENCES' | translate }}</span>
        </div>
      } @else if (loadFailed()) {
        <p role="alert">{{ 'NOTIFICATIONS.PREFERENCES_LOAD_FAILED' | translate }}</p>
        <button type="button" class="btn-secondary" (click)="loadPreferences()">
          {{ 'COMMON.RETRY' | translate }}
        </button>
      } @else {
        <p class="settings-desc">{{ 'NOTIFICATIONS.SETTINGS_DESC' | translate }}</p>
        <!-- QA r64: account security messages are not a choice. -->
        <p class="settings-desc">{{ 'NOTIFICATIONS.SETTINGS_SECURITY_NOTE' | translate }}</p>

        <div class="table-scroll">
          <table class="pref-table">
            <thead>
              <tr>
                <th>{{ 'NOTIFICATIONS.NOTIFICATION_TYPE' | translate }}</th>
                @for (ch of channels; track ch) {
                  <th class="channel-col">{{ channelLabelKey(ch) | translate }}</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (row of rows(); track row.notificationType) {
                <tr>
                  <td class="type-label">{{ row.labelKey | translate }}</td>
                  @for (ch of channels; track ch) {
                    <td class="channel-col">
                      <label class="toggle">
                        <input
                          type="checkbox"
                          [attr.aria-label]="
                            (row.labelKey | translate) + ' — ' + (channelLabelKey(ch) | translate)
                          "
                          [ngModel]="row.channels[ch]"
                          (ngModelChange)="toggle(row.notificationType, ch, $event)"
                          [disabled]="saving()"
                        />
                        <span class="slider"></span>
                      </label>
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>

        <div class="settings-actions">
          <button
            class="btn-primary"
            (click)="save()"
            [disabled]="saving()"
            [appSubmitGuard]="!dirty() && ('SUBMIT_GUARD.NO_CHANGES' | translate)"
          >
            {{
              saving()
                ? ('NOTIFICATIONS.SAVING' | translate)
                : ('NOTIFICATIONS.SAVE_PREFERENCES' | translate)
            }}
          </button>
        </div>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [
    `
      .settings-page {
        max-width: 800px;
        margin: 0 auto;
        padding: 2rem 1rem;
      }
      .settings-desc {
        color: var(--text-secondary);
        margin-bottom: 1.5rem;
      }
      .settings-loading {
        display: flex;
        align-items: center;
        gap: 1rem;
        padding: 3rem;
        justify-content: center;
      }
      .spinner {
        width: 24px;
        height: 24px;
        border: 3px solid #e0e0e0;
        border-top-color: var(--primary);
        border-radius: 50%;
        animation: spin 0.8s linear infinite;
      }
      @keyframes spin {
        to {
          transform: rotate(360deg);
        }
      }
      .pref-table {
        width: 100%;
        border-collapse: collapse;
        background: #fff;
        border-radius: 8px;
        overflow: hidden;
        box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
      }
      .pref-table th,
      .pref-table td {
        padding: 0.75rem clamp(0.4rem, 2vw, 1rem);
        border-bottom: 1px solid #f0f0f0;
        text-align: left;
      }
      .pref-table thead th {
        background: #f5f5f5;
        font-weight: 600;
        font-size: 0.85rem;
        text-transform: uppercase;
        letter-spacing: 0.03em;
      }
      .channel-col {
        text-align: center !important;
        width: 100px;
      }
      /* QA D2345 (r63): the columns fit a phone, so SMS stays on screen. */
      @media (max-width: 480px) {
        .channel-col {
          width: auto;
        }
        /* QA D2345 (r64): hyphenate at syllables (« APPLI-CATION »), never
           letter by letter, and a long type name (« Renouvellement ») hyphenates
           too, so PUSH is on screen. */
        .pref-table thead th {
          font-size: 0.7rem;
          letter-spacing: 0;
          hyphens: auto;
        }
        .type-label {
          hyphens: auto;
        }
      }
      .type-label {
        font-weight: 500;
      }
      .toggle {
        position: relative;
        display: inline-block;
        width: 40px;
        height: 22px;
      }
      .toggle input {
        opacity: 0;
        width: 0;
        height: 0;
      }
      .slider {
        position: absolute;
        inset: 0;
        background-color: #ccc;
        border-radius: 22px;
        transition: 0.3s;
        cursor: pointer;
      }
      .slider::before {
        content: '';
        position: absolute;
        height: 16px;
        width: 16px;
        left: 3px;
        bottom: 3px;
        background-color: white;
        border-radius: 50%;
        transition: 0.3s;
      }
      .toggle input:checked + .slider {
        background-color: var(--primary);
      }
      .toggle input:checked + .slider::before {
        transform: translateX(18px);
      }
      .settings-actions {
        margin-top: 1.5rem;
        display: flex;
        justify-content: flex-end;
      }
    `,
  ],
})
export class NotificationSettingsComponent implements OnInit {
  private readonly notifService = inject(NotificationService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  channels = CHANNELS;
  loading = signal(true);
  loadFailed = signal(false);
  private savedPreferences: NotificationPreference[] = [];
  saving = signal(false);
  dirty = signal(false);
  rows = signal<PrefRow[]>([]);

  ngOnInit(): void {
    this.loadPreferences();
  }

  /** i18n key for a delivery channel column header. */
  channelLabelKey(ch: string): string {
    return `NOTIFICATIONS.CHANNEL_${ch}`;
  }

  loadPreferences(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.notifService.getPreferences().subscribe({
      next: (prefs) => {
        this.applyPreferences(prefs);
        this.loading.set(false);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loading.set(false);
      },
    });
  }

  private applyPreferences(prefs: NotificationPreference[]): void {
    const defaultRows: PrefRow[] = NOTIFICATION_TYPES.map((type) => ({
      notificationType: type,
      labelKey: `NOTIFICATIONS.TYPE_${type}`,
      channels: Object.fromEntries(CHANNELS.map((channel) => [channel, true])),
    }));
    for (const pref of prefs) {
      const row = defaultRows.find(
        (candidate) => candidate.notificationType === pref.notificationType,
      );
      if (row && this.channels.some((channel) => channel === pref.channel))
        row.channels[pref.channel] = pref.enabled;
    }
    this.savedPreferences = prefs.map((pref) => ({ ...pref }));
    this.rows.set(defaultRows);
    this.dirty.set(false);
  }

  private currentChoices(): NotificationPreferenceUpdate[] {
    const updates: NotificationPreferenceUpdate[] = [];
    for (const row of this.rows()) {
      for (const ch of this.channels) {
        updates.push({
          notificationType: row.notificationType,
          channel: ch,
          enabled: row.channels[ch],
        });
      }
    }
    return updates;
  }

  toggle(type: string, channel: string, enabled: boolean): void {
    if (this.saving() || this.loading() || this.loadFailed()) return;
    this.rows.update((rows) =>
      rows.map((row) =>
        row.notificationType === type
          ? { ...row, channels: { ...row.channels, [channel]: enabled } }
          : row,
      ),
    );
    this.markChanged();
  }

  markChanged(): void {
    this.dirty.set(changedChannelChoices(this.currentChoices(), this.savedPreferences).length > 0);
  }

  save(): void {
    const updates = this.currentChoices();
    const changed = changedChannelChoices(updates, this.savedPreferences);
    if (this.loadFailed() || this.saving() || changed.length === 0) return;
    this.saving.set(true);
    this.notifService.updatePreferences(changed).subscribe({
      next: (prefs) => {
        this.applyPreferences(prefs);
        this.saving.set(false);
        this.dirty.set(false);
        this.toast.success(this.translate.instant('NOTIFICATIONS.PREFERENCES_SAVED'));
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.toast.error(
          refusalReason(err) ?? this.translate.instant('NOTIFICATIONS.PREFERENCES_SAVE_FAILED'),
        );
      },
    });
  }
}
