import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, PortalReferral } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-referrals',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-referrals.html',
  styleUrl: './my-referrals.scss',
})
export class MyReferralsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  referrals = signal<PortalReferral[]>([]);

  ngOnInit(): void {
    this.portal.getMyReferrals().subscribe({
      next: (data) => {
        this.referrals.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'status-completed';
    if (s === 'IN_PROGRESS' || s === 'ACKNOWLEDGED') return 'status-active';
    if (s === 'CANCELLED' || s === 'OVERDUE') return 'status-inactive';
    return 'status-pending';
  }

  urgencyClass(urgency: string): string {
    const u = urgency?.toUpperCase();
    if (u === 'STAT' || u === 'URGENT') return 'urgency-urgent';
    if (u === 'ROUTINE') return 'urgency-routine';
    return 'urgency-routine';
  }
}
