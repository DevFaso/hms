import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatientPortalService, ImmunizationDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-upcoming-vaccines',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">vaccines</span>
          Upcoming Vaccinations
        </h1>
        <div class="header-controls">
          <label class="range-label">
            <span>Look ahead</span>
            <select [(ngModel)]="selectedMonths" (ngModelChange)="loadVaccinations()">
              <option [ngValue]="1">1 month</option>
              <option [ngValue]="3">3 months</option>
              <option [ngValue]="6">6 months</option>
              <option [ngValue]="12">12 months</option>
            </select>
          </label>
        </div>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading upcoming vaccinations...</p>
        </div>
      } @else if (vaccinations().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">vaccines</span>
          <h3>No upcoming vaccinations</h3>
          <p>You have no vaccinations scheduled in the next {{ selectedMonths }} month(s).</p>
        </div>
      } @else {
        <section class="portal-section">
          <p class="vax-count">
            {{ vaccinations().length }} vaccination(s) in the next {{ selectedMonths }} month(s)
          </p>
          <div class="vax-list">
            @for (v of vaccinations(); track v.id) {
              <div class="vax-card" [class.overdue]="v.overdue">
                <div class="vax-icon" [class.overdue-icon]="v.overdue">
                  <span class="material-symbols-outlined">vaccines</span>
                </div>
                <div class="vax-body">
                  <h4 class="vax-name">{{ v.vaccineDisplay || v.vaccineCode }}</h4>
                  @if (v.targetDisease) {
                    <p class="vax-disease">Protects against: {{ v.targetDisease }}</p>
                  }
                  <div class="vax-meta-row">
                    <span class="vax-badge" [class.overdue-badge]="v.overdue">
                      {{ v.overdue ? 'Overdue' : 'Due' }}:
                      {{ v.nextDoseDueDate | date: 'MMMM d, yyyy' }}
                    </span>
                    @if (v.doseNumber && v.totalDosesInSeries) {
                      <span class="dose-info"
                        >Dose {{ v.doseNumber }} of {{ v.totalDosesInSeries }}</span
                      >
                    }
                  </div>
                  @if (v.notes) {
                    <p class="vax-notes">{{ v.notes }}</p>
                  }
                </div>
              </div>
            }
          </div>
        </section>
      }
    </div>
  `,
  styles: [
    `
      .header-controls {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-top: 8px;
      }
      .range-label {
        display: flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
        color: #475569;
        font-weight: 500;
      }
      .range-label select {
        padding: 6px 10px;
        border: 1px solid #e2e8f0;
        border-radius: 8px;
        font-size: 14px;
        background: #fff;
        color: #1e293b;
        cursor: pointer;
      }
      .vax-count {
        font-size: 14px;
        color: #64748b;
        margin-bottom: 16px;
      }
      .vax-list {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .vax-card {
        display: flex;
        align-items: flex-start;
        gap: 14px;
        padding: 18px;
        background: #fff;
        border-radius: 14px;
        border: 1px solid #e2e8f0;
        transition: box-shadow 0.15s;
      }
      .vax-card:hover {
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
      }
      .vax-card.overdue {
        border-color: #fca5a5;
        background: #fff5f5;
      }
      .vax-icon {
        width: 44px;
        height: 44px;
        background: #dcfce7;
        color: #16a34a;
        border-radius: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .vax-icon.overdue-icon {
        background: #fee2e2;
        color: #dc2626;
      }
      .vax-body {
        flex: 1;
      }
      .vax-name {
        font-size: 16px;
        font-weight: 600;
        color: #1e293b;
        margin: 0 0 4px;
      }
      .vax-disease {
        font-size: 13px;
        color: #475569;
        margin: 0 0 8px;
      }
      .vax-meta-row {
        display: flex;
        align-items: center;
        gap: 12px;
        flex-wrap: wrap;
      }
      .vax-badge {
        font-size: 12px;
        font-weight: 600;
        padding: 3px 10px;
        border-radius: 20px;
        background: #dcfce7;
        color: #15803d;
      }
      .vax-badge.overdue-badge {
        background: #fee2e2;
        color: #dc2626;
      }
      .dose-info {
        font-size: 12px;
        color: #64748b;
        font-weight: 500;
      }
      .vax-notes {
        font-size: 13px;
        color: #64748b;
        margin: 8px 0 0;
        font-style: italic;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyUpcomingVaccinesComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  vaccinations = signal<ImmunizationDTO[]>([]);
  loading = signal(true);
  selectedMonths = 6;

  ngOnInit() {
    this.loadVaccinations();
  }

  loadVaccinations() {
    this.loading.set(true);
    this.portal.getUpcomingVaccinations(this.selectedMonths).subscribe({
      next: (v) => {
        this.vaccinations.set(v);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }
}
