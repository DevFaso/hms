import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ReferralService, ReferralResponse } from '../services/referral.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-referrals',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './referrals.html',
  styleUrl: './referrals.scss',
})
export class ReferralsComponent implements OnInit {
  private readonly referralService = inject(ReferralService);
  private readonly toast = inject(ToastService);

  referrals = signal<ReferralResponse[]>([]);
  filtered = signal<ReferralResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'pending' | 'active' | 'completed'>('all');
  selectedReferral = signal<ReferralResponse | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.referralService.getAll().subscribe({
      next: (list) => {
        this.referrals.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load referrals');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'pending' | 'active' | 'completed'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.referrals();
    const tab = this.activeTab();
    if (tab === 'pending') list = list.filter((r) => ['DRAFT', 'SUBMITTED'].includes(r.status));
    else if (tab === 'active')
      list = list.filter((r) => ['ACKNOWLEDGED', 'IN_PROGRESS'].includes(r.status));
    else if (tab === 'completed')
      list = list.filter((r) => ['COMPLETED', 'CANCELLED'].includes(r.status));
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (r) =>
          (r.patientName ?? '').toLowerCase().includes(term) ||
          (r.targetSpecialty ?? '').toLowerCase().includes(term) ||
          (r.receivingProviderName ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(r: ReferralResponse): void {
    this.selectedReferral.set(r);
  }
  closeDetail(): void {
    this.selectedReferral.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'DRAFT':
        return 'status-draft';
      case 'SUBMITTED':
        return 'status-submitted';
      case 'ACKNOWLEDGED':
      case 'IN_PROGRESS':
        return 'status-active';
      case 'COMPLETED':
        return 'status-completed';
      case 'CANCELLED':
        return 'status-cancelled';
      case 'OVERDUE':
        return 'status-overdue';
      default:
        return '';
    }
  }

  getUrgencyClass(urgency: string): string {
    switch (urgency?.toUpperCase()) {
      case 'STAT':
      case 'EMERGENT':
        return 'urgency-stat';
      case 'URGENT':
        return 'urgency-urgent';
      default:
        return 'urgency-routine';
    }
  }

  countByGroup(group: string): number {
    if (group === 'pending')
      return this.referrals().filter((r) => ['DRAFT', 'SUBMITTED'].includes(r.status)).length;
    if (group === 'active')
      return this.referrals().filter((r) => ['ACKNOWLEDGED', 'IN_PROGRESS'].includes(r.status))
        .length;
    if (group === 'completed')
      return this.referrals().filter((r) => r.status === 'COMPLETED').length;
    return 0;
  }
}
