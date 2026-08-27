import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  AppointmentService,
  AppointmentResponse,
  AppointmentUpsertRequest,
  AppointmentStatus,
} from '../services/appointment.service';
import { ReceptionService } from '../reception/reception.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { EnumLabelPipe } from '../shared/pipes/enum-label.pipe';

/** Roles permitted to update appointment status (confirm / complete / no-show). */
const STATUS_UPDATE_ROLES: readonly string[] = [
  'ROLE_DOCTOR',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_RECEPTIONIST',
  'ROLE_STAFF',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_ADMIN',
  'ROLE_SUPER_ADMIN',
];

/**
 * Roles that can check a patient in from this page.
 *
 * <p>Must stay a subset of what the backend's {@code POST /reception/check-in}
 * accepts — this list only decides whether the button is drawn.
 */
const CHECK_IN_ROLES: readonly string[] = [
  'ROLE_RECEPTIONIST',
  'ROLE_HOSPITAL_ADMIN',
  'ROLE_ADMIN',
  'ROLE_SUPER_ADMIN',
];

/**
 * Statuses a patient can be checked in from. Mirrors the guard in
 * {@code ReceptionServiceImpl.checkInPatient}, which rejects anything else
 * with an IllegalStateException — drawing the button for a status the API
 * refuses would just move the failure one click later.
 */
const CHECK_IN_ELIGIBLE: readonly AppointmentStatus[] = ['SCHEDULED', 'CONFIRMED'];

