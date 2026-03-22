import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, LabResultSummary } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-lab-results',
  standalone: true,
  imports: [CommonModule, DatePipe],
  templateUrl: './my-lab-results.html',
  styleUrl: './my-lab-results.scss',
})
export class MyLabResultsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  results = signal<LabResultSummary[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyLabResults().subscribe({
      next: (r) => {
        this.results.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
