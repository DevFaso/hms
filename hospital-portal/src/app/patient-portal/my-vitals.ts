import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { PatientPortalService, VitalSignSummary } from '../services/patient-portal.service';

@Component({
  selector: 'app-my-vitals',
  standalone: true,
  imports: [CommonModule, DatePipe],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">monitor_heart</span>
          My Vitals
        </h1>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading vitals...</p>
        </div>
      } @else if (vitals().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">monitor_heart</span>
          <h3>No vital signs recorded</h3>
          <p>Your vital sign readings will appear here.</p>
        </div>
      } @else {
        <section class="portal-section">
          <div class="vitals-grid">
            @for (v of vitals(); track v.id) {
              <div class="vital-card">
                <div class="vc-icon">
                  <span class="material-symbols-outlined">{{ getVitalIcon(v.type) }}</span>
                </div>
                <div class="vc-body">
                  <span class="vc-type">{{ v.type }}</span>
                  <span class="vc-value"
                    >{{ v.value }} <small>{{ v.unit }}</small></span
                  >
                  <span class="vc-meta"
                    >{{ v.recordedAt | date: 'MMM d, yyyy' }} · {{ v.source || 'Clinical' }}</span
                  >
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
      .vitals-grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
        gap: 14px;
      }
      .vital-card {
        display: flex;
        align-items: flex-start;
        gap: 12px;
        padding: 16px;
        background: #fff;
        border-radius: 12px;
        border: 1px solid #e2e8f0;
      }
      .vc-icon {
        width: 40px;
        height: 40px;
        border-radius: 10px;
        background: #fee2e2;
        color: #dc2626;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .vc-body {
        display: flex;
        flex-direction: column;
        gap: 2px;
      }
      .vc-type {
        font-size: 12px;
        font-weight: 600;
        color: #64748b;
        text-transform: uppercase;
        letter-spacing: 0.3px;
      }
      .vc-value {
        font-size: 20px;
        font-weight: 700;
        color: #1e293b;
      }
      .vc-value small {
        font-size: 13px;
        font-weight: 500;
        color: #94a3b8;
      }
      .vc-meta {
        font-size: 12px;
        color: #94a3b8;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyVitalsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  vitals = signal<VitalSignSummary[]>([]);
  loading = signal(true);

  ngOnInit() {
    this.portal.getMyVitals().subscribe({
      next: (v) => {
        this.vitals.set(v);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  getVitalIcon(type: string): string {
    const map: Record<string, string> = {
      BLOOD_PRESSURE: 'vital_signs',
      HEART_RATE: 'heart_check',
      TEMPERATURE: 'thermostat',
      WEIGHT: 'monitor_weight',
      HEIGHT: 'height',
      OXYGEN_SATURATION: 'spo2',
      RESPIRATORY_RATE: 'pulmonology',
      BMI: 'speed',
    };
    return map[type?.toUpperCase()] || 'monitor_heart';
  }
}
