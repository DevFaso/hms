import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, AfterVisitSummary } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-summaries',
  standalone: true,
  imports: [CommonModule, DatePipe],
  styleUrl: './my-summaries.scss',
  templateUrl: './my-summaries.html',
})
export class MySummariesComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  loading = signal(true);
  summaries = signal<AfterVisitSummary[]>([]);
  expandedId = signal<string | null>(null);

  ngOnInit(): void {
    this.portal.getAfterVisitSummaries().subscribe({
      next: (s) => {
        this.summaries.set(s);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggle(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }

  printSummary(event: Event): void {
    event.stopPropagation();
    window.print();
  }
}
