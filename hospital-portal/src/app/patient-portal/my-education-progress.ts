import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, EducationProgressDTO } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-education-progress',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">school</span>
          My Education Progress
        </h1>
        <div class="tab-bar">
          <button class="tab-btn" [class.active]="filter() === 'all'" (click)="filter.set('all')">
            All ({{ all().length }})
          </button>
          <button
            class="tab-btn"
            [class.active]="filter() === 'in_progress'"
            (click)="filter.set('in_progress')"
          >
            In Progress ({{ inProgress().length }})
          </button>
          <button
            class="tab-btn"
            [class.active]="filter() === 'completed'"
            (click)="filter.set('completed')"
          >
            Completed ({{ completed().length }})
          </button>
        </div>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading education progress...</p>
        </div>
      } @else if (visible().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">school</span>
          <h3>No education records</h3>
          <p>
            @if (filter() === 'in_progress') {
              You have no education resources currently in progress.
            } @else if (filter() === 'completed') {
              You have not completed any education resources yet.
            } @else {
              You have not started any patient education resources yet.
            }
          </p>
        </div>
      } @else {
        <!-- Summary stats -->
        @if (filter() === 'all' && all().length > 0) {
          <div class="stats-row">
            <div class="stat-card">
              <span class="stat-value">{{ all().length }}</span>
              <span class="stat-label">Total</span>
            </div>
            <div class="stat-card in-progress">
              <span class="stat-value">{{ inProgress().length }}</span>
              <span class="stat-label">In Progress</span>
            </div>
            <div class="stat-card completed">
              <span class="stat-value">{{ completed().length }}</span>
              <span class="stat-label">Completed</span>
            </div>
          </div>
        }

        <section class="portal-section">
          <div class="progress-list">
            @for (p of visible(); track p.id) {
              <div class="progress-card">
                <div class="progress-icon" [ngClass]="statusColorClass(p.comprehensionStatus)">
                  <span class="material-symbols-outlined">{{
                    statusIcon(p.comprehensionStatus)
                  }}</span>
                </div>
                <div class="progress-body">
                  <div class="progress-header-row">
                    <span class="status-badge" [ngClass]="statusColorClass(p.comprehensionStatus)">
                      {{ comprehensionLabel(p.comprehensionStatus) }}
                    </span>
                    @if (p.confirmedUnderstanding) {
                      <span class="understood-chip">
                        <span class="material-symbols-outlined icon-sm">check_circle</span>
                        Confirmed Understanding
                      </span>
                    }
                    @if (p.needsClarification) {
                      <span class="clarification-chip">
                        <span class="material-symbols-outlined icon-sm">help</span>
                        Needs Clarification
                      </span>
                    }
                  </div>

                  @if (p.progressPercentage !== null) {
                    <div class="progress-bar-wrap">
                      <div class="progress-bar">
                        <div class="progress-fill" [style.width.%]="p.progressPercentage"></div>
                      </div>
                      <span class="progress-pct">{{ p.progressPercentage }}%</span>
                    </div>
                  }

                  <div class="meta-row">
                    @if (p.lastAccessedAt) {
                      <span class="meta-chip">
                        <span class="material-symbols-outlined icon-sm">schedule</span>
                        Last accessed {{ p.lastAccessedAt | date: 'MMM d, yyyy' }}
                      </span>
                    }
                    @if (p.completedAt) {
                      <span class="meta-chip completed-chip">
                        <span class="material-symbols-outlined icon-sm">task_alt</span>
                        Completed {{ p.completedAt | date: 'MMM d, yyyy' }}
                      </span>
                    }
                    @if (p.accessCount) {
                      <span class="meta-chip">{{ p.accessCount }}× viewed</span>
                    }
                    @if (p.timeSpentSeconds) {
                      <span class="meta-chip">{{ formatTime(p.timeSpentSeconds) }}</span>
                    }
                  </div>

                  @if (p.rating) {
                    <div class="rating-row">
                      @for (star of [1, 2, 3, 4, 5]; track star) {
                        <span class="star" [class.filled]="star <= p.rating">★</span>
                      }
                    </div>
                  }
                  @if (p.feedback) {
                    <p class="feedback-text">"{{ p.feedback }}"</p>
                  }
                  @if (p.providerNotes) {
                    <p class="provider-notes">Provider note: {{ p.providerNotes }}</p>
                  }
                  @if (p.clarificationRequest) {
                    <p class="clarification-text">
                      Clarification request: {{ p.clarificationRequest }}
                    </p>
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
      .tab-bar {
        display: flex;
        gap: 8px;
        margin-top: 12px;
        flex-wrap: wrap;
      }
      .tab-btn {
        padding: 6px 16px;
        border: 1.5px solid #e2e8f0;
        border-radius: 20px;
        background: #fff;
        color: #64748b;
        font-size: 13px;
        font-weight: 600;
        cursor: pointer;
        transition: all 0.15s;
      }
      .tab-btn:hover {
        background: #f1f5f9;
      }
      .tab-btn.active {
        background: #2563eb;
        color: #fff;
        border-color: #2563eb;
      }
      .stats-row {
        display: flex;
        gap: 12px;
        margin-bottom: 20px;
        flex-wrap: wrap;
      }
      .stat-card {
        flex: 1;
        min-width: 90px;
        padding: 14px;
        background: #fff;
        border-radius: 12px;
        border: 1px solid #e2e8f0;
        text-align: center;
      }
      .stat-card.in-progress {
        border-color: #fcd34d;
        background: #fefce8;
      }
      .stat-card.completed {
        border-color: #6ee7b7;
        background: #f0fdf4;
      }
      .stat-value {
        display: block;
        font-size: 24px;
        font-weight: 700;
        color: #1e293b;
      }
      .stat-label {
        display: block;
        font-size: 12px;
        color: #64748b;
        margin-top: 2px;
      }
      .progress-list {
        display: flex;
        flex-direction: column;
        gap: 14px;
      }
      .progress-card {
        display: flex;
        align-items: flex-start;
        gap: 14px;
        padding: 18px;
        background: #fff;
        border-radius: 14px;
        border: 1px solid #e2e8f0;
        transition: box-shadow 0.15s;
      }
      .progress-card:hover {
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.06);
      }
      .progress-icon {
        width: 44px;
        height: 44px;
        border-radius: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        background: #f1f5f9;
        color: #64748b;
      }
      .progress-icon.completed {
        background: #dcfce7;
        color: #16a34a;
      }
      .progress-icon.in-progress {
        background: #fef9c3;
        color: #ca8a04;
      }
      .progress-body {
        flex: 1;
      }
      .progress-header-row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        margin-bottom: 10px;
      }
      .status-badge {
        font-size: 12px;
        font-weight: 700;
        padding: 3px 10px;
        border-radius: 20px;
        background: #f1f5f9;
        color: #475569;
      }
      .status-badge.completed {
        background: #dcfce7;
        color: #15803d;
      }
      .status-badge.in-progress {
        background: #fef9c3;
        color: #a16207;
      }
      .understood-chip {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        font-weight: 600;
        padding: 3px 10px;
        border-radius: 20px;
        background: #dcfce7;
        color: #15803d;
      }
      .clarification-chip {
        display: flex;
        align-items: center;
        gap: 4px;
        font-size: 12px;
        font-weight: 600;
        padding: 3px 10px;
        border-radius: 20px;
        background: #fef9c3;
        color: #a16207;
      }
      .progress-bar-wrap {
        display: flex;
        align-items: center;
        gap: 10px;
        margin-bottom: 10px;
      }
      .progress-bar {
        flex: 1;
        height: 8px;
        background: #e2e8f0;
        border-radius: 4px;
        overflow: hidden;
      }
      .progress-fill {
        height: 100%;
        background: linear-gradient(90deg, #2563eb, #7c3aed);
        border-radius: 4px;
        transition: width 0.3s;
      }
      .progress-pct {
        font-size: 13px;
        font-weight: 700;
        color: #1e293b;
        min-width: 36px;
      }
      .meta-row {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 8px;
        margin-bottom: 8px;
      }
      .meta-chip {
        font-size: 12px;
        font-weight: 500;
        padding: 3px 10px;
        border-radius: 20px;
        background: #f8fafc;
        color: #64748b;
        display: flex;
        align-items: center;
        gap: 4px;
      }
      .meta-chip.completed-chip {
        background: #dcfce7;
        color: #15803d;
        font-weight: 600;
      }
      .icon-sm {
        font-size: 14px;
      }
      .rating-row {
        display: flex;
        gap: 2px;
        margin-bottom: 6px;
      }
      .star {
        font-size: 18px;
        color: #e2e8f0;
      }
      .star.filled {
        color: #f59e0b;
      }
      .feedback-text {
        font-size: 13px;
        color: #475569;
        font-style: italic;
        margin: 0 0 4px;
      }
      .provider-notes {
        font-size: 13px;
        color: #475569;
        margin: 4px 0;
        padding: 8px;
        background: #f8fafc;
        border-radius: 8px;
        border-left: 3px solid #6ee7b7;
      }
      .clarification-text {
        font-size: 13px;
        color: #a16207;
        margin: 4px 0;
        font-style: italic;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyEducationProgressComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  all = signal<EducationProgressDTO[]>([]);
  loading = signal(true);
  filter = signal<'all' | 'in_progress' | 'completed'>('all');

  inProgress = computed(() =>
    this.all().filter(
      (p) =>
        p.comprehensionStatus?.toUpperCase() === 'IN_PROGRESS' ||
        (p.progressPercentage != null &&
          p.progressPercentage > 0 &&
          p.comprehensionStatus?.toUpperCase() !== 'COMPLETED'),
    ),
  );

  completed = computed(() =>
    this.all().filter((p) => p.comprehensionStatus?.toUpperCase() === 'COMPLETED'),
  );

  visible = computed<EducationProgressDTO[]>(() => {
    if (this.filter() === 'in_progress') return this.inProgress();
    if (this.filter() === 'completed') return this.completed();
    return this.all();
  });

  ngOnInit() {
    this.portal.getMyEducationProgress().subscribe({
      next: (data) => {
        this.all.set(data);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  statusColorClass(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'completed';
    if (s === 'IN_PROGRESS') return 'in-progress';
    return '';
  }

  statusIcon(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'task_alt';
    if (s === 'IN_PROGRESS') return 'pending';
    return 'school';
  }

  comprehensionLabel(status: string): string {
    const s = status?.toUpperCase();
    if (s === 'COMPLETED') return 'Completed';
    if (s === 'IN_PROGRESS') return 'In Progress';
    if (s === 'NOT_STARTED') return 'Not Started';
    return status ?? 'Unknown';
  }

  formatTime(seconds: number): string {
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.round(seconds / 60)}m`;
    return `${Math.floor(seconds / 3600)}h ${Math.round((seconds % 3600) / 60)}m`;
  }
}
