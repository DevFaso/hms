import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import {
  PrescriptionService,
  PrescriptionResponse,
  PrescriptionRequest,
} from '../services/prescription.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-prescriptions',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './prescriptions.html',
  styleUrl: './prescriptions.scss',
})
export class PrescriptionsComponent implements OnInit {
  private readonly prescriptionService = inject(PrescriptionService);
  private readonly staffService = inject(StaffService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);

  prescriptions = signal<PrescriptionResponse[]>([]);
  filtered = signal<PrescriptionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'active' | 'completed' | 'cancelled'>('all');
  selectedPrescription = signal<PrescriptionResponse | null>(null);

  staffMembers = signal<StaffResponse[]>([]);

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
  form: PrescriptionRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingRx = signal<PrescriptionResponse | null>(null);
  deleting = signal(false);

  ngOnInit(): void {
    this.load();
    this.staffService.list().subscribe((s) => this.staffMembers.set(s ?? []));
    this.initPatientSearch();
  }

  emptyForm(): PrescriptionRequest {
    return {
      patientId: '',
      medicationName: '',
      dosage: '',
      frequency: '',
      duration: '',
      notes: '',
    };
  }

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

  openCreate(): void {
    this.form = this.emptyForm();
    this.editing.set(false);
    this.editingId.set(null);
    this.selectedPatient.set(null);
    this.patientQuery.set('');
    this.showModal.set(true);
  }

  openEdit(p: PrescriptionResponse): void {
    this.form = {
      patientId: p.patientId ?? '',
      staffId: p.staffId ?? '',
      encounterId: p.encounterId,
      medicationName: p.medicationName ?? '',
      dosage: p.dosage ?? '',
      frequency: p.frequency ?? '',
      duration: p.duration ?? '',
      notes: p.notes ?? '',
    };
    this.selectedPatient.set({
      id: p.patientId ?? '',
      firstName: p.patientFullName?.split(' ')[0] ?? '',
      lastName: p.patientFullName?.split(' ').slice(1).join(' ') ?? '',
      email: '',
    } as PatientResponse);
    this.editing.set(true);
    this.editingId.set(p.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.prescriptionService.update(this.editingId()!, this.form)
      : this.prescriptionService.create(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Prescription updated' : 'Prescription created');
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

  confirmDelete(p: PrescriptionResponse): void {
    this.deletingRx.set(p);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingRx.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.prescriptionService.delete(this.deletingRx()!.id).subscribe({
      next: () => {
        this.toast.success('Prescription deleted');
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
    this.prescriptionService.list().subscribe({
      next: (res) => {
        const list = Array.isArray(res) ? res : [];
        this.prescriptions.set(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load prescriptions');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'active' | 'completed' | 'cancelled'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.prescriptions();
    const tab = this.activeTab();
    if (tab === 'active') list = list.filter((p) => p.status === 'ACTIVE');
    else if (tab === 'completed') list = list.filter((p) => p.status === 'COMPLETED');
    else if (tab === 'cancelled') list = list.filter((p) => p.status === 'CANCELLED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (p) =>
          (p.patientFullName ?? '').toLowerCase().includes(term) ||
          (p.medicationName ?? '').toLowerCase().includes(term) ||
          (p.staffFullName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(p: PrescriptionResponse): void {
    this.selectedPrescription.set(p);
  }
  closeDetail(): void {
    this.selectedPrescription.set(null);
  }

  getStatusClass(status?: string): string {
    switch (status) {
      case 'ACTIVE':
        return 'status-active';
      case 'COMPLETED':
        return 'status-completed';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'SUSPENDED':
        return 'status-suspended';
      default:
        return '';
    }
  }

  countByStatus(status: string): number {
    return this.prescriptions().filter((p) => p.status === status).length;
  }
}
