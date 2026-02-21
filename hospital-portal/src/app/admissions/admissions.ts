import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  AdmissionService,
  AdmissionResponse,
  AdmissionRequest,
  AdmissionType,
  AcuityLevel,
} from '../services/admission.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
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
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);

  admissions = signal<AdmissionResponse[]>([]);
  filtered = signal<AdmissionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'admitted' | 'discharged'>('all');

  hospitals = signal<HospitalResponse[]>([]);
  staffMembers = signal<StaffResponse[]>([]);
  private allStaff: StaffResponse[] = [];
  departments = signal<{ id: string; name: string }[]>([]);

  // Patient picker
  patientQuery = signal('');
  patientSuggestions = signal<PatientResponse[]>([]);
  patientDropdownOpen = signal(false);
  patientSearchLoading = signal(false);
  selectedPatient = signal<PatientResponse | null>(null);
  private readonly patientSearch$ = new Subject<string>();

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
    this.staffService.list().subscribe((s) => {
      this.allStaff = s ?? [];
      this.staffMembers.set(s ?? []);
    });
    this.initPatientSearch();
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

  // ── Patient picker ──
  initPatientSearch(): void {
    this.patientSearch$
      .pipe(
        debounceTime(220),
        distinctUntilChanged(),
        switchMap((q) => {
          this.patientSearchLoading.set(true);
          return this.patientService.list(undefined, q);
        }),
      )
      .subscribe({
        next: (list) => {
          this.patientSuggestions.set(list.slice(0, 8));
          this.patientDropdownOpen.set(list.length > 0);
          this.patientSearchLoading.set(false);
        },
        error: () => this.patientSearchLoading.set(false),
      });
  }

  onPatientQueryChange(q: string): void {
    this.patientQuery.set(q);
    if (q.length >= 2) this.patientSearch$.next(q);
    else {
      this.patientSuggestions.set([]);
      this.patientDropdownOpen.set(false);
    }
  }

  selectPatient(p: PatientResponse): void {
    this.selectedPatient.set(p);
    this.form.patientId = p.id;
    this.patientDropdownOpen.set(false);
    this.patientQuery.set('');
  }

  clearPatient(): void {
    this.selectedPatient.set(null);
    this.form.patientId = '';
    this.patientQuery.set('');
  }

  patientInitials(p: PatientResponse): string {
    return ((p.firstName?.[0] ?? '') + (p.lastName?.[0] ?? '')).toUpperCase() || '?';
  }

  // ── Department derivation ──
  onHospitalChange(hospitalId: string): void {
    this.form.hospitalId = hospitalId;
    this.form.departmentId = undefined;
    this.loadDepartmentsFor(hospitalId);
  }

  loadDepartmentsFor(hospitalId: string): void {
    if (!hospitalId) {
      this.departments.set([]);
      return;
    }
    const seen = new Set<string>();
    const depts = this.allStaff
      .filter((s) => s.hospitalId === hospitalId && s.departmentId)
      .reduce<{ id: string; name: string }[]>((acc, s) => {
        if (!seen.has(s.departmentId!)) {
          seen.add(s.departmentId!);
          acc.push({ id: s.departmentId!, name: s.departmentName || s.departmentId! });
        }
        return acc;
      }, []);
    this.departments.set(depts);
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.departments.set([]);
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
    this.selectedPatient.set({
      id: a.patientId ?? '',
      firstName: a.patientName?.split(' ')[0] ?? '',
      lastName: a.patientName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
    } as PatientResponse);
    this.loadDepartmentsFor(a.hospitalId ?? '');
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
