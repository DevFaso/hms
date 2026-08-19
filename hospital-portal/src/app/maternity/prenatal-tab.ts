import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PrenatalService,
  PrenatalAppointmentSummary,
  PrenatalScheduleRequest,
  PrenatalScheduleResponse,
} from '../services/prenatal.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';

@Component({
  selector: 'app-prenatal-tab',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, PatientPickerComponent],
  templateUrl: './prenatal-tab.html',
  styleUrl: './maternity.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PrenatalTabComponent implements OnInit {
  private readonly prenatalService = inject(PrenatalService);
  private readonly auth = inject(AuthService);
  private readonly roleContext = inject(RoleContextService);
  private readonly toast = inject(ToastService);
  private readonly translate = inject(TranslateService);

  private hospitalId: string | null = null;

  patient = signal<PatientResponse | null>(null);
  lmpDate = '';
  highRisk = false;
  supplementalWeeks = '';
  notes = '';
  generating = signal(false);
  schedule = signal<PrenatalScheduleResponse | null>(null);

  /* ── Reschedule modal ── */
  showRescheduleModal = signal(false);
  rescheduleTarget = signal<PrenatalAppointmentSummary | null>(null);
  rescheduleDate = '';
  rescheduleTime = '';
  rescheduleNotes = '';
  rescheduleBusy = signal(false);

  /* ── Reminder modal ── */
  showReminderModal = signal(false);
  reminderTarget = signal<PrenatalAppointmentSummary | null>(null);
  reminderDaysBefore = 1;
  reminderMessage = '';
  reminderBusy = signal(false);

  ngOnInit(): void {
    this.hospitalId = this.roleContext.activeHospitalId ?? this.auth.getHospitalId();
  }

  onPatientPicked(p: PatientResponse | null): void {
    this.patient.set(p);
    this.schedule.set(null);
  }

  generate(): void {
    const patient = this.patient();
    if (!patient || !this.lmpDate || !this.hospitalId) {
      this.toast.error(this.translate.instant('PRENATAL.REQUIRED_FIELDS'));
      return;
    }
    const weeks = this.supplementalWeeks
      .split(',')
      .map((w) => Number.parseInt(w.trim(), 10))
      .filter((w) => Number.isFinite(w) && w > 0)
      .slice(0, 8);
    const req: PrenatalScheduleRequest = {
      patientId: patient.id,
      hospitalId: this.hospitalId,
      staffId: this.auth.getUserProfile()?.staffId ?? undefined,
      lastMenstrualPeriodDate: this.lmpDate,
      highRisk: this.highRisk,
      supplementalVisitWeeks: weeks.length ? weeks : undefined,
      notes: this.notes.trim() || undefined,
    };
    this.generating.set(true);
    this.prenatalService.schedule(req).subscribe({
      next: (schedule) => {
        this.schedule.set(schedule);
        this.generating.set(false);
      },
      error: () => {
        this.toast.error(this.translate.instant('PRENATAL.GENERATE_ERROR'));
        this.generating.set(false);
      },
    });
  }

  /* ── Reschedule ── */

  openReschedule(appointment: PrenatalAppointmentSummary): void {
    this.rescheduleTarget.set(appointment);
    this.rescheduleDate = '';
    this.rescheduleTime = appointment.startTime?.substring(0, 5) ?? '';
    this.rescheduleNotes = '';
    this.showRescheduleModal.set(true);
  }

  closeReschedule(): void {
    this.showRescheduleModal.set(false);
    this.rescheduleTarget.set(null);
  }

  submitReschedule(): void {
    const target = this.rescheduleTarget();
    if (!target || !this.rescheduleDate || !this.rescheduleTime) {
      this.toast.error(this.translate.instant('PRENATAL.RESCHEDULE_REQUIRED'));
      return;
    }
    this.rescheduleBusy.set(true);
    this.prenatalService
      .reschedule({
        appointmentId: target.appointmentId,
        newAppointmentDate: this.rescheduleDate,
        newStartTime: this.rescheduleTime,
        notes: this.rescheduleNotes.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('PRENATAL.RESCHEDULED'));
          this.rescheduleBusy.set(false);
          // Patch the row in place rather than re-running generate(): the
          // generate form is live state the user may have edited or cleared
          // since, so regenerating could toast a spurious required-fields
          // error or repaint the table with a different LMP's cadence.
          const date = this.rescheduleDate;
          const time = this.rescheduleTime;
          const appointmentId = target.appointmentId;
          this.schedule.update((s) =>
            s
              ? {
                  ...s,
                  existingAppointments: (s.existingAppointments ?? []).map((a) =>
                    a.appointmentId === appointmentId
                      ? { ...a, appointmentDate: date, startTime: time }
                      : a,
                  ),
                }
              : s,
          );
          this.closeReschedule();
        },
        error: () => {
          this.toast.error(this.translate.instant('PRENATAL.RESCHEDULE_ERROR'));
          this.rescheduleBusy.set(false);
        },
      });
  }

  /* ── Reminder ── */

  openReminder(appointment: PrenatalAppointmentSummary): void {
    this.reminderTarget.set(appointment);
    this.reminderDaysBefore = 1;
    this.reminderMessage = '';
    this.showReminderModal.set(true);
  }

  closeReminder(): void {
    this.showReminderModal.set(false);
    this.reminderTarget.set(null);
  }

  submitReminder(): void {
    const target = this.reminderTarget();
    if (!target) return;
    this.reminderBusy.set(true);
    this.prenatalService
      .sendReminder(
        target.appointmentId,
        Math.max(0, this.reminderDaysBefore),
        this.reminderMessage.trim() || undefined,
      )
      .subscribe({
        next: () => {
          this.toast.success(this.translate.instant('PRENATAL.REMINDER_QUEUED'));
          this.reminderBusy.set(false);
          this.closeReminder();
        },
        error: () => {
          this.toast.error(this.translate.instant('PRENATAL.REMINDER_ERROR'));
          this.reminderBusy.set(false);
        },
      });
  }
}
