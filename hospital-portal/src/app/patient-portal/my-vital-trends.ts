import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatientPortalService, PatientVitalSignDTO } from '../services/patient-portal.service';

interface VitalSeries {
  label: string;
  key: keyof PatientVitalSignDTO;
  unit: string;
  icon: string;
  color: string;
}

const VITAL_SERIES: VitalSeries[] = [
  { label: 'Heart Rate', key: 'heartRateBpm', unit: 'bpm', icon: 'favorite', color: '#ef4444' },
  {
    label: 'Systolic BP',
    key: 'systolicBpMmHg',
    unit: 'mmHg',
    icon: 'vital_signs',
    color: '#8b5cf6',
  },
  {
    label: 'Diastolic BP',
    key: 'diastolicBpMmHg',
    unit: 'mmHg',
    icon: 'vital_signs',
    color: '#7c3aed',
  },
  { label: 'SpO₂', key: 'spo2Percent', unit: '%', icon: 'air', color: '#06b6d4' },
  {
    label: 'Temperature',
    key: 'temperatureCelsius',
    unit: '°C',
    icon: 'thermostat',
    color: '#f59e0b',
  },
  { label: 'Weight', key: 'weightKg', unit: 'kg', icon: 'monitor_weight', color: '#10b981' },
  {
    label: 'Blood Glucose',
    key: 'bloodGlucoseMgDl',
    unit: 'mg/dL',
    icon: 'water_drop',
    color: '#f97316',
  },
];

@Component({
  selector: 'app-my-vital-trends',
  standalone: true,
  imports: [CommonModule, DatePipe, FormsModule],
  template: `
    <div class="portal-page">
      <div class="portal-page-header">
        <h1>
          <span class="material-symbols-outlined">trending_up</span>
          Vital Sign Trends
        </h1>
        <div class="header-controls">
          <label class="range-label">
            <span>Show last</span>
            <select [(ngModel)]="selectedMonths" (ngModelChange)="loadTrends()">
              <option [ngValue]="1">1 month</option>
              <option [ngValue]="3">3 months</option>
              <option [ngValue]="6">6 months</option>
              <option [ngValue]="12">12 months</option>
              <option [ngValue]="24">24 months</option>
            </select>
          </label>
        </div>
      </div>

      @if (loading()) {
        <div class="portal-loading">
          <div class="portal-spinner"></div>
          <p>Loading vital trends...</p>
        </div>
      } @else if (vitals().length === 0) {
        <div class="portal-empty">
          <span class="material-symbols-outlined">trending_up</span>
          <h3>No vital sign data available</h3>
          <p>Your vital sign history will appear here once recorded.</p>
        </div>
      } @else {
        @for (series of vitalSeries; track series.key) {
          @if (hasData(series.key)) {
            <section class="portal-section trend-section">
              <div class="trend-header">
                <span
                  class="trend-icon"
                  [style.background]="series.color + '22'"
                  [style.color]="series.color"
                >
                  <span class="material-symbols-outlined">{{ series.icon }}</span>
                </span>
                <div>
                  <h3 class="trend-title">{{ series.label }}</h3>
                  <p class="trend-meta">
                    Latest:
                    <strong>{{ latestValue(series.key) }} {{ series.unit }}</strong> &nbsp;·&nbsp;
                    {{ vitals().length }} readings
                  </p>
                </div>
              </div>

              <div class="trend-readings">
                @for (v of vitals(); track v.id) {
                  @if (v[series.key] !== null && v[series.key] !== undefined) {
                    <div class="reading-chip">
                      <span class="reading-val"
                        >{{ v[series.key] }}<small> {{ series.unit }}</small></span
                      >
                      <span class="reading-date">{{ v.recordedAt | date: 'MMM d' }}</span>
                    </div>
                  }
                }
              </div>
            </section>
          }
        }
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
      .trend-section {
        margin-bottom: 20px;
      }
      .trend-header {
        display: flex;
        align-items: center;
        gap: 14px;
        margin-bottom: 14px;
      }
      .trend-icon {
        width: 44px;
        height: 44px;
        border-radius: 12px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }
      .trend-icon .material-symbols-outlined {
        font-size: 22px;
      }
      .trend-title {
        font-size: 16px;
        font-weight: 600;
        color: #1e293b;
        margin: 0;
      }
      .trend-meta {
        font-size: 13px;
        color: #64748b;
        margin: 2px 0 0;
      }
      .trend-readings {
        display: flex;
        flex-wrap: wrap;
        gap: 10px;
      }
      .reading-chip {
        display: flex;
        flex-direction: column;
        align-items: center;
        padding: 10px 14px;
        background: #f8fafc;
        border: 1px solid #e2e8f0;
        border-radius: 10px;
        min-width: 80px;
      }
      .reading-val {
        font-size: 18px;
        font-weight: 700;
        color: #1e293b;
      }
      .reading-val small {
        font-size: 11px;
        font-weight: 500;
        color: #94a3b8;
      }
      .reading-date {
        font-size: 11px;
        color: #94a3b8;
        margin-top: 2px;
      }
    `,
  ],
  styleUrl: './patient-portal-pages.scss',
})
export class MyVitalTrendsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);

  readonly vitalSeries = VITAL_SERIES;
  vitals = signal<PatientVitalSignDTO[]>([]);
  loading = signal(true);
  selectedMonths = 3;

  ngOnInit() {
    this.loadTrends();
  }

  loadTrends() {
    this.loading.set(true);
    this.portal.getVitalTrends(this.selectedMonths).subscribe({
      next: (v) => {
        this.vitals.set(v);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  hasData(key: keyof PatientVitalSignDTO): boolean {
    return this.vitals().some((v) => v[key] !== null && v[key] !== undefined);
  }

  latestValue(key: keyof PatientVitalSignDTO): number | string | null {
    const vals = this.vitals()
      .filter((v) => v[key] !== null && v[key] !== undefined)
      .sort((a, b) => new Date(b.recordedAt).getTime() - new Date(a.recordedAt).getTime());
    return vals.length > 0 ? (vals[0][key] as number | string) : null;
  }
}
