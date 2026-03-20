import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, LabOrderDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-lab-orders',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-lab-orders.html',
  styleUrl: './my-lab-orders.scss',
})
export class MyLabOrdersComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  orders = signal<LabOrderDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyLabOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED' || s === 'RESULTED') return 'green';
    if (s === 'CANCELLED' || s === 'REJECTED') return 'red';
    if (s === 'IN_PROGRESS' || s === 'SPECIMEN_COLLECTED') return 'amber';
    return '';
  }

  priorityColorClass(priority: string): string {
    const p = priority?.toUpperCase();
    if (p === 'STAT' || p === 'URGENT') return 'red';
    if (p === 'ROUTINE') return 'green';
    return '';
  }
}
