import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';

import {
  PrenatalService,
  PrenatalAppointmentSummary,
  PrenatalScheduleRequest,
  PrenatalScheduleResponse,
  PrenatalVisitRecommendation,
} from '../services/prenatal.service';
import { PatientResponse } from '../services/patient.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';
import { refusalReason } from '../shared/refusal-reason';
import { localDateString, serverLocalDate } from '../shared/date-utils';
import { PatientPickerComponent } from '../shared/patient-picker/patient-picker.component';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { ClockTimePipe } from '../shared/pipes/clock-time.pipe';

@Component({
  selector: 'app-prenatal-tab',
  standalone: true,
  imports: [
    ClockTimePipe,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    PatientPickerComponent,
    EnumLabelPipe,
    RouterLink,
  ],
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
  /** QA D746: the date picker refuses an LMP after today (the server does too). */
  readonly today = localDateString();
  highRisk = false;
  supplementalWeeks = '';
  notes = '';
  generating = signal(false);
  schedule = signal<PrenatalScheduleResponse | null>(null);

  /**
   * QA D810: a recommended visit read "Non planifiée" with no way to act on
   * it. Each unplanned row links to the appointment form with the patient, the
   * recommended day (today when that day has passed) and an antenatal reason
   * filled in; the scheduler counts an appointment whose reason names
   * antenatal care as that visit, so the row reads "Planifiée" once booked.
   * Null for a row that is already planned.
   */
  readonly bookLinks = computed<(Record<string, string> | null)[]>(() => {
    const s = this.schedule();
    const patient = this.patient();
    if (!s || !patient) return [];
    return s.recommendations.map((r) => (r.scheduled ? null : this.bookParams(patient.id, r)));
  });

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
      error: (err: unknown) => {
        // QA D732: the server's localised reason (e.g. an LMP in the future)
        // is what the midwife needs; the generic text is only the fallback.
        this.toast.error(this.reasonOr(err, 'PRENATAL.GENERATE_ERROR'));
        this.generating.set(false);
      },
    });
  }

  private bookParams(patientId: string, r: PrenatalVisitRecommendation): Record<string, string> {
    const target = r.targetDate ? serverLocalDate(r.targetDate) : '';
    const today = localDateString();
    const params: Record<string, string> = {
      patientId,
      date: target && target >= today ? target : today,
      reason: this.translate.instant('PRENATAL.BOOK_REASON', { week: r.gestationalWeek }),
    };
    const time = r.suggestedStartTime?.substring(0, 5);
    if (time && /^\d{2}:\d{2}$/.test(time)) params['time'] = time;
    return params;
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
        error: (err: unknown) => {
          this.toast.error(this.reasonOr(err, 'PRENATAL.RESCHEDULE_ERROR'));
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
        error: (err: unknown) => {
          this.toast.error(this.reasonOr(err, 'PRENATAL.REMINDER_ERROR'));
          this.reminderBusy.set(false);
        },
      });
  }

  /** QA D732: a 4xx carries the backend's reason in the caller's language. */
  private reasonOr(err: unknown, fallbackKey: string): string {
    return refusalReason(err) ?? this.translate.instant(fallbackKey);
  }
}
