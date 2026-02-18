import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-appointment-list',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule],
  templateUrl: './appointment-list.html',
  styleUrl: './appointment-list.scss',
})
export class AppointmentListComponent implements OnInit {
  private readonly appointmentService = inject(AppointmentService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);

  appointments = signal<AppointmentResponse[]>([]);
  filtered = signal<AppointmentResponse[]>([]);
  searchTerm = '';
  statusFilter = '';
  loading = signal(true);
  canCreate = this.permissions.hasPermission('Create Appointments');

  ngOnInit(): void {
    this.loadAppointments();
  }

  loadAppointments(): void {
    this.loading.set(true);
    this.appointmentService.list().subscribe({
      next: (data) => {
        this.appointments.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load appointments');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    let result = this.appointments();
    if (this.statusFilter) {
      result = result.filter((a) => a.status === this.statusFilter);
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      result = result.filter(
        (a) =>
          a.patientName.toLowerCase().includes(term) ||
          a.staffName.toLowerCase().includes(term) ||
          a.reason.toLowerCase().includes(term),
      );
    }
    this.filtered.set(result);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'SCHEDULED':
      case 'CONFIRMED':
        return 'status-badge scheduled';
      case 'COMPLETED':
        return 'status-badge completed';
      case 'CANCELLED':
      case 'NO_SHOW':
        return 'status-badge cancelled';
      case 'IN_PROGRESS':
        return 'status-badge in-progress';
      default:
        return 'status-badge';
    }
  }
}
