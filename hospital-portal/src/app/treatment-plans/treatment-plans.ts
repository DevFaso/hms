import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { TreatmentPlanService, TreatmentPlanResponse } from '../services/treatment-plan.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-treatment-plans',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './treatment-plans.html',
  styleUrl: './treatment-plans.scss',
})
export class TreatmentPlansComponent implements OnInit {
  private readonly tpService = inject(TreatmentPlanService);
  private readonly toast = inject(ToastService);

  plans = signal<TreatmentPlanResponse[]>([]);
  filtered = signal<TreatmentPlanResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'active' | 'draft' | 'completed'>('all');
  selectedPlan = signal<TreatmentPlanResponse | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.tpService.getAll({ size: 200 }).subscribe({
      next: (list) => {
        this.plans.set(list ?? []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load treatment plans');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'active' | 'draft' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.plans();
    const tab = this.activeTab();
    if (tab === 'active') list = list.filter((p) => ['ACTIVE', 'APPROVED'].includes(p.status));
    else if (tab === 'draft')
      list = list.filter((p) => ['DRAFT', 'PENDING_REVIEW'].includes(p.status));
    else if (tab === 'completed') list = list.filter((p) => p.status === 'COMPLETED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (p) =>
          (p.patientName ?? '').toLowerCase().includes(term) ||
          (p.problemStatement ?? '').toLowerCase().includes(term) ||
          (p.authorStaffName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(p: TreatmentPlanResponse): void {
    this.selectedPlan.set(p);
  }
  closeDetail(): void {
    this.selectedPlan.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'status-draft';
      case 'PENDING_REVIEW':
        return 'status-pending';
      case 'APPROVED':
      case 'ACTIVE':
        return 'status-active';
      case 'COMPLETED':
        return 'status-completed';
      case 'REJECTED':
        return 'status-rejected';
      default:
        return '';
    }
  }

  countByGroup(group: string): number {
    if (group === 'active')
      return this.plans().filter((p) => ['ACTIVE', 'APPROVED'].includes(p.status)).length;
    if (group === 'draft')
      return this.plans().filter((p) => ['DRAFT', 'PENDING_REVIEW'].includes(p.status)).length;
    if (group === 'completed') return this.plans().filter((p) => p.status === 'COMPLETED').length;
    return 0;
  }
}
