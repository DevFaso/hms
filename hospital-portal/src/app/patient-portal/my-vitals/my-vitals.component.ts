import { Component, OnInit, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, VitalSignSummary } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

@Component({
  selector: 'app-my-vitals',
  standalone: true,
  imports: [CommonModule, DatePipe, EnumLabelPipe, TranslateModule],
  templateUrl: './my-vitals.component.html',
  styleUrls: ['./my-vitals.component.scss', '../patient-portal-pages.scss'],
})
export class MyVitalsComponent implements OnInit {
  private readonly portal = inject(PatientPortalService);
  vitals = signal<VitalSignSummary[]>([]);
  loading = signal(true);
  expandedId = signal<string | null>(null);

  ngOnInit() {
    this.portal.getMyVitals().subscribe({
      next: (v) => {
        this.vitals.set(v);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedId.set(this.expandedId() === id ? null : id);
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

  getNormalRange(type: string): string {
    const ranges: Record<string, string> = {
      BLOOD_PRESSURE: '90/60 – 120/80 mmHg',
      HEART_RATE: '60 – 100 bpm',
      TEMPERATURE: '36.1 – 37.2 °C',
      OXYGEN_SATURATION: '95 – 100 %',
      RESPIRATORY_RATE: '12 – 20 breaths/min',
      BMI: '18.5 – 24.9',
      BLOOD_GLUCOSE: '70 – 100 mg/dL (fasting)',
    };
    return ranges[type?.toUpperCase()] || '';
  }
}
