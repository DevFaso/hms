import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AdmissionService, AdmissionResponse } from '../services/admission.service';
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
  private readonly toast = inject(ToastService);

  admissions = signal<AdmissionResponse[]>([]);
  filtered = signal<AdmissionResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'admitted' | 'discharged'>('all');

  ngOnInit(): void {
    this.load();
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
