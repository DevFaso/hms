import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, LabResultSummary } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-lab-results',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-lab-results.component.html',
  styleUrls: ['./my-lab-results.component.scss', '../patient-portal-pages.scss'],
})
export class MyLabResultsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  results = signal<LabResultSummary[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  ngOnInit() {
    this.portal.getMyLabResults().subscribe({
      next: (r) => {
        this.results.set(r);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
  }
}
