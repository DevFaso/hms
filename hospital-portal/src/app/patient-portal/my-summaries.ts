import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, AfterVisitSummary } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-summaries',
  standalone: true,
  imports: [CommonModule, DatePipe],
  styleUrl: './patient-portal-pages.scss',
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">summarize</span>
          After-Visit Summaries
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <span>Loading summaries…</span>
        </div>
      } @else if (!summaries().length) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">article</span>
          <h3>No Visit Summaries Yet</h3>
          <p>
            After your visits are complete, discharge instructions and summaries will appear here.
          </p>
        </div>
      } @else {
        <div class="avs-list">
          @for (s of summaries(); track s.id) {
            <div class="avs-card" [class.expanded]="expandedId() === s.id">
              <div
                class="avs-header"
                role="button"
                tabindex="0"
                (click)="toggle(s.id)"
                (keydown.enter)="toggle(s.id)"
              >
                <div class="avs-header-left">
                  <div class="avs-date-badge">
                    <span class="avs-month">{{ s.encounterDate | date: 'MMM' }}</span>
                    <span class="avs-day">{{ s.encounterDate | date: 'd' }}</span>
                  </div>
                  <div class="avs-header-info">
                    <span class="avs-provider">{{ s.providerName }}</span>
                    <span class="avs-dept">{{ s.department }} · {{ s.chiefComplaint }}</span>
                  </div>
                </div>
                <span class="material-symbols-outlined avs-expand-icon">
                  {{ expandedId() === s.id ? 'expand_less' : 'expand_more' }}
                </span>
              </div>

              @if (expandedId() === s.id) {
                <div class="avs-body">
                  @if (s.diagnoses?.length) {
                    <div class="avs-section">
                      <h4>Diagnoses</h4>
                      <ul>
                        @for (d of s.diagnoses; track d) {
                          <li>{{ d }}</li>
                        }
                      </ul>
                    </div>
                  }

                  @if (s.treatmentSummary) {
                    <div class="avs-section">
                      <h4>Treatment Summary</h4>
                      <p>{{ s.treatmentSummary }}</p>
                    </div>
                  }

                  @if (s.instructions) {
                    <div class="avs-section">
                      <h4>Instructions</h4>
                      <p>{{ s.instructions }}</p>
                    </div>
                  }

                  @if (s.medications?.length) {
                    <div class="avs-section">
                      <h4>Medications</h4>
                      <ul>
                        @for (m of s.medications; track m) {
                          <li>{{ m }}</li>
                        }
                      </ul>
                    </div>
                  }

                  @if (s.followUpDate) {
                    <div class="avs-section">
                      <h4>Follow-Up</h4>
                      <p>Scheduled for {{ s.followUpDate | date: 'mediumDate' }}</p>
                    </div>
                  }

                  <div class="avs-actions">
                    <button class="avs-print-btn" (click)="printSummary($event)">
                      <span class="material-symbols-outlined">print</span>
                      Print Summary
                    </button>
                  </div>
                </div>
              }
            </div>
          }
        </div>
      }
    </div>
  `,
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
