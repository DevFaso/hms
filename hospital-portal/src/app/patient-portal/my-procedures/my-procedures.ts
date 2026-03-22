import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, ProcedureOrderDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-procedures',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-procedures.html',
  styleUrl: './my-procedures.scss',
})
export class MyProceduresComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  orders = signal<ProcedureOrderDTO[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyProcedureOrders().subscribe({
      next: (data) => {
        this.orders.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'green';
    if (s === 'CANCELLED') return 'red';
    if (s === 'IN_PROGRESS' || s === 'READY_FOR_PROCEDURE' || s === 'SCHEDULED') return 'amber';
    return '';
  }

  urgencyColorClass(urgency: string): string {
    const u = urgency?.toUpperCase();
    if (u === 'STAT' || u === 'EMERGENT') return 'red';
    if (u === 'URGENT') return 'amber';
    return '';
  }
}
