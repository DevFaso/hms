import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { TranslateModule } from '@ngx-translate/core';
import { PatientPortalService, VitalSignSummary } from '../../services/patient-portal.service';
import { EnumLabelPipe } from '../../shared/pipes/enum-label.pipe';

interface VitalGroup {
  id: string;
  recordedAt: string;
  source: string;
  vitals: VitalSignSummary[];
}

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
  expandedGroupId = signal<string | null>(null);

  private readonly vitalTypeOrder: Record<string, number> = {
    BLOOD_PRESSURE: 1,
    HEART_RATE: 2,
    RESPIRATORY_RATE: 3,
    OXYGEN_SATURATION: 4,
    TEMPERATURE: 5,
    BLOOD_GLUCOSE: 6,
    WEIGHT: 7,
    HEIGHT: 8,
    BMI: 9,
  };

  groupedVitals = computed<VitalGroup[]>(() => {
    const groups = new Map<string, VitalGroup>();

    for (const vital of this.vitals()) {
      const groupId = vital.groupId ?? `${vital.recordedAt}|${vital.source}`;
      if (!groups.has(groupId)) {
        groups.set(groupId, {
          id: groupId,
          recordedAt: vital.recordedAt,
          source: vital.source,
          vitals: [],
        });
      }
      groups.get(groupId)?.vitals.push(vital);
    }

    return Array.from(groups.values())
      .map((group) => ({
        ...group,
        vitals: [...group.vitals].sort(
          (a, b) =>
            (this.vitalTypeOrder[a.type] ?? Number.MAX_SAFE_INTEGER) -
            (this.vitalTypeOrder[b.type] ?? Number.MAX_SAFE_INTEGER),
        ),
      }))
      .sort((a, b) => new Date(b.recordedAt).getTime() - new Date(a.recordedAt).getTime());
  });

  ngOnInit() {
    this.portal.getMyVitals().subscribe({
      next: (v) => {
        this.vitals.set(v);
        const firstGroup = this.groupedVitals()[0];
        this.expandedGroupId.set(firstGroup?.id ?? null);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  toggleExpand(id: string): void {
    this.expandedGroupId.set(this.expandedGroupId() === id ? null : id);
  }

  isExpanded(id: string): boolean {
    return this.expandedGroupId() === id;
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