@Component({
  selector: 'app-appointment-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule, EnumLabelPipe],
  templateUrl: './appointment-detail.html',
  styleUrl: './appointment-detail.scss',
})
export class AppointmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly appointmentService = inject(AppointmentService);
  private readonly toast = inject(ToastService);
  private readonly receptionService = inject(ReceptionService);
  private readonly translate = inject(TranslateService);
  protected readonly permissions = inject(PermissionService);
  private readonly auth = inject(AuthService);

  appointment = signal<AppointmentResponse | null>(null);
  loading = signal(true);
  saving = signal(false);
  cancelling = signal(false);

  // Reschedule modal
  showReschedule = signal(false);
  rescheduleDate = '';
  rescheduleStart = '';
  rescheduleEnd = '';
  rescheduleNotes = '';

  // Cancel confirm modal
  showCancelConfirm = signal(false);

  private appointmentId = '';

  get currentUserRoles(): string[] {
    return this.auth.getUserProfile()?.roles ?? [];
  }

  get canCancel(): boolean {
    const appt = this.appointment();
    if (!appt) return false;
    const cancellableStatuses: AppointmentStatus[] = ['SCHEDULED', 'CONFIRMED', 'PENDING'];
    return cancellableStatuses.includes(appt.status);
  }

  get canReschedule(): boolean {
    const appt = this.appointment();
    if (!appt) return false;
    const reschedulableStatuses: AppointmentStatus[] = ['SCHEDULED', 'CONFIRMED', 'PENDING'];
    return reschedulableStatuses.includes(appt.status);
  }

  get canUpdateStatus(): boolean {
    return STATUS_UPDATE_ROLES.some((r) => this.currentUserRoles.includes(r));
  }

  get canCheckIn(): boolean {
    const appt = this.appointment();
    if (!appt) return false;
    return (
      CHECK_IN_ELIGIBLE.includes(appt.status) &&
      CHECK_IN_ROLES.some((r) => this.currentUserRoles.includes(r))
    );
  }

  /** True once the current date/time is at or past the appointment's start time. */
  get hasAppointmentStarted(): boolean {
    const appt = this.appointment();
    if (!appt) return false;
    const start = new Date(`${appt.appointmentDate}T${appt.startTime}`);
    return new Date() >= start;
  }

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      void this.router.navigate(['/appointments']);
      return;
    }
    this.appointmentId = id;
    this.loadAppointment(id);
  }

  loadAppointment(id: string): void {
    this.loading.set(true);
    this.appointmentService.getById(id).subscribe({
      next: (appt) => {
        this.appointment.set(appt);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Appointment not found');
        this.loading.set(false);
        void this.router.navigate(['/appointments']);
      },
    });
  }

  getStatusClass(status: AppointmentStatus): string {
    const map: Record<AppointmentStatus, string> = {
      SCHEDULED: 'status-scheduled',
      CONFIRMED: 'status-confirmed',
      CHECKED_IN: 'status-checked-in',
      IN_PROGRESS: 'status-in-progress',
      COMPLETED: 'status-completed',
      CANCELLED: 'status-cancelled',
      RESCHEDULED: 'status-rescheduled',
      NO_SHOW: 'status-no-show',
      FAILED: 'status-failed',
      PENDING: 'status-requested',
      UNKNOWN: '',
    };
    return map[status] ?? '';
  }

  // ─── Reschedule ─────────────────────────────────────────────────────────────

  openReschedule(): void {
    const appt = this.appointment();
    if (!appt) return;
    this.rescheduleDate = appt.appointmentDate;
    this.rescheduleStart = appt.startTime;
    this.rescheduleEnd = appt.endTime;
    this.rescheduleNotes = appt.notes ?? '';
    this.showReschedule.set(true);
  }

  closeReschedule(): void {
    this.showReschedule.set(false);
  }

  submitReschedule(): void {
    const appt = this.appointment();
    if (!appt || !this.rescheduleDate || !this.rescheduleStart || !this.rescheduleEnd) {
      this.toast.error('Please fill in all required fields.');
      return;
    }

    const selectedDate = new Date(this.rescheduleDate);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    if (selectedDate < today) {
      this.toast.error('Cannot schedule appointments in the past.');
      return;
    }

    if (this.rescheduleEnd <= this.rescheduleStart) {
      this.toast.error('End time must be after start time.');
      return;
    }

    this.saving.set(true);
    const req: AppointmentUpsertRequest = {
      appointmentDate: this.rescheduleDate,
      startTime: this.rescheduleStart,
      endTime: this.rescheduleEnd,
      // SCHEDULED, not RESCHEDULED. A moved appointment is a LIVE appointment
      // at a new time — the patient is expected to turn up and be checked in.
      // Writing RESCHEDULED here stranded it: every consumer treats that
      // status as terminal, so the detail page drew no action buttons, the
      // reception queue drew no check-in icon, and the backend's
      // checkInPatient rejected it outright. The appointment kept its date
      // and lost every way to act on it. The backend's own transition map
      // already says RESCHEDULED -> {SCHEDULED, CONFIRMED, CANCELLED}, i.e.
      // it was always meant to be passed through, and nothing passed it.
      //
      // Landing on SCHEDULED rather than keeping CONFIRMED is deliberate: the
      // patient confirmed a DIFFERENT time, so that confirmation no longer
      // means anything and should be asked for again.
      status: 'SCHEDULED',
      patientId: appt.patientId,
      staffId: appt.staffId,
      hospitalId: appt.hospitalId,
      departmentId: appt.departmentId ?? undefined,
      reason: appt.reason,
      notes: this.rescheduleNotes || appt.notes || undefined,
    };
    this.appointmentService.update(this.appointmentId, req).subscribe({
      next: (updated) => {
        this.appointment.set(updated);
        this.toast.success('Appointment rescheduled successfully');
        this.showReschedule.set(false);
        this.saving.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to reschedule appointment');
        this.saving.set(false);
      },
    });
  }

  // ─── Cancel ─────────────────────────────────────────────────────────────────

  openCancelConfirm(): void {
    this.showCancelConfirm.set(true);
  }

  closeCancelConfirm(): void {
    this.showCancelConfirm.set(false);
  }

  executeCancel(): void {
    this.cancelling.set(true);
    this.appointmentService.updateStatus(this.appointmentId, 'CANCEL').subscribe({
      next: (updated) => {
        this.appointment.set(updated);
        this.toast.success('Appointment cancelled');
        this.showCancelConfirm.set(false);
        this.cancelling.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to cancel appointment');
        this.cancelling.set(false);
      },
    });
  }

  // ─── Quick status actions ────────────────────────────────────────────────────

  markAsConfirmed(): void {
    this.updateStatus('CONFIRM');
  }

  markAsCompleted(): void {
    this.updateStatus('COMPLETE');
  }

  markAsNoShow(): void {
    this.updateStatus('NO_SHOW');
  }

  /**
   * Put a legacy RESCHEDULED appointment back into play.
   *
   * <p>Reschedule now lands on SCHEDULED directly, so this only exists for
   * rows written before that fix. Without it those appointments have a date,
   * a time, an expected patient, and no action anywhere that can move them
   * on.
   */
  markAsScheduled(): void {
    this.updateStatus('SCHEDULE');
  }

  private updateStatus(action: string): void {
    this.saving.set(true);
    this.appointmentService.updateStatus(this.appointmentId, action).subscribe({
      next: (updated) => {
        this.appointment.set(updated);
        this.toast.success('Status updated');
        this.saving.set(false);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to update status');
        this.saving.set(false);
      },
    });
  }

  goBack(): void {
    void this.router.navigate(['/appointments']);
  }

  /**
   * Check the patient in.
   *
   * <p>This used to be {@code router.navigate(['/reception'])} and nothing
   * else — a button sitting in the same action bar as Confirm, Complete and
   * No Show, all of which call the API, that silently changed nothing. It did
   * not even pass the appointment id, so reception could not pre-select the
   * patient either.
   *
   * <p>What it has to do is real work, because check-in is what creates the
   * {@code Encounter(ARRIVED)} that the Patient Tracker board is built from.
   * No check-in, no encounter, and the board stays empty however many times
   * somebody presses the button.
   */
  checkIn(): void {
    const appt = this.appointment();
    if (!appt || this.saving()) return;

    this.saving.set(true);
    this.receptionService.checkInPatient({ appointmentId: this.appointmentId }).subscribe({
      next: () => {
        this.toast.success(this.translate.instant('RECEPTION.CHECK_IN_SUCCESS'));
        // Re-read rather than patching the status locally: check-in also
        // stamps checkedInAt and creates the encounter, and the row the
        // server has is the one that matters.
        this.loadAppointment(this.appointmentId);
        this.saving.set(false);
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('RECEPTION.CHECK_IN_FAILED'),
        );
        this.saving.set(false);
      },
    });
  }

  /** Open the Reception cockpit — for the co-pay, insurance and consent steps. */
  goToReception(): void {
    void this.router.navigate(['/reception']);
  }
}
