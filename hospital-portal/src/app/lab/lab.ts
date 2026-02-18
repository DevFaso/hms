import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LabService, LabOrderResponse } from '../services/lab.service';
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
  private readonly toast = inject(ToastService);

  orders = signal<LabOrderResponse[]>([]);
  filtered = signal<LabOrderResponse[]>([]);
  searchTerm = '';
  loading = signal(true);
  selectedOrder = signal<LabOrderResponse | null>(null);

  activeTab = signal<'all' | 'pending' | 'completed'>('all');

  stats = signal({ total: 0, pending: 0, completed: 0, cancelled: 0 });

  ngOnInit(): void {
    this.loadOrders();
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
