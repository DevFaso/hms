import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PrescriptionService, PrescriptionResponse } from '../services/prescription.service';
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
  private readonly toast = inject(ToastService);

  prescriptions = signal<PrescriptionResponse[]>([]);
  filtered = signal<PrescriptionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'active' | 'completed' | 'cancelled'>('all');
  selectedPrescription = signal<PrescriptionResponse | null>(null);

  ngOnInit(): void {
    this.load();
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
