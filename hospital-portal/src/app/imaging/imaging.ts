import { Component, inject, OnInit, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ImagingService, ImagingOrderResponse } from '../services/imaging.service';
import { ToastService } from '../core/toast.service';

@Component({
  selector: 'app-imaging',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './imaging.html',
  styleUrl: './imaging.scss',
})
export class ImagingComponent implements OnInit {
  private readonly imagingService = inject(ImagingService);
  private readonly toast = inject(ToastService);

  orders = signal<ImagingOrderResponse[]>([]);
  filtered = signal<ImagingOrderResponse[]>([]);
  loading = signal(true);
  searchTerm = '';
  activeTab = signal<'all' | 'ordered' | 'completed' | 'cancelled'>('all');
  selectedOrder = signal<ImagingOrderResponse | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.imagingService.getAllOrders().subscribe({
      next: (list) => {
        this.orders.set(Array.isArray(list) ? list : []);
        this.applyFilter();
        this.loading.set(false);
      },
      error: () => {
        this.toast.error('Failed to load imaging orders');
        this.loading.set(false);
      },
    });
  }

  setTab(tab: 'all' | 'ordered' | 'completed' | 'cancelled'): void {
    this.activeTab.set(tab);
    this.applyFilter();
  }

  applyFilter(): void {
    let list = this.orders();
    const tab = this.activeTab();
    if (tab === 'ordered')
      list = list.filter((o) => ['ORDERED', 'SCHEDULED', 'IN_PROGRESS'].includes(o.status));
    else if (tab === 'completed')
      list = list.filter((o) => ['COMPLETED', 'PRELIMINARY', 'FINAL'].includes(o.status));
    else if (tab === 'cancelled') list = list.filter((o) => o.status === 'CANCELLED');
    const term = this.searchTerm.toLowerCase().trim();
    if (term) {
      list = list.filter(
        (o) =>
          (o.patientDisplayName ?? '').toLowerCase().includes(term) ||
          (o.studyType ?? '').toLowerCase().includes(term) ||
          (o.bodyRegion ?? '').toLowerCase().includes(term) ||
          (o.modality ?? '').toLowerCase().includes(term),
      );
    }
    this.filtered.set(list);
  }

  viewDetail(o: ImagingOrderResponse): void {
    this.selectedOrder.set(o);
  }
  closeDetail(): void {
    this.selectedOrder.set(null);
  }

  getStatusClass(status: string): string {
    switch (status) {
      case 'ORDERED':
      case 'SCHEDULED':
        return 'status-ordered';
      case 'IN_PROGRESS':
        return 'status-progress';
      case 'COMPLETED':
      case 'FINAL':
        return 'status-completed';
      case 'PRELIMINARY':
        return 'status-preliminary';
      case 'CANCELLED':
        return 'status-cancelled';
      default:
        return '';
    }
  }

  getPriorityClass(priority: string): string {
    switch (priority) {
      case 'STAT':
        return 'priority-stat';
      case 'URGENT':
      case 'ASAP':
        return 'priority-urgent';
      case 'ROUTINE':
        return 'priority-routine';
      default:
        return '';
    }
  }

  countByGroup(group: string): number {
    if (group === 'active')
      return this.orders().filter((o) => ['ORDERED', 'SCHEDULED', 'IN_PROGRESS'].includes(o.status))
        .length;
    if (group === 'completed')
      return this.orders().filter((o) => ['COMPLETED', 'PRELIMINARY', 'FINAL'].includes(o.status))
        .length;
    return 0;
  }
}
