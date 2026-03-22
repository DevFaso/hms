import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, LabResultTrendDTO } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-lab-trends',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-lab-trends.html',
  styleUrl: './my-lab-trends.scss',
})
export class MyLabTrendsComponent implements OnInit {
  private readonly svc = inject(PatientPortalService);

  readonly loading = signal(true);
  readonly error = signal(false);
  readonly trends = signal<LabResultTrendDTO[]>([]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(false);
    this.svc.getMyLabResultTrends().subscribe({
      next: (data) => {
        this.trends.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  statusClass(status: string, abnormal: boolean): string {
    const s = status?.toUpperCase() ?? '';
    if (s.includes('CRITICAL')) return 'status-critical';
    if (s.includes('PENDING')) return 'status-pending';
    if (abnormal || s.includes('ABNORMAL') || s.includes('HIGH') || s.includes('LOW')) {
      return 'status-abnormal';
    }
    return 'status-normal';
  }
}
