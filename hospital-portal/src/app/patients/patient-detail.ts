import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { PatientService, PatientResponse } from '../services/patient.service';
import { VitalSignService, VitalSignResponse } from '../services/vital-sign.service';
import { EncounterService, EncounterResponse } from '../services/encounter.service';
import { AppointmentService, AppointmentResponse } from '../services/appointment.service';
import {
  RecordSharingService,
  RecordShareResult,
  ShareScope,
  ConsentGrantRequest,
} from '../services/record-sharing.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';
import { TranslateModule } from '@ngx-translate/core';
import { BpaPanelComponent } from './bpa-panel/bpa-panel.component';

type TabKey = 'overview' | 'medical' | 'vitals' | 'encounters' | 'appointments' | 'sharing';

@Component({
  selector: 'app-patient-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink, TranslateModule, BpaPanelComponent],
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
  private readonly sharingService = inject(RecordSharingService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);
  protected readonly permissions = inject(PermissionService);
  private readonly roleContext = inject(RoleContextService);

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

  /* Sharing tab */
  hospitals = signal<HospitalResponse[]>([]);
  hospitalsLoading = signal(false);
  selectedHospitalId = signal('');
  sharingResult = signal<RecordShareResult | null>(null);
  sharingLoading = signal(false);
  sharingError = signal('');
  /** Grant consent modal */
  showGrantModal = signal(false);
  grantFromHospitalId = signal('');
  grantToHospitalId = signal('');
  grantPurpose = signal('');
  grantExpiry = signal('');
  grantLoading = signal(false);

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
    const hospitalId = this.roleContext.activeHospitalId ?? undefined;
    this.patientService.getById(id, hospitalId).subscribe({
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

  /** Whether the current user can view clinical vitals */
  canViewVitals(): boolean {
    return this.permissions.hasPermission('Update Vital Signs');
  }

  /** Whether the current user can view clinical encounters */
  canViewEncounters(): boolean {
    return this.permissions.hasPermission('Create Encounters');
  }

  /** Whether the current user can view the Record Sharing tab */
  canViewSharing(): boolean {
    return this.permissions.hasAnyPermission('View Record Sharing', 'Manage Patient Consents', '*');
  }

  /** Whether the current user can grant or revoke patient consents */
  canManageConsents(): boolean {
    return this.permissions.hasPermission('Manage Patient Consents');
  }

  setTab(tab: TabKey): void {
    // Prevent navigating to the sharing tab when the user lacks permission.
    // Falls back to the overview tab so component state is never left on an
    // unauthorised panel, even when setTab() is called programmatically.
    if (tab === 'sharing' && !this.canViewSharing()) {
      this.activeTab.set('overview');
      return;
    }
    this.activeTab.set(tab);
    if (tab === 'vitals' && this.canViewVitals() && this.vitals().length === 0) this.loadVitals();
    if (tab === 'encounters' && this.canViewEncounters() && this.encounters().length === 0)
      this.loadEncounters();
    if (tab === 'appointments' && this.appointments().length === 0) this.loadAppointments();
    if (tab === 'sharing' && this.hospitals().length === 0) this.loadHospitals();
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

  private loadHospitals(): void {
    this.hospitalsLoading.set(true);
    // ── TENANT ISOLATION: only SUPER_ADMIN loads full hospital list ──
    if (this.roleContext.isSuperAdmin()) {
      this.hospitalService.list().subscribe({
        next: (h) => {
          this.hospitals.set(h);
          this.hospitalsLoading.set(false);
        },
        error: () => {
          this.toast.error('Failed to load hospitals');
          this.hospitalsLoading.set(false);
        },
      });
    } else {
      this.hospitalService.getMyHospitalAsResponse().subscribe({
        next: (h) => {
          this.hospitals.set([h]);
          this.hospitalsLoading.set(false);
        },
        error: () => {
          this.toast.error('Failed to load hospital');
          this.hospitalsLoading.set(false);
        },
      });
    }
  }

  // ── Sharing actions ──────────────────────────────────────────────────────

  requestRecords(): void {
    const hospitalId = this.selectedHospitalId();
    if (!hospitalId) {
      this.toast.error('Please select a hospital first.');
      return;
    }
    this.sharingLoading.set(true);
    this.sharingError.set('');
    this.sharingResult.set(null);
    this.sharingService.resolveAndShare(this.patientId, hospitalId).subscribe({
      next: (result) => {
        this.sharingResult.set(result);
        this.sharingLoading.set(false);
      },
      error: (err) => {
        const msg = err?.error?.message ?? 'Failed to resolve patient records.';
        this.sharingError.set(msg);
        this.sharingLoading.set(false);
      },
    });
  }

  openGrantModal(): void {
    this.grantPurpose.set('');
    this.grantExpiry.set('');
    this.showGrantModal.set(true);
  }

  closeGrantModal(): void {
    this.showGrantModal.set(false);
  }

  submitGrant(): void {
    const from = this.grantFromHospitalId();
    const to = this.grantToHospitalId();
    if (!from || !to) {
      this.toast.error('Please select both source and target hospitals.');
      return;
    }
    this.grantLoading.set(true);
    const req: ConsentGrantRequest = {
      patientId: this.patientId,
      fromHospitalId: from,
      toHospitalId: to,
      purpose: this.grantPurpose() || undefined,
      consentExpiration: this.grantExpiry() || undefined,
    };
    this.sharingService.grantConsent(req).subscribe({
      next: () => {
        this.toast.success('Consent granted successfully.');
        this.grantLoading.set(false);
        this.closeGrantModal();
      },
      error: (err) => {
        this.toast.error(err?.error?.message ?? 'Failed to grant consent.');
        this.grantLoading.set(false);
      },
    });
  }

  exportRecord(format: 'pdf' | 'csv'): void {
    const result = this.sharingResult();
    if (!result) return;
    this.sharingService
      .exportRecord(
        this.patientId,
        result.resolvedFromHospitalId,
        result.requestingHospitalId,
        format,
      )
      .subscribe({
        next: (blob) => {
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.href = url;
          a.download = `patient_record_${this.patientId}.${format}`;
          a.click();
          URL.revokeObjectURL(url);
        },
        error: () => this.toast.error(`Failed to export record as ${format.toUpperCase()}.`),
      });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  scopeBadgeClass(scope: ShareScope): string {
    switch (scope) {
      case 'SAME_HOSPITAL':
        return 'badge-scope same-hospital';
      case 'INTRA_ORG':
        return 'badge-scope intra-org';
      case 'CROSS_ORG':
        return 'badge-scope cross-org';
      default:
        return 'badge-scope';
    }
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
        return 'status-badge completed';
      case 'SCHEDULED':
      case 'ARRIVED':
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
