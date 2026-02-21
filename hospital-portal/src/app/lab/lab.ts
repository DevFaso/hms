import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { LabService, LabOrderResponse, LabOrderRequest } from '../services/lab.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-lab',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './lab.html',
  styleUrl: './lab.scss',
})
export class LabComponent implements OnInit {
  private readonly labService = inject(LabService);
  private readonly hospitalService = inject(HospitalService);
  private readonly patientService = inject(PatientService);
  private readonly toast = inject(ToastService);

  orders = signal<LabOrderResponse[]>([]);
  filtered = signal<LabOrderResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  selectedOrder = signal<LabOrderResponse | null>(null);

  activeTab = signal<'all' | 'pending' | 'completed'>('all');

  stats = signal({ total: 0, pending: 0, completed: 0, cancelled: 0 });

  hospitals = signal<HospitalResponse[]>([]);

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
  form: LabOrderRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingOrder = signal<LabOrderResponse | null>(null);
  deleting = signal(false);

  priorities = ['ROUTINE', 'URGENT', 'STAT', 'ASAP'];

  ngOnInit(): void {
    this.loadOrders();
    this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
    this.initPatientSearch();
  }

  emptyForm(): LabOrderRequest {
    return {
      patientId: '',
      hospitalId: '',
      testName: '',
      status: 'ORDERED',
      clinicalIndication: '',
      medicalNecessityNote: '',
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

  openEdit(o: LabOrderResponse): void {
    this.form = {
      patientId: '',
      hospitalId: '',
      testName: o.labTestName ?? '',
      testCode: o.labTestCode,
      status: o.status ?? 'ORDERED',
      clinicalIndication: o.clinicalIndication ?? '',
      medicalNecessityNote: o.medicalNecessityNote ?? '',
      notes: o.notes,
    };
    this.selectedPatient.set({
      id: '',
      firstName: o.patientFullName?.split(' ')[0] ?? '',
      lastName: o.patientFullName?.split(' ').slice(1).join(' ') ?? '',
      email: o.patientEmail ?? '',
    } as PatientResponse);
    this.editing.set(true);
    this.editingId.set(o.id);
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    const op = this.editing()
      ? this.labService.updateOrder(this.editingId()!, this.form)
      : this.labService.createOrder(this.form);
    op.subscribe({
      next: () => {
        this.toast.success(this.editing() ? 'Lab order updated' : 'Lab order created');
        this.closeModal();
        this.saving.set(false);
        this.loadOrders();
      },
      error: () => {
        this.toast.error('Save failed');
        this.saving.set(false);
      },
    });
  }

  confirmDelete(o: LabOrderResponse): void {
    this.deletingOrder.set(o);
    this.showDeleteConfirm.set(true);
  }
  cancelDelete(): void {
    this.showDeleteConfirm.set(false);
    this.deletingOrder.set(null);
  }
  executeDelete(): void {
    this.deleting.set(true);
    this.labService.deleteOrder(this.deletingOrder()!.id).subscribe({
      next: () => {
        this.toast.success('Lab order deleted');
        this.cancelDelete();
        this.deleting.set(false);
        this.loadOrders();
      },
      error: () => {
        this.toast.error('Delete failed');
        this.deleting.set(false);
      },
    });
  }

  loadOrders(): void {
    this.loading.set(true);
    this.labService.listOrders({ size: 200 }).subscribe({
      next: (list) => {
        this.orders.set(list);
        this.computeStats(list);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load lab orders');
        this.loading.set(false);
      },
    });
  }

  private computeStats(list: LabOrderResponse[]): void {
    this.stats.set({
      total: list.length,
      pending: list.filter(
        (o) => o.status === 'PENDING' || o.status === 'IN_PROGRESS' || o.status === 'ORDERED',
      ).length,
      completed: list.filter((o) => o.status === 'COMPLETED' || o.status === 'RESULTED').length,
      cancelled: list.filter((o) => o.status === 'CANCELLED').length,
    });
  }

  setTab(tab: 'all' | 'pending' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.orders();
    const tab = this.activeTab();
    if (tab === 'pending') {
      list = list.filter(
        (o) => o.status === 'PENDING' || o.status === 'IN_PROGRESS' || o.status === 'ORDERED',
      );
    } else if (tab === 'completed') {
      list = list.filter((o) => o.status === 'COMPLETED' || o.status === 'RESULTED');
    }
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (o) =>
          (o.labOrderCode ?? '').toLowerCase().includes(term) ||
          (o.patientFullName ?? '').toLowerCase().includes(term) ||
          (o.labTestName ?? '').toLowerCase().includes(term) ||
          (o.status ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewOrder(order: LabOrderResponse): void {
    this.selectedOrder.set(order);
  }

  closeDetail(): void {
    this.selectedOrder.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'COMPLETED':
      case 'RESULTED':
        return 'status-badge status-completed';
      case 'IN_PROGRESS':
        return 'status-badge status-in_progress';
      case 'COLLECTED':
        return 'status-badge status-collected';
      case 'PENDING':
      case 'ORDERED':
        return 'status-badge status-pending';
      case 'CANCELLED':
        return 'status-badge status-cancelled';
      default:
        return 'status-badge';
    }
  }
}
