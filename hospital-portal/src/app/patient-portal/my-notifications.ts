import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  NotificationPreferenceDTO,
  NotificationPreferenceUpdateDTO,
} from '../services/patient-portal.service';

const NOTIFICATION_TYPES = [
  { value: 'APPOINTMENT_REMINDER', label: 'Appointment Reminders' },
  { value: 'LAB_RESULT', label: 'Lab Results' },
  { value: 'MEDICATION_REFILL', label: 'Medication Refill Updates' },
  { value: 'BILLING', label: 'Billing & Payments' },
  { value: 'DISCHARGE_SUMMARY', label: 'Discharge Summaries' },
  { value: 'SYSTEM', label: 'System Notices' },
  { value: 'GENERAL', label: 'General Announcements' },
];

const CHANNELS = [
  { value: 'IN_APP', label: 'In-App', icon: 'notifications' },
  { value: 'EMAIL', label: 'Email', icon: 'email' },
  { value: 'SMS', label: 'SMS', icon: 'sms' },
  { value: 'PUSH', label: 'Push', icon: 'phone_iphone' },
];

@Component({
  selector: 'app-my-notifications',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">tune</span>
          Notification Settings
        </h1>
        <p class="portal-subtitle">Choose how and when you want to hear from us.</p>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading preferences...</p>
        </div>
      } @else {
        <section class="portal-section">
          <div class="pref-table-wrap">
            <table class="pref-table">
              <thead>
                <tr>
                  <th class="pref-type-col">Notification Type</th>
                  @for (ch of channels; track ch.value) {
                    <th class="pref-ch-col">
                      <span class="material-symbols-outlined">{{ ch.icon }}</span>
                      {{ ch.label }}
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (nt of notifTypes; track nt.value) {
                  <tr>
                    <td class="pref-type-label">{{ nt.label }}</td>
                    @for (ch of channels; track ch.value) {
                      <td class="pref-toggle-cell">
                        <label class="toggle">
                          <input
                            type="checkbox"
                            [checked]="isEnabled(nt.value, ch.value)"
                            (change)="toggle(nt.value, ch.value, $event)"
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

          <div class="pref-footer">
            <button class="btn-reset" (click)="resetAll()" [disabled]="saving()">
              <span class="material-symbols-outlined">restart_alt</span>
              Reset to Defaults
            </button>
            @if (saveMsg()) {
              <span class="save-msg">{{ saveMsg() }}</span>
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: [
    `
      .portal-subtitle {
        margin: 4px 0 0;
        color: #64748b;
        font-size: 14px;
      }
      .pref-table-wrap {
        overflow-x: auto;
      }
      .pref-table {
        width: 100%;
        border-collapse: collapse;
        font-size: 14px;
      }
      .pref-table th,
      .pref-table td {
        padding: 12px 16px;
        border: 1px solid #e2e8f0;
        text-align: center;
      }
      .pref-type-col {
        text-align: left !important;
        min-width: 220px;
        background: #f8fafc;
        font-weight: 600;
        color: #475569;
      }
      .pref-ch-col {
        min-width: 90px;
        background: #f8fafc;
        font-weight: 600;
        color: #475569;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 2px;
      }
      .pref-ch-col .material-symbols-outlined {
        font-size: 18px;
      }
      .pref-type-label {
        text-align: left !important;
        color: #1e293b;
        font-weight: 500;
      }
      .pref-toggle-cell {
        vertical-align: middle;
      }

      /* Toggle switch */
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
        cursor: pointer;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background: #cbd5e1;
        border-radius: 22px;
        transition: 0.2s;
      }
      .slider::before {
        content: '';
        position: absolute;
        height: 16px;
        width: 16px;
        left: 3px;
        bottom: 3px;
        background: #fff;
        border-radius: 50%;
        transition: 0.2s;
      }
      .toggle input:checked + .slider {
        background: #3b82f6;
      }
      .toggle input:checked + .slider::before {
        transform: translateX(18px);
      }

      .pref-footer {
        display: flex;
        align-items: center;
        gap: 16px;
        margin-top: 20px;
      }
      .btn-reset {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 8px 16px;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        background: #fff;
        color: #ef4444;
        font-size: 13px;
        font-weight: 500;
        cursor: pointer;
        transition: background 0.15s;
      }
      .btn-reset:hover:not(:disabled) {
        background: #fef2f2;
      }
      .btn-reset:disabled {
        opacity: 0.5;
        cursor: default;
      }
      .save-msg {
        font-size: 13px;
        color: #16a34a;
        font-weight: 500;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyNotificationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  readonly notifTypes = NOTIFICATION_TYPES;
  readonly channels = CHANNELS;

  prefs = signal<NotificationPreferenceDTO[]>([]);
  loading = signal(true);
  saving = signal(false);
  saveMsg = signal('');

  ngOnInit() {
    this.portal.getNotificationPreferences().subscribe({
      next: (p) => {
        this.prefs.set(p);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  isEnabled(type: string, channel: string): boolean {
    const pref = this.prefs().find((p) => p.notificationType === type && p.channel === channel);
    return pref?.enabled ?? true; // default to enabled if no saved preference
  }

  toggle(type: string, channel: string, event: Event) {
    const enabled = (event.target as HTMLInputElement).checked;
    const dto: NotificationPreferenceUpdateDTO = {
      notificationType: type,
      channel,
      enabled,
    };
    this.saving.set(true);
    this.saveMsg.set('');
    this.portal.setNotificationPreference(dto).subscribe({
      next: (updated) => {
        this.prefs.update((list) => {
          const idx = list.findIndex(
            (p) => p.notificationType === updated.notificationType && p.channel === updated.channel,
          );
          if (idx >= 0) {
            const copy = [...list];
            copy[idx] = updated;
            return copy;
          }
          return [...list, updated];
        });
        this.saving.set(false);
        this.showSaveMsg('Saved');
      },
      error: () => {
        this.saving.set(false);
        this.showSaveMsg('Error saving');
      },
    });
  }

  resetAll() {
    if (!confirm('Reset all notification preferences to defaults?')) return;
    this.saving.set(true);
    this.portal.resetNotificationPreferences().subscribe({
      next: () => {
        this.prefs.set([]);
        this.saving.set(false);
        this.showSaveMsg('Reset to defaults');
      },
      error: () => {
        this.saving.set(false);
        this.showSaveMsg('Error resetting');
      },
    });
  }

  private showSaveMsg(msg: string) {
    this.saveMsg.set(msg);
    setTimeout(() => this.saveMsg.set(''), 3000);
  }
}
