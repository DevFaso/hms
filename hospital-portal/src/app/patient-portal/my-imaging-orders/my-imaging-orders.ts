import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, ImagingOrderDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-imaging-orders',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-imaging-orders.html',
  styleUrl: './my-imaging-orders.scss',
})
export class MyImagingOrdersComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  orders = signal<ImagingOrderDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyImagingOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED' || s === 'REPORTED') return 'green';
    if (s === 'CANCELLED') return 'red';
    if (s === 'SCHEDULED' || s === 'IN_PROGRESS') return 'amber';
    return '';
  }

  priorityColorClass(priority: string): string {
    const p = priority?.toUpperCase();
    if (p === 'STAT' || p === 'URGENT') return 'red';
    return '';
  }
}
