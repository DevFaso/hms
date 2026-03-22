import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, PortalConsultation } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-consultations',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-consultations.html',
  styleUrl: './my-consultations.scss',
})
export class MyConsultationsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  consultations = signal<PortalConsultation[]>([]);

  ngOnInit(): void {
    this.portal.getMyConsultations().subscribe({
      next: (data) => {
        this.consultations.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'status-completed';
    if (s === 'IN_PROGRESS' || s === 'ACKNOWLEDGED' || s === 'ASSIGNED') return 'status-active';
    if (s === 'CANCELLED' || s === 'DECLINED') return 'status-inactive';
    return 'status-pending';
  }

  urgencyClass(urgency: string): string {
    const u = urgency?.toUpperCase();
    if (u === 'STAT' || u === 'URGENT') return 'urgency-urgent';
    return 'urgency-routine';
  }
}
