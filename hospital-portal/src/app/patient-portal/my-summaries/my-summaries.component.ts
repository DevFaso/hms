import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, AfterVisitSummary } from '../../services/patient-portal.service';

@Component({
  selector: 'app-my-summaries',
  standalone: true,
  imports: [CommonModule, DatePipe, TranslateModule],
  templateUrl: './my-summaries.component.html',
  styleUrls: ['./my-summaries.component.scss', '../patient-portal-pages.scss'],
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
