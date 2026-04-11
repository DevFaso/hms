import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TranslateModule } from '@ngx-translate/core';
import {
  PatientPortalService,
  PortalAppointment,
  BookAppointmentRequest,
  SchedulingHospital,
  SchedulingDepartment,
  SchedulingProvider,
} from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { PreCheckinFormComponent } from './pre-checkin-form/pre-checkin-form.component';

@Component({
  selector: 'app-my-appointments',
  standalone: true,
  imports: [
    CommonModule,
    DatePipe,
    EnumLabelPipe,
    TranslateModule,
    FormsModule,
    PreCheckinFormComponent,
  ],
  templateUrl: './my-appointments.component.html',
  styleUrls: ['./my-appointments.component.scss', '../patient-portal-pages.scss'],
})
export class MyAppointmentsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  appointments = signal<PortalAppointment[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  upcoming = signal<PortalAppointment[]>([]);
  past = signal<PortalAppointment[]>([]);

  // ── Booking form state ───────────────────────────────────────────
  showBookingForm = signal(false);
  bookingLoading = signal(false);
  bookingError = signal<string | null>(null);
  bookingSuccess = signal(false);

  // ── Pre-check-in state ──────────────────────────────────────────
  showPreCheckIn = signal(false);
  preCheckInAppointment = signal<PortalAppointment | null>(null);

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

  private loadAppointments(): void {
    this.loading.set(true);
    this.portal.getMyAppointments().subscribe({
      next: (appts) => {
        this.appointments.set(appts);
        const now = new Date();
        this.upcoming.set(appts.filter((a) => a.status !== 'CANCELLED' && new Date(a.date) >= now));
        this.past.set(appts.filter((a) => a.status === 'COMPLETED' || new Date(a.date) < now));
        this.loading.set(false);
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
      this.bookingError.set('Please fill in all required fields.');
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
          'Failed to schedule appointment. Please try again.';
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
    return new Date().toISOString().split('T')[0];
  }
}
