import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  NotificationService,
  NotificationPreferenceUpdate,
} from '../services/notification.service';
import { ToastService } from '../core/toast.service';

const NOTIFICATION_TYPES = [
  'APPOINTMENT_REMINDER',
  'LAB_RESULT',
  'MEDICATION_REFILL',
  'BILLING',
  'DISCHARGE_SUMMARY',
  'PROXY_ACCESS',
  'SYSTEM',
  'GENERAL',
] as const;

const CHANNELS = ['IN_APP', 'EMAIL', 'SMS', 'PUSH'] as const;

interface PrefRow {
  notificationType: string;
  label: string;
  channels: Record<string, boolean>;
}

@Component({
  selector: 'app-notification-settings',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  template: `
    <div class="settings-page">
      <div class="settings-header">
        <h1>
          <span class="material-symbols-outlined">tune</span>
          Notification Preferences
        </h1>
        <a routerLink="/notifications" class="back-link">
          <span class="material-symbols-outlined">arrow_back</span> Back to Notifications
        </a>
      </div>

      @if (loading()) {
        <div class="settings-loading">
          <div class="spinner"></div>
          <span>Loading preferences…</span>
        </div>
      } @else {
        <p class="settings-desc">Choose how you want to be notified for each type of event.</p>

        <table class="pref-table">
          <thead>
            <tr>
              <th>Notification Type</th>
              @for (ch of channels; track ch) {
                <th class="channel-col">{{ formatChannel(ch) }}</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.notificationType) {
              <tr>
                <td class="type-label">{{ row.label }}</td>
                @for (ch of channels; track ch) {
                  <td class="channel-col">
                    <label class="toggle">
                      <input
                        type="checkbox"
                        [(ngModel)]="row.channels[ch]"
                        (ngModelChange)="dirty.set(true)"
                      />
                      <span class="slider"></span>
                    </label>
                  </td>
                }
              </tr>
            }
          </tbody>
        </table>

        <div class="settings-actions">
          <button class="btn-primary" (click)="save()" [disabled]="saving() || !dirty()">
            {{ saving() ? 'Saving…' : 'Save Preferences' }}
          </button>
        </div>
      }
    </div>
  `,
  styles: [
    `
      .settings-page {
        max-width: 800px;
        margin: 0 auto;
        padding: 2rem 1rem;
      }
      .settings-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 1.5rem;
      }
      .settings-header h1 {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        font-size: 1.5rem;
      }
      .back-link {
        display: flex;
        align-items: center;
        gap: 0.25rem;
        color: var(--primary, #1976d2);
        text-decoration: none;
        font-size: 0.9rem;
      }
      .settings-desc {
        color: #666;
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
        border-top-color: #1976d2;
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
        padding: 0.75rem 1rem;
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
        background-color: #1976d2;
      }
      .toggle input:checked + .slider::before {
        transform: translateX(18px);
      }
      .settings-actions {
        margin-top: 1.5rem;
        display: flex;
        justify-content: flex-end;
      }
      .btn-primary {
        background: #1976d2;
        color: #fff;
        border: none;
        padding: 0.6rem 1.5rem;
        border-radius: 6px;
        font-weight: 600;
        cursor: pointer;
      }
      .btn-primary:disabled {
        opacity: 0.5;
        cursor: default;
      }
    `,
  ],
})
export class NotificationSettingsComponent implements OnInit {
  private readonly notifService = inject(NotificationService);
  private readonly toast = inject(ToastService);

  channels = CHANNELS;
  loading = signal(true);
  saving = signal(false);
  dirty = signal(false);
  rows = signal<PrefRow[]>([]);

  ngOnInit(): void {
    this.loadPreferences();
  }

  formatChannel(ch: string): string {
    return ch.replace('_', ' ');
  }

  private loadPreferences(): void {
    // Build default grid — all enabled
    const defaultRows: PrefRow[] = NOTIFICATION_TYPES.map((t) => ({
      notificationType: t,
      label: this.formatType(t),
      channels: Object.fromEntries(CHANNELS.map((ch) => [ch, true])),
    }));

    this.notifService.getPreferences().subscribe({
      next: (prefs) => {
        // Merge saved preferences into the grid
        for (const p of prefs) {
          const row = defaultRows.find((r) => r.notificationType === p.notificationType);
          if (row) row.channels[p.channel] = p.enabled;
        }
        this.rows.set(defaultRows);
        this.loading.set(false);
      },
      error: () => {
        this.rows.set(defaultRows);
        this.loading.set(false);
      },
    });
  }

  save(): void {
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
    this.saving.set(true);
    this.notifService.updatePreferences(updates).subscribe({
      next: () => {
        this.saving.set(false);
        this.dirty.set(false);
        this.toast.success('Preferences saved');
      },
      error: () => {
        this.saving.set(false);
        this.toast.error('Failed to save preferences');
      },
    });
  }

  private formatType(t: string): string {
    return t
      .split('_')
      .map((w) => w.charAt(0) + w.slice(1).toLowerCase())
      .join(' ');
  }
}
