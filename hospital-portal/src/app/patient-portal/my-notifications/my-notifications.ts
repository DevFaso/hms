import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  PatientPortalService,
  NotificationPreferenceDTO,
  NotificationPreferenceUpdateDTO,
} from '../../services/patient-portal.service';

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
  templateUrl: './my-notifications.html',
  styleUrl: './my-notifications.scss',
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
