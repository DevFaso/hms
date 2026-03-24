import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe, TitleCasePipe } from '@angular/common';
import { PatientPortalService, PortalEncounter } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-visits',
  standalone: true,
  imports: [CommonModule, DatePipe, TitleCasePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">history</span>
          Visit History
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading visit history...</p>
        </div>
      } @else if (encounters().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">event_note</span>
          <h3>No visit records</h3>
          <p>Your past visits and encounters will appear here.</p>
        </div>
      } @else {
        <section class="portal-section">
          <div class="portal-list">
            @for (enc of encounters(); track enc.id) {
              <div class="portal-list-item">
                <div class="pli-icon" style="background:#eef2ff;color:#4f46e5">
                  <span class="material-symbols-outlined">clinical_notes</span>
                </div>
                <div class="pli-body">
                  <span class="pli-title"
                    >{{ enc.type || 'Visit' }} · {{ enc.department || 'General' }}</span
                  >
                  <span class="pli-sub">{{ enc.providerName || 'Provider' }}</span>
                  <span class="pli-meta">
                    {{ enc.date | date: 'MMM d, yyyy' }}
                    @if (enc.chiefComplaint) {
                      · {{ enc.chiefComplaint }}
                    }
                  </span>
                </div>
                <span class="portal-status-chip" [attr.data-status]="enc.status">{{
                  enc.status | titlecase
                }}</span>
              </div>
            }
          </div>
        </section>
      }
    </div>
  `,
  styleUrl: './patient-portal-pages.scss',
})
export class MyVisitsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  encounters = signal<PortalEncounter[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyEncounters().subscribe({
      next: (enc) => {
        this.encounters.set(enc);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
