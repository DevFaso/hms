import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { PatientService, PatientResponse } from '../services/patient.service';
import { PermissionService } from '../core/permission.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-patient-list',
  standalone: true,
  imports: [CommonModule, RouterLink, FormsModule],
  templateUrl: './patient-list.html',
  styleUrl: './patient-list.scss',
})
export class PatientListComponent implements OnInit {
  private readonly patientService = inject(PatientService);
  private readonly permissions = inject(PermissionService);
  private readonly toast = inject(ToastService);

  patients = signal<PatientResponse[]>([]);
  filtered = signal<PatientResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  canCreate = this.permissions.hasPermission('Register Patients');

  ngOnInit(): void {
    this.loadPatients();
  }

  loadPatients(): void {
    this.loading.set(true);
    this.patientService.list().subscribe({
      next: (data) => {
        this.patients.set(data);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load patients');
        this.loading.set(false);
      },
    });
  }

  applyFilter(): void {
    const term = this.searchTerm.toLowerCase().trim();
    if (!term) {
      this.filtered.set(this.patients());
      return;
    }
    this.filtered.set(
      this.patients().filter(
        (p) =>
          p.firstName.toLowerCase().includes(term) ||
          p.lastName.toLowerCase().includes(term) ||
          p.email.toLowerCase().includes(term) ||
          (p.mrn?.toLowerCase().includes(term) ?? false),
      ),
    );
  }
}
