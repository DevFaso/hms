import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AppointmentBookingRequest,
  CancelAppointmentRequest,
  DepartmentMinimal,
  PatientPortalService,
  PortalAppointment,
  RescheduleAppointmentRequest,
  StaffMinimal,
} from '../../services/patient-portal.service';
import { ToastService } from '../../core/toast.service';

@Component({
  selector: 'app-my-appointments',
  standalone: true,
  imports: [CommonModule, DatePipe, TitleCasePipe, FormsModule],
  templateUrl: './my-appointments.html',
  styleUrl: './my-appointments.scss',
})
export class MyAppointmentsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  private readonly toast = inject(ToastService);

  appointments = signal<PortalAppointment[]>([]);
  loading = signal(true);
  upcoming = signal<PortalAppointment[]>([]);
  past = signal<PortalAppointment[]>([]);

  // Cancel modal state
  cancelTarget = signal<PortalAppointment | null>(null);
  cancelReason = signal('');
  cancelling = signal(false);

  // Reschedule modal state
  rescheduleTarget = signal<PortalAppointment | null>(null);
  rescheduleForm = signal<Omit<RescheduleAppointmentRequest, 'appointmentId'>>({
    newDate: '',
    newStartTime: '',
    newEndTime: '',
    reason: '',
  });
  rescheduling = signal(false);

  // Booking modal state
  showBookingForm = signal(false);
  booking = signal(false);
  departments = signal<DepartmentMinimal[]>([]);
  providers = signal<StaffMinimal[]>([]);
  loadingDepartments = signal(false);
  loadingProviders = signal(false);
  bookingForm = signal<AppointmentBookingRequest>(this.emptyBookingForm());

  ngOnInit(): void {
    this.portal.getMyAppointments().subscribe({
      next: (appts) => {
        this.appointments.set(appts);
        this.splitAppointments(appts);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  private splitAppointments(appts: PortalAppointment[]): void {
    const now = new Date();
    this.upcoming.set(appts.filter((a) => a.status !== 'CANCELLED' && new Date(a.date) >= now));
    this.past.set(appts.filter((a) => a.status === 'COMPLETED' || new Date(a.date) < now));
  }

  // ── Cancel ──────────────────────────────────────────────────────────────
  openCancelModal(appt: PortalAppointment): void {
    this.cancelTarget.set(appt);
    this.cancelReason.set('');
  }

  closeCancelModal(): void {
    this.cancelTarget.set(null);
  }

  confirmCancel(): void {
    const target = this.cancelTarget();
    if (!target || !this.cancelReason().trim()) return;
    this.cancelling.set(true);
    const dto: CancelAppointmentRequest = {
      appointmentId: target.id,
      reason: this.cancelReason(),
    };
    this.portal.cancelAppointment(dto).subscribe({
      next: () => {
        this.appointments.update((list) =>
          list.map((a) => (a.id === target.id ? { ...a, status: 'CANCELLED' } : a)),
        );
        this.splitAppointments(this.appointments());
        this.cancelTarget.set(null);
        this.cancelling.set(false);
        this.toast.success('Appointment cancelled');
      },
      error: () => {
        this.cancelling.set(false);
        this.toast.error('Failed to cancel appointment');
      },
    });
  }

  // ── Reschedule ───────────────────────────────────────────────────────────
  openRescheduleModal(appt: PortalAppointment): void {
    this.rescheduleTarget.set(appt);
    this.rescheduleForm.set({
      newDate: appt.date,
      newStartTime: appt.startTime,
      newEndTime: appt.endTime,
      reason: '',
    });
  }

  closeRescheduleModal(): void {
    this.rescheduleTarget.set(null);
  }

  updateRescheduleField<K extends keyof Omit<RescheduleAppointmentRequest, 'appointmentId'>>(
    field: K,
    value: string,
  ): void {
    this.rescheduleForm.update((f) => ({ ...f, [field]: value }));
  }

  confirmReschedule(): void {
    const target = this.rescheduleTarget();
    const form = this.rescheduleForm();
    if (!target || !form.newDate || !form.newStartTime) return;
    this.rescheduling.set(true);
    const dto: RescheduleAppointmentRequest = { appointmentId: target.id, ...form };
    this.portal.rescheduleAppointment(dto).subscribe({
      next: () => {
        this.appointments.update((list) =>
          list.map((a) =>
            a.id === target.id
              ? { ...a, date: form.newDate, startTime: form.newStartTime, endTime: form.newEndTime }
              : a,
          ),
        );
        this.splitAppointments(this.appointments());
        this.rescheduleTarget.set(null);
        this.rescheduling.set(false);
        this.toast.success('Appointment rescheduled');
      },
      error: () => {
        this.rescheduling.set(false);
        this.toast.error('Failed to reschedule appointment');
      },
    });
  }

  canCancel(appt: PortalAppointment): boolean {
    return ['SCHEDULED', 'CONFIRMED', 'PENDING'].includes(appt.status?.toUpperCase());
  }

  canReschedule(appt: PortalAppointment): boolean {
    return ['SCHEDULED', 'CONFIRMED', 'PENDING'].includes(appt.status?.toUpperCase());
  }

  // ── Booking ─────────────────────────────────────────────────────────────
  openBookingForm(): void {
    this.bookingForm.set(this.emptyBookingForm());
    this.providers.set([]);
    this.showBookingForm.set(true);
    if (!this.departments().length) {
      this.loadingDepartments.set(true);
      this.portal.getMyDepartments().subscribe({
        next: (d) => {
          this.departments.set(d);
          this.loadingDepartments.set(false);
        },
        error: () => this.loadingDepartments.set(false),
      });
    }
  }

  closeBookingForm(): void {
    this.showBookingForm.set(false);
  }

  updateBookingField<K extends keyof AppointmentBookingRequest>(
    field: K,
    value: AppointmentBookingRequest[K],
  ): void {
    this.bookingForm.update((f) => ({ ...f, [field]: value }));
    if (field === 'departmentId' && value) {
      this.loadProviders(value as string);
    }
  }

  private loadProviders(departmentId: string): void {
    this.loadingProviders.set(true);
    this.providers.set([]);
    this.bookingForm.update((f) => ({ ...f, staffId: undefined }));
    this.portal.getDepartmentProviders(departmentId).subscribe({
      next: (p) => {
        this.providers.set(p);
        this.loadingProviders.set(false);
      },
      error: () => this.loadingProviders.set(false),
    });
  }

  isBookingValid(): boolean {
    const f = this.bookingForm();
    return !!(f.departmentId && f.appointmentDate && f.startTime && f.endTime && f.reason);
  }

  confirmBooking(): void {
    if (!this.isBookingValid() || this.booking()) return;
    this.booking.set(true);
    this.portal.bookAppointment(this.bookingForm()).subscribe({
      next: (saved) => {
        const newAppt: PortalAppointment = {
          id: saved.id,
          date: saved.appointmentDate,
          startTime: saved.startTime,
          endTime: saved.endTime,
          providerName: saved.staffName || '',
          department: saved.departmentName || '',
          reason: this.bookingForm().reason,
          status: saved.status || 'PENDING',
          location: saved.hospitalName || '',
        };
        this.appointments.update((list) => [newAppt, ...list]);
        this.splitAppointments(this.appointments());
        this.toast.success('Appointment request submitted');
        this.booking.set(false);
        this.closeBookingForm();
      },
      error: () => {
        this.toast.error('Failed to request appointment. Please try again.');
        this.booking.set(false);
      },
    });
  }

  private emptyBookingForm(): AppointmentBookingRequest {
    return {
      departmentId: '',
      staffId: undefined,
      appointmentDate: '',
      startTime: '',
      endTime: '',
      reason: '',
      notes: '',
    };
  }
}
