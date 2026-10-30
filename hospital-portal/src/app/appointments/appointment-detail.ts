import { refuseFields } from '../shared/forms/invalid-fields';
import { StaffService, StaffResponse, isClinicalProvider } from '../services/staff.service';
import { DialogFocusDirective } from '../shared/a11y/dialog-focus.directive';
import {
  Component,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
  ElementRef,
} from '@angular/core';
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
import { ServerTimePipe } from '../shared/pipes/server-time.pipe';
import { serverDateOrInstant } from '../shared/date-utils';

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
  imports: [
    DialogFocusDirective,
    ServerTimePipe,
    CommonModule,
    FormsModule,
    TranslateModule,
    EnumLabelPipe,
  ],
  templateUrl: './appointment-detail.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './appointment-detail.scss',
})
export class AppointmentDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly appointmentService = inject(AppointmentService);
  private readonly toast = inject(ToastService);
  private readonly receptionService = inject(ReceptionService);
  private readonly translate = inject(TranslateService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  protected readonly permissions = inject(PermissionService);
  private readonly auth = inject(AuthService);
  private readonly staffService = inject(StaffService);

  /**
   * QA D1018 / D923: the reschedule can move the visit to another clinician,
   * department or reason, not only another time. The hospital's clinicians,
   * read when the dialog opens.
   */
  readonly rescheduleStaffOptions = signal<StaffResponse[]>([]);
  rescheduleStaffId = '';
  rescheduleDepartmentId = '';
  rescheduleReason = '';

  /** The departments of the hospital's clinicians (the booking form's list). */
  rescheduleDepartments(): { id: string; name: string }[] {
    const seen = new Map<string, string>();
    for (const s of this.rescheduleStaffOptions()) {
      if (s.departmentId && !seen.has(s.departmentId)) {
        seen.set(s.departmentId, s.departmentName || s.departmentId);
      }
    }
    const appt = this.appointment();
    if (appt?.departmentId && !seen.has(appt.departmentId)) {
      seen.set(appt.departmentId, appt.departmentName || appt.departmentId);
    }
    return Array.from(seen.entries()).map(([id, name]) => ({ id, name }));
  }

  /** Whether the booked clinician is in the loaded list (else it is offered on its own). */
  hasRescheduleStaff(staffId: string): boolean {
    return this.rescheduleStaffOptions().some((s) => s.id === staffId);
  }

  /** A clinician picked: the visit follows them to their department. */
  onRescheduleStaffChange(staffId: string): void {
    this.rescheduleStaffId = staffId;
    const picked = this.rescheduleStaffOptions().find((s) => s.id === staffId);
    if (picked?.departmentId) this.rescheduleDepartmentId = picked.departmentId;
  }

  appointment = signal<AppointmentResponse | null>(null);
  loading = signal(true);
  saving = signal(false);
  cancelling = signal(false);

  // Reschedule modal
  showReschedule = signal(false);
  rescheduleDate = '';
  rescheduleStart = '';
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

  /**
   * QA D788: a CHECKED_IN appointment has an open visit (check-in opens it),
   * and that visit's check-out completes the appointment. Completing it by
   * hand is offered only where no visit was opened at check-in; the API
   * refuses it too while a linked visit is not checked out.
   */
  get canComplete(): boolean {
    const status = this.appointment()?.status;
    return status === 'CONFIRMED' || status === 'IN_PROGRESS';
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
        this.toast.error(this.translate.instant('APPOINTMENTS.NOT_FOUND'));
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
    this.rescheduleNotes = appt.notes ?? '';
    this.rescheduleStaffId = appt.staffId;
    this.rescheduleDepartmentId = appt.departmentId ?? '';
    this.rescheduleReason = appt.reason ?? '';
    this.showReschedule.set(true);
    if (appt.hospitalId) {
      this.staffService.list(appt.hospitalId).subscribe({
        next: (list) => this.rescheduleStaffOptions.set(list.filter(isClinicalProvider)),
        // The dialog still moves the time with the booked clinician.
        error: () => this.rescheduleStaffOptions.set([]),
      });
    }
  }

  closeReschedule(): void {
    this.showReschedule.set(false);
  }

  submitReschedule(): void {
    const appt = this.appointment();
    if (!appt || !this.rescheduleDate || !this.rescheduleStart) {
      // QA D1072: the missing field is marked and focused.
      refuseFields(
        this.host.nativeElement,
        [
          !this.rescheduleDate ? 'reschedDate' : null,
          !this.rescheduleStart ? 'reschedStart' : null,
        ].filter((id): id is string => id !== null),
        ['reschedDate', 'reschedStart'],
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      this.toast.error(this.translate.instant('APPOINTMENTS.REQUIRED_FIELDS'));
      return;
    }

    // #948 review: the date input's value is that local day, compared with local midnight.
    const selectedDate = serverDateOrInstant(this.rescheduleDate);
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    if (selectedDate < today) {
      this.toast.error(this.translate.instant('APPOINTMENTS.DATE_IN_PAST'));
      return;
    }

    this.saving.set(true);
    const req: AppointmentUpsertRequest = {
      appointmentDate: this.rescheduleDate,
      startTime: this.rescheduleStart,
      // QA D1060: no end time; the server sets it from the slot rules.
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
      // QA D1018: what the desk chose in the dialog, the booked values otherwise.
      staffId: this.rescheduleStaffId || appt.staffId,
      hospitalId: appt.hospitalId,
      departmentId: this.rescheduleDepartmentId || appt.departmentId || undefined,
      reason: this.rescheduleReason.trim() || appt.reason,
      notes: this.rescheduleNotes || appt.notes || undefined,
    };
    this.appointmentService.update(this.appointmentId, req).subscribe({
      next: (updated) => {
        this.appointment.set(updated);
        this.toast.success(this.translate.instant('APPOINTMENTS.RESCHEDULED'));
        this.showReschedule.set(false);
        this.saving.set(false);
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('APPOINTMENTS.RESCHEDULE_FAILED'),
        );
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
        this.toast.success(this.translate.instant('APPOINTMENTS.CANCEL_SUCCESS'));
        this.showCancelConfirm.set(false);
        this.cancelling.set(false);
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('APPOINTMENTS.CANCEL_FAILED'),
        );
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
        this.toast.success(this.translate.instant('APPOINTMENTS.STATUS_UPDATED'));
        this.saving.set(false);
      },
      error: (err) => {
        this.toast.error(
          err?.error?.message ?? this.translate.instant('APPOINTMENTS.STATUS_UPDATE_FAILED'),
        );
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
