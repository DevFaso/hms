import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  ConsultationService,
  ConsultationResponse,
  ConsultationRequest,
  ConsultationType,
  ConsultationUrgency,
} from '../services/consultation.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-consultations',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './consultations.html',
  styleUrl: './consultations.scss',
})
export class ConsultationsComponent implements OnInit {
  private readonly consultService = inject(ConsultationService);
  private readonly hospitalService = inject(HospitalService);
  private readonly toast = inject(ToastService);

  consultations = signal<ConsultationResponse[]>([]);
  filtered = signal<ConsultationResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'pending' | 'active' | 'completed'>('all');
  selectedConsult = signal<ConsultationResponse | null>(null);

  hospitals = signal<HospitalResponse[]>([]);

  /* ── CRUD signals ── */
  showModal = signal(false);
  saving = signal(false);
  form: ConsultationRequest = this.emptyForm();

  showDeleteConfirm = signal(false);
  deletingItem = signal<ConsultationResponse | null>(null);
  deleting = signal(false);

  consultationTypes: ConsultationType[] = ['FORMAL', 'CURBSIDE', 'TRANSFER_OF_CARE', 'SECOND_OPINION'];
  urgencies: ConsultationUrgency[] = ['ROUTINE', 'URGENT', 'EMERGENT', 'STAT'];

  ngOnInit(): void {
    this.load();
    this.hospitalService.list().subscribe((h) => this.hospitals.set(h ?? []));
  }

  emptyForm(): ConsultationRequest {
    return {
      patientId: '',
      hospitalId: '',
      consultationType: 'FORMAL' as ConsultationType,
      specialtyRequested: '',
      reasonForConsult: '',
      urgency: 'ROUTINE' as ConsultationUrgency,
    };
  }

  openCreate(): void {
    this.form = this.emptyForm();
    this.showModal.set(true);
  }

  closeModal(): void {
    this.showModal.set(false);
  }

  submitForm(): void {
    this.saving.set(true);
    this.consultService.create(this.form).subscribe({
      next: () => {
        this.toast.success('Consultation created');
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

  confirmCancel(c: ConsultationResponse): void {
    this.deletingItem.set(c);
    this.showDeleteConfirm.set(true);
  }
  cancelDeleteAction(): void {
    this.showDeleteConfirm.set(false);
    this.deletingItem.set(null);
  }
  executeCancel(): void {
    this.deleting.set(true);
    this.consultService.cancel(this.deletingItem()!.id, 'Cancelled by admin').subscribe({
      next: () => {
        this.toast.success('Consultation cancelled');
        this.cancelDeleteAction();
        this.deleting.set(false);
        this.load();
      },
      error: () => {
        this.toast.error('Cancel failed');
        this.deleting.set(false);
      },
    });
  }

  load(): void {
    this.loading.set(true);
    this.consultService.getAll().subscribe({
      next: (list) => {
        this.consultations.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load consultations');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'pending' | 'active' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.consultations();
    const tab = this.activeTab();
    if (tab === 'pending') list = list.filter((c) => ['REQUESTED'].includes(c.status));
    else if (tab === 'active')
      list = list.filter((c) => ['ACKNOWLEDGED', 'SCHEDULED', 'IN_PROGRESS'].includes(c.status));
    else if (tab === 'completed')
      list = list.filter((c) => ['COMPLETED', 'CANCELLED'].includes(c.status));
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (c) =>
          (c.patientName ?? '').toLowerCase().includes(term) ||
          (c.specialtyRequested ?? '').toLowerCase().includes(term) ||
          (c.consultantName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(c: ConsultationResponse): void {
    this.selectedConsult.set(c);
  }
  closeDetail(): void {
    this.selectedConsult.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'REQUESTED':
        return 'status-requested';
      case 'ACKNOWLEDGED':
      case 'SCHEDULED':
        return 'status-acknowledged';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'COMPLETED':
        return 'status-completed';
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getUrgencyClass(urgency: string): string {
    switch (urgency) {
      case 'STAT':
      case 'EMERGENT':
        return 'urgency-stat';
      case 'URGENT':
        return 'urgency-urgent';
      case 'ROUTINE':
        return 'urgency-routine';
      default:
        return '';
    }
  }

  countByGroup(group: string): number {
    if (group === 'pending')
      return this.consultations().filter((c) => c.status === 'REQUESTED').length;
    if (group === 'active')
      return this.consultations().filter((c) =>
        ['ACKNOWLEDGED', 'SCHEDULED', 'IN_PROGRESS'].includes(c.status),
      ).length;
    if (group === 'completed')
      return this.consultations().filter((c) => c.status === 'COMPLETED').length;
    return 0;
  }
}
