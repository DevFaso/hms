import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AppointmentService, AppointmentUpsertRequest } from '../services/appointment.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-appointment-form',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './appointment-form.html',
  styleUrl: './appointment-form.scss',
})
export class AppointmentFormComponent {
  private readonly appointmentService = inject(AppointmentService);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  saving = signal(false);

  form: AppointmentUpsertRequest = {
    appointmentDate: '',
    startTime: '',
    endTime: '',
    status: 'SCHEDULED',
    patientEmail: '',
    staffEmail: '',
    hospitalName: '',
    departmentId: '',
    reason: '',
    notes: '',
  };

  submit(): void {
    if (!this.form.appointmentDate || !this.form.startTime || !this.form.endTime) {
      this.toast.error('Date, start time, and end time are required');
      return;
    }
    if (!this.form.patientEmail && !this.form.patientId) {
      this.toast.error('Patient email or ID is required');
      return;
    }
    if (!this.form.staffEmail && !this.form.staffId) {
      this.toast.error('Staff email or ID is required');
      return;
    }

    this.saving.set(true);

    // Clean up empty strings
    const payload: AppointmentUpsertRequest = { ...this.form };
    for (const key of Object.keys(payload) as (keyof AppointmentUpsertRequest)[]) {
      if (payload[key] === '') {
        delete payload[key];
      }
    }

    this.appointmentService.create(payload).subscribe({
      next: () => {
        this.toast.success('Appointment created successfully');
        this.router.navigate(['/appointments']);
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to create appointment');
        this.saving.set(false);
      },
    });
  }
}
