import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ConsultationService, ConsultationResponse } from '../services/consultation.service';
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
  private readonly toast = inject(ToastService);

  consultations = signal<ConsultationResponse[]>([]);
  filtered = signal<ConsultationResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'pending' | 'active' | 'completed'>('all');
  selectedConsult = signal<ConsultationResponse | null>(null);

  ngOnInit(): void {
    this.load();
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
