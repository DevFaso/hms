import { refusalReason } from '../../shared/refusal-reason';
import { refuseFields } from '../../shared/forms/invalid-fields';
import {
  Component,
  OnInit,
  computed,
  inject,
  signal,
  ChangeDetectionStrategy,
  ElementRef,
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
  PortalOpenSlot,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { DialogA11yDirective } from '../../shared/a11y/dialog-a11y.directive';
import { localDateString } from '../../shared/date-utils';
import { currentLocale } from '../../shared/i18n/app-locale';
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
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
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
  /** QA D1060: the booking as the server stored it (its start and end, set by the slot rules). */
  bookedAppointment = signal<PortalAppointment | null>(null);

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
  rescheduleReason = '';
  /** QA D1093: the published, free slots the visit can move to on the chosen day. */
  rescheduleSlots = signal<PortalOpenSlot[]>([]);
  rescheduleSlotsLoaded = signal(false);

  /**
   * QA D1093: the published, free slots of the chosen day. The time is picked
   * from these, never typed: a free time input booked 10:10 across two slots,
   * and a Saturday or an afternoon with no session.
   */
  openSlots = signal<PortalOpenSlot[]>([]);
  slotsLoaded = signal(false);

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

  /** The opening day as the reader writes a date ("3 novembre 2026"); a local calendar day, not a server instant. */
  preCheckInOpensOnLabel(day: Date): string {
    return new Intl.DateTimeFormat(currentLocale(), { dateStyle: 'long' }).format(day);
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
          refusalReason(err) ?? this.translate.instant('PORTAL.APPOINTMENTS.CANCEL.FAILED');
        this.cancelError.set(msg);
      },
    });
  }

  openReschedule(appt: PortalAppointment): void {
    this.rescheduleTarget.set(appt);
    this.rescheduleDate = appt.date;
    this.rescheduleStartTime = appt.rawStartTime;
    this.rescheduleReason = '';
    this.rescheduleError.set(null);
    this.loadRescheduleSlots();
  }

  /** QA D1093: the slots of the visit's clinician on the chosen day. */
  loadRescheduleSlots(): void {
    const appt = this.rescheduleTarget();
    this.rescheduleSlots.set([]);
    this.rescheduleSlotsLoaded.set(false);
    if (!appt || !this.rescheduleDate) return;
    this.portal.getRescheduleSlots(appt.id, this.rescheduleDate).subscribe({
      next: (slots) => {
        this.rescheduleSlots.set(slots);
        this.rescheduleSlotsLoaded.set(true);
      },
      error: (err) => {
        this.rescheduleSlotsLoaded.set(true);
        this.rescheduleError.set(
          refusalReason(err) ?? this.translate.instant('PORTAL.APPOINTMENTS.SLOTS_FAILED'),
        );
      },
    });
  }

  onRescheduleDateChange(): void {
    this.rescheduleStartTime = '';
    this.loadRescheduleSlots();
  }

  closeReschedule(): void {
    this.rescheduleTarget.set(null);
    this.rescheduleLoading.set(false);
  }

  submitReschedule(): void {
    const appt = this.rescheduleTarget();
    if (!appt) return;
    // QA D1060: no end time is asked for or sent; the clinic's slot length sets it.
    if (!this.rescheduleDate || !this.rescheduleStartTime) {
      // QA D1072: the missing field is marked and focused.
      refuseFields(
        this.host.nativeElement,
        [
          !this.rescheduleDate ? 'reschedDate' : null,
          !this.rescheduleStartTime ? 'reschedStart' : null,
        ].filter((id): id is string => id !== null),
        ['reschedDate', 'reschedStart'],
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
      this.rescheduleError.set(
        this.translate.instant('PORTAL.APPOINTMENTS.RESCHEDULE.MISSING_FIELDS'),
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
            refusalReason(err) ?? this.translate.instant('PORTAL.APPOINTMENTS.RESCHEDULE.FAILED');
          this.rescheduleError.set(msg);
        },
      });
  }

  // ── Booking form actions ─────────────────────────────────────────

  openBookingForm(): void {
    // QA D1124: the opener stays on the page (QA D1146: focus returns to it);
    // a second open while the form is up would wipe what was typed.
    if (this.showBookingForm()) return;
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
    this.loadSlots();
    if (this.selectedHospitalId) {
      this.portal.getSchedulingDepartments(this.selectedHospitalId).subscribe({
        next: (d) => this.departments.set(d),
      });
    }
  }

  onDepartmentChange(): void {
    this.selectedProviderId = '';
    this.providers.set([]);
    this.loadSlots();
    if (this.selectedHospitalId && this.selectedDepartmentId) {
      this.portal
        .getSchedulingProviders(this.selectedHospitalId, this.selectedDepartmentId)
        .subscribe({
          next: (p) => this.providers.set(p),
        });
    }
  }

  /**
   * QA D1093: the free slots of the chosen day, once a hospital, a department
   * and a date are chosen (narrowed to the clinician when one is picked).
   * With "any clinician", one time is offered once: the server books the
   * first clinician whose slot starts then.
   */
  loadSlots(): void {
    this.selectedTime = '';
    this.openSlots.set([]);
    this.slotsLoaded.set(false);
    if (!this.selectedHospitalId || !this.selectedDepartmentId || !this.selectedDate) return;
    this.portal
      .getOpenSlots(
        this.selectedHospitalId,
        this.selectedDepartmentId,
        this.selectedDate,
        this.selectedProviderId || undefined,
      )
      .subscribe({
        next: (slots) => {
          const seen = new Set<string>();
          this.openSlots.set(
            slots.filter((s) => {
              if (seen.has(s.startTime)) return false;
              seen.add(s.startTime);
              return true;
            }),
          );
          this.slotsLoaded.set(true);
        },
        error: (err) => {
          this.slotsLoaded.set(true);
          this.bookingError.set(
            refusalReason(err) ?? this.translate.instant('PORTAL.APPOINTMENTS.SLOTS_FAILED'),
          );
        },
      });
  }

  submitBooking(): void {
    if (
      !this.selectedHospitalId ||
      !this.selectedDepartmentId ||
      !this.selectedDate ||
      !this.selectedTime
    ) {
      // QA D1072: the missing field is marked and focused.
      refuseFields(
        this.host.nativeElement,
        [
          !this.selectedHospitalId ? 'hospital' : null,
          !this.selectedDepartmentId ? 'department' : null,
          !this.selectedDate ? 'apptDate' : null,
          !this.selectedTime ? 'apptTime' : null,
        ].filter((id): id is string => id !== null),
        ['hospital', 'department', 'apptDate', 'apptTime'],
        this.translate.instant('COMMON.FIELD_REQUIRED'),
      );
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
      next: (booked) => {
        this.bookingLoading.set(false);
        this.bookedAppointment.set(booked ?? null);
        this.bookingSuccess.set(true);
        this.loadAppointments();
        setTimeout(() => this.closeBookingForm(), 2000);
      },
      error: (err) => {
        this.bookingLoading.set(false);
        const msg =
          refusalReason(err) ?? this.translate.instant('PORTAL.APPOINTMENTS.BOOKING.FAILED');
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
    this.openSlots.set([]);
    this.slotsLoaded.set(false);
    this.bookingError.set(null);
    this.bookingSuccess.set(false);
    this.bookedAppointment.set(null);
  }

  get todayDate(): string {
    return localDateString();
  }
}
