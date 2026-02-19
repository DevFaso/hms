import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  AdmissionService,
  AdmissionResponse,
  AdmissionRequest,
  AdmissionType,
  AcuityLevel,
} from '../services/admission.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-admissions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './admissions.html',
  styleUrl: './admissions.scss',
})
export class AdmissionsComponent implements OnInit {
  private readonly admissionService = inject(AdmissionService);
  private readonly hospitalService = inject(HospitalService);
  private readonly staffService = inject(StaffService);
  private readonly toast = inject(ToastService);

  admissions = signal<AdmissionResponse[]>([]);
  filtered = signal<AdmissionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'admitted' | 'discharged'>('all');

  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<StaffResponse[]>([]);

  /* ── CRUD signals ── */
  showModal = signal(false);
  editing = signal(false);
  saving = signal(false);
  editingId = signal<string | null>(null);
  form: AdmissionRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingAdm = signal<AdmissionResponse | null>(null);
  deleting = signal(false);

  admissionTypes: AdmissionType[] = ['EMERGENCY', 'ELECTIVE', 'URGENT', 'TRANSFER', 'OBSERVATION'];
  acuityLevels: AcuityLevel[] = ['CRITICAL', 'HIGH', 'MODERATE', 'LOW', 'MINIMAL'];

  ngOnInit(): void {
    this.load();
    this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    this.staffService.list().subscribe((s) => this.staffMembers.set(s ?? []));
  }

  emptyForm(): AdmissionRequest {
    return {
      patientId: '',
      hospitalId: '',
      admittingProviderId: '',
      admissionType: 'ELECTIVE',
      acuityLevel: 'MODERATE',
      admissionDateTime: '',
      chiefComplaint: '',
    };
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.showModal.set(true);
  }

  openEdit(a: AdmissionResponse): void {
    this.form = {
      patientId: a.patientId ?? '',
      hospitalId: a.hospitalId ?? '',
      admittingProviderId: a.admittingProviderId ?? '',
      departmentId: a.departmentId,
      roomBed: a.roomBed,
      admissionType: a.admissionType ?? 'ELECTIVE',
      acuityLevel: a.acuityLevel ?? 'MODERATE',
      admissionDateTime: a.admissionDateTime?.substring(0, 10) ?? '',
      chiefComplaint: a.chiefComplaint ?? '',
      admissionNotes: a.admissionNotes,
    };
    this.editing.set(true);
    this.editingId.set(a.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.admissionService.update(this.editingId()!, this.form)
      : this.admissionService.create(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Admission updated' : 'Admission created');
        this.closeModal();
        this.saving.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Save failed');
        this.saving.set(false);
      },
    });
  }

  confirmDelete(a: AdmissionResponse): void {
    this.deletingAdm.set(a);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingAdm.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.admissionService.delete(this.deletingAdm()!.id).subscribe({
      next: () => {
        this.toast.success('Admission deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Delete failed');
        this.deleting.set(false);
      },
    });
  }

  load(): void {
    this.loading.set(true);
    this.admissionService.getAll().subscribe({
      next: (list) => {
        this.admissions.set(list ?? []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load admissions');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'admitted' | 'discharged'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.admissions();
    const tab = this.activeTab();
    if (tab === 'admitted') list = list.filter((a) => a.status === 'ADMITTED');
    else if (tab === 'discharged') list = list.filter((a) => a.status === 'DISCHARGED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (a) =>
          (a.patientName ?? '').toLowerCase().includes(term) ||
          (a.chiefComplaint ?? '').toLowerCase().includes(term) ||
          (a.roomBed ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ADMITTED':
        return 'status-admitted';
      case 'DISCHARGED':
        return 'status-discharged';
      case 'PENDING':
        return 'status-pending';
      case 'TRANSFERRED':
        return 'status-transferred';
      default:
        return '';
    }
  }

  getAcuityClass(level: string): string {
    switch (level) {
      case 'CRITICAL':
        return 'acuity-critical';
      case 'HIGH':
        return 'acuity-high';
      case 'MODERATE':
        return 'acuity-moderate';
      default:
        return 'acuity-low';
    }
  }

  countByStatus(status: string): number {
    return this.admissions().filter((a) => a.status === status).length;
  }
}
