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
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { AuthService } from '../auth/auth.service';
import { TranslateModule } from '@ngx-translate/core';

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

@Component({
  selector: 'app-appointment-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, TranslateModule],
  templateUrl: './appointment-detail.html',
  styleUrl: './appointment-detail.scss',
})
export class AppointmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly appointmentService = inject(AppointmentService);
  private readonly toast = inject(ToastService);
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
      status: 'RESCHEDULED',
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

  goToCheckIn(): void {
    void this.router.navigate(['/reception']);
  }
}
