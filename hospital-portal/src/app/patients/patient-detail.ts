import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PatientService, PatientResponse } from '../services/patient.service';
import { VitalSignService, VitalSignResponse } from '../services/vital-sign.service';
import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import { ToastService } from '../core/toast.service';

type TabKey = 'overview' | 'medical' | 'vitals' | 'encounters' | 'appointments';

@Component({
  selector: 'app-patient-detail',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './patient-detail.html',
  styleUrl: './patient-detail.scss',
})
export class PatientDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly patientService = inject(PatientService);
  private readonly vitalService = inject(VitalSignService);
  private readonly encounterService = inject(EncounterService);
  private readonly appointmentService = inject(AppointmentService);
  private readonly toast = inject(ToastService);

  patient = signal<PatientResponse | null>(null);
  loading = signal(true);
  activeTab = signal<TabKey>('overview');

  /* Sub-tab data */
  vitals = signal<VitalSignResponse[]>([]);
  vitalsLoading = signal(false);
  encounters = signal<EncounterResponse[]>([]);
  encountersLoading = signal(false);
  appointments = signal<AppointmentResponse[]>([]);
  appointmentsLoading = signal(false);

  private patientId = '';

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (!id) {
      this.router.navigate(['/patients']);
      return;
    }
    this.patientId = id;
    this.loadPatient(id);
  }

  loadPatient(id: string): void {
    this.loading.set(true);
    this.patientService.getById(id).subscribe({
      next: (p) => {
        this.patient.set(p);
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Patient not found');
        this.loading.set(false);
        this.router.navigate(['/patients']);
      },
    });
  }

  setTab(tab: TabKey): void {
    this.activeTab.set(tab);
    if (tab === 'vitals' && this.vitals().length === 0) this.loadVitals();
    if (tab === 'encounters' && this.encounters().length === 0) this.loadEncounters();
    if (tab === 'appointments' && this.appointments().length === 0) this.loadAppointments();
  }

  private loadVitals(): void {
    this.vitalsLoading.set(true);
    this.vitalService.getRecent(this.patientId).subscribe({
      next: (v) => {
        this.vitals.set(v);
        this.vitalsLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load vitals');
        this.vitalsLoading.set(false);
      },
    });
  }

  private loadEncounters(): void {
    this.encountersLoading.set(true);
    this.encounterService.list({ patientId: this.patientId }).subscribe({
      next: (e) => {
        this.encounters.set(e);
        this.encountersLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load encounters');
        this.encountersLoading.set(false);
      },
    });
  }

  private loadAppointments(): void {
    this.appointmentsLoading.set(true);
    this.appointmentService.list({ patientId: this.patientId }).subscribe({
      next: (a) => {
        this.appointments.set(a);
        this.appointmentsLoading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load appointments');
        this.appointmentsLoading.set(false);
      },
    });
  }

  getInitials(p: PatientResponse): string {
    return `${p.firstName?.charAt(0) ?? ''}${p.lastName?.charAt(0) ?? ''}`.toUpperCase();
  }

  getAge(dob?: string): string {
    if (!dob) return '—';
    const birth = new Date(dob);
    const now = new Date();
    let age = now.getFullYear() - birth.getFullYear();
    if (
      now.getMonth() < birth.getMonth() ||
      (now.getMonth() === birth.getMonth() && now.getDate() < birth.getDate())
    ) {
      age--;
    }
    return `${age} years`;
  }

  getEncounterStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
      case 'DISCHARGED':
        return 'status-badge completed';
      case 'OPEN':
      case 'IN_PROGRESS':
        return 'status-badge scheduled';
      case 'CANCELLED':
        return 'status-badge cancelled';
      default:
        return 'status-badge';
    }
  }

  getApptStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
        return 'status-badge completed';
      case 'SCHEDULED':
      case 'CONFIRMED':
        return 'status-badge scheduled';
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
