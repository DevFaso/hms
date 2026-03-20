import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PatientPortalService, PatientVitalSignDTO } from '../../services/patient-portal.service';

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
  { label: 'SpOâ‚‚', key: 'spo2Percent', unit: '%', icon: 'air', color: '#06b6d4' },
  {
    label: 'Temperature',
    key: 'temperatureCelsius',
    unit: 'Â°C',
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
  templateUrl: './my-vital-trends.html',
  styleUrl: './my-vital-trends.scss',
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
