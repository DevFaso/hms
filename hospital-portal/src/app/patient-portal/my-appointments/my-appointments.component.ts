import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import {
  PatientPortalService,
  PortalAppointment,
  BookAppointmentRequest,
  SchedulingHospital,
  SchedulingDepartment,
  SchedulingProvider,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { localDateString } from '../../shared/date-utils';
import { PreCheckinFormComponent } from './pre-checkin-form/pre-checkin-form.component';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { ClockTimePipe } from '../../shared/pipes/clock-time.pipe';

/** Sort key of a visit: its wire date and 24 h start time (QA D642). */
function visitKey(a: PortalAppointment): string {
  return `${a.date ?? ''}T${a.rawStartTime ?? ''}`;
}

@Component({
  selector: 'app-my-appointments',
  standalone: true,
  imports: [
    ClockTimePipe,
    ServerTimePipe,
    CommonModule,
    DatePipe,
    EnumLabelPipe,
    TranslateModule,
    FormsModule,
    PreCheckinFormComponent,
    DialogA11yDirective,
  ],
  templateUrl: './my-appointments.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrls: ['./my-appointments.component.scss', '../patient-portal-pages.scss'],
})
export class MyAppointmentsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly translate = inject(TranslateService);
  private readonly route = inject(ActivatedRoute);
  appointments = signal<PortalAppointment[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  upcoming = signal<PortalAppointment[]>([]);
  past = signal<PortalAppointment[]>([]);
  /** QA D542: cancelled visits whose date is still ahead - not "past". */
  cancelled = signal<PortalAppointment[]>([]);
  readonly closedGroups = computed(() =>
    [
      { titleKey: 'PORTAL.APPOINTMENTS.CANCELLED_SECTION', items: this.cancelled() },
      { titleKey: 'PORTAL.APPOINTMENTS.PAST', items: this.past() },
    ].filter((g) => g.items.length > 0),
  );

  // ── Booking form state ───────────────────────────────────────────
  showBookingForm = signal(false);
  bookingLoading = signal(false);
  bookingError = signal<string | null>(null);
  bookingSuccess = signal(false);

  // ── Pre-check-in state ──────────────────────────────────────────
  showPreCheckIn = signal(false);
  preCheckInAppointment = signal<PortalAppointment | null>(null);

  // ── Cancel state ────────────────────────────────────────────────
  cancelTarget = signal<PortalAppointment | null>(null);
  cancelLoading = signal(false);
  cancelError = signal<string | null>(null);
  cancelReason = '';

  // ── Reschedule state ────────────────────────────────────────────
  rescheduleTarget = signal<PortalAppointment | null>(null);
  rescheduleLoading = signal(false);
  rescheduleError = signal<string | null>(null);
  rescheduleDate = '';
  rescheduleStartTime = '';
  rescheduleEndTime = '';
  rescheduleReason = '';

  hospitals = signal<SchedulingHospital[]>([]);
  departments = signal<SchedulingDepartment[]>([]);
  providers = signal<SchedulingProvider[]>([]);

  selectedHospitalId = '';
  selectedDepartmentId = '';
  selectedProviderId = '';
  selectedDate = '';
  selectedTime = '';
  appointmentReason = '';
  appointmentNotes = '';

  ngOnInit() {
    this.loadAppointments();
  }

  /**
   * Open the modal an emailed link asked for, once the data it needs is in.
   *
   * <p>Confirmation emails link to `/appointments/cancel/{id}` and
   * `/appointments/reschedule/{id}`; AppointmentLinkGuard rewrites those to
   * `?cancel=` / `?reschedule=` on this route. Applied AFTER the load
   * because both modals bind to a PortalAppointment, not to an id.
   *
   * <p>An id that does not resolve is left alone deliberately - the patient
   * still gets their appointment list, which beats an error for a link that
   * may simply be stale (already cancelled, or a since-rebooked visit).
   */
  private applyDeepLink(appts: PortalAppointment[]): void {
    const params = this.route.snapshot.queryParamMap;
    const cancelId = params.get('cancel');
    const rescheduleId = params.get('reschedule');
    if (!cancelId && !rescheduleId) {
      return;
    }

    const target = appts.find((a) => a.id === (cancelId ?? rescheduleId));
    if (!target || !this.canModify(target)) {
      return;
    }

    if (cancelId) {
      this.openCancel(target);
    } else {
      this.openReschedule(target);
    }
  }

  private loadAppointments(): void {
    this.loading.set(true);
    this.portal.getMyAppointments().subscribe({
      next: (appts) => {
        this.appointments.set(appts);
        // QA D433: compare calendar days in the patient's own time zone.
        // new Date('yyyy-MM-dd') is UTC midnight, which is already behind
        // "now" on the day itself, so today's visit was listed as past.
        const today = localDateString();
        const isClosed = (a: PortalAppointment) =>
          a.status === 'COMPLETED' || a.status === 'CANCELLED';
        // QA D642: "À venir" listed 30 Oct before 28 Oct — soonest first.
        this.upcoming.set(
          appts
            .filter((a) => !isClosed(a) && a.date >= today)
            .sort((a, b) => visitKey(a).localeCompare(visitKey(b))),
        );
        // QA D542: a cancelled visit still ahead is listed under "Cancelled",
        // not "Past"; it stays visible after the patient cancels.
        const cancelledAhead = (a: PortalAppointment) =>
          a.status === 'CANCELLED' && a.date >= today;
        this.cancelled.set(
          appts.filter(cancelledAhead).sort((a, b) => visitKey(a).localeCompare(visitKey(b))),
        );
        // QA D642: "past" reads newest first.
        this.past.set(
          appts
            .filter((a) => !cancelledAhead(a) && (isClosed(a) || a.date < today))
            .sort((a, b) => visitKey(b).localeCompare(visitKey(a))),
        );
        this.loading.set(false);
        this.applyDeepLink(appts);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }

  // ── Pre-check-in actions ──────────────────────────────────────────

  openPreCheckIn(appt: PortalAppointment): void {
    this.preCheckInAppointment.set(appt);
    this.showPreCheckIn.set(true);
  }

  closePreCheckIn(): void {
    this.showPreCheckIn.set(false);
    this.preCheckInAppointment.set(null);
  }

  onPreCheckInCompleted(): void {
    this.closePreCheckIn();
    this.loadAppointments();
  }

  // ── Cancel / reschedule actions ──────────────────────────────────

  /**
   * The backend rejects only COMPLETED and CANCELLED, but only offer the
   * actions on states a patient can sensibly act on.
   */
  /** Whole days from today to the appointment, local calendar; null when the date is unreadable. */
  private daysUntil(appt: PortalAppointment): number | null {
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(appt.date ?? '');
    if (!match) return null;
    const day = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]));
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    return Math.round((day.getTime() - today.getTime()) / 86_400_000);
  }

  /**
   * QA D940: the server takes a pre-check-in from 7 days before the
   * appointment to the day itself; outside that window the button was offered
   * and the patient learned it only after three steps, in English.
   */
  preCheckInOpen(appt: PortalAppointment): boolean {
    const days = this.daysUntil(appt);
    return days !== null && days >= 0 && days <= 7;
  }

  /** QA D940: the day the window opens, for an appointment still too far away. */
  preCheckInOpensOn(appt: PortalAppointment): Date | null {
    const days = this.daysUntil(appt);
    if (days === null || days <= 7) return null;
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), now.getDate() + days - 7);
  }

  canModify(appt: PortalAppointment): boolean {
    return ['SCHEDULED', 'CONFIRMED', 'PENDING', 'RESCHEDULED'].includes(appt.status);
  }

  openCancel(appt: PortalAppointment): void {
    this.cancelTarget.set(appt);
    this.cancelReason = '';
    this.cancelError.set(null);
  }

  closeCancel(): void {
    this.cancelTarget.set(null);
    this.cancelLoading.set(false);
  }

  submitCancel(): void {
    const appt = this.cancelTarget();
    if (!appt) return;
    this.cancelLoading.set(true);
    this.cancelError.set(null);
    this.portal.cancelAppointment({ appointmentId: appt.id, reason: this.cancelReason }).subscribe({
      next: () => {
        this.closeCancel();
        this.loadAppointments();
      },
      error: (err) => {
        this.cancelLoading.set(false);
        const msg =
          err?.error?.message ||
          err?.error?.error ||
          this.translate.instant('PORTAL.APPOINTMENTS.CANCEL.FAILED');
        this.cancelError.set(msg);
      },
    });
  }

  openReschedule(appt: PortalAppointment): void {
    this.rescheduleTarget.set(appt);
    this.rescheduleDate = appt.date;
    this.rescheduleStartTime = appt.rawStartTime;
    this.rescheduleEndTime = appt.rawEndTime;
    this.rescheduleReason = '';
    this.rescheduleError.set(null);
  }

  closeReschedule(): void {
    this.rescheduleTarget.set(null);
    this.rescheduleLoading.set(false);
  }

  submitReschedule(): void {
    const appt = this.rescheduleTarget();
    if (!appt) return;
    if (!this.rescheduleDate || !this.rescheduleStartTime || !this.rescheduleEndTime) {
      this.rescheduleError.set(
        this.translate.instant('PORTAL.APPOINTMENTS.RESCHEDULE.MISSING_FIELDS'),
      );
      return;
    }
    if (this.rescheduleEndTime <= this.rescheduleStartTime) {
      this.rescheduleError.set(
        this.translate.instant('PORTAL.APPOINTMENTS.RESCHEDULE.END_AFTER_START'),
      );
      return;
    }
    if (this.rescheduleDate < this.todayDate) {
      this.rescheduleError.set(this.translate.instant('PORTAL.APPOINTMENTS.RESCHEDULE.PAST_DATE'));
      return;
    }

    this.rescheduleLoading.set(true);
    this.rescheduleError.set(null);
    this.portal
      .rescheduleAppointment({
        appointmentId: appt.id,
        newDate: this.rescheduleDate,
        newStartTime: this.rescheduleStartTime,
        newEndTime: this.rescheduleEndTime,
        reason: this.rescheduleReason,
      })
      .subscribe({
        next: () => {
          this.closeReschedule();
          this.loadAppointments();
        },
        error: (err) => {
          this.rescheduleLoading.set(false);
          const msg =
            err?.error?.message ||
            err?.error?.error ||
            this.translate.instant('PORTAL.APPOINTMENTS.RESCHEDULE.FAILED');
          this.rescheduleError.set(msg);
        },
      });
  }

  // ── Booking form actions ─────────────────────────────────────────

  openBookingForm(): void {
    this.showBookingForm.set(true);
    this.bookingError.set(null);
    this.bookingSuccess.set(false);
    this.resetBookingForm();
    this.portal.getSchedulingHospitals().subscribe({
      next: (h) => this.hospitals.set(h),
    });
  }

  closeBookingForm(): void {
    this.showBookingForm.set(false);
    this.resetBookingForm();
  }

  onHospitalChange(): void {
    this.selectedDepartmentId = '';
    this.selectedProviderId = '';
    this.departments.set([]);
    this.providers.set([]);
    if (this.selectedHospitalId) {
      this.portal.getSchedulingDepartments(this.selectedHospitalId).subscribe({
        next: (d) => this.departments.set(d),
      });
    }
  }

  onDepartmentChange(): void {
    this.selectedProviderId = '';
    this.providers.set([]);
    if (this.selectedHospitalId && this.selectedDepartmentId) {
      this.portal
        .getSchedulingProviders(this.selectedHospitalId, this.selectedDepartmentId)
        .subscribe({
          next: (p) => this.providers.set(p),
        });
    }
  }

  submitBooking(): void {
    if (
      !this.selectedHospitalId ||
      !this.selectedDepartmentId ||
      !this.selectedDate ||
      !this.selectedTime
    ) {
      this.bookingError.set(this.translate.instant('PORTAL.APPOINTMENTS.BOOKING.MISSING_FIELDS'));
      return;
    }

    this.bookingLoading.set(true);
    this.bookingError.set(null);

    const dto: BookAppointmentRequest = {
      hospitalId: this.selectedHospitalId,
      departmentId: this.selectedDepartmentId,
      staffId: this.selectedProviderId || undefined,
      date: this.selectedDate,
      startTime: this.selectedTime,
      reason: this.appointmentReason || undefined,
      notes: this.appointmentNotes || undefined,
    };

    this.portal.bookAppointment(dto).subscribe({
      next: () => {
        this.bookingLoading.set(false);
        this.bookingSuccess.set(true);
        this.loadAppointments();
        setTimeout(() => this.closeBookingForm(), 2000);
      },
      error: (err) => {
        this.bookingLoading.set(false);
        const msg =
          err?.error?.message ||
          err?.error?.error ||
          this.translate.instant('PORTAL.APPOINTMENTS.BOOKING.FAILED');
        this.bookingError.set(msg);
      },
    });
  }

  private resetBookingForm(): void {
    this.selectedHospitalId = '';
    this.selectedDepartmentId = '';
    this.selectedProviderId = '';
    this.selectedDate = '';
    this.selectedTime = '';
    this.appointmentReason = '';
    this.appointmentNotes = '';
    this.departments.set([]);
    this.providers.set([]);
    this.bookingError.set(null);
    this.bookingSuccess.set(false);
  }

  get todayDate(): string {
    return localDateString();
  }
}
