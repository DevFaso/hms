import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, AfterVisitSummary } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';
import { ServerTimePipe } from '../../shared/pipes/server-time.pipe';
import { AfterVisitSummary as VisitDetail } from '../../services/encounter.service';
import {
  VisitSummarySectionsComponent,
  hasDocumentSections,
} from '../../encounters/visit-summary-sections/visit-summary-sections.component';

@Component({
  selector: 'app-my-summaries',
  standalone: true,
  imports: [
    ServerTimePipe,
    CommonModule,
    DatePipe,
    TranslateModule,
    EnumLabelPipe,
    VisitSummarySectionsComponent,
  ],
  templateUrl: './my-summaries.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
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

  /**
   * User decision 2026-11-12: the visit's content sections, read once per
   * opened summary from the patient's own visit. A summary with no visit, or a
   * visit the server will not summarise, keeps the card as it was.
   */
  details = signal<Record<string, VisitDetail | null>>({});

  toggle(id: string): void {
    const opening = this.expandedId() !== id;
    this.expandedId.set(opening ? id : null);
    if (opening) {
      this.loadDetail(this.summaries().find((s) => s.id === id));
    }
  }

  private loadDetail(summary: AfterVisitSummary | undefined): void {
    const encounterId = summary?.encounterId;
    if (!summary || !encounterId || summary.id in this.details()) return;
    this.details.update((d) => ({ ...d, [summary.id]: null }));
    this.portal.getVisitSummaryDetail(encounterId).subscribe({
      next: (detail) => this.details.update((d) => ({ ...d, [summary.id]: detail })),
      error: () =>
        this.details.update((d) => {
          const retryable = { ...d };
          delete retryable[summary.id];
          return retryable;
        }),
    });
  }

  detailOf(id: string): VisitDetail | null {
    const detail = this.details()[id] ?? null;
    return hasDocumentSections(detail) ? detail : null;
  }

  /**
   * Polish 2026-11-13: the visit's document already shows this part (the same
   * stored text), so the card does not repeat it underneath.
   */
  shownInDetail(
    id: string,
    field:
      | 'dischargeDiagnoses'
      | 'prescriptionSummary'
      | 'followUpInstructions'
      | 'patientEducationMaterials'
      | 'followUpAppointmentDate',
  ): boolean {
    const detail = this.detailOf(id);
    if (!detail) return false;
    const value = detail[field];
    return Array.isArray(value) ? value.length > 0 : !!value;
  }

  /** Remove the complete plan suffix appended by CheckOutMapper once it has its own section. */
  followUpText(s: AfterVisitSummary): string {
    const plan = this.details()[s.id]?.clinicalPlan?.trim();
    if (!plan || !s.followUpInstructions) return s.followUpInstructions;
    const text = s.followUpInstructions.trim();
    if (text === plan) return '';
    const suffix = '\n\n' + plan;
    return text.endsWith(suffix) ? text.slice(0, -suffix.length).trim() : s.followUpInstructions;
  }

  printSummary(event: Event): void {
    event.stopPropagation();
    window.print();
  }
}
